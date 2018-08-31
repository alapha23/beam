package beam.agentsim.agents.ridehail

import java.awt.Color
import java.util
import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.Resource._
import beam.agentsim.ResourceManager.{NotifyVehicleResourceIdle, VehicleManager}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.ridehail.RideHailIterationHistoryActor.GetCurrentIterationRideHailStats
import beam.agentsim.agents.ridehail.RideHailManager.{RoutingResponses, _}
import beam.agentsim.agents.ridehail.allocation._
import beam.agentsim.agents.vehicles.AccessErrorCodes.{
  CouldNotFindRouteToCustomer,
  DriverNotFoundError,
  RideHailVehicleTakenError
}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{PassengerSchedule, _}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse, _}
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamTime, DiscreteTime}
import beam.sim.{BeamServices, HasServices}
import beam.utils.{DebugLib, PointToPlot, SpatialPlot}
import com.eaio.uuid.UUIDGen
import com.google.common.cache.{Cache, CacheBuilder}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.{concurrent, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future}

object RideHailAgentLocationWithRadiusOrdering extends Ordering[(RideHailAgentLocation, Double)] {
  override def compare(
    o1: (RideHailAgentLocation, Double),
    o2: (RideHailAgentLocation, Double)
  ): Int = {
    java.lang.Double.compare(o1._2, o2._2)
  }
}

// TODO: RW: We need to update the location of vehicle as it is moving to give good estimate to ride hail allocation manager
// TODO: Build RHM from XML to be able to specify different kinds of TNC/Rideshare types and attributes
// TODO: remove name variable, as not used currently in the code anywhere?

