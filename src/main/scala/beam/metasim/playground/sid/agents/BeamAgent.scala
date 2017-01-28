package beam.metasim.playground.sid.agents

import akka.actor.FSM
import beam.metasim.playground.sid.agents.BeamAgent._
import org.matsim.api.core.v01.{Coord, TransportMode}
import org.matsim.core.utils.geometry.CoordUtils
import org.slf4j.LoggerFactory

// NOTE: companion objects used to define static methods and factory methods for a class

object BeamAgent {

  // states
  sealed trait BeamState
  case object InitState extends BeamState
  case object InActivity extends BeamState
  case object Traveling extends BeamState

  case class BeamAgentInfo(currentLocation: Coord)

}

object DecisionProtocol{
  // TODO: Inherit from "BeamChoice" or some such abstract parent trait
  trait ModeChoice
  trait ActivityChoice

  case class ChooseMode(modeType: TransportMode) extends ModeChoice
}



class BeamAgent extends FSM[BeamState,BeamAgentInfo]{

  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])

  startWith(InitState,BeamAgentInfo(CoordUtils.createCoord(0,0)))

//  when(InitState)
////  {}
////  {
//////    case Event()
////  }
//  when(InActivity)
////  {}
//  when(Traveling)
////  {}
//
////  whenUnhandled

  onTransition {
    case InitState -> InActivity => logger.debug("From init state to first activity")
    case InActivity -> Traveling => logger.debug("From activity to traveling")
    case Traveling -> InActivity=> logger.debug("From traveling to activity")
  }

}
