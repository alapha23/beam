package beam.sim

import java.io.FileOutputStream
import java.io.{File, FileOutputStream, FileWriter, PrintWriter}
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.time.ZonedDateTime
import java.util.Properties

import beam.agentsim.agents.choice.mode.{ModeIncentive, PtFares}
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.agentsim.agents.vehicles.VehicleCategory.MediumDutyPassenger
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.handling.BeamEventsHandling
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZTreeMap}
import beam.analysis.ActivityLocationPlotter
import beam.analysis.plots.{GraphSurgePricing, RideHailRevenueAnalysis}
import beam.matsim.{CustomPlansDumpingImpl, MatsimConfigUpdater}
import beam.replanning._
import beam.replanning.utilitybased.UtilityBasedModeChoice
import beam.router._
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.{DefaultNetworkCoordinator, FrequencyAdjustingNetworkCoordinator, NetworkCoordinator}
import beam.scoring.BeamScoringFunctionFactory
import beam.sim.ArgumentsParser.{Arguments, Worker}
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config._
import beam.sim.metrics.Metrics._
import beam.sim.metrics.{BeamStaticMetricsWriter, InfluxDbSimulationMetricCollector, SimulationMetricCollector}
import beam.sim.modules.{BeamAgentModule, UtilsModule}
import beam.sim.population.{PopulationAdjustment, PopulationScaling}
import beam.utils.BeamVehicleUtils.{readBeamVehicleTypeFile, readFuelTypeFile, readVehiclesFile}
import beam.utils.csv.readers
import beam.utils.scenario.matsim.BeamScenarioSource
import beam.utils.scenario.urbansim.{CsvScenarioReader, ParquetScenarioReader, UrbanSimScenarioSource}
import beam.utils.scenario.{BeamScenarioLoader, InputType, UrbanSimScenarioLoader}
import beam.utils.{NetworkHelper, _}
import com.conveyal.r5.transit.TransportNetwork
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.google.inject
import com.typesafe.config.{
  ConfigFactory,
  ConfigRenderOptions,
  ConfigResolveOptions,
  ConfigValueFactory,
  ConfigValueType,
  Config => TypesafeConfig
}
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.config.{ConfigWriter, Config => MatsimConfig}
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners.{ControlerDefaultCoreListenersModule, EventsHandling, PlansDumping}
import org.matsim.core.scenario.{MutableScenario, ScenarioBuilder, ScenarioByInstanceModule, ScenarioUtils}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.utils.objectattributes.AttributeConverter
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.sys.process.Process
import scala.util.Try

trait BeamHelper extends LazyLogging {

  protected val beamAsciiArt: String =
    """
    |  ________
    |  ___  __ )__________ _______ ___
    |  __  __  |  _ \  __ `/_  __ `__ \
    |  _  /_/ //  __/ /_/ /_  / / / / /
    |  /_____/ \___/\__,_/ /_/ /_/ /_/
    |
    | _____________________________________
    |
    """.stripMargin

  private def updateConfigForClusterUsing(
    parsedArgs: Arguments,
    config: TypesafeConfig
  ): TypesafeConfig = {
    (for {
      seedAddress <- parsedArgs.seedAddress
      nodeHost    <- parsedArgs.nodeHost
      nodePort    <- parsedArgs.nodePort
    } yield {
      config.withFallback(
        ConfigFactory.parseMap(
          Map(
            "seed.address" -> seedAddress,
            "node.host"    -> nodeHost,
            "node.port"    -> nodePort
          ).asJava
        )
      )
    }).getOrElse(config)
  }

  private def embedSelectArgumentsIntoConfig(
    parsedArgs: Arguments,
    config: TypesafeConfig
  ): TypesafeConfig = {
    config.withFallback(
      ConfigFactory.parseMap(
        (
          Map(
            "beam.cluster.enabled" -> parsedArgs.useCluster,
            "beam.useLocalWorker" -> parsedArgs.useLocalWorker.getOrElse(
              if (parsedArgs.useCluster) false else true
            )
          ) ++ {
            if (parsedArgs.useCluster)
              Map(
                "beam.cluster.clusterType"              -> parsedArgs.clusterType.get.toString,
                "akka.actor.provider"                   -> "akka.cluster.ClusterActorRefProvider",
                "akka.remote.artery.canonical.hostname" -> parsedArgs.nodeHost.get,
                "akka.remote.artery.canonical.port"     -> parsedArgs.nodePort.get,
                "akka.cluster.seed-nodes" -> java.util.Arrays
                  .asList(s"akka://ClusterSystem@${parsedArgs.seedAddress.get}")
              )
            else Map.empty[String, Any]
          }
        ).asJava
      )
    )
  }