class RideHailManager(
  val beamServices: BeamServices,
  val scheduler: ActorRef,
  val router: ActorRef,
  val boundingBox: Envelope,
  val surgePricingManager: RideHailSurgePricingManager
) extends VehicleManager
    with ActorLogging
    with HasServices {

  implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  val radiusInMeters: Double =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.rideHailManager.radiusInMeters
  override val resources: mutable.Map[Id[BeamVehicle], BeamVehicle] =
    mutable.Map[Id[BeamVehicle], BeamVehicle]()

  private val vehicleFuelLevel: mutable.Map[Id[Vehicle], Double] =
    mutable.Map[Id[Vehicle], Double]()

  private val DefaultCostPerMinute = BigDecimal(
    beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute
  )
  private val DefaultCostPerSecond = DefaultCostPerMinute / 60.0d

  private val rideHailAllocationManagerTimeoutInSeconds = {
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.timeoutInSeconds
  }
  val rideHailNetworkApi: RideHailNetworkAPI = new RideHailNetworkAPI()

  val tncIterationStats: Option[TNCIterationStats] = {
    val rideHailIterationHistoryActor =
      context.actorSelection("/user/rideHailIterationHistoryActor")
    val future =
      rideHailIterationHistoryActor.ask(GetCurrentIterationRideHailStats)
    Await
      .result(future, timeout.duration)
      .asInstanceOf[Option[TNCIterationStats]]
  }
  tncIterationStats.foreach(_.logMap())

  val rideHailResourceAllocationManager = RideHailResourceAllocationManager(
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name,
    this
  )

  val modifyPassengerScheduleManager =
    new RideHailModifyPassengerScheduleManager(
      log,
      self,
      rideHailAllocationManagerTimeoutInSeconds,
      scheduler,
      beamServices.beamConfig
    )

  private val handleRideHailInquirySubmitted = mutable.Set[String]()

  var nextCompleteNoticeRideHailAllocationTimeout: CompletionNotice = _

  beamServices.beamRouter ! GetTravelTime
  beamServices.beamRouter ! GetMatSimNetwork

  //TODO improve search to take into account time when available
  private val availableRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }

  private val inServiceRideHailAgentSpatialIndex = {
    new QuadTree[RideHailAgentLocation](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
  }
  private val availableRideHailVehicles =
    concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation]()
  private val inServiceRideHailVehicles =
    concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation]()

  /**
    * Customer inquiries awaiting reservation confirmation.
    */
  lazy val travelProposalCache: Cache[String, TravelProposal] = {
    CacheBuilder
      .newBuilder()
      .maximumSize(
        (10 * beamServices.beamConfig.beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation * beamServices.beamConfig.beam.agentsim.numAgents).toLong
      )
      .expireAfterWrite(1, TimeUnit.MINUTES)
      .build()
  }
  private val pendingModifyPassengerScheduleAcks =
    collection.concurrent.TrieMap[String, RideHailResponse]()
  private var lockedVehicles = Set[Id[Vehicle]]()

  //context.actorSelection("user/")
  //rideHailIterationHistoryActor send message to ridheailiterationhsitoryactor

  DebugLib.emptyFunctionForSettingBreakPoint()

  override def receive: Receive = {
    case ev @ StopDrivingIfNoPassengerOnBoardReply(success, requestId, tick) =>
      Option(travelProposalCache.getIfPresent(requestId.toString)) match {
        case Some(travelProposal) =>
          if (success) {
            travelProposal.rideHailAgentLocation.rideHailAgent ! StopDriving(tick)
            travelProposal.rideHailAgentLocation.rideHailAgent ! Resume()
          }
          rideHailResourceAllocationManager.handleRideCancellationReply(ev)

        case None =>
          log.error(s"request not found: ${ev}")
      }

    case NotifyIterationEnds() =>
      surgePricingManager.incrementIteration()

      sender ! Unit // return empty object to blocking caller

    case RegisterResource(vehId: Id[Vehicle]) =>
      resources.put(agentsim.vehicleId2BeamVehicleId(vehId), beamServices.vehicles(vehId))

    case NotifyVehicleResourceIdle(
        vehicleId: Id[Vehicle],
        whenWhere,
        passengerSchedule,
        fuelLevel
        ) =>
      updateLocationOfAgent(vehicleId, whenWhere, isAvailable = isAvailable(vehicleId))

      //updateLocationOfAgent(vehicleId, whenWhere, isAvailable = true)
      resources(agentsim.vehicleId2BeamVehicleId(vehicleId)).driver
        .foreach(driver => {
          val rideHailAgentLocation =
            RideHailAgentLocation(driver, vehicleId, whenWhere)
          if (modifyPassengerScheduleManager
                .noPendingReservations(vehicleId) || modifyPassengerScheduleManager
                .isPendingReservationEnding(vehicleId, passengerSchedule)) {
            log.debug("Making available: {}", vehicleId)
            // we still might have some ongoing resrvation in going on
            makeAvailable(rideHailAgentLocation)
          }
          modifyPassengerScheduleManager
            .checkInResource(vehicleId, Some(whenWhere), Some(passengerSchedule))
          vehicleFuelLevel.put(vehicleId, fuelLevel)
        })

      // ridehail agent awaiting NotifyVehicleResourceIdleReply
      sender() ! NotifyVehicleResourceIdleReply(Vector[ScheduleTrigger]())

    case NotifyResourceInUse(vehId: Id[Vehicle], whenWhere) =>
      updateLocationOfAgent(vehId, whenWhere, isAvailable = false)

    case BeamVehicleFuelLevelUpdate(id, fuelLevel) =>
      vehicleFuelLevel.put(id, fuelLevel)

    case MATSimNetwork(network) =>
      rideHailNetworkApi.setMATSimNetwork(network)

    case CheckInResource(vehicleId: Id[Vehicle], whenWhere) =>
      updateLocationOfAgent(vehicleId, whenWhere.get, isAvailable = true)

      if (whenWhere.get.time == 0) {
        resources(agentsim.vehicleId2BeamVehicleId(vehicleId)).driver
          .foreach(driver => {
            val rideHailAgentLocation =
              RideHailAgentLocation(driver, vehicleId, whenWhere.get)
            if (modifyPassengerScheduleManager
                  .noPendingReservations(vehicleId)) {
              // we still might have some ongoing resrvation in going on
              log.debug("Checking in: {}", vehicleId)
              makeAvailable(rideHailAgentLocation)
            }
            sender ! CheckInSuccess
            log.debug(
              "checking in resource: vehicleId({});availableIn.time({})",
              vehicleId,
              whenWhere.get.time
            )
            modifyPassengerScheduleManager.checkInResource(vehicleId, whenWhere, None)
            driver ! GetBeamVehicleFuelLevel
          })
      }

    case CheckOutResource(_) =>
      // Because the RideHail Manager is in charge of deciding which specific vehicles to assign to customers, this should never be used
      throw new RuntimeException(
        "Illegal use of CheckOutResource, RideHailManager is responsible for checking out vehicles in fleet."
      )

    case inquiry @ RideHailRequest(RideHailInquiry, _, _, _, _) =>
      findDriverAndSendRoutingRequests(inquiry)

    case R5Network(network) =>
      rideHailNetworkApi.setR5Network(network)

    // In the following case, we have responses but no RHAgent defined, which means we are calculating routes
    // for the allocation manager, so we resume the allocation process.
    case RoutingResponses(request, None, responses) =>
//      println(s"got routingResponse: ${request.requestId} with no RHA")
      findDriverAndSendRoutingRequests(request, responses)

    case RoutingResponses(
        request,
        Some(rideHailLocation),
        responses
        ) =>
//      println(s"got routingResponse: ${request.requestId} with RHA ${rideHailLocation.vehicleId}")
      val itins = responses.map { response =>
        response.itineraries.filter(_.tripClassifier.equals(RIDE_HAIL))
      }

      // We can rely on preserved ordering here (see RideHailManager.requestRoutes),
      // for a simple single-occupant trip sequence, we know that first
      // itin is RH2Customer and second is Pickup2Destination.
      // TODO generalize the processing below to allow for pooling
      val itins2Cust = itins.head
      val itins2Dest = itins.last

      val rideHailFarePerSecond = DefaultCostPerSecond * surgePricingManager
        .getSurgeLevel(
          request.pickUpLocation,
          request.departAt.atTime.toDouble
        )
      val customerPlans2Costs: Map[RoutingModel.EmbodiedBeamTrip, BigDecimal] =
        itins2Dest.map(trip => (trip, rideHailFarePerSecond * trip.totalTravelTimeInSecs)).toMap

      if (itins2Cust.nonEmpty && itins2Dest.nonEmpty) {
        val (customerTripPlan, cost) = customerPlans2Costs.minBy(_._2)
        val tripDriver2Cust = RoutingResponse(
          Vector(
            itins2Cust.head.copy(legs = itins2Cust.head.legs.map(l => l.copy(asDriver = true)))
          )
        )
        val timeToCustomer =
          tripDriver2Cust.itineraries.head.totalTravelTimeInSecs

        val tripCust2Dest = RoutingResponse(
          Vector(
            customerTripPlan.copy(
              legs = customerTripPlan.legs.zipWithIndex.map(
                legWithInd =>
                  legWithInd._1.copy(
                    asDriver = legWithInd._1.beamLeg.mode == WALK,
                    unbecomeDriverOnCompletion = legWithInd._2 == 2,
                    beamLeg = legWithInd._1.beamLeg
                      .copy(startTime = legWithInd._1.beamLeg.startTime + timeToCustomer),
                    cost =
                      if (legWithInd._1.beamLeg == customerTripPlan
                            .legs(1)
                            .beamLeg) {
                        cost
                      } else {
                        0.0
                      }
                )
              )
            )
          )
        )

        val travelProposal = TravelProposal(
          rideHailLocation,
          timeToCustomer,
          cost,
          Some(FiniteDuration(customerTripPlan.totalTravelTimeInSecs, TimeUnit.SECONDS)),
          tripDriver2Cust,
          tripCust2Dest
        )

        travelProposalCache.put(request.requestId.toString, travelProposal)

        //        log.debug(s"Found ridehail ${rideHailLocation.vehicleId} for person=${request.customer.personId} and ${request.requestType} " +
        //          s"requestId=${request.requestId}, timeToCustomer=$timeToCustomer seconds and cost=$$$cost")

        request.requestType match {
          case RideHailInquiry =>
            request.customer.personRef.get ! RideHailResponse(request, Some(travelProposal))
          case ReserveRide =>
            self ! request
        }
      } else {
        log.debug(
          "Router could not find route to customer person={} for requestId={}",
          request.customer.personId,
          request.requestId
        )
        request.customer.personRef.get ! RideHailResponse(
          request,
          None,
          Some(CouldNotFindRouteToCustomer)
        )
      }

    case reserveRide @ RideHailRequest(ReserveRide, _, _, _, _) =>
      //  if (rideHailResourceAllocationManager.isBufferedRideHailAllocationMode) {
      //    val requestId = reserveRide.requestId
      //    bufferedReserveRideMessages += (requestId.toString -> reserveRide)
      //System.out.println("")
      //  } else {
      handleReservationRequest(reserveRide)
    //  }

    case modifyPassengerScheduleAck @ ModifyPassengerScheduleAck(
          requestIdOpt,
          triggersToSchedule,
          _
        ) =>
      log.debug("modifyPassengerScheduleAck received: " + modifyPassengerScheduleAck)
      requestIdOpt match {
        case None =>
          modifyPassengerScheduleManager
            .modifyPassengerScheduleAckReceivedForRepositioning(
              triggersToSchedule
            )
        // val newTriggers = triggersToSchedule ++ nextCompleteNoticeRideHailAllocationTimeout.newTriggers
        // scheduler ! CompletionNotice(nextCompleteNoticeRideHailAllocationTimeout.id, newTriggers)
        case Some(requestId) =>
          completeReservation(requestId, triggersToSchedule)
      }

    case UpdateTravelTime(travelTime) =>
      rideHailNetworkApi.setTravelTime(travelTime)

    case DebugRideHailManagerDuringExecution =>
      modifyPassengerScheduleManager.printState()

    case TriggerWithId(BufferedRideHailRequestsTimeout(tick), triggerId) =>
      rideHailResourceAllocationManager.updateVehicleAllocations(tick, triggerId, this)

    case TriggerWithId(RideHailAllocationManagerTimeout(tick), triggerId) =>
      val produceDebugImages = true
      if (produceDebugImages) {
        if (tick > 0 && tick.toInt % 3600 == 0 && tick < 24 * 3600) {
          val spatialPlot = new SpatialPlot(1100, 1100, 50)

          for (veh <- resources.values) {
            spatialPlot.addPoint(
              PointToPlot(getRideHailAgentLocation(veh.id).currentLocation.loc, Color.BLACK, 5)
            )
          }

          tncIterationStats.foreach(tncIterationStats => {

            val tazEntries = tncIterationStats getCoordinatesWithRideHailStatsEntry (tick, tick + 3600)

            for (tazEntry <- tazEntries.filter(x => x._2.sumOfRequestedRides > 0)) {
              spatialPlot.addPoint(
                PointToPlot(
                  tazEntry._1,
                  Color.RED,
                  10 + Math.log(tazEntry._2.sumOfRequestedRides).toInt
                )
              )
            }
          })

          val iteration = "it." + beamServices.iterationNumber
          spatialPlot.writeImage(
            beamServices.matsimServices.getControlerIO
              .getIterationFilename(
                beamServices.iterationNumber,
                tick.toInt / 3600 + "locationOfAgentsInitally.png"
              )
              .replace(iteration, iteration + "/rideHailDebugging")
          )
        }
      }

      modifyPassengerScheduleManager.startWaiveOfRepositioningRequests(tick, triggerId)

      log.debug("getIdleVehicles().size:{}", getIdleVehicles.size)
      getIdleVehicles.foreach(x => log.debug("getIdleVehicles(): {}", x._1.toString))

      val repositionVehicles: Vector[(Id[Vehicle], Location)] =
        rideHailResourceAllocationManager.repositionVehicles(tick)

      if (repositionVehicles.isEmpty) {
        modifyPassengerScheduleManager
          .sendoutAckMessageToSchedulerForRideHailAllocationmanagerTimeout()
      } else {
        modifyPassengerScheduleManager.setNumberOfRepositioningsToProcess(repositionVehicles.size)
        //   printRepositionDistanceSum(repositionVehicles)
      }

      for (repositionVehicle <- repositionVehicles) {

        val (vehicleId, destinationLocation) = repositionVehicle

        if (getIdleVehicles.contains(vehicleId)) {
          val rideHailAgentLocation = getIdleVehicles(vehicleId)

          val rideHailAgent = rideHailAgentLocation.rideHailAgent

          val rideHailVehicleAtOrigin = StreetVehicle(
            rideHailAgentLocation.vehicleId,
            SpaceTime((rideHailAgentLocation.currentLocation.loc, tick.toLong)),
            CAR,
            asDriver = false
          )

          val departAt = DiscreteTime(tick.toInt)

          val routingRequest = RoutingRequest(
            origin = rideHailAgentLocation.currentLocation.loc,
            destination = destinationLocation,
            departureTime = departAt,
            transitModes = Vector(),
            streetVehicles = Vector(rideHailVehicleAtOrigin)
          )
          val futureRideHailAgent2CustomerResponse = router ? routingRequest

          for {
            rideHailAgent2CustomerResponse <- futureRideHailAgent2CustomerResponse
              .mapTo[RoutingResponse]
          } {
            val itins2Cust = rideHailAgent2CustomerResponse.itineraries.filter(
              x => x.tripClassifier.equals(RIDE_HAIL)
            )

            if (itins2Cust.nonEmpty) {
              val modRHA2Cust: IndexedSeq[RoutingModel.EmbodiedBeamTrip] =
                itins2Cust
                  .map(l => l.copy(legs = l.legs.map(c => c.copy(asDriver = true))))
                  .toIndexedSeq
              val rideHailAgent2CustomerResponseMod =
                RoutingResponse(modRHA2Cust, Some(routingRequest.requestId))

              // TODO: extract creation of route to separate method?
              val passengerSchedule = PassengerSchedule().addLegs(
                rideHailAgent2CustomerResponseMod.itineraries.head.toBeamTrip.legs
              )

              self ! RepositionVehicleRequest(passengerSchedule, tick, vehicleId, rideHailAgent)

            } else {
              self ! ReduceAwaitingRepositioningAckMessagesByOne
            }
          }

        } else {
          modifyPassengerScheduleManager
            .modifyPassengerScheduleAckReceivedForRepositioning(
              Vector()
            )
        }
      }

    case ReduceAwaitingRepositioningAckMessagesByOne =>
      modifyPassengerScheduleManager
        .modifyPassengerScheduleAckReceivedForRepositioning(Vector())

    case RepositionVehicleRequest(passengerSchedule, tick, vehicleId, rideHailAgent) =>
      // TODO: send following to a new case, which handles it
      // -> code for sending message could be prepared in modifyPassengerScheduleManager
      // e.g. create case class
      log.debug(
        "RideHailAllocationManagerTimeout: requesting to send interrupt message to vehicle for repositioning: " + vehicleId
      )
      modifyPassengerScheduleManager.repositionVehicle(
        passengerSchedule,
        tick,
        vehicleId,
        rideHailAgent
      )
    //repositioningPassengerSchedule.put(vehicleId,(rideHailAgentInterruptId, Some(passengerSchedule)))

    case InterruptedWhileIdle(interruptId, vehicleId, tick) =>
      modifyPassengerScheduleManager.handleInterrupt(
        InterruptedWhileIdle.getClass,
        interruptId,
        None,
        vehicleId,
        tick
      )

    case InterruptedAt(interruptId, interruptedPassengerSchedule, _, vehicleId, tick) =>
      modifyPassengerScheduleManager.handleInterrupt(
        InterruptedAt.getClass,
        interruptId,
        Some(interruptedPassengerSchedule),
        vehicleId,
        tick
      )

    case Finish =>
      log.info("finish message received from BeamAgentScheduler")

    case msg =>
      log.warning(s"unknown message received by RideHailManager $msg")

  }

  private def printRepositionDistanceSum(
    repositionVehicles: Vector[(Id[Vehicle], Location)]
  ): Unit = {
    var sumOfDistances: Double = 0
    var numberOfTrips = 0
    for (repositionVehicle <- repositionVehicles) {
      val (vehicleId, destinationLocation) = repositionVehicle
      val rideHailAgentLocation = getIdleVehicles(vehicleId)

      sumOfDistances += beamServices.geo
        .distInMeters(rideHailAgentLocation.currentLocation.loc, destinationLocation)
      numberOfTrips += 1
    }

    //println(s"sumOfDistances: $sumOfDistances - numberOfTrips: $numberOfTrips")

    DebugLib.emptyFunctionForSettingBreakPoint()
  }

  // Returns Boolean indicating success/failure
  def findDriverAndSendRoutingRequests(
    request: RideHailRequest,
    responses: List[RoutingResponse] = List()
  ): Unit = {

    val vehicleAllocationRequest = VehicleAllocationRequest(request, responses)

    val vehicleAllocationResponse =
      rideHailResourceAllocationManager.proposeVehicleAllocation(vehicleAllocationRequest)

    vehicleAllocationResponse match {
      case VehicleAllocation(agentLocation, None) =>
//        println(s"${request.requestId} -- VehicleAllocation(${agentLocation.vehicleId}, None)")
        requestRoutes(
          request,
          Some(agentLocation),
          createRoutingRequestsToCustomerAndDestination(request, agentLocation)
        )
      case VehicleAllocation(agentLocation, Some(routingResponses)) =>
//        println(s"${request.requestId} -- VehicleAllocation(agentLocation, Some())")
        self ! RoutingResponses(request, Some(agentLocation), routingResponses)
      case RoutingRequiredToAllocateVehicle(_, routesRequired) =>
//        println(s"${request.requestId} -- RoutingRequired")
        requestRoutes(request, None, routesRequired)
      case NoVehicleAllocated =>
//        println(s"${request.requestId} -- NoVehicleAllocated")
        request.customer.personRef.get ! RideHailResponse(request, None, Some(DriverNotFoundError))
    }
  }

  private def getRideHailAgent(vehicleId: Id[Vehicle]): ActorRef = {
    getRideHailAgentLocation(vehicleId).rideHailAgent
  }

  def getRideHailAgentLocation(vehicleId: Id[Vehicle]): RideHailAgentLocation = {
    getIdleVehicles.getOrElse(vehicleId, inServiceRideHailVehicles(vehicleId))
  }

  /*
  //TODO requires proposal in cache
  private def findClosestRideHailAgents(requestId: Int, customerPickUp: Location) = {
    val travelPlanOpt = Option(travelProposalCache.asMap.remove(requestId))
    /**
   * 1. customerAgent ! ReserveRideConfirmation(availableRideHailAgentSpatialIndex, customerId, travelProposal)
   * 2. availableRideHailAgentSpatialIndex ! PickupCustomer
   */
    val nearbyRideHailAgents = availableRideHailAgentSpatialIndex.getDisk(customerPickUp.getX, customerPickUp.getY,
      radiusInMeters).asScala.toVector
    val closestRHA: Option[RideHailAgentLocation] = nearbyRideHailAgents.filter(x =>
      lockedVehicles(x.vehicleId)).find(_.vehicleId.equals(travelPlanOpt.get.responseRideHail2Pickup
      .itineraries.head.vehiclesInTrip.head))
    (travelPlanOpt, closestRHA)
  }
   */

  private def isAvailable(vehicleId: Id[Vehicle]): Boolean = {
    availableRideHailVehicles.contains(vehicleId)
  }

  private def updateLocationOfAgent(
    vehicleId: Id[Vehicle],
    whenWhere: SpaceTime,
    isAvailable: Boolean
  ) = {
    if (isAvailable) {
      availableRideHailVehicles.get(vehicleId) match {
        case Some(prevLocation) =>
          val newLocation = prevLocation.copy(currentLocation = whenWhere)
          availableRideHailAgentSpatialIndex.remove(
            prevLocation.currentLocation.loc.getX,
            prevLocation.currentLocation.loc.getY,
            prevLocation
          )
          availableRideHailAgentSpatialIndex.put(
            newLocation.currentLocation.loc.getX,
            newLocation.currentLocation.loc.getY,
            newLocation
          )
          availableRideHailVehicles.put(newLocation.vehicleId, newLocation)
        case None =>
      }
    } else {
      inServiceRideHailVehicles.get(vehicleId) match {
        case Some(prevLocation) =>
          val newLocation = prevLocation.copy(currentLocation = whenWhere)
          inServiceRideHailAgentSpatialIndex.remove(
            prevLocation.currentLocation.loc.getX,
            prevLocation.currentLocation.loc.getY,
            prevLocation
          )
          inServiceRideHailAgentSpatialIndex.put(
            newLocation.currentLocation.loc.getX,
            newLocation.currentLocation.loc.getY,
            newLocation
          )
          inServiceRideHailVehicles.put(newLocation.vehicleId, newLocation)
        case None =>
      }
    }
  }

  private def makeAvailable(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    availableRideHailAgentSpatialIndex.put(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.remove(agentLocation.vehicleId)
    inServiceRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
  }

  private def putIntoService(agentLocation: RideHailAgentLocation) = {
    availableRideHailVehicles.remove(agentLocation.vehicleId)
    availableRideHailAgentSpatialIndex.remove(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
    inServiceRideHailVehicles.put(agentLocation.vehicleId, agentLocation)
    inServiceRideHailAgentSpatialIndex.put(
      agentLocation.currentLocation.loc.getX,
      agentLocation.currentLocation.loc.getY,
      agentLocation
    )
  }

  private def handleReservation(request: RideHailRequest, travelProposal: TravelProposal): Unit = {

    surgePricingManager.addRideCost(
      request.departAt.atTime,
      travelProposal.estimatedPrice.doubleValue(),
      request.pickUpLocation
    )

    // Modify RH agent passenger schedule and create BeamAgentScheduler message that will dispatch RH agent to do the
    // pickup
    val passengerSchedule = PassengerSchedule()
      .addLegs(travelProposal.responseRideHail2Pickup.itineraries.head.toBeamTrip.legs) // Adds empty trip to customer
      .addPassenger(
        request.customer,
        travelProposal.responseRideHail2Dest.itineraries.head.legs
          .filter(_.isRideHail)
          .map(_.beamLeg)
      ) // Adds customer's actual trip to destination
    putIntoService(travelProposal.rideHailAgentLocation)
    lockVehicle(travelProposal.rideHailAgentLocation.vehicleId)

    // Create confirmation info but stash until we receive ModifyPassengerScheduleAck
    //val tripLegs = travelProposal.responseRideHail2Dest.itineraries.head.legs.map(_.beamLeg)
    pendingModifyPassengerScheduleAcks.put(
      request.requestId.toString,
      RideHailResponse(request, Some(travelProposal))
    )

    log.debug(
      "Reserving vehicle: {} customer: {} request: {}",
      travelProposal.rideHailAgentLocation.vehicleId,
      request.customer.personId,
      request.requestId
    )

    modifyPassengerScheduleManager.reserveVehicle(
      passengerSchedule,
      passengerSchedule.schedule.head._1.startTime,
      travelProposal.rideHailAgentLocation,
      Some(request.requestId)
    )
  }

  private def completeReservation(
    requestId: java.util.UUID,
    triggersToSchedule: Seq[ScheduleTrigger]
  ): Unit = {
    pendingModifyPassengerScheduleAcks.remove(requestId.toString) match {
      case Some(response) =>
        log.debug("Completing reservation for {}", requestId)
        unlockVehicle(response.travelProposal.get.rideHailAgentLocation.vehicleId)

        log.debug(
          s"completing reservation - customer: ${response.request.customer.personId} " +
          s"- vehicle: ${response.travelProposal.get.rideHailAgentLocation.vehicleId}"
        )

        val bufferedRideHailRequests = rideHailResourceAllocationManager.bufferedRideHailRequests

        if (bufferedRideHailRequests.isReplacementVehicle(
              response.travelProposal.get.rideHailAgentLocation.vehicleId
            )) {

          bufferedRideHailRequests.addTriggerMessages(triggersToSchedule.toVector)

          bufferedRideHailRequests.replacementVehicleReservationCompleted(
            response.travelProposal.get.rideHailAgentLocation.vehicleId
          )

          bufferedRideHailRequests.tryClosingBufferedRideHailRequestWaive()
        } else {
          response.request.customer.personRef.get ! response.copy(
            triggersToSchedule = triggersToSchedule.toVector
          )
        }
      case None =>
        log.error(s"Vehicle was reserved by another agent for inquiry id $requestId")
        sender() ! RideHailResponse.dummyWithError(RideHailVehicleTakenError)
    }
  }

  def getClosestIdleVehiclesWithinRadius(
    pickupLocation: Coord,
    radius: Double
  ): Array[RideHailAgentLocation] = {
    val idleVehicles = getIdleVehiclesWithinRadius(pickupLocation, radius).toArray
    util.Arrays.sort(idleVehicles, RideHailAgentLocationWithRadiusOrdering)
    idleVehicles.map { case (location, _) => location }
  }

  def getIdleVehiclesWithinRadius(
    pickupLocation: Location,
    radius: Double
  ): Iterable[(RideHailAgentLocation, Double)] = {
    val nearbyRideHailAgents = availableRideHailAgentSpatialIndex
      .getDisk(pickupLocation.getX, pickupLocation.getY, radius)
      .asScala
      .view
    val distances2RideHailAgents =
      nearbyRideHailAgents.map(rideHailAgentLocation => {
        val distance = CoordUtils
          .calcProjectedEuclideanDistance(pickupLocation, rideHailAgentLocation.currentLocation.loc)
        (rideHailAgentLocation, distance)
      })
    //TODO: Possibly get multiple taxis in this block
    distances2RideHailAgents
      .filter(x => availableRideHailVehicles.contains(x._1.vehicleId))
  }

  def getClosestIdleRideHailAgent(
    pickupLocation: Coord,
    radius: Double
  ): Option[RideHailAgentLocation] = {
    val idleVehicles = getIdleVehiclesWithinRadius(pickupLocation, radius)
    if (idleVehicles.isEmpty) None
    else {
      val min = idleVehicles.min(RideHailAgentLocationWithRadiusOrdering)
      Some(min._1)
    }
  }

  private def handleReservationRequest(request: RideHailRequest): Unit = {
    //    log.debug(s"handleReservationRequest: $request")
    Option(travelProposalCache.getIfPresent(request.requestId.toString)) match {
      case Some(travelProposal) =>
        if (inServiceRideHailVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId) ||
            lockedVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId)) {
          findDriverAndSendRoutingRequests(request)
        } else {
          handleReservation(request, travelProposal)

        }
      case None =>
        findDriverAndSendRoutingRequests(request)
    }
  }

  def attemptToCancelCurrentRideRequest(tick: Double, requestId: java.util.UUID): Unit = {
    Option(travelProposalCache.getIfPresent(requestId.toString)) match {
      case Some(travelProposal) =>
        log.debug(
          s"trying to stop vehicle: ${travelProposal.rideHailAgentLocation.vehicleId}, tick: $tick"
        )
        travelProposal.rideHailAgentLocation.rideHailAgent ! StopDrivingIfNoPassengerOnBoard(
          tick,
          requestId
        )

      case None =>
    }

  }

  def unlockVehicle(vehicleId: Id[Vehicle]): Unit = {
    lockedVehicles -= vehicleId
  }

  def lockVehicle(vehicleId: Id[Vehicle]): Unit = {
    lockedVehicles += vehicleId
  }

  def getVehicleFuelLevel(vehicleId: Id[Vehicle]): Double =
    vehicleFuelLevel(vehicleId)

  def getIdleVehicles: collection.concurrent.TrieMap[Id[Vehicle], RideHailAgentLocation] = {
    availableRideHailVehicles
  }

  def cleanCurrentPickupAssignment(request: RideHailRequest) = {
    //vehicleAllocationRequest.request, vehicleId: Id[Vehicle], tick:Double

    val tick = 0.0 // TODO: get tick of timeout here

    Option(travelProposalCache.getIfPresent(request.requestId.toString)) match {
      case Some(travelProposal) =>
        if (inServiceRideHailVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId) ||
            lockedVehicles.contains(travelProposal.rideHailAgentLocation.vehicleId)) {
          val rideHailAgent = getRideHailAgent(travelProposal.rideHailAgentLocation.vehicleId)
          // TODO: this creates friction with the interrupt Id -> go through the passenger schedule manager?
          rideHailAgent ! Interrupt(
            Id.create(travelProposal.rideHailAgentLocation.vehicleId.toString, classOf[Interrupt]),
            tick
          )
        } else {
          // TODO: provide input to caller to change option resp. test this?
        }
      case None =>
      // TODO: provide input to caller to change option resp. test this?
    }

  }

  def createRoutingRequestsToCustomerAndDestination(
    request: RideHailRequest,
    rideHailLocation: RideHailAgentLocation
  ): List[RoutingRequest] = {

    val pickupSpaceTime = SpaceTime((request.pickUpLocation, request.departAt.atTime))
    val customerAgentBody =
      StreetVehicle(request.customer.vehicleId, pickupSpaceTime, WALK, asDriver = true)
    val rideHailVehicleAtOrigin = StreetVehicle(
      rideHailLocation.vehicleId,
      SpaceTime((rideHailLocation.currentLocation.loc, request.departAt.atTime)),
      CAR,
      asDriver = false
    )
    val rideHailVehicleAtPickup =
      StreetVehicle(rideHailLocation.vehicleId, pickupSpaceTime, CAR, asDriver = false)

    // route from ride hailing vehicle to customer
    val rideHailAgent2Customer = RoutingRequest(
      rideHailLocation.currentLocation.loc,
      request.pickUpLocation,
      request.departAt,
      Vector(),
      Vector(rideHailVehicleAtOrigin)
    )
    // route from customer to destination
    val rideHail2Destination = RoutingRequest(
      request.pickUpLocation,
      request.destination,
      request.departAt,
      Vector(),
      Vector(customerAgentBody, rideHailVehicleAtPickup)
    )

    List(rideHailAgent2Customer, rideHail2Destination)
  }

  def requestRoutes(
    rideHailRequest: RideHailRequest,
    rideHailAgentLocation: Option[RideHailAgentLocation],
    routingRequests: List[RoutingRequest]
  ) = {
    val preservedOrder = routingRequests.map(_.requestId)
//    print(s"Routing reqs for RHReq ${rideHailRequest.requestId}: ")
//    routingRequests.foreach(req => print(s"${req.requestId}, "))
//    println("")
    Future
      .sequence(routingRequests.map { req =>
        akka.pattern.ask(router, req).mapTo[RoutingResponse]
      })
      .foreach { responseList =>
//        print(s"Routing responses for RHReq ${rideHailRequest.requestId}: ")
//        responseList.foreach(req => print(s"${req.requestId}, "))
//        println("")
        val requestIdToResponse = responseList.map { response =>
          (response.requestId.get -> response)
        }.toMap
        val orderedResponses = preservedOrder.map(requestId => requestIdToResponse(requestId))
        self ! RoutingResponses(rideHailRequest, rideHailAgentLocation, orderedResponses)
      }
  }

}

