package com.example

import java.util.concurrent.{Executors, TimeUnit}

import org.reactivecouchbase.elastic.ElasticClient
import org.scalatest._
import play.api.libs.json.Json

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Promise}

object Timeout {
  val sched = Executors.newSingleThreadScheduledExecutor()
  def timeout(duration: Duration) = {
    val p = Promise[Unit]()
    sched.schedule(new Runnable {
      override def run(): Unit = p.trySuccess(())
    }, duration.toMillis, TimeUnit.MILLISECONDS)
    p.future
  }
}

class ElasticSpec extends FlatSpec with Matchers {

  "ElasticClient" should "be able to search an ES server" in {

    val port = Network.freePort
    val embedded = new EmbeddedElastic(Some(port))

    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

    val values = for {
      client <- ElasticClient.local(port).future
      _      <- client.createIndex("events-2016.09.13")(None)
      events <- (client / "events-2016.09.13" / "event").future
      _      <- events.indexWithId("AVciusDsj6Wd5pYs2q3r", refresh = true)(Json.obj("Hello" -> "World"))
      _      <- events.indexWithId("AVciusDsj6Wd5pYs2q32", refresh = true)(Json.obj("Goodbye" -> "Here"))
      _      <- Timeout.timeout(Duration("2s"))
      resp   <- events / "AVciusDsj6Wd5pYs2q3r" get()
      resp2  <- events / "AVciusDsj6Wd5pYs2q32" get()
      search <- client.search("events-*")(Json.obj())
      items  <- search.future.hitsSeq
      doc    <- resp.future.raw
      doc2   <- resp2.future.raw
      stats  <- client.stats()
      health <- client.health()
    } yield (items, doc, doc2, stats, health)

    val (items, doc, doc2, stats, health) = Await.result(values, Duration("10s"))

    // println(items.map(Json.prettyPrint).mkString("\n"))
    // println(Json.prettyPrint(doc))
    // println(Json.prettyPrint(stats.raw))
    // println(Json.prettyPrint(health.raw))

    embedded.stop()

    (doc \ "_source" \ "Hello").as[String] should be("World")
    (doc2 \ "_source" \ "Goodbye").as[String] should be("Here")
    items.size should be(2)
  }
}