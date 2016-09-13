package org.reactivecouchbase.elastic

import okhttp3.Response
import org.slf4j.LoggerFactory
import play.api.libs.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

trait ElasticResponseResult
case class ElasticResponseSuccess(rawResponse: JsObject) extends ElasticResponseResult
case class ElasticResponseFailure(error: JsObject) extends ElasticResponseResult

case class ElasticResponse(raw: JsValue, underlying: Response) {
  def future: AsyncElasticResponse = AsyncElasticResponse(this)
  def isError: Boolean = (raw \ "error").toOption.isDefined
  def map[T](f: JsValue => T): T = f(raw)
  def mapHits[T](f: Reads[T]): Seq[T] = (raw \ "hits" \ "hits").as[JsArray].value.map(i => f.reads(i)).filter(_.isSuccess).map(_.get)
  def mapHits[T](f: Seq[JsValue] => Seq[T]): Seq[T] = f((raw \ "hits" \ "hits").as[JsArray].value)
  def hits: JsArray = (raw \ "hits" \ "hits").as[JsArray]
  def hitsSeq: Seq[JsValue] = (raw \ "hits" \ "hits").as[JsArray].value
  def fold[T](f: ElasticResponseResult => T): T = {
    if (isError) {
      f(ElasticResponseSuccess(raw.as[JsObject]))
    } else {
      f(ElasticResponseFailure((raw \ "error").as[JsObject]))
    }
  }
}

case class AsyncElasticResponse(response: ElasticResponse) {
  def raw: Future[JsValue] = Future.successful(response.raw)
  def isError: Future[Boolean] = Future.successful(response.isError)
  def map[T](f: JsValue => T): Future[T] = Future.successful(response.map(f))
  def flatMap[T](f: JsValue => Future[T]): Future[T] = f(response.raw)
  def mapHits[T](f: Reads[T]): Future[Seq[T]] = Future.successful(response.mapHits(f))
  def mapHits[T](f: Seq[JsValue] => Seq[T]): Future[Seq[T]] = Future.successful(response.mapHits(f))
  def flatMapHits[T](f: Seq[JsValue] => Seq[Future[T]])(implicit ec: ExecutionContext): Future[Seq[T]] = Future.sequence(response.mapHits(f))
  def hits: Future[JsArray] = Future.successful(response.hits)
  def hitsSeq: Future[Seq[JsValue]] = Future.successful(response.hitsSeq)
  def fold[T](f: ElasticResponseResult => T): Future[T] = Future.successful(response.fold(f))
}

class ElasticClient(hosts: Seq[String], timeout: Duration, retry: Int) {

  val logger = LoggerFactory.getLogger("ElasticClient")
  val httpClient = Http.hosts(hosts)

  def future: Future[ElasticClient] = Future.successful(this)

  // Tools

  def health(
    indices: Seq[String] = Seq.empty[String],
    level: Option[String] = None,
    waitForStatus: Option[String] = None,
    waitForRelocatingShards: Option[String] = None,
    waitForNodes: Option[String] = None,
    timeout: Option[String] = None
  )(implicit ec: ExecutionContext) = {
    performRequest(s"/_cluster/health/${indices.mkString(",")}", GET, None, Seq(
      "level" -> level.getOrElse(""),
      "wait_for_status" -> waitForStatus.getOrElse(""),
      "wait_for_relocation_shards" -> waitForRelocatingShards.getOrElse(""),
      "wait_for_nodes" -> waitForNodes.getOrElse(""),
      "timeout" -> timeout.getOrElse("")
    ))
  }

  def stats(idxs: Seq[String] = Seq.empty[String])(implicit ec: ExecutionContext) = {
    val url = idxs match {
      case Nil => "/_stats"
      case _ => s"/${idxs.mkString(",")}/_stats"
    }
    performRequest(url, GET, None, Seq(
      "clear" -> "true",
      "refresh" -> "true",
      "flush" -> "true",
      "merge" -> "true",
      "warmer" -> "true"
    ))
  }

