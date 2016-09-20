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

  "SimpleElasticClient" should "Work" in {

    val port = Network.freePort
    val elastic = new EmbeddedElastic(Some(port))

    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

    val values = for {
      client  <- ElasticClient.remote("host1.elastic.cluster:9200" :: "host2.elastic.cluster:9200" :: Nil).future()
      // or use the remote client with multi-hosts and retries
      // client  <- ElasticClient.local(port).future()
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

    elastic.stop()

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
```

## Client API

```scala
trait ElasticClient {
  def future: Future[ElasticClient]

  def selectIndex(index: String): SelectedIndex
  def /(index: String): SelectedIndex

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

