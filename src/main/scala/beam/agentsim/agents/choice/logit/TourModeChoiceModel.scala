package beam.agentsim.agents.choice.logit

import beam.sim.config.BeamConfig
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.Activity

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object TourModeChoiceModel {
  def apply(beamConfig: BeamConfig) = new TourModeChoiceModel(beamConfig)

  sealed trait TourModeParameters

  object TourModeParameters {
    final case object ExpectedMaxUtility extends TourModeParameters with Serializable
    final case object Intercept extends TourModeParameters with Serializable
  }

  type TourModeMNLConfig = Map[TourModeParameters, UtilityFunctionOperation]
}

class TourModeChoiceModel(
  val beamConfig: BeamConfig
) {

  val DefaultMNLParameters: TourModeChoiceModel.TourModeMNLConfig = Map(
    TourModeChoiceModel.TourModeParameters.ExpectedMaxUtility -> UtilityFunctionOperation.Multiplier(1.0),
    TourModeChoiceModel.TourModeParameters.Intercept          -> UtilityFunctionOperation.Intercept(1.0)
  )

}
