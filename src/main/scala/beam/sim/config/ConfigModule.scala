package beam.sim.config

import beam.agentsim.agents.vehicles.Trajectory
import beam.sim.common.GeoUtils
import com.google.inject._
import com.typesafe.config.{Config => TypesafeConfig}
import net.codingwell.scalaguice.ScalaModule

class ConfigModule(val typesafeConfig: TypesafeConfig) extends AbstractModule with ScalaModule {

  @Provides @Singleton
  def getTypesafeConfig: TypesafeConfig = {
    typesafeConfig
  }

  @Provides @Singleton
  def beamConfig(typesafeConfig: TypesafeConfig): BeamConfig =
    BeamConfig(typesafeConfig)

  override def configure(): Unit = {
    requestInjection(GeoUtils)
    requestInjection(Trajectory)
  }

}
