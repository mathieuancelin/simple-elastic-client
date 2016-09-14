package com.example

import java.net.ServerSocket

import scala.util.{Random, Try}
import java.nio.file._
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.node._

class EmbeddedElastic(port: Option[Int]) {

  val tempDirectory = Files.createTempDirectory("es-unit-test")
  tempDirectory.toFile.deleteOnExit()
  val settings = Settings.builder()
    .put("http.port", port.getOrElse(Network.freePort))
    .put("network.host", "127.0.0.1")
    .put("path.home", tempDirectory.toAbsolutePath)
    .put("path.data", tempDirectory.toAbsolutePath + "/data/")
    .build()
  val elasticsearchTestNode = NodeBuilder.nodeBuilder()
    .local(true)
    .data(true)
    .clusterName("hubshare-unit-test")
    .settings(settings)
    .build()
  elasticsearchTestNode.start()

  def stop() {
    if (elasticsearchTestNode != null && !elasticsearchTestNode.isClosed) {
      elasticsearchTestNode.close()
    }
  }
}

object Network {
  def freePort: Int = {
    Try {
      val serverSocket = new ServerSocket(0)
      val port = serverSocket.getLocalPort
      serverSocket.close()
      port
    }.toOption.getOrElse(Random.nextInt(1000) + 7000)
  }
}