  def module(
    typesafeConfig: TypesafeConfig,
    beamConfig: BeamConfig,
    scenario: Scenario,
    beamScenario: BeamScenario
  ): com.google.inject.Module =
    AbstractModule.`override`(
      ListBuffer(new AbstractModule() {
        override def install(): Unit = {
          // MATSim defaults
          install(new NewControlerModule)
          install(new ScenarioByInstanceModule(scenario))
          install(new ControllerModule)
          install(new ControlerDefaultCoreListenersModule)

          // Beam Inject below:
          install(new ConfigModule(typesafeConfig))
          install(new BeamAgentModule(beamConfig))
          install(new UtilsModule)
        }
      }).asJava,
      new AbstractModule() {
        private val mapper = new ObjectMapper()
        mapper.registerModule(DefaultScalaModule)

        override def install(): Unit = {
          // This code will be executed 3 times due to this https://github.com/LBNL-UCB-STI/matsim/blob/master/matsim/src/main/java/org/matsim/core/controler/Injector.java#L99:L101
          // createMapBindingsForType is called 3 times. Be careful not to do expensive operations here
          bind(classOf[BeamConfigHolder])
          val beamConfigChangesObservable = new BeamConfigChangesObservable(beamConfig)

          bind(classOf[MatsimConfigUpdater]).asEagerSingleton()

          bind(classOf[PlansDumping]).to(classOf[CustomPlansDumpingImpl])

          bind(classOf[BeamConfigChangesObservable]).toInstance(beamConfigChangesObservable)

          bind(classOf[TerminationCriterion]).to(classOf[CustomTerminateAtFixedIterationNumber])

          bind(classOf[PrepareForSim]).to(classOf[BeamPrepareForSim])
          bind(classOf[RideHailSurgePricingManager]).asEagerSingleton()

          addControlerListenerBinding().to(classOf[BeamSim])
          addControlerListenerBinding().to(classOf[BeamScoringFunctionFactory])
          addControlerListenerBinding().to(classOf[RouteHistory])

          addControlerListenerBinding().to(classOf[ActivityLocationPlotter])
          addControlerListenerBinding().to(classOf[GraphSurgePricing])
          bind(classOf[BeamOutputDataDescriptionGenerator])
          addControlerListenerBinding().to(classOf[RideHailRevenueAnalysis])
          addControlerListenerBinding().to(classOf[NonCarModeIterationPlanCleaner])

          bindMobsim().to(classOf[BeamMobsim])
          bind(classOf[EventsHandling]).to(classOf[BeamEventsHandling])
          bindScoringFunctionFactory().to(classOf[BeamScoringFunctionFactory])
          if (getConfig.strategy().getPlanSelectorForRemoval == "tryToKeepOneOfEachClass") {
            bindPlanSelectorForRemoval().to(classOf[TryToKeepOneOfEachClass])
          }
          addPlanStrategyBinding("SelectExpBeta").to(classOf[BeamExpBeta])
          addPlanStrategyBinding("SwitchModalityStyle").to(classOf[SwitchModalityStyle])
          addPlanStrategyBinding("ClearRoutes").to(classOf[ClearRoutes])
          addPlanStrategyBinding("ClearModes").to(classOf[ClearModes])
          addPlanStrategyBinding("TimeMutator").to(classOf[BeamTimeMutator])
          addPlanStrategyBinding(BeamReplanningStrategy.UtilityBasedModeChoice.toString)
            .toProvider(classOf[UtilityBasedModeChoice])
          addAttributeConverterBinding(classOf[MapStringDouble])
            .toInstance(new AttributeConverter[MapStringDouble] {
              override def convertToString(o: scala.Any): String =
                mapper.writeValueAsString(o.asInstanceOf[MapStringDouble].data)

              override def convert(value: String): MapStringDouble =
                MapStringDouble(mapper.readValue(value, classOf[Map[String, Double]]))
            })
          bind(classOf[BeamScenario]).toInstance(beamScenario)
          bind(classOf[TransportNetwork]).toInstance(beamScenario.transportNetwork)
          bind(classOf[TravelTimeCalculator]).toInstance(
            new FakeTravelTimeCalculator(
              beamScenario.network,
              new TravelTimeCalculatorConfigGroup()
            )
          )

          bind(classOf[NetworkHelper]).to(classOf[NetworkHelperImpl]).asEagerSingleton()
          bind(classOf[RideHailIterationHistory]).asEagerSingleton()
          bind(classOf[RouteHistory]).asEagerSingleton()
          bind(classOf[FareCalculator]).asEagerSingleton()
          bind(classOf[TollCalculator]).asEagerSingleton()

          bind(classOf[EventsManager]).to(classOf[LoggingEventsManager]).asEagerSingleton()
          bind(classOf[SimulationMetricCollector]).to(classOf[InfluxDbSimulationMetricCollector]).asEagerSingleton()
        }
      }
    )