  def count(indexes: Seq[String], types: Seq[String])(query: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/${indexes.mkString(",")}/${types.mkString(",")}/_count", GET, Some(query))

  def count(index: String, typ: String)(query: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/$typ/_count", GET, Some(query))

  // Documents
  def delete(index: String, typ: String, id: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/$typ/$id", DELETE, None)

  def delete(index: String, typ: String)(query: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/$typ/_query", DELETE, Some(query))

  def get(index: String, typ: String, id: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/$typ/$id", GET, None)

  def get(index: String, typ: String)(query: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/$typ/_query", GET, Some(query))

  def index(index: String, typ: String, id: Option[String], refresh: Boolean = false)(doc: JsValue)(implicit ec: ExecutionContext) = {
    val params = Seq(("refresh", if (refresh) "true" else "false"))
    id match {
      case Some(i) => performRequest(s"/$index/$typ/$i", PUT, None, params)
      case None => performRequest(s"/$index/$typ", POST, None, params)
    }
  }

  def search(index: String, typ: Option[String] = None)(query: JsObject, params: (String, String)*)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/${typ.getOrElse("")}/_search", POST, Some(query), params)

  def uriSearch(index: String, typ: Option[String] = None)(params: (String, String)*)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/${typ.getOrElse("")}/_search", POST, None, params)

  def suggest(index: String)(query: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_suggest", POST, Some(query))

  // Bulk
  def bulk(index: Option[String], typ: Option[String], operations: JsArray)(implicit ec: ExecutionContext) =
    performRequest(s"/${index.getOrElse("")}/${typ.getOrElse("")}/_bulk", POST, Some(operations))

  // Indexes
  def createIndex(index: String)(settings: Option[JsObject])(implicit ec: ExecutionContext) =
    performRequest(s"/$index", PUT, settings)

  def deleteIndex(index: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index", DELETE, None)

  def refresh(index: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_refresh", POST, None)

  def flush(index: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_flush", POST, None)

  // Mappings
  def mapping(indexes: Seq[String], types: Seq[String])(implicit ec: ExecutionContext) =
    performRequest(s"/${indexes.mkString(",")}/_mapping/${types.mkString(",")}", GET, None)

  def putMapping(indexes: Seq[String], typ: String, ignoreConflicts: Boolean)(mapping: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/${indexes.mkString(",")}/$typ/_mapping", PUT, Some(mapping), Seq("ignore_conflicts" -> ignoreConflicts.toString))

  // Templates
  def putTemplate(index: String)(template: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/_template/$index", PUT, Some(template))

  def template(index: String, name: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_template/$name", GET, None)

  def deleteTemplate(index: String, name: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_template/$name", DELETE, None)

  // Aliases
  def createAlias(actions: JsArray)(implicit ec: ExecutionContext) =
    performRequest(s"/_aliases", POST, Some(Json.obj("actions" -> actions)))

  def deleteAlias(index: String, name: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_alias/$name", DELETE, None)

  def alias(index: String, name: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_alias/$name", GET, None)

  private def performRequest(url: String, method: Method, payload: Option[JsValue], params: Seq[(String, String)] = Seq.empty[(String, String)])(implicit ec: ExecutionContext): Future[ElasticResponse] = {
    if (url.endsWith("_bulk")) {
      Utils.retry(retry) {
        httpClient
          .withUrl(url.replace("//", "/"))
          .withRequestTimeout(timeout)
          .withBody(payload.get.as[JsArray].value.map(Json.stringify).mkString("\n"))
          .withParams(params:_*)
          .run(method)
          .map(r => ElasticResponse(Json.parse(r.body().string()), r))
      }
    } else {
      Utils.retry(retry) {
        httpClient
          .withUrl(url.replace("//", "/"))
          .withRequestTimeout(timeout)
          .withBody(payload)
          .withParams(params:_*)
          .run(method)
          .map(r => ElasticResponse(Json.parse(r.body().string()), r))
      }
    }
  }
}

object ElasticClient {
  private val defaultTimeout = Duration("10s")
  private val defaultRetry = 5
  def local: ElasticClient = new ElasticClient(Seq("localhost:9200"), defaultTimeout, defaultRetry)
  def remote(hosts: Seq[String]): ElasticClient = new ElasticClient(hosts, defaultTimeout, defaultRetry)
  def remote(hosts: Seq[String], timeout: Duration): ElasticClient = new ElasticClient(hosts, timeout, defaultRetry)
  def remote(hosts: Seq[String], retry: Int): ElasticClient = new ElasticClient(hosts, defaultTimeout, retry)
  def remote(hosts: Seq[String], timeout: Duration, retry: Int): ElasticClient = new ElasticClient(hosts, timeout, retry)
}