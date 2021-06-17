package beam.agentsim.agents.ridehail

import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.ridehail.ParkingZoneDepotData.ChargingQueueEntry
import beam.agentsim.agents.ridehail.RideHailManager.{RefuelSource, VehicleId}
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.infrastructure.parking.{GeoLevel, ParkingZone, ParkingZoneId}
import beam.agentsim.infrastructure.{ChargingNetwork, ParkingStall}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter.Location
import beam.sim.Geofence
import org.matsim.api.core.v01.Id

import scala.collection.mutable

abstract class RideHailDepotParkingManager[GEO: GeoLevel](
  vehicleManagerId: Id[VehicleManager],
  parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]]
) extends ChargingNetwork[GEO](vehicleManagerId, parkingZones) {

  override def processReleaseParkingStall(release: ReleaseParkingStall): Boolean = {
    if (!parkingZones.contains(release.stall.parkingZoneId)) {
      false
    } else {
      val parkingZone: ParkingZone[GEO] = parkingZones(release.stall.parkingZoneId)
      val success = ParkingZone.releaseStall(parkingZone)
      if (success) {
        totalStallsInUse -= 1
        totalStallsAvailable += 1
      }
      success
    }
  }

  /**
    * Assigns a [[ParkingStall]] to a CAV Ride Hail vehicle.
    *
    * @param locationUtm the position of this agent
    * @param beamVehicle the [[BeamVehicle]] associated with the driver
    * @param currentTick
    * @param findDepotAttributes extensible data structure allowing customization of data to be passed to DepotManager
    * @return the ParkingStall, or, nothing if no parking is available
    */
  def findDepot(
    locationUtm: Location,
    beamVehicle: BeamVehicle,
    currentTick: Int,
    findDepotAttributes: Option[FindDepotAttributes] = None
  ): Option[ParkingStall]

  /**
    * Notify this [[RideHailDepotParkingManager]] that a vehicles is no longer on the way to the depot.
    *
    * @param vehicleId
    * @return the optional [[ParkingStall]] of the vehicle if it was found in the internal tracking, None if
    *         the vehicle was not found.
    */
  def notifyVehicleNoLongerOnWayToRefuelingDepot(vehicleId: VehicleId): Option[ParkingStall]

  /**
    * Makes an attempt to "claim" the parking stall passed in as an argument and returns a [[StartRefuelSessionTrigger]]
    * or puts the vehicle into a queue.
    *
    * @param beamVehicle
    * @param originalParkingStallFoundDuringAssignment
    * @param tick
    * @param vehicleQueuePriority
    * @param source
    * @return vector of [[ScheduleTrigger]] objects
    */
  def attemptToRefuel(
    beamVehicle: BeamVehicle,
    originalParkingStallFoundDuringAssignment: ParkingStall,
    tick: Int,
    vehicleQueuePriority: Double,
    source: RefuelSource
  ): (Vector[ScheduleTrigger], Option[Id[ParkingZoneId]])

  /**
    * This vehicle is no longer charging and should be removed from internal tracking data.
    *
    * @param vehicle
    * @return the stall if found and successfully removed
    */
  def removeFromCharging(vehicle: VehicleId, tick: Int): Option[ParkingStall]

  /**
    * Given a parkingZoneId, dequeue the next vehicle that is waiting to charge.
    *
    * @param parkingZoneId
    * @param tick
    * @return
    */
  def dequeueNextVehicleForRefuelingFrom(
    parkingZoneId: Id[ParkingZoneId],
    tick: Int
  ): Option[ChargingQueueEntry]

  /**
    * Give depot manager opportunity to internalize geofences associated with fleet.
    *
    * @param vehicleIdToGeofenceMap
    */
  def registerGeofences(vehicleIdToGeofenceMap: mutable.Map[VehicleId, Option[Geofence]])

  /**
    * Is the [[vehicleId]] currently on the way to a refueling depot to charge or actively charging?
    *
    * @param vehicleId
    * @return
    */
  def isOnWayToRefuelingDepotOrIsRefuelingOrInQueue(vehicleId: VehicleId): Boolean

  /**
    * Notify this [[RideHailDepotParkingManager]] that vehicles are on the way to the depot for the purpose of refueling.
    *
    * @param newVehiclesHeadedToDepot
    */
  def notifyVehiclesOnWayToRefuelingDepot(newVehiclesHeadedToDepot: Vector[(VehicleId, ParkingStall)]): Unit

  /**
    * Is the [[vehicleId]] currently on the way to a refueling depot to charge?
    *
    * @param vehicleId
    * @return
    */
  def isOnWayToRefuelingDepot(vehicleId: VehicleId): Boolean
}

trait FindDepotAttributes {}

case class ParkingZoneDepotData(
  vehiclesOnWayToDepot: mutable.Set[VehicleId],
  chargingVehicles: mutable.Set[VehicleId],
  chargingQueue: mutable.PriorityQueue[ChargingQueueEntry],
  numPhantomVehiclesCharging: Int,
  numPhantomVehiclesQueued: Int,
  serviceTimeOfQueuedPhantomVehicles: Int
)

object ParkingZoneDepotData {
  case class ChargingQueueEntry(beamVehicle: BeamVehicle, parkingStall: ParkingStall, priority: Double)

  def empty: ParkingZoneDepotData =
    ParkingZoneDepotData(
      mutable.Set.empty[VehicleId],
      mutable.Set.empty[VehicleId],
      mutable.PriorityQueue.empty[ChargingQueueEntry](Ordering.by[ChargingQueueEntry, Double](_.priority)),
      0,
      0,
      0
    )
}
