package beam.sim.population

import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator._
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.RouteHistory.LinkId
import beam.router.model.EmbodiedBeamLeg
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population._
import org.matsim.households.Income.IncomePeriod
import org.matsim.households.{Household, IncomeImpl}

import scala.collection.JavaConverters._
import scala.collection.mutable

sealed trait PopulationAttributes

case class AttributesOfIndividual(
  householdAttributes: HouseholdAttributes,
  modalityStyle: Option[String],
  isMale: Boolean,
  availableModes: Seq[BeamMode],
  valueOfTime: Double,
  age: Option[Int],
  income: Option[Double]
) extends PopulationAttributes {
  lazy val hasModalityStyle: Boolean = modalityStyle.nonEmpty

  // Get Value of Travel Time for a specific leg of a travel alternative:
  // If it is a car leg, we use link-specific multipliers, otherwise we just look at the entire leg travel time and mode

  def getGeneralizedTimeOfLinkForMNL(
    IdAndTT: (LinkId, Int),
    beamMode: BeamMode,
    modeChoiceModel: ModeChoiceMultinomialLogit,
    beamServices: BeamServices,
    beamVehicleTypeId: Id[BeamVehicleType],
    destinationActivity: Option[Activity] = None,
    isRideHail: Boolean = false,
    isPooledTrip: Boolean = false
  ): Double = {
    // NOTE: This is in hours
    val isWorkTrip = destinationActivity match {
      case None =>
        false
      case Some(activity) =>
        activity.getType().equalsIgnoreCase("work")
    }

    val multiplier = beamMode match {
      case CAR =>
        val vehicleAutomationLevel = getAutomationLevel(beamVehicleTypeId, beamServices)
        if (isRideHail) {
          if (isPooledTrip) {
            getModeVotMultiplier(Option(RIDE_HAIL_POOLED), modeChoiceModel) *
            getPooledFactor(vehicleAutomationLevel, modeChoiceModel.poolingMultipliers)
          } else {
            getModeVotMultiplier(Option(RIDE_HAIL), modeChoiceModel)
          }
        } else {
          getSituationMultiplier(
            IdAndTT._1,
            IdAndTT._2,
            isWorkTrip,
            modeChoiceModel.situationMultipliers(beamMode),
            vehicleAutomationLevel,
            beamServices
          ) * getModeVotMultiplier(Option(CAR), modeChoiceModel)
        }
      case _ =>
        getModeVotMultiplier(Option(beamMode), modeChoiceModel)
    }
    multiplier * IdAndTT._2 / 3600
  }

  def getGeneralizedTimeOfLegForMNL(
    embodiedBeamLeg: EmbodiedBeamLeg,
    modeChoiceModel: ModeChoiceMultinomialLogit,
    beamServices: BeamServices,
    destinationActivity: Option[Activity],
    originActivity: Option[Activity]
  ): Double = {
    //NOTE: This gives answers in hours
    embodiedBeamLeg.beamLeg.mode match {
      case CAR => // NOTE: Ride hail legs are classified as CAR mode. For now we only need to loop through links here
        val idsAndTravelTimes =
          embodiedBeamLeg.beamLeg.travelPath.linkIds.tail // ignore the first link because activities are located along links
            .zip(embodiedBeamLeg.beamLeg.travelPath.linkTravelTime.tail.map(time => math.round(time.toFloat)))
        idsAndTravelTimes.foldLeft(0.0)(
          _ + getGeneralizedTimeOfLinkForMNL(
            _,
            embodiedBeamLeg.beamLeg.mode,
            modeChoiceModel,
            beamServices,
            embodiedBeamLeg.beamVehicleTypeId,
            destinationActivity,
            embodiedBeamLeg.isRideHail,
            embodiedBeamLeg.isPooledTrip
          )
        )
      case BIKE =>
        val situation = getSituationForVOT(destinationActivity, originActivity)
        getModeVotMultiplier(Option(embodiedBeamLeg.beamLeg.mode), modeChoiceModel, Some(situation)) *
        embodiedBeamLeg.beamLeg.duration / 3600
      case _ =>
        getModeVotMultiplier(Option(embodiedBeamLeg.beamLeg.mode), modeChoiceModel) *
          embodiedBeamLeg.beamLeg.duration / 3600
    }
  }

  def getVOT(generalizedTime: Double): Double = {
    valueOfTime * generalizedTime
  }

  private def getSituationForVOT(
    destinationActivity: Option[Activity],
    originActivity: Option[Activity]
  ): Set[SituationMultiplier] = {
    (destinationActivity, originActivity, age, income) match {
      case (Some(origin), Some(destination), Some(travelerAge), Some(traverlerIncome)) =>
        val ageBin = if (travelerAge > 60) { ageGT60 }
        else { ageLE60 }
        val incomeBin = if (traverlerIncome > 50000) { incomeGT50k }
        else { incomeLE50k }

        if (isCommute(destination, origin)) {
          Set[SituationMultiplier](commuteTrip, ageBin, incomeBin)
        } else {
          Set[SituationMultiplier](nonCommuteTrip, ageBin, incomeBin)
        }
      case _ =>
        Set[SituationMultiplier]()
    }
  }

  def isCommute(destinationActivity: Activity, originActivity: Activity): Boolean = {
    val homeToWork =
      originActivity.getType.equalsIgnoreCase("home") && destinationActivity.getType.equalsIgnoreCase("work")
    val workToHome =
      originActivity.getType.equalsIgnoreCase("work") && destinationActivity.getType.equalsIgnoreCase("home")
    homeToWork || workToHome
  }

  private def getAutomationLevel(
    beamVehicleTypeId: Id[BeamVehicleType],
    beamServices: BeamServices
  ): AutomationLevel = {
    val automationInt = if (beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.overrideAutomationForVOTT) {
      beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.overrideAutomationLevel
    } else {
      beamServices.beamScenario.vehicleTypes(beamVehicleTypeId).automationLevel
    }
    automationInt match {
      case 1 => levelLE2
      case 2 => levelLE2
      case 3 => level3
      case 4 => level4
      case 5 => level5
      case _ => levelLE2
    }
  }

  // Convert from seconds to hours and bring in person's base VOT
  def unitConversionVOTT(duration: Double): Double = {
    valueOfTime / 3600 * duration
  }

  def getModeVotMultiplier(
    beamMode: Option[BeamMode],
    modeChoiceModel: ModeChoiceMultinomialLogit,
    situation: Option[Set[SituationMultiplier]] = None
  ): Double = {
    val situationMultiplier = beamMode match {
      case Some(mode) =>
        situation match {
          case Some(situation) => modeChoiceModel.situationMultipliers.getOrElse(mode, Map()).getOrElse(situation, 1.0)
          case None            => 1.0
        }
      case None => 1.0
    }
    situationMultiplier * modeChoiceModel.modeMultipliers.getOrElse(beamMode, 1.0)
  }

  private def getPooledFactor(
    vehicleAutomationLevel: AutomationLevel,
    poolingMultipliers: Map[AutomationLevel, Double]
  ): Double = {
    poolingMultipliers.getOrElse(vehicleAutomationLevel, 1.0)
  }

  private def getLinkCharacteristics(
    linkID: Int,
    travelTime: Double,
    beamServices: BeamServices
  ): (CongestionLevel, RoadwayType) = {
    // Note: cutoffs for congested (2/3 free flow speed) and highway (ff speed > 20 m/s) are arbitrary and could be inputs
    val currentLink = beamServices.networkHelper.getLink(linkID).get
    val freeSpeed: Double = currentLink.getFreespeed()
    val currentSpeed: Double = if (travelTime == 0) { freeSpeed }
    else { currentLink.getLength() / travelTime }
    if (currentSpeed < 0.67 * freeSpeed) {
      if (freeSpeed > 20) {
        (highCongestion, highway)
      } else {
        (highCongestion, nonHighway)
      }
    } else {
      if (freeSpeed > 20) {
        (lowCongestion, highway)
      } else {
        (lowCongestion, nonHighway)
      }
    }
  }

  private def getSituationMultiplier(
    linkID: Int,
    travelTime: Double,
    isWorkTrip: Boolean = true,
    situationMultipliers: Map[Set[SituationMultiplier], Double],
    vehicleAutomationLevel: AutomationLevel,
    beamServices: BeamServices
  ): Double = {
    val sensitivity: TimeSensitivity = if (isWorkTrip) {
      highSensitivity
    } else {
      lowSensitivity
    }
    val (congestion, roadway) = getLinkCharacteristics(linkID, travelTime, beamServices)
    situationMultipliers.getOrElse(Set(sensitivity, congestion, roadway, vehicleAutomationLevel), 1.0)
  }

}

object AttributesOfIndividual {

  val EMPTY: AttributesOfIndividual =
    AttributesOfIndividual(HouseholdAttributes.EMPTY, None, true, Seq(), 0.0, None, None)
}

case class HouseholdAttributes(
  householdId: String,
  householdIncome: Double,
  householdSize: Int,
  numCars: Int,
  numBikes: Int
) extends PopulationAttributes

object HouseholdAttributes {

  val EMPTY: HouseholdAttributes = HouseholdAttributes("0", 0.0, 0, 0, 0)

  def apply(household: Household, vehicles: Map[Id[BeamVehicle], BeamVehicle]): HouseholdAttributes = {
    new HouseholdAttributes(
      householdId = household.getId.toString,
      householdIncome = Option(household.getIncome)
        .getOrElse(new IncomeImpl(0, IncomePeriod.year))
        .getIncome,
      householdSize = household.getMemberIds.size(),
      numCars = household.getVehicleIds.asScala
        .map(id => vehicles(id))
        .count(_.beamVehicleType.id.toString.toLowerCase.contains("car")),
      numBikes = household.getVehicleIds.asScala
        .map(id => vehicles(id))
        .count(_.beamVehicleType.id.toString.toLowerCase.contains("bike"))
    )
  }
}
