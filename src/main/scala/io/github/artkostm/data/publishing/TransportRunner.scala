package io.github.artkostm.data.publishing

import io.github.artkostm.data.landing.rest.runtime.DibAppRuntime
import zio.IO

object TransportRunner extends DibAppRuntime {
  override def run(args: List[String]): IO[Nothing, Int] =
    Dependencies(args).build.use { env =>
      handleErrors(transport.run().provide(env))
    }.fold(_ => 1, _ => 0)
}
