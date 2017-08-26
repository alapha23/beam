package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.agents.RideHailingManager._
import beam.agentsim.agents.RideHailingAgent.PickupCustomer
import beam.agentsim.agents.util.{AggregatorFactory, SingleActorAggregationResult}
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.{ReservationError, ReservationErrorCode}
import beam.agentsim.events.resources.ReservationErrorCode.ReservationErrorCode
import beam.agentsim.events.resources.vehicle.VehicleUnavailable
import beam.router.{BeamRouter, Modes}
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingRequestTripInfo, RoutingResponse}
import beam.router.Modes.BeamMode._
import beam.router.RoutingModel.{BeamTime, BeamTrip}
import beam.sim.{BeamServices, HasServices}
import com.eaio.uuid.UUIDGen
import org.geotools.geometry.DirectPosition2D
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.vehicles.{Vehicle, VehicleType}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import beam.sim.config.ConfigModule._
/**
  * BEAM
  */

object RideHailingManager {
  val log = LoggerFactory.getLogger(classOf[RideHailingManager])

  def nextTaxiInquiryId = Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[RideHailingInquiry])

  case class RideHailingInquiry(inquiryId: Id[RideHailingInquiry], customerId: Id[PersonAgent], pickUpLocation: Location, departAt: BeamTime, radius: Double, destination: Location)

  case class TravelProposal(rideHailingAgentLocation: RideHailingAgentLocation, timesToCustomer: Long, estimatedPrice: BigDecimal, estimatedTravelTime: Option[Duration])

  case class RideHailingInquiryResponse(inquiryId: Id[RideHailingInquiry], proposals: Vector[TravelProposal], error: Option[ReservationError] = None)
  case object RideUnavailableError extends ReservationError {
    override def errorCode: ReservationErrorCode = ReservationErrorCode.ResourceUnAvailable
  }

  case class ReserveRide(inquiryId: Id[RideHailingInquiry], customerId: Id[PersonAgent], pickUpLocation: Location, departAt: BeamTime, destination: Location)
  case class ReserveRideResponse(inquiryId: Id[RideHailingInquiry], data: Either[ReservationError, RideHailConfirmData])
  case class RideHailConfirmData(rideHailAgent: ActorRef, customerId: Id[PersonAgent], travelProposal: TravelProposal)

  case class RegisterRideAvailable(rideHailingAgent: ActorRef, vehicleId: Id[Vehicle], availableIn: SpaceTime)
  case class RegisterRideUnavailable(ref: ActorRef, location: Coord )
  case object RideUnavailableAck
  case object RideAvailableAck

  case class RideHailingAgentLocation(rideHailAgent: ActorRef, vehicleId: Id[Vehicle], currentLocation: SpaceTime)

  def props(name: String,fares: Map[Id[VehicleType], BigDecimal], fleet: Map[Id[Vehicle], Vehicle],  services: BeamServices) = {
    Props(classOf[RideHailingManager], RideHailingManagerData(name, fares, fleet), services)
  }
}
case class RideHailingManagerData(name: String, fares: Map[Id[VehicleType], BigDecimal],
                                  fleet: Map[Id[Vehicle], Vehicle]) extends BeamAgentData