object RideHailManager {

  val dummyID: Id[RideHailRequest] =
    Id.create("dummyInquiryId", classOf[RideHailRequest])

  val INITIAL_RIDEHAIL_LOCATION_HOME = "HOME"
  val INITIAL_RIDEHAIL_LOCATION_UNIFORM_RANDOM = "UNIFORM_RANDOM"
  val INITIAL_RIDEHAIL_LOCATION_ALL_AT_CENTER = "ALL_AT_CENTER"
  val INITIAL_RIDEHAIL_LOCATION_ALL_IN_CORNER = "ALL_IN_CORNER"

  def nextRideHailInquiryId: Id[RideHailRequest] = {
    Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailRequest])
  }

  val dummyRideHailVehicleId = Id.createVehicleId("dummyRideHailVehicle")

  case class NotifyIterationEnds()

  case class TravelProposal(
    rideHailAgentLocation: RideHailAgentLocation,
    timeToCustomer: Long,
    estimatedPrice: BigDecimal,
    estimatedTravelTime: Option[Duration],
    responseRideHail2Pickup: RoutingResponse,
    responseRideHail2Dest: RoutingResponse
  ) {
    override def toString(): String =
      s"RHA: ${rideHailAgentLocation.vehicleId}, waitTime: ${timeToCustomer}, price: ${estimatedPrice}, travelTime: ${estimatedTravelTime}"
  }

  case class RoutingResponses(
    request: RideHailRequest,
    rideHailLocation: Option[RideHailAgentLocation],
    routingResponses: List[RoutingResponse]
  )

  case class RegisterRideAvailable(
    rideHailAgent: ActorRef,
    vehicleId: Id[Vehicle],
    availableSince: SpaceTime
  )

  case class RegisterRideUnavailable(ref: ActorRef, location: Coord)

  case class RideHailAgentLocation(
    rideHailAgent: ActorRef,
    vehicleId: Id[Vehicle],
    currentLocation: SpaceTime
  ) {

    def toStreetVehicle(): StreetVehicle = {
      StreetVehicle(vehicleId, currentLocation, CAR, true)
    }
  }

  case object RideUnavailableAck

  case object RideAvailableAck

  case object DebugRideHailManagerDuringExecution

  case class RepositionResponse(
    rnd1: RideHailAgentLocation,
    rnd2: RideHailManager.RideHailAgentLocation,
    rnd1Response: RoutingResponse,
    rnd2Response: RoutingResponse
  )

  case class BufferedRideHailRequestsTimeout(tick: Double) extends Trigger

  case class RideHailAllocationManagerTimeout(tick: Double) extends Trigger

  def props(
    services: BeamServices,
    scheduler: ActorRef,
    router: ActorRef,
    boundingBox: Envelope,
    surgePricingManager: RideHailSurgePricingManager
  ): Props = {
    Props(new RideHailManager(services, scheduler, router, boundingBox, surgePricingManager))
  }
}
