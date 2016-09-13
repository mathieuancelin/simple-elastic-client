# simple-elastic-client

`simple-elastic-client` is a very basic client for Elastic using only the REST API.

## Use it

```scala
libraryDependencies += "wabisabi" %% "wabisabi" % "2.1.6"
```

```scala

import java.util.concurrent.Executors

import org.reactivecouchbase.elastic.{ElasticClient, Utils}
import org.scalatest._
import play.api.libs.json.Json

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

object MyApp extends App {

  implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  val values = for {
    client <- ElasticClient.remote("127.0.0.1:9200" :: "127.0.0.2:9200" :: "127.0.0.3:9200" :: Nil).future
    search <- client.search("events-*")(Json.obj())
    items  <- search.future.hitsSeq
    resp   <- client.get("events-2016.09.13", "event", "AVciusDsj6Wd5pYs2q3r")
    doc    <- resp.future.raw
    stats  <- client.stats()
    health <- client.health()
  } yield (items, doc, stats, health)

  val (items, doc, stats, health) = Await.result(values, Duration("10s"))

  println(items.map(Json.prettyPrint).mkString("\n"))
  println(Json.prettyPrint(doc))
  println(Json.prettyPrint(stats.raw))
  println(Json.prettyPrint(health.raw))

}

```