package com.example

import scala.collection.JavaConverters._
import java.net.ServerSocket

import scala.util.{Random, Try}
import java.nio.file._
import java.util

import org.elasticsearch.common.network.NetworkModule
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.node._
import org.elasticsearch.node.internal.InternalSettingsPreparer
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.transport.Netty3Plugin




class EmbeddedElastic(port: Option[Int]) {

  var classpathPlugins: util.Collection[Class[_ <: Plugin]] = List(classOf[Netty3Plugin]).asJavaCollection.asInstanceOf[util.Collection[Class[_ <: Plugin]]]

  class NodeWithNetty(settings: Settings) extends Node(InternalSettingsPreparer.prepareEnvironment(settings, null), classpathPlugins)
  val tempDirectory = Files.createTempDirectory("es-unit-test")
  tempDirectory.toFile.deleteOnExit()
  val settings = Settings.builder()
    .put("http.port", port.getOrElse(Network.freePort))
    .put("network.host", "127.0.0.1")
    .put("path.home", tempDirectory.toAbsolutePath)
    .put("path.data", tempDirectory.toAbsolutePath + "/data/")
      .put("transport.type", NetworkModule.LOCAL_TRANSPORT)
        .put("http.type", "netty3")
    .build()


  val elasticsearchTestNode = new NodeWithNetty(settings)


  /*val elasticsearchTestNode = NodeBuilder.nodeBuilder()
    .local(true)
    .data(true)
    .clusterName("hubshare-unit-test")
    .settings(settings)
    .build()*/
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