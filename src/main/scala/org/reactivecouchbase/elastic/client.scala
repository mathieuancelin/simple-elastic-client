package org.reactivecouchbase.elastic

import java.util.concurrent.atomic.AtomicInteger

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import okhttp3.Response
import org.slf4j.LoggerFactory
import play.api.libs.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

trait ElasticResponseResult
case class ElasticResponseSuccess(rawResponse: JsObject) extends ElasticResponseResult
case class ElasticResponseFailure(error: JsObject) extends ElasticResponseResult

case class ElasticResponse(raw: JsValue, underlying: Response) {
  def future(): AsyncElasticResponse = AsyncElasticResponse(this)
  def isError: Boolean = (raw \ "error").toOption.isDefined
  def map[T](f: JsValue => T): T = f(raw)
  def mapHits[T](f: Reads[T]): Seq[T] = (raw \ "hits" \ "hits").as[JsArray].value.map(i => f.reads(i)).filter(_.isSuccess).map(_.get)
  def mapHits[T](f: Seq[JsValue] => Seq[T]): Seq[T] = f((raw \ "hits" \ "hits").as[JsArray].value)
  def mapSingleHit[T](f: Reads[T]): Option[T] = (raw \ "hits" \ "hits").as[JsArray].value.headOption.flatMap(d => f.reads(d).asOpt)
  def mapSingleHit[T](f: JsValue => T): Option[T] = (raw \ "hits" \ "hits").as[JsArray].value.headOption.map(f)
  def mapFirstHit[T](f: Reads[T]): T = (raw \ "hits" \ "hits").as[JsArray].value.headOption.flatMap(d => f.reads(d).asOpt).get
  def mapFirstHit[T](f: JsValue => T): T = (raw \ "hits" \ "hits").as[JsArray].value.headOption.map(f).get
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
  def mapSingleHit[T](f: Reads[T]): Future[Option[T]] = Future.successful(response.mapSingleHit(f))
  def mapSingleHit[T](f: JsValue => T): Future[Option[T]] = Future.successful(response.mapSingleHit(f))
  def mapFirstHit[T](f: Reads[T]): Future[T] = Future.successful(response.mapSingleHit(f).get)
  def mapFirstHit[T](f: JsValue => T): Future[T] = Future.successful(response.mapSingleHit(f).get)
  def flatMapHits[T](f: Seq[JsValue] => Seq[Future[T]])(implicit ec: ExecutionContext): Future[Seq[T]] = Future.sequence(response.mapHits(f))
  def hits: Future[JsArray] = Future.successful(response.hits)
  def hitsSeq: Future[Seq[JsValue]] = Future.successful(response.hitsSeq)
  def fold[T](f: ElasticResponseResult => T): Future[T] = Future.successful(response.fold(f))
}

class ElasticClient(hosts: Seq[String], timeout: Duration, retry: Int) {

  val logger = LoggerFactory.getLogger("ElasticClient")
  val loggerBulk = LoggerFactory.getLogger("ElasticClientBulk")
  val httpClient = Http.hosts(hosts)

  def future(): Future[ElasticClient] = Future.successful(this)

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

  def selectIndex(index: String): SelectedIndex = new SelectedIndex(index, this)
  def /(index: String): SelectedIndex = selectIndex(index)

  def index(index: String, typ: String, id: Option[String], refresh: Boolean = false)(doc: JsValue)(implicit ec: ExecutionContext) = {
    val params = Seq(("refresh", if (refresh) "true" else "false"))
    id match {
      case Some(i) => performRequest(s"/$index/$typ/$i", PUT, Some(doc), params)
      case None => performRequest(s"/$index/$typ", POST, Some(doc), params)
    }
  }

  def search(index: String, typ: Option[String] = None)(query: JsObject, params: (String, String)*)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/${typ.getOrElse("")}/_search", POST, Some(query), params)

  def uriSearch(index: String, typ: Option[String] = None)(params: (String, String)*)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/${typ.getOrElse("")}/_search", GET, None, params)

