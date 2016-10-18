package com.example

import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
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

  "ElasticClient" should "work" in {

    val port = Network.freePort
    val embedded = new EmbeddedElastic(Some(port))

    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

    val values = for {
      client <- ElasticClient.local(port).future()
      _      <- client createIndex "events-2016.09.13" withNoSettings()
      events <- client / "events-2016.09.13" / "event" future()
      _      <- events / "AVciusDsj6Wd5pYs2q3r" index Json.obj("Hello" -> "World")
      _      <- events / "AVciusDsj6Wd5pYs2q32" index Json.obj("Goodbye" -> "Here")
      _      <- Timeout.timeout(Duration("2s"))
      resp   <- events / "AVciusDsj6Wd5pYs2q3r" get()
      resp2  <- events / "AVciusDsj6Wd5pYs2q32" get()
      search <- client.search("events-*")(Json.obj())
      items  <- search.future().hitsSeq
      doc    <- resp.future().raw
      doc2   <- resp2.future().raw
      stats  <- client.stats()
      health <- client.health()
    } yield (items, doc, doc2, stats, health)

    val (items, doc, doc2, _, _) = Await.result(values, Duration("10s"))

    (doc \ "_source" \ "Hello").as[String] should be("World")
    (doc2 \ "_source" \ "Goodbye").as[String] should be("Here")
    items.size should be(2)

    embedded.stop()
  }

  "ElasticClient" should "be able to create index during put mapping" in {
    val port = Network.freePort
    val embedded = new EmbeddedElastic(Some(port))

    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
    val values = for {
      client <- ElasticClient.local(port).future()
      _ <- client.putMapping("places")(
        Json.obj(
          "mappings" -> Json.obj(
            "cities" -> Json.obj(
              "properties" -> Json.obj(
                "name" -> Json.obj(
                  "type" -> "string",
                  "index" -> "not_analyzed"
                ),
                "country" -> Json.obj(
                  "type" -> "string",
                  "index" -> "not_analyzed"
                ),
                "continent" -> Json.obj(
                  "type" -> "string",
                  "index" -> "not_analyzed"
                ),
                "status" -> Json.obj(
                  "type" -> "string",
                  "index" -> "not_analyzed"
                )
              )
            )
          )
        )
      )
      cities  <- client / "places" / "cities" future()
      _       <- cities index Json.obj(
        "name"      -> "Glasgow",
        "country"   -> "United Kingdom",
        "continent" -> "Scotland",
        "status"    -> "Such Wow"
      )
      _       <- cities index Json.obj(
        "name"      -> "London",
        "country"   -> "United Kingdom",
        "continent" -> "Europe",
        "status"    -> "Awesome"
      )
      _       <- Timeout.timeout(Duration("2s"))
      lSearch <- cities search Json.obj("query" -> Json.obj("term" -> Json.obj("name" -> "London")))
      gSearch <- cities search Json.obj("query" -> Json.obj("term" -> Json.obj("name" -> "Glasgow")))
      london  <- lSearch.future().mapFirstHit(d => d \ "_source")
      glasgow <- gSearch.future().mapFirstHit(d => d \ "_source")
      all     <- cities search Json.obj() map (_.hitsSeq)
      // _       <- client.bulkFromSource(None, None)(Source(1 to 100).map(v => Json.obj("value" -> v)), 10)
    } yield (london, glasgow, all)

    val (london, glasgow, all) = Await.result(values, Duration("10s"))

    embedded.stop()

    (london \ "name").as[String] should be("London")
    (london \ "country").as[String] should be("United Kingdom")
    (london \ "continent").as[String] should be("Europe")
    (london \ "status").as[String] should be("Awesome")

    (glasgow \ "name").as[String] should be("Glasgow")
    (glasgow \ "country").as[String] should be("United Kingdom")
    (glasgow \ "continent").as[String] should be("Scotland")
    (glasgow \ "status").as[String] should be("Such Wow")

    all.size should be(2)

  }

  "ElasticClient" should "be able to search an ES server" in {

    val port = Network.freePort
    val embedded = new EmbeddedElastic(Some(port))

    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
    implicit val system = ActorSystem("Streams")
    implicit val materializer = ActorMaterializer()

    val values = for {
      client  <- ElasticClient.local(port).future()
      _       <- client createIndex "places" withSettings Json.obj(
        "settings" -> Json.obj(
          "index" -> Json.obj(
            "refresh_interval" -> "1s"
          )
        ),
        "mappings" -> Json.obj(
          "cities" -> Json.obj(
            "properties" -> Json.obj(
              "name" -> Json.obj(
                "type" -> "string",
                "index" -> "not_analyzed"
              ),
              "country" -> Json.obj(
                "type" -> "string",
                "index" -> "not_analyzed"
              ),
              "continent" -> Json.obj(
                "type" -> "string",
                "index" -> "not_analyzed"
              ),
              "status" -> Json.obj(
                "type" -> "string",
                "index" -> "not_analyzed"
              )
            )
          )
        )
      )
      cities  <- client / "places" / "cities" future()
      _       <- cities index Json.obj(
        "name"      -> "Glasgow",
        "country"   -> "United Kingdom",
        "continent" -> "Scotland",
        "status"    -> "Such Wow"
      )
      _       <- cities index Json.obj(
        "name"      -> "London",
        "country"   -> "United Kingdom",
        "continent" -> "Europe",
        "status"    -> "Awesome"
      )
      _       <- Timeout.timeout(Duration("2s"))
      lSearch <- cities search Json.obj("query" -> Json.obj("term" -> Json.obj("name" -> "London")))
      gSearch <- cities search Json.obj("query" -> Json.obj("term" -> Json.obj("name" -> "Glasgow")))
      london  <- lSearch.future().mapFirstHit(d => d \ "_source")
      glasgow <- gSearch.future().mapFirstHit(d => d \ "_source")
      all     <- cities search Json.obj() map (_.hitsSeq)
    } yield (london, glasgow, all)

    val (london, glasgow, all) = Await.result(values, Duration("10s"))

    embedded.stop()

    (london \ "name").as[String] should be("London")
    (london \ "country").as[String] should be("United Kingdom")
    (london \ "continent").as[String] should be("Europe")
    (london \ "status").as[String] should be("Awesome")

    (glasgow \ "name").as[String] should be("Glasgow")
    (glasgow \ "country").as[String] should be("United Kingdom")
    (glasgow \ "continent").as[String] should be("Scotland")
    (glasgow \ "status").as[String] should be("Such Wow")

    all.size should be(2)
  }
}