  def loadScenario(beamConfig: BeamConfig): BeamScenario = {
    val vehicleTypes = maybeScaleTransit(
      beamConfig,
      readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath)
    )
    val vehicleCsvReader = new VehicleCsvReader(beamConfig)
    val baseFilePath = Paths.get(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath).getParent

    val consumptionRateFilterStore =
      new ConsumptionRateFilterStoreImpl(
        vehicleCsvReader.getVehicleEnergyRecordsUsing,
        Option(baseFilePath.toString),
        primaryConsumptionRateFilePathsByVehicleType =
          vehicleTypes.values.map(x => (x, x.primaryVehicleEnergyFile)).toIndexedSeq,
        secondaryConsumptionRateFilePathsByVehicleType =
          vehicleTypes.values.map(x => (x, x.secondaryVehicleEnergyFile)).toIndexedSeq
      )

    val dates = DateUtils(
      ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
      ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
    )

    val networkCoordinator = buildNetworkCoordinator(beamConfig)
    val tazMap = TAZTreeMap.getTazTreeMap(beamConfig.beam.agentsim.taz.filePath)

    BeamScenario(
      readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.fuelTypesFilePath).toMap,
      vehicleTypes,
      privateVehicles(beamConfig, vehicleTypes),
      new VehicleEnergy(
        consumptionRateFilterStore,
        vehicleCsvReader.getLinkToGradeRecordsUsing
      ),
      beamConfig,
      dates,
      PtFares(beamConfig.beam.agentsim.agents.ptFare.filePath),
      networkCoordinator.transportNetwork,
      networkCoordinator.network,
      tazMap,
      ModeIncentive(beamConfig.beam.agentsim.agents.modeIncentive.filePath),
      H3TAZ(networkCoordinator.network, tazMap, beamConfig)
    )
  }

  def vehicleEnergy(beamConfig: BeamConfig, vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]): VehicleEnergy = {
    val baseFilePath = Paths.get(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath).getParent
    val vehicleCsvReader = new VehicleCsvReader(beamConfig)
    val consumptionRateFilterStore =
      new ConsumptionRateFilterStoreImpl(
        vehicleCsvReader.getVehicleEnergyRecordsUsing,
        Option(baseFilePath.toString),
        primaryConsumptionRateFilePathsByVehicleType =
          vehicleTypes.values.map(x => (x, x.primaryVehicleEnergyFile)).toIndexedSeq,
        secondaryConsumptionRateFilePathsByVehicleType =
          vehicleTypes.values.map(x => (x, x.secondaryVehicleEnergyFile)).toIndexedSeq
      )
    // TODO Fix me once `TrieMap` is removed
    new VehicleEnergy(
      consumptionRateFilterStore,
      vehicleCsvReader.getLinkToGradeRecordsUsing
    )
  }

  def privateVehicles(
    beamConfig: BeamConfig,
    vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]
  ): TrieMap[Id[BeamVehicle], BeamVehicle] =
    if (beamConfig.beam.agentsim.agents.population.useVehicleSampling) {
      TrieMap[Id[BeamVehicle], BeamVehicle]()
    } else {
      TrieMap(
        readVehiclesFile(
          beamConfig.beam.agentsim.agents.vehicles.vehiclesFilePath,
          vehicleTypes,
          beamConfig.matsim.modules.global.randomSeed
        ).toSeq: _*
      )
    }

  // Note that this assumes standing room is only available on transit vehicles. Not sure of any counterexamples modulo
  // say, a yacht or personal bus, but I think this will be fine for now.
  // New Feb-2020: Switched over to MediumDutyPassenger -> Transit to solve issue with AV shuttles
  private def maybeScaleTransit(beamConfig: BeamConfig, vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]) = {
    beamConfig.beam.agentsim.tuning.transitCapacity match {
      case Some(scalingFactor) =>
        vehicleTypes.map {
          case (id, bvt) =>
            id -> (if (bvt.vehicleCategory == MediumDutyPassenger)
                     bvt.copy(
                       seatingCapacity = Math.ceil(bvt.seatingCapacity.toDouble * scalingFactor).toInt,
                       standingRoomCapacity = Math.ceil(bvt.standingRoomCapacity.toDouble * scalingFactor).toInt
                     )
                   else
                     bvt)
        }
      case None => vehicleTypes
    }
  }

  def runBeamUsing(args: Array[String], isConfigArgRequired: Boolean = true): Unit = {
    val (parsedArgs, config) = prepareConfig(args, isConfigArgRequired)

    parsedArgs.clusterType match {
      case Some(Worker) => runClusterWorkerUsing(config) //Only the worker requires a different path
      case _ =>
        val (_, outputDirectory) = runBeamWithConfig(config)
        postRunActivity(parsedArgs.configLocation.get, config, outputDirectory)
    }
  }

  def prepareConfig(args: Array[String], isConfigArgRequired: Boolean): (Arguments, TypesafeConfig) = {
    val parsedArgs = ArgumentsParser.parseArguments(args) match {
      case Some(pArgs) => pArgs
      case None =>
        throw new IllegalArgumentException(
          "Arguments provided were unable to be parsed. See above for reasoning."
        )
    }
    assert(
      !isConfigArgRequired || (isConfigArgRequired && parsedArgs.config.isDefined),
      "Please provide a valid configuration file."
    )

    ConfigConsistencyComparator.parseBeamTemplateConfFile(parsedArgs.configLocation.get)

    if (parsedArgs.configLocation.get.contains("\\")) {
      throw new RuntimeException("wrong config path, expected:forward slash, found: backward slash")
    }

    val location = ConfigFactory.parseString(s"""config="${parsedArgs.configLocation.get}"""")
    System.setProperty("configFileLocation", parsedArgs.configLocation.getOrElse(""))
    val config = embedSelectArgumentsIntoConfig(parsedArgs, {
      if (parsedArgs.useCluster) updateConfigForClusterUsing(parsedArgs, parsedArgs.config.get)
      else parsedArgs.config.get
    }).withFallback(location).resolve()

    checkDockerIsInstalledForCCHPhysSim(config)

    (parsedArgs, config)
  }

  private def checkDockerIsInstalledForCCHPhysSim(config: TypesafeConfig): Unit = {
    val physSimType = Try(config.getString("beam.physsim.physSimType")).getOrElse("")
    if (physSimType == "CCHRoutingAssignment") {
      // Exception will be thrown if docker is not available on device
      if (Try(Process("docker version").!!).isFailure) {
        throw new RuntimeException("Docker is required to run CCH phys simulation")
      }
    }
  }

  private def postRunActivity(configLocation: String, config: TypesafeConfig, outputDirectory: String) = {
    val props = new Properties()
    props.setProperty("commitHash", BashUtils.getCommitHash)
    props.setProperty("configFile", configLocation)
    val out = new FileOutputStream(Paths.get(outputDirectory, "beam.properties").toFile)
    props.store(out, "Simulation out put props.")
    val beamConfig = BeamConfig(config)
    if (beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass
          .equalsIgnoreCase("ModeChoiceLCCM")) {
      Files.copy(
        Paths.get(beamConfig.beam.agentsim.agents.modalBehaviors.lccm.filePath),
        Paths.get(
          outputDirectory,
          Paths
            .get(beamConfig.beam.agentsim.agents.modalBehaviors.lccm.filePath)
            .getFileName
            .toString
        )
      )
    }
    Files.copy(
      Paths.get(configLocation),
      Paths.get(outputDirectory, "beam.conf"),
      StandardCopyOption.REPLACE_EXISTING
    )
  }

  def runClusterWorkerUsing(config: TypesafeConfig): Unit = {
    val clusterConfig = ConfigFactory
      .parseString(s"""
           |akka.cluster.roles = [compute]
           |akka.actor.deployment {
           |      /statsService/singleton/workerRouter {
           |        router = round-robin-pool
           |        cluster {
           |          enabled = on
           |          max-nr-of-instances-per-node = 1
           |          allow-local-routees = on
           |          use-roles = ["compute"]
           |        }
           |      }
           |    }
          """.stripMargin)
      .withFallback(config)

    if (isMetricsEnable) {
      Kamon.init()
    }

    import akka.actor.{ActorSystem, DeadLetter, PoisonPill, Props}
    import akka.cluster.singleton.{
      ClusterSingletonManager,
      ClusterSingletonManagerSettings,
      ClusterSingletonProxy,
      ClusterSingletonProxySettings
    }
    import beam.router.ClusterWorkerRouter
    import beam.sim.monitoring.DeadLetterReplayer

    val system = ActorSystem("ClusterSystem", clusterConfig)
    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props(classOf[ClusterWorkerRouter], clusterConfig),
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system).withRole("compute")
      ),
      name = "statsService"
    )
    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = "/user/statsService",
        settings = ClusterSingletonProxySettings(system).withRole("compute")
      ),
      name = "statsServiceProxy"
    )
    val replayer = system.actorOf(DeadLetterReplayer.props())
    system.eventStream.subscribe(replayer, classOf[DeadLetter])

    import scala.concurrent.ExecutionContext.Implicits.global
    Await.ready(system.whenTerminated.map(_ => {
      logger.info("Exiting BEAM")
    }), scala.concurrent.duration.Duration.Inf)
  }

  def runBeamWithConfig(config: TypesafeConfig): (MatsimConfig, String) = {
    val (
      beamExecutionConfig: BeamExecutionConfig,
      scenario: MutableScenario,
      beamScenario: BeamScenario,
      services: BeamServices
    ) = prepareBeamService(config)

    runBeam(
      services,
      scenario,
      beamScenario,
      beamExecutionConfig.outputDirectory
    )
    (scenario.getConfig, beamExecutionConfig.outputDirectory)
  }

  def prepareBeamService(config: TypesafeConfig): (BeamExecutionConfig, MutableScenario, BeamScenario, BeamServices) = {
    val beamExecutionConfig = updateConfigWithWarmStart(setupBeamWithConfig(config))
    val (scenario, beamScenario) = buildBeamServicesAndScenario(
      beamExecutionConfig.beamConfig,
      beamExecutionConfig.matsimConfig,
    )

    val logStart = {
      val populationSize = scenario.getPopulation.getPersons.size()
      val vehiclesSize = scenario.getVehicles.getVehicles.size()
      val lanesSize = scenario.getLanes.getLanesToLinkAssignments.size()

      val logHHsize = scenario.getHouseholds.getHouseholds.size()
      val logBeamPrivateVehiclesSize = beamScenario.privateVehicles.size
      val logVehicleTypeSize = beamScenario.vehicleTypes.size
      val modIncentivesSize = beamScenario.modeIncentives.modeIncentives.size
      s"""
         |Scenario population size: $populationSize
         |Scenario vehicles size: $vehiclesSize
         |Scenario lanes size: $lanesSize
         |BeamScenario households size: $logHHsize
         |BeamScenario privateVehicles size: $logBeamPrivateVehiclesSize
         |BeamScenario vehicleTypes size: $logVehicleTypeSize
         |BeamScenario modIncentives size $modIncentivesSize
         |""".stripMargin
    }
    logger.warn(logStart)

    val injector: inject.Injector = buildInjector(config, beamExecutionConfig.beamConfig, scenario, beamScenario)

    val services = injector.getInstance(classOf[BeamServices])
    (beamExecutionConfig, scenario, beamScenario, services)
  }

  def fixDanglingPersons(result: MutableScenario): Unit = {
    val peopleViaHousehold = result.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap { x =>
        x.getMemberIds.asScala
      }
      .toSet
    val danglingPeople = result.getPopulation.getPersons
      .values()
      .asScala
      .filter(person => !peopleViaHousehold.contains(person.getId))
    if (danglingPeople.nonEmpty) {
      logger.error(s"There are ${danglingPeople.size} persons not connected to household, removing them")
      danglingPeople.foreach { p =>
        result.getPopulation.removePerson(p.getId)
      }
    }
  }

  protected def buildScenarioFromMatsimConfig(
    matsimConfig: MatsimConfig,
    beamScenario: BeamScenario
  ): MutableScenario = {
    val result = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    fixDanglingPersons(result)
    result.setNetwork(beamScenario.network)
    result
  }

  def buildBeamServices(
    injector: inject.Injector,
    scenario: MutableScenario,
  ): BeamServices = {
    val result = injector.getInstance(classOf[BeamServices])
    result
  }

  protected def buildInjector(
    config: TypesafeConfig,
    beamConfig: BeamConfig,
    scenario: MutableScenario,
    beamScenario: BeamScenario
  ): inject.Injector = {
    org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      module(config, beamConfig, scenario, beamScenario)
    )
  }

  def runBeam(
    beamServices: BeamServices,
    scenario: MutableScenario,
    beamScenario: BeamScenario,
    outputDir: String
  ): Unit = {
    samplePopulation(scenario, beamScenario, beamServices.beamConfig, scenario.getConfig, beamServices, outputDir)

    val houseHoldVehiclesInScenario: Iterable[Id[Vehicle]] = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap(_.getVehicleIds.asScala)

    val vehiclesGroupedByType = houseHoldVehiclesInScenario.groupBy(
      v => beamScenario.privateVehicles.get(v).map(_.beamVehicleType.id.toString).getOrElse("")
    )
    val vehicleInfo = vehiclesGroupedByType.map {
      case (vehicleType, groupedValues) =>
        s"$vehicleType (${groupedValues.size})"
    } mkString " , "
    logger.info(s"Vehicles assigned to households : $vehicleInfo")

    run(beamServices)
  }

  protected def buildBeamServicesAndScenario(
    beamConfig: BeamConfig,
    matsimConfig: MatsimConfig
  ): (MutableScenario, BeamScenario) = {
    val scenarioConfig = beamConfig.beam.exchange.scenario

    val src = scenarioConfig.source.toLowerCase

    val fileFormat = scenarioConfig.fileFormat

    ProfilingUtils.timed(s"Load scenario using $src/$fileFormat", x => logger.info(x)) {
      if (src == "urbansim") {
        val beamScenario = loadScenario(beamConfig)
        val emptyScenario = ScenarioBuilder(matsimConfig, beamScenario.network).build
        val scenario = {
          val source = buildUrbansimScenarioSource(new GeoUtilsImpl(beamConfig), beamConfig)
          new UrbanSimScenarioLoader(emptyScenario, beamScenario, source, new GeoUtilsImpl(beamConfig)).loadScenario()
        }.asInstanceOf[MutableScenario]
        (scenario, beamScenario)
      } else if (src == "beam") {
        fileFormat match {
          case "csv" =>
            val beamScenario = loadScenario(beamConfig)
            val scenario = {
              val source = new BeamScenarioSource(
                beamConfig,
                rdr = readers.BeamCsvScenarioReader
              )
              val scenarioBuilder = ScenarioBuilder(matsimConfig, beamScenario.network)
              new BeamScenarioLoader(scenarioBuilder, beamScenario, source, new GeoUtilsImpl(beamConfig)).loadScenario()
            }.asInstanceOf[MutableScenario]
            (scenario, beamScenario)
          case "xml" =>
            val beamScenario = loadScenario(beamConfig)
            val scenario = {
              val result = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
              fixDanglingPersons(result)
              result
            }
            (scenario, beamScenario)
          case unknown =>
            throw new IllegalArgumentException(s"Beam does not support [$unknown] file type")
        }
      } else {
        throw new NotImplementedError(s"ScenarioSource '$src' is not yet implemented")
      }
    }
  }

  def setupBeamWithConfig(
    config: TypesafeConfig
  ): BeamExecutionConfig = {
    val beamConfig = BeamConfig(config)
    val outputDirectory = FileUtils.getConfigOutputFile(
      beamConfig.beam.outputs.baseOutputDirectory,
      beamConfig.beam.agentsim.simulationName,
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )
    LoggingUtil.initLogger(outputDirectory, beamConfig.beam.logger.keepConsoleAppenderOn)
    logger.debug(s"Beam output directory is: $outputDirectory")
    logger.info(ConfigConsistencyComparator.getMessage.getOrElse(""))

    level = beamConfig.beam.metrics.level
    runName = beamConfig.beam.agentsim.simulationName
    if (isMetricsEnable) {
      Kamon.init(config.withFallback(ConfigFactory.load()))
    }

    logger.info("Starting beam on branch {} at commit {}.", BashUtils.getBranch, BashUtils.getCommitHash)

    prepareDirectories(config, beamConfig, outputDirectory)

    writeFullConfigs(config, outputDirectory)

    val matsimConfig: MatsimConfig = buildMatsimConfig(config, beamConfig, outputDirectory)

    BeamExecutionConfig(beamConfig, matsimConfig, outputDirectory)
  }

  /**
    * This method merges all configuration parameters into a single file including parameters from
    * 'include' statements. Two full config files are written out: One without comments and one with
    * comments in JSON format.
    * @param config the input config file
    * @param outputDirectory output folder where full configs will be generated
    */
  private def writeFullConfigs(config: TypesafeConfig, outputDirectory: String): Unit = {

    val configResolveOptions = ConfigResolveOptions.defaults().setAllowUnresolved(true)

    val templateConf = ConfigFactory
      .parseResources("beam-template.conf")
      .withoutPath("matsim.modules.vehicles.vehiclesFile")
      .withoutPath("matsim.modules.transit.vehiclesFile")
      .withoutPath("matsim.modules.counts.inputCountsFile")
      .withoutPath("matsim.modules.strategy.planSelectorForRemoval")
      .resolve(configResolveOptions)
    val fullConfig = config.resolve().withFallback(templateConf).resolve()

    val defaultConfig = fullConfig
      .entrySet()
      .asScala
      .collect {
        case entry if shouldAddKey(entry.getValue.unwrapped) =>
          val unwrapped = entry.getValue.unwrapped()
          val paramValue = unwrapped.toString
          if (paramValue.contains("|")) {
            entry.getKey -> actualValue(paramValue)
          } else {
            entry.getKey -> unwrapped
          }
      }
      .toMap
      .asJava
    val defaultValues = ConfigFactory.parseMap(defaultConfig).resolve()
    val configConciseWithoutJson =
      defaultValues.root().render(ConfigRenderOptions.concise().setFormatted(true).setJson(false))
    writeStringToFile(configConciseWithoutJson, new File(outputDirectory, "fullBeamConfig.conf"))
    writeStringToFile(defaultValues.root().render(), new File(outputDirectory, "fullBeamConfigJson.conf"))
  }

  private def actualValue(paramValue: String): Any = {
    val value = paramValue.substring(paramValue.lastIndexOf('|') + 1).trim
    if (paramValue.contains("int")) {
      return value.toInt
    }
    if (paramValue.contains("double")) {
      if ("Double.PositiveInfinity" == value) {
        return Double.PositiveInfinity
      }
      if ("Double.NegativeInfinity" == value) {
        return Double.NegativeInfinity
      }
      return value.toDouble
    }
    if (paramValue.contains("boolean")) {
      return value.toBoolean
    }
    value
  }

  private def shouldAddKey(value: AnyRef): Boolean = {
    if ("int?" == value.toString || "double?" == value.toString || "[double]" == value.toString) {
      return false
    }
    true
  }

  private def writeStringToFile(text: String, output: File): Unit = {
    val fileWriter = new PrintWriter(output)
    fileWriter.write(text)
    fileWriter.close
  }

  protected def buildNetworkCoordinator(beamConfig: BeamConfig): NetworkCoordinator = {
    val result = if (Files.isRegularFile(Paths.get(beamConfig.beam.agentsim.scenarios.frequencyAdjustmentFile))) {
      FrequencyAdjustingNetworkCoordinator(beamConfig)
    } else {
      DefaultNetworkCoordinator(beamConfig)
    }
    result.init()
    result
  }

  private def updateConfigWithWarmStart(beamExecutionConfig: BeamExecutionConfig): BeamExecutionConfig = {
    BeamWarmStart.updateExecutionConfig(beamExecutionConfig)
  }

  private def prepareDirectories(config: TypesafeConfig, beamConfig: BeamConfig, outputDirectory: String): Unit = {
    new java.io.File(outputDirectory).mkdirs
    val location = config.getString("config")

    val confNameToPath = BeamConfigUtils.getFileNameToPath(location)

    logger.info("Processing configs for [{}] simulation.", beamConfig.beam.agentsim.simulationName)
    confNameToPath.foreach {
      case (fileName, filePath) =>
        val outFile = Paths.get(outputDirectory, fileName)
        Files.copy(Paths.get(filePath), outFile, StandardCopyOption.REPLACE_EXISTING)
        logger.info("Config '{}' copied to '{}'.", filePath, outFile)
    }
  }

  def buildMatsimConfig(
    config: TypesafeConfig,
    beamConfig: BeamConfig,
    outputDirectory: String
  ): MatsimConfig = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val result = configBuilder.buildMatSimConf()
    if (!beamConfig.beam.outputs.writeGraphs) {
      result.counts.setOutputFormat("txt")
      result.controler.setCreateGraphs(false)
    }
    result.planCalcScore().setMemorizingExperiencedPlans(true)
    result.controler.setOutputDirectory(outputDirectory)
    result.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)
    result
  }

  def run(beamServices: BeamServices) {
    beamServices.controler.run()
  }

  // sample population (beamConfig.beam.agentsim.numAgents - round to nearest full household)
  def samplePopulation(
    scenario: MutableScenario,
    beamScenario: BeamScenario,
    beamConfig: BeamConfig,
    matsimConfig: MatsimConfig,
    beamServices: BeamServices,
    outputDir: String
  ): Unit = {
    val populationScaling = new PopulationScaling()
    if (!beamConfig.beam.warmStart.enabled && beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation < 1) {
      populationScaling.downSample(beamServices, scenario, beamScenario, outputDir)
    }
    if (!beamConfig.beam.warmStart.enabled && beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation > 1) {
      populationScaling.upSample(beamServices, scenario, beamScenario)
    }
    val populationAdjustment = PopulationAdjustment.getPopulationAdjustment(beamServices)
    populationAdjustment.update(scenario)

    // write static metrics, such as population size, vehicles fleet size, etc.
    // necessary to be called after population sampling
    BeamStaticMetricsWriter.writeSimulationParameters(
      scenario,
      beamScenario,
      beamServices,
      beamConfig
    )
  }

  private def getVehicleGroupingStringUsing(vehicleIds: IndexedSeq[Id[Vehicle]], beamScenario: BeamScenario): String = {
    vehicleIds
      .groupBy(
        vehicleId => beamScenario.privateVehicles.get(vehicleId).map(_.beamVehicleType.id.toString).getOrElse("")
      )
      .map {
        case (vehicleType, ids) => s"$vehicleType (${ids.size})"
      }
      .mkString(" , ")
  }

  private def buildUrbansimScenarioSource(
    geo: GeoUtils,
    beamConfig: BeamConfig
  ): UrbanSimScenarioSource = {
    val fileFormat: InputType = Option(beamConfig.beam.exchange.scenario.fileFormat)
      .map(str => InputType(str.toLowerCase))
      .getOrElse(
        throw new IllegalStateException(
          s"`beamConfig.beam.exchange.scenario.fileFormat` is null or empty!"
        )
      )
    val scenarioReader = fileFormat match {
      case InputType.CSV     => CsvScenarioReader
      case InputType.Parquet => ParquetScenarioReader
    }

    new UrbanSimScenarioSource(
      scenarioSrc = beamConfig.beam.exchange.scenario.folder,
      rdr = scenarioReader,
      geoUtils = geo,
      shouldConvertWgs2Utm = beamConfig.beam.exchange.scenario.convertWgs2Utm
    )
  }
}

case class MapStringDouble(data: Map[String, Double])