class RideHailingManager(info: RideHailingManagerData, val beamServices: BeamServices) extends VehicleManager
  with HasServices with AggregatorFactory {
  import scala.collection.JavaConverters._

  val DefaultCostPerMile = BigDecimal(beamServices.beamConfig.beam.taxi.defaultCostPerMile)
  // improve search to take into account time taxi is available
  private val rideHailingAgent = {
    val bbBuffer = beamServices.beamConfig.bbBuffer
    new QuadTree[RideHailingAgentLocation](
      beamServices.bbox.minX - bbBuffer,
      beamServices.bbox.minY - bbBuffer,
      beamServices.bbox.maxX + bbBuffer,
      beamServices.bbox.maxY + bbBuffer)
  }
  private val availableRideHailVehicles = mutable.Map[Id[Vehicle], RideHailingAgentLocation]()
  private val inServiceRideHailVehicles = mutable.Map[Id[Vehicle], RideHailingAgentLocation]()
  //XXX: let's make sorted-map to be get latest and oldest orders to expire some of the
  private val pendingInquiries = mutable.TreeMap[Id[RideHailingInquiry],(TravelProposal, BeamTrip)]()

  private val transform = CRS.findMathTransform(CRS.decode("EPSG:4326", true), CRS.decode(beamServices.beamConfig.beam.routing.gtfs.crs, true), false)

  override def receive: Receive = {
    case RegisterRideAvailable(taxiAgentRef: ActorRef, vehicleId: Id[Vehicle], availableIn: SpaceTime) =>
      // register
      val pos = new DirectPosition2D(availableIn.loc.getX, availableIn.loc.getY)
      val posTransformed = new DirectPosition2D(availableIn.loc.getX, availableIn.loc.getY)
      if (availableIn.loc.getX <= 180.0 & availableIn.loc.getX >= -180.0 & availableIn.loc.getY > -90.0 & availableIn.loc.getY < 90.0) {
        transform.transform(pos, posTransformed)
      }
      val taxiAgentLocation = RideHailingAgentLocation(taxiAgentRef, vehicleId, availableIn)
      rideHailingAgent.put(posTransformed.x,posTransformed.y, taxiAgentLocation)
      availableRideHailVehicles.put(vehicleId, taxiAgentLocation)
      inServiceRideHailVehicles.remove(vehicleId)
      sender ! RideAvailableAck

    case inquiry@RideHailingInquiry(inquiryId, personId, customerPickUp, departAt, radius, destination) =>
      val nearByTaxiAgents = rideHailingAgent.getDisk(inquiry.pickUpLocation.getX, inquiry.pickUpLocation.getY, radius).asScala.toVector
      val distances2taxi = nearByTaxiAgents.map(taxiAgentLocation => {
        val distance = CoordUtils.calcProjectedEuclideanDistance(inquiry.pickUpLocation, taxiAgentLocation.currentLocation.loc)
        (taxiAgentLocation, distance)
      })
      val chosenTaxiLocationOpt = distances2taxi.sortBy(_._2).headOption
      val customerAgent = sender()
      chosenTaxiLocationOpt match {
        case Some((taxiLocation, shortDistanceToTaxi)) =>
//          val params = RoutingRequestParams(departAt, Vector(TAXI), personId)

          val humanBodyVehicle = StreetVehicle(Id.createVehicleId(s"body-${personId.toString}"),SpaceTime((inquiry.pickUpLocation,departAt.atTime)),WALK)

          val customerTripRequestId = BeamRouter.nextId
          val taxi2CustomerRequestId = BeamRouter.nextId
          val taxiVehicle = StreetVehicle(taxiLocation.vehicleId, taxiLocation.currentLocation, CAR)
          val routeRequests = Map(
            beamServices.beamRouter -> List(
              RoutingRequest(customerTripRequestId, RoutingRequestTripInfo(customerPickUp, destination, departAt, Vector(), Vector(humanBodyVehicle,taxiVehicle), personId)),
              RoutingRequest(taxi2CustomerRequestId, RoutingRequestTripInfo(taxiLocation.currentLocation.loc, customerPickUp, departAt, Vector(), Vector(humanBodyVehicle,taxiVehicle), personId)))
          )
          aggregateResponsesTo(customerAgent, routeRequests,Option(self)){ case result: SingleActorAggregationResult =>
            val responses = result.mapListTo[RoutingResponse].map(res => (res.id, res)).toMap
            val (_, timeToCustomer) = responses(taxi2CustomerRequestId).itineraries.map(t => (t, t.totalTravelTime)).minBy(_._2)
            val taxiFare = findVehicle(taxiLocation.vehicleId).flatMap(vehicle => info.fares.get(vehicle.getType.getId)).getOrElse(DefaultCostPerMile)
            val (customerTripPlan, cost) = responses(customerTripRequestId).itineraries.map(t => (t, t.estimateCost(taxiFare).min)).minBy(_._2)
            //TODO: include customerTrip plan in response to reuse( as option BeamTrip can include createdTime to check if the trip plan is still valid
            //TODO: we response with collection of TravelCost to be able to consolidate responses from different ride hailing companies
            log.debug(s"Found taxi $taxiLocation for inquiryId=$inquiryId within $shortDistanceToTaxi miles, timeToCustomer=$timeToCustomer" )
            val travelProposal = TravelProposal(taxiLocation, timeToCustomer, cost, Option(FiniteDuration(customerTripPlan.totalTravelTime, TimeUnit.SECONDS)))
            pendingInquiries.put(inquiryId, (travelProposal, customerTripPlan.toBeamTrip))
            RideHailingInquiryResponse(inquiryId, Vector(travelProposal))
          }
        case None =>
          // no taxi available
          customerAgent !  RideHailingInquiryResponse(inquiryId, Vector(), error = Option(VehicleUnavailable))
      }
    case ReserveRide(inquiryId, personId, customerPickUp, departAt, destination) =>
      //TODO: probably it make sense to add some expiration time (TTL) to pending inquiries
      val travelPlanOpt = pendingInquiries.remove(inquiryId)
      val customerAgent = sender()
      /**
        * 1. customerAgent ! ReserveTaxiConfirmation(taxiAgent, customerId, travelProposal)
        * 2. taxiAgent ! PickupCustomer
        */
      Option(rideHailingAgent.getClosest(customerPickUp.getX, customerPickUp.getY)) match {
        case Some(closestTaxi) if travelPlanOpt.isDefined && closestTaxi == travelPlanOpt.get._1.rideHailingAgentLocation =>
          val travelProposal = travelPlanOpt.get._1
          val tripPlan = travelPlanOpt.map(_._2)
          handleReservation(inquiryId, personId, customerPickUp, destination, customerAgent, closestTaxi, travelProposal, tripPlan)
        case Some(closestTaxi) =>
          handleReservation(inquiryId, closestTaxi, personId, customerPickUp, departAt, destination, customerAgent)
        case None =>
          customerAgent ! ReserveRideResponse(inquiryId, Left(VehicleUnavailable))
      }
    case msg =>
      log.info(s"unknown message received by TaxiManager $msg")
  }

  private def handleReservation(inquiryId: Id[RideHailingInquiry], closestTaxi: RideHailingAgentLocation, personId: Id[PersonAgent], customerPickUp: Location, departAt: BeamTime, destination: Location, customerAgent: ActorRef) = {
//    val params = RoutingRequestParams(departAt, Vector(TAXI), personId)
    val customerTripRequestId = BeamRouter.nextId
    val routeRequests = Map(
      beamServices.beamRouter -> List(
        //TODO update based on new Request spec
        RoutingRequest(customerTripRequestId, RoutingRequestTripInfo(customerPickUp, destination, departAt, Vector(HailedRide), Vector(), personId))
      ))
    aggregateResponsesTo(customerAgent, routeRequests) { case result: SingleActorAggregationResult =>
      val customerTripPlan = result.mapListTo[RoutingResponse].headOption
      val taxiFare = findVehicle(closestTaxi.vehicleId).flatMap(vehicle => info.fares.get(vehicle.getType.getId)).getOrElse(DefaultCostPerMile)
      val tripAndCostOpt = customerTripPlan.map(_.itineraries.map(t => (t, t.estimateCost(taxiFare).min)).minBy(_._2))
      val responseToCustomer = tripAndCostOpt.map { case (tripRoute, cost) =>
        //XXX: we didn't find taxi inquiry in pendingInquiries let's set max pickup time to avoid another routing request
        val timeToCustomer = beamServices.beamConfig.MaxPickupTimeInSeconds
        val travelProposal = TravelProposal(closestTaxi, timeToCustomer, cost, Option(FiniteDuration(tripRoute.totalTravelTime, TimeUnit.SECONDS)))
        val confirmation = ReserveRideResponse(inquiryId, Right(RideHailConfirmData(closestTaxi.rideHailAgent, personId, travelProposal)))
        triggerCustomerPickUp(customerPickUp, destination, closestTaxi, Option(tripRoute.toBeamTrip()), confirmation)
        confirmation
      }.getOrElse {
        ReserveRideResponse(inquiryId, Left(VehicleUnavailable))
      }
      responseToCustomer
    }
  }

  private def handleReservation(inquiryId: Id[RideHailingInquiry], personId: Id[PersonAgent], customerPickUp: Location, destination: Location,
                                customerAgent: ActorRef, closestTaxi: RideHailingAgentLocation, travelProposal: TravelProposal, tripPlan: Option[BeamTrip]) = {
    val confirmation = ReserveRideResponse(inquiryId, Right(RideHailConfirmData(closestTaxi.rideHailAgent, personId, travelProposal)))
    triggerCustomerPickUp(customerPickUp, destination, closestTaxi, tripPlan, confirmation)
    customerAgent ! confirmation
  }

  private def triggerCustomerPickUp(customerPickUp: Location, destination: Location, closestTaxi: RideHailingAgentLocation, tripPlan: Option[BeamTrip], confirmation: ReserveRideResponse) = {
    closestTaxi.rideHailAgent ! PickupCustomer(confirmation, customerPickUp, destination, tripPlan)
    inServiceRideHailVehicles.put(closestTaxi.vehicleId, closestTaxi)
    rideHailingAgent.remove(closestTaxi.currentLocation.loc.getX, closestTaxi.currentLocation.loc.getY, closestTaxi)
  }

  private def findVehicle(resourceId: Id[Vehicle]): Option[Vehicle] = {
    info.fleet.get(resourceId)
  }

  override def findResource(resourceId: Id[Vehicle]): Option[ActorRef] = ???

}
