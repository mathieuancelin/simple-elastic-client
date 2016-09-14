# simple-elastic-client

`simple-elastic-client` is a very basic client for Elastic in Scala, using only the REST API.

## Use it

```scala
resolvers += "simple-elastic-client repository" at "https://raw.githubusercontent.com/mathieuancelin/simple-elastic-client/master/snapshots"

libraryDependencies += "org.reactivecouchbase" %% "simple-elastic-client" % "1.0-SNAPSHOT"
```

```scala
import java.util.concurrent.Executors

import org.reactivecouchbase.elastic._
import org.scalatest._
import play.api.libs.json.Json

import scala.concurrent.duration.Duration
import scala.concurrent._

class SimpleElasticClientSpec extends FlatSpec with Matchers {

  "SimpleElasticClientSpec" should "Work" in {

    val port = Network.freePort
    val elastic = new EmbeddedElastic(Some(port))

    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

    val values = for {
      client <- ElasticClient.local(port).liftable
      // client      <- ElasticClient.remote("127.0.0.1:9200" :: "127.0.0.2:9200" :: Nil).liftable
      _      <- client.createIndex("events-2016.09.13")(None)
      index  <- (client / "events-2016.09.13" / "event").liftable
      _      <- index.index(Some("AVciusDsj6Wd5pYs2q3r"), true)(Json.obj("Hello" -> "World"))
      _      <- index.index(Some("AVciusDsj6Wd5pYs2q32"), true)(Json.obj("Goodbye" -> "Here"))
      _      <- Timeout.timeout(Duration("2s"))
      resp   <- index get "AVciusDsj6Wd5pYs2q3r"
      resp2  <- index get "AVciusDsj6Wd5pYs2q32"
      search <- client.search("events-*")(Json.obj())
      items  <- search.liftable.hitsSeq
      doc    <- resp.liftable.raw
      doc2   <- resp2.liftable.raw
    } yield (items, doc, doc2, stats, health)

    val (items, doc, doc2) = Await.result(values, Duration("10s"))

    (doc \ "_source" \ "Hello").as[String] should be("World")
    (doc2 \ "_source" \ "Goodbye").as[String] should be("Here")
    items.size should be(2)

    elastic.stop()
  }
}
```

## Client API

```scala
trait ElasticClient {
  def future: Future[ElasticClient]

  def health(): Future[ElasticResponse]
  def stats(idxs: Seq[String] = Seq.empty[String])(implicit ec: ExecutionContext): Future[ElasticResponse]

  def count(indexes: Seq[String], types: Seq[String])(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def count(index: String, typ: String)(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse]

  def delete(index: String, typ: String, id: String)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def delete(index: String, typ: String)(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def get(index: String, typ: String, id: String)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def get(index: String, typ: String)(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def index(index: String, typ: String, id: Option[String], refresh: Boolean = false)(doc: JsValue)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def search(index: String, typ: Option[String] = None)(query: JsObject, params: (String, String)*)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def uriSearch(index: String, typ: Option[String] = None)(params: (String, String)*)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def suggest(index: String)(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse]

  def bulk(index: Option[String], typ: Option[String], operations: JsArray)(implicit ec: ExecutionContext): Future[ElasticResponse]

  def createIndex(index: String)(settings: Option[JsObject])(implicit ec: ExecutionContext): Future[ElasticResponse]
  def deleteIndex(index: String)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def refresh(index: String)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def flush(index: String)(implicit ec: ExecutionContext): Future[ElasticResponse]

  def mapping(indexes: Seq[String], types: Seq[String])(implicit ec: ExecutionContext): Future[ElasticResponse]
  def putMapping(indexes: Seq[String], typ: String, ignoreConflicts: Boolean)(mapping: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def putTemplate(index: String)(template: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse]

  def template(index: String, name: String)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def deleteTemplate(index: String, name: String)(implicit ec: ExecutionContext): Future[ElasticResponse]

  def createAlias(actions: JsArray)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def deleteAlias(index: String, name: String)(implicit ec: ExecutionContext): Future[ElasticResponse]
  def alias(index: String, name: String)(implicit ec: ExecutionContext): Future[ElasticResponse]
}
```

## Response API

```scala
trait ElasticResponse {
  def future: AsyncElasticResponse
  def isError: Boolean
  def map[T](f: JsValue => T): T
  def mapHits[T](f: Reads[T]): Seq[T]
  def mapHits[T](f: Seq[JsValue] => Seq[T]): Seq[T]
  def hits: JsArray
  def hitsSeq: Seq[JsValue]
  def fold[T](f: ElasticResponseResult => T): T
}

// that one is useful in for comprehensions ;-)
trait AsyncElasticResponse {
  def raw: Future[JsValue]
  def isError: Future[Boolean]
  def map[T](f: JsValue => T): Future[T]
  def flatMap[T](f: JsValue => Future[T]): Future[T]
  def mapHits[T](f: Reads[T]): Future[Seq[T]]
  def mapHits[T](f: Seq[JsValue] => Seq[T]): Future[Seq[T]]
  def flatMapHits[T](f: Seq[JsValue] => Seq[Future[T]])(implicit ec: ExecutionContext): Future[Seq[T]]
  def hits: Future[JsArray]
  def hitsSeq: Future[Seq[JsValue]]
  def fold[T](f: ElasticResponseResult => T): Future[T]
}
```

