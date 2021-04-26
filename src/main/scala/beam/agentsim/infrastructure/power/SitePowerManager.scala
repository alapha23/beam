package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetwork
import beam.agentsim.infrastructure.ChargingNetwork.{ChargingCycle, ChargingStation, ChargingVehicle}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.router.skim.event
import beam.sim.BeamServices
import cats.Eval
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

class SitePowerManager(chargingNetworkMap: Map[Id[VehicleManager], ChargingNetwork], beamServices: BeamServices)
    extends LazyLogging {
  import SitePowerManager._

  private val cnmConfig = beamServices.beamConfig.beam.agentsim.chargingNetworkManager
  private val tazSkimmer = beamServices.skims.taz_skimmer
  private lazy val allChargingStations = chargingNetworkMap.flatMap(_._2.chargingStations).toList.distinct
  private val unlimitedPhysicalBounds = getUnlimitedPhysicalBounds(allChargingStations).value

  /**
    * Get required power for electrical vehicles
    *
    * @param tick current time
    * @return power (in Kilo Watt) over planning horizon
    */
  def requiredPowerInKWOverNextPlanningHorizon(tick: Int): Map[ChargingStation, PowerInKW] = {
//    val previousTimeBin = cnmConfig.timeStepInSeconds * ((tick / cnmConfig.timeStepInSeconds) - 1)
    val plans = allChargingStations.par
      .map { station =>
        val estimatedLoad =
          observedPowerDemandInKW(tick, station.zone).getOrElse(estimatePowerDemandInKW(tick, station.zone))
//        logger.info(
//          "estimatePowerDemandInKW,{},{},{},{},{},{},{},{},{}",
//          tick,
//          previousTimeBin,
//          station.zone.managerId,
//          station.zone.tazId.toString,
//          station.zone.parkingType.toString,
//          station.zone.chargingPointType.toString,
//          station.zone.numChargers,
//          station.zone.id,
//          estimatedLoad
//        )
        station -> estimatedLoad
      }
      .seq
      .toMap
    if (plans.isEmpty) logger.error(s"Charging Replan did not produce allocations")
//    tazSkimmer.getPartialSkim(previousTimeBin, "CNM").foreach {
//      case (k, v) =>
//        logger.info(
//          "getPartialSkim,{},{},{},{},{},{},{},{},{}",
//          tick,
//          k.time,
//          "",
//          k.taz.toString,
//          "",
//          "",
//          "",
//          k.key,
//          v.value * v.observations
//        )
//    }
    plans
  }

  /**
    * get observed power from previous iteration
    * @param tick timeBin
    * @param zone the Charging Zone
    * @return power in KW
    */
  private def observedPowerDemandInKW(tick: Int, zone: ChargingZone): Option[Double] = {
    if (!tazSkimmer.isLatestSkimEmpty) {
      val currentTimeBin = cnmConfig.timeStepInSeconds * (tick / cnmConfig.timeStepInSeconds)
      beamServices.skims.taz_skimmer.getLatestSkim(currentTimeBin, zone.tazId, "CNM", zone.id) match {
        case Some(skim) =>
          Some((skim.value * skim.observations / 3.6e+6) / (cnmConfig.timeStepInSeconds / 3600.0))
        case None => Some(0.0)
      }
    } else None
  }

  /**
    * get estimated power from current partial skim
    * @param tick timeBin
    * @param chargingZone the Charging Zone
    * @return Power in kW
    */
  private def estimatePowerDemandInKW(tick: Int, chargingZone: ChargingZone): Double = {
    val previousTimeBin = cnmConfig.timeStepInSeconds * ((tick / cnmConfig.timeStepInSeconds) - 1)
    val cz @ ChargingZone(tazId, _, _, _, _, _) = chargingZone
    tazSkimmer.getPartialSkim(previousTimeBin, tazId, "CNM", cz.id) match {
      case Some(skim) =>
        (skim.value * skim.observations / 3.6e+6) / (cnmConfig.timeStepInSeconds / 3600.0)
      case None => 0.0
    }
  }

  /**
    *
    * @param chargingVehicle the vehicle being charging
    * @param physicalBounds physical bounds under which the dispatch occur
    * @return
    */
  def dispatchEnergy(
    timeInterval: Int,
    chargingVehicle: ChargingVehicle,
    physicalBounds: Map[ChargingStation, PhysicalBounds]
  ): (ChargingDurationInSec, EnergyInJoules) = {
    assume(timeInterval >= 0, "timeInterval should not be negative!")
    val ChargingVehicle(vehicle, _, station, _, _, _, _, _) = chargingVehicle
    // dispatch
    val maxZoneLoad = physicalBounds(station).powerLimitUpper
    val maxUnlimitedZoneLoad = unlimitedPhysicalBounds(station).powerLimitUpper
    val chargingPointLoad =
      ChargingPointType.getChargingPointInstalledPowerInKw(station.zone.chargingPointType)
    val chargingPowerLimit = maxZoneLoad * chargingPointLoad / maxUnlimitedZoneLoad
    vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(timeInterval),
      stateOfChargeLimit = None,
      chargingPowerLimit = Some(chargingPowerLimit)
    )
  }

  /**
    * Collect rough power demand per vehicle
    * @param chargingVehicle vehicle charging information
    * @param chargingSession latest charging sessions to collect
    */
  def collectObservedLoadInKW(chargingVehicle: ChargingVehicle, chargingSession: ChargingCycle): Unit = {
    import chargingSession._
    import chargingVehicle._
    // Collect data on load demand
    val (chargingDuration, requiredEnergy) = vehicle.refuelingSessionDurationAndEnergyInJoules(
      sessionDurationLimit = Some(duration),
      stateOfChargeLimit = None,
      chargingPowerLimit = None
    )
    beamServices.matsimServices.getEvents.processEvent(
      event.TAZSkimmerEvent(
        cnmConfig.timeStepInSeconds * (startTime / cnmConfig.timeStepInSeconds),
        chargingStation.zone.tazId,
        chargingStation.zone.id,
        requiredEnergy,
        beamServices,
        "CNM"
      )
    )
  }
}

object SitePowerManager {
  type PowerInKW = Double
  type EnergyInJoules = Double
  type ChargingDurationInSec = Int

  case class PhysicalBounds(
    station: ChargingStation,
    powerLimitUpper: PowerInKW,
    powerLimitLower: PowerInKW,
    lpmWithControlSignal: Double
  )

  /**
    * create unlimited physical bounds
    * @param stations sequence of stations for which to produce physical bounds
    * @return map of physical bounds
    */
  def getUnlimitedPhysicalBounds(stations: Seq[ChargingStation]): Eval[Map[ChargingStation, PhysicalBounds]] = {
    Eval.later {
      stations.map {
        case station @ ChargingStation(zone) =>
          station -> PhysicalBounds(
            station,
            ChargingPointType.getChargingPointInstalledPowerInKw(zone.chargingPointType) * zone.numChargers,
            ChargingPointType.getChargingPointInstalledPowerInKw(zone.chargingPointType) * zone.numChargers,
            0.0
          )
      }.toMap
    }
  }
}
