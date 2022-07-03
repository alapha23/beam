package scripts

import org.scalatest.wordspec.AnyWordSpecLike
import scripts.csv.conversion.NetworkXmlToCSV

class NetworkXmlToCsvSpec extends AnyWordSpecLike {

  "networkXmlToCsv class " in {
    val path = "beam.sim.test/input/beamville/r5/physsim-network.xml"
    val nodeOutput = "beam.sim.test/input/beamville/node-network.csv"
    val linkOutput = "beam.sim.test/input/beamville/link-network.csv"
    val mergeOutput = "beam.sim.test/input/beamville/merge-network.csv"
    val delimiter = "\t"
    NetworkXmlToCSV.networkXmlParser(path, delimiter, nodeOutput, linkOutput, mergeOutput)
  }
}