  def suggest(index: String)(query: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_suggest", POST, Some(query))

  // Bulk
  def bulk(index: Option[String], typ: Option[String])(operations: JsArray)(implicit ec: ExecutionContext) =
    performRequest(s"/${index.getOrElse("")}/${typ.getOrElse("")}/_bulk", POST, Some(operations))

  def bulkFromSeq(index: Option[String], typ: Option[String])(operations: Seq[JsValue])(implicit ec: ExecutionContext) =
    performRequest(s"/${index.getOrElse("")}/${typ.getOrElse("")}/_bulk", POST, Some(JsArray(operations)))

  def bulkFromSource(index: Option[String], typ: Option[String])(operations: Source[JsValue, _], batchEvery: Int = ElasticClient.BATCH_EVERY)(implicit ec: ExecutionContext, materialize: Materializer) = {
    val counter = new AtomicInteger(0)
    operations
      .grouped(batchEvery)
      .runFoldAsync(Seq.empty[ElasticResponse])((finalSeq, seq) => {
        val start = System.currentTimeMillis()
        val count = counter.incrementAndGet()
        loggerBulk.debug(s"[$count] - Will bulk ${seq.size} operations to ES")
        bulkFromSeq(index, typ)(seq).map(e => finalSeq :+ e).andThen {
          case _ => loggerBulk.debug(s"[$count] - Bulk done in ${System.currentTimeMillis() - start} milliseconds")
        }
      })
  }

  // Indexes
  def putIndex(index: String, settings: Option[JsObject] = None)(implicit ec: ExecutionContext) =
    performRequest(s"/$index", PUT, settings)

  def createIndex(index: String): IndexCreator = new IndexCreator(index, this)

  def deleteIndex(index: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index", DELETE, None)

  def refresh(index: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_refresh", POST, None)

  def flush(index: String)(implicit ec: ExecutionContext) =
    performRequest(s"/$index/_flush", POST, None)

  // Mappings
  def mapping(indexes: Seq[String], types: Seq[String])(implicit ec: ExecutionContext) =
    performRequest(s"/${indexes.mkString(",")}/_mapping/${types.mkString(",")}", GET, None)

  def putMapping(indexes: Seq[String], typ: String, ignoreConflicts: Boolean = false)(mapping: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/${indexes.mkString(",")}/_mapping/$typ", PUT, Some(mapping), Seq("ignore_conflicts" -> ignoreConflicts.toString))

  def putMapping(index: String)(mapping: JsObject)(implicit ec: ExecutionContext) =
    performRequest(s"/$index", POST, Some(mapping))

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
    val debugUrl = if (params.isEmpty) s"http://$$host:$$port$url" else s"http://$$host:$$port$url?${params.map(p => s"${p._1}=${p._2}").mkString("&")}"
    payload match {
      case Some(obj) => logger.debug(s"curl -X ${method.name} -H 'Content-Type: application/json' '$debugUrl' -d '${Json.prettyPrint(obj)}'")
      case None      => logger.debug(s"curl -X ${method.name} '$debugUrl'")
    }
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
  val BATCH_EVERY = 1000
  def local: ElasticClient = new ElasticClient(Seq("localhost:9200"), defaultTimeout, defaultRetry)
  def local(port: Int): ElasticClient = new ElasticClient(Seq(s"localhost:$port"), defaultTimeout, defaultRetry)
  def remote(hosts: Seq[String]): ElasticClient = new ElasticClient(hosts, defaultTimeout, defaultRetry)
  def remote(hosts: Seq[String], timeout: Duration): ElasticClient = new ElasticClient(hosts, timeout, defaultRetry)
  def remote(hosts: Seq[String], retry: Int): ElasticClient = new ElasticClient(hosts, defaultTimeout, retry)
  def remote(hosts: Seq[String], timeout: Duration, retry: Int): ElasticClient = new ElasticClient(hosts, timeout, retry)
}

class SelectedIndex(index: String, cli: ElasticClient) {
  def future(): Future[SelectedIndex] = Future.successful(this)

  def selectType(typ: String): SelectedType = new SelectedType(index, typ, cli)
  def /(typ: String): SelectedType = selectType(typ)

  def search(query: JsObject, params: (String, String)*)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.search(index, None)(query, params:_*)(ec)
  def uriSearch(params: (String, String)*)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.uriSearch(index, None)(params:_*)(ec)
  def suggest(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.suggest(index)(query)(ec)
  def bulk(operations: JsArray)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.bulk(Some(index), None)(operations)(ec)
  def bulk(operations: Seq[JsValue])(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.bulkFromSeq(Some(index), None)(operations)(ec)
  def bulk(source: Source[JsValue, _], batchEvery: Int = ElasticClient.BATCH_EVERY)(implicit ec: ExecutionContext, materializer: Materializer): Future[Seq[ElasticResponse]] = cli.bulkFromSource(Some(index), None)(source, batchEvery)(ec, materializer)
  def deleteIndex(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.deleteIndex(index)(ec)
  def refresh(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.refresh(index)(ec)
  def flush(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.flush(index)(ec)
  def putTemplate(index: String)(template: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.putTemplate(index)(template)(ec)
  def template(name: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.template(index, name)(ec)
  def deleteTemplate(name: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.deleteTemplate(index, name)(ec)
  def deleteAlias(name: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.deleteAlias(index, name)(ec)
  def alias(name: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.alias(index, name)(ec)
}

class SelectedType(index: String, typ: String, cli: ElasticClient) {
  def future(): Future[SelectedType] = Future.successful(this)

  def selectObject(id: String): SelectedObject = new SelectedObject(index, typ, id, cli)
  def /(id: String): SelectedObject = selectObject(id)

  def count(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.count(index, typ)(query)(ec)
  def delete(id: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.delete(index, typ, id)(ec)
  def delete(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.delete(index, typ)(query)(ec)
  def get(id: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.get(index, typ, id)(ec)
  def get(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.get(index, typ)(query)(ec)
  def index(doc: JsValue, refresh: Boolean = true)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.index(index, typ, None, refresh)(doc)(ec)
  def indexWithId(id: String, refresh: Boolean = false)(doc: JsValue)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.index(index, typ, Some(id), refresh)(doc)(ec)
  def search(query: JsObject, params: (String, String)*)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.search(index, Some(typ))(query, params:_*)(ec)
  def uriSearch(params: (String, String)*)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.uriSearch(index, Some(typ))(params:_*)(ec)
  def suggest(query: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.suggest(index)(query)(ec)
  def bulk(operations: JsArray)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.bulk(Some(index), Some(typ))(operations)(ec)
  def bulk(operations: Seq[JsValue])(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.bulkFromSeq(Some(index), Some(typ))(operations)(ec)
  def bulk(source: Source[JsValue, _], batchEvery: Int = ElasticClient.BATCH_EVERY)(implicit ec: ExecutionContext, materializer: Materializer): Future[Seq[ElasticResponse]] = cli.bulkFromSource(Some(index), Some(typ))(source, batchEvery)(ec, materializer)
  def deleteIndex(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.deleteIndex(index)(ec)
  def refresh(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.refresh(index)(ec)
  def flush(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.flush(index)(ec)
  def mapping(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.mapping(Seq(index), Seq(typ))(ec)
  def putMapping(ignoreConflicts: Boolean = false)(mapping: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.putMapping(Seq(index), typ, ignoreConflicts)(mapping)(ec)
  def putMapping(mapping: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.putMapping(Seq(index), typ, false)(mapping)(ec)
  def putTemplate(index: String)(template: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.putTemplate(index)(template)(ec)
  def template(name: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.template(index, name)(ec)
  def deleteTemplate(name: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.deleteTemplate(index, name)(ec)
  def deleteAlias(name: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.deleteAlias(index, name)(ec)
  def alias(name: String)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.alias(index, name)(ec)
}

class SelectedObject(index: String, typ: String, id: String, cli: ElasticClient) {
  def future(): Future[SelectedObject] = Future.successful(this)
  def index(doc: JsValue, refresh: Boolean = false)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.index(index, typ, Some(id), refresh)(doc)(ec)
  def delete()(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.delete(index, typ, id)(ec)
  def get()(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.get(index, typ, id)(ec)
  def update(doc: JsValue, refresh: Boolean = false)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.index(index, typ, Some(id), refresh)(doc)(ec)
}

class IndexCreator(index: String, cli: ElasticClient) {
  def withNoSettings()(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.putIndex(index, None)(ec)
  def withSettings(settings: JsObject)(implicit ec: ExecutionContext): Future[ElasticResponse] = cli.putIndex(index, Some(settings))(ec)
}
