package org.reactivecouchbase.elastic

import java.io.{File, IOException}
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import okhttp3._
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}

private object ClientHolder {
  val client = new OkHttpClient()
  val empty = "".getBytes("UTF-8")
}

private trait Method {
  def name: String
}
private object GET extends Method {
  def name: String = "GET"
}
private object PUT extends Method{
  def name: String = "PUT"
}
private object POST extends Method{
  def name: String = "POST"
}
private object DELETE extends Method{
  def name: String = "DELETE"
}
private object PATCH extends Method{
  def name: String = "PATCH"
}
private object HEAD extends Method{
  def name: String = "HEAD"
}
private object OPTIONS extends Method{
  def name: String = "OPTIONS"
}

object Http {
  def hosts(hosts: Seq[String])(implicit client: OkHttpClient = ClientHolder.client): RequestHolder = new RequestHolder(
    hosts = hosts.map {
      case s if s.startsWith("http") => s
      case s => s"http://$s"
    } map {
      case s if s.contains(":") => s
      case s => s"$s:9200"
    },
    url = "/",
    client = client
  )
}

case class Cookie(name: String, value: String, domain: Option[String] = None, expires:  Option[String] = None, path: Option[String] = None, secure: Boolean = false, httpOnly: Boolean = false) {
  def toCookie = s"$name=$value" + domain.map("; domain=" + _).getOrElse("") + expires.map("; expires=" + _).getOrElse("") + path.map("; path=" + _).getOrElse("") + (if(secure) "; secure" else "") + (if(httpOnly) "; HttpOnly" else "")
}

case class RequestHolder(
  hosts: Seq[String],
  url: String,
  client: OkHttpClient,
  body: Option[Array[Byte]] = None,
  rbody: Option[RequestBody] = None,
  vhost: Option[String] = None,
  headers: Seq[(String, String)] = Seq(),
  params: Seq[(String, String)] = Seq(),
  timeout: Option[Duration] = Some(Duration(60, TimeUnit.SECONDS)),
  redirect: Boolean = false,
  authenticator: Option[Authenticator] = None,
  dispatcher: Option[Dispatcher] = None,
  proxy: Option[java.net.Proxy] = None,
  media: String = "application/octet-stream",
  parts: Seq[String] = Seq()
) {

  private val counter = new AtomicInteger(0)
  private val logger = LoggerFactory.getLogger("RequestHolder")

  def addPart(part: String): RequestHolder = this.copy(parts = this.parts :+ part)

  def withPart(part: String): RequestHolder = addPart(part)

  def withUrl(u: String): RequestHolder = this.copy(url = u)

  def withAuth(a: Authenticator): RequestHolder = this.copy(authenticator = Some(a))

  def withAuth(user: String, password: String): RequestHolder = this.copy(authenticator = Some(new Authenticator {
    def authenticate(route: Route, response: Response): Request =
      response.request().newBuilder().header("Authorization", Credentials.basic(user, password)).build()
  }))

  def withFollowSslRedirects(follow: Boolean): RequestHolder = this.copy(redirect = follow)

  def withParams(p: (String, String)*): RequestHolder = this.copy(params = params ++ p.toSeq)

  def withRequestTimeout(timeout: Int): RequestHolder = this.copy(timeout = Some(Duration(timeout, TimeUnit.SECONDS)))

  def withRequestTimeout(timeout: String): RequestHolder = this.copy(timeout = Some(Duration(timeout)))

  def withRequestTimeout(timeout: Duration): RequestHolder = this.copy(timeout = Some(timeout))

  def withVirtualHost(vh: String): RequestHolder = this.copy(vhost = Some(vh))

  def withMediaType(mt: String): RequestHolder = this.copy(media = mt)

  def withBody(body: String): RequestHolder = this.copy(body = Some(body.getBytes("UTF-8")), media = "text/plain")

  def withBody(jsv: JsValue): RequestHolder = this.copy(body = Some(Json.stringify(jsv).getBytes("UTF-8")), media = "application/json")

  def withBody(opt: Option[JsValue]): RequestHolder = this.copy(body = opt.map(jsv => Json.stringify(jsv).getBytes("UTF-8")), media = "application/json")

  def withBody(body: Array[Byte]): RequestHolder = this.copy(body = Some(body), media = "application/octet-stream")

  def withBody(file: File): RequestHolder = this.copy(body = Some(Files.readAllBytes(file.toPath)), media = "application/octet-stream" )

  def withBody(b: RequestBody): RequestHolder = this.copy(rbody = Some(b))

  def withHeaders(h: (String, String)*): RequestHolder = this.copy(headers = headers ++ h.toSeq)

  def withCookies(cookies: Cookie*): RequestHolder = withHeaders(cookies.toSeq.map(c => ("Set-Cookie", c.toCookie)):_*)

  def withExecutionContext(implicit ec: ExecutionContext) = this.copy(dispatcher = Some(new Dispatcher(ExecutionContextExecutorServiceBridge(ec))))

  def run(method: Method) = execute(buildClient(), buildRequest(method))

  def get() = execute(buildClient(), buildRequest(GET))

  def patch() = execute(buildClient(), buildRequest(PATCH))

  def post() = execute(buildClient(), buildRequest(POST))

  def put() = execute(buildClient(), buildRequest(PUT))

  def delete() = execute(buildClient(), buildRequest(DELETE))

  def head() = execute(buildClient(), buildRequest(HEAD))

  def options() = execute(buildClient(), buildRequest(OPTIONS))

  private[this] def buildClient(): OkHttpClient = {
    var c = client.newBuilder()
    c = c.followSslRedirects(redirect)
    if (proxy.isDefined) c = c.proxy(proxy.get)
    if (authenticator.isDefined) c = c.authenticator(authenticator.get)
    if (dispatcher.isDefined) c = c.dispatcher(dispatcher.get)
    if (timeout.isDefined) {
      c.connectTimeout(timeout.get.toMillis, TimeUnit.MILLISECONDS)
      c.readTimeout(timeout.get.toMillis, TimeUnit.MILLISECONDS)
      c.writeTimeout(timeout.get.toMillis, TimeUnit.MILLISECONDS)
    }
    c.build()
  }

  private[this] def buildRequest(method: Method): Request = {
    var builder = new Request.Builder()
    for(header <- headers) {
      builder = builder.addHeader(header._1, header._2)
    }
    val host = Utils.applyWhile(hosts.size) { host => CircuitBreaker(host).isOpen } { () =>
      val size = hosts.size
      val modulo = if (size > 0) size else 1
      val index = counter.incrementAndGet() % modulo
      hosts(index)
    }
    val theUrl = s"$host$url"
    logger.debug(s"Use Elastic host : $theUrl")
    val urlWithPart = theUrl + parts.mkString("/").replace("//", "/")
    val finalUrl = params match {
      case p if p.nonEmpty && urlWithPart.contains("?") => urlWithPart + "&" + params.map(t => s"${t._1}=${URLEncoder.encode(t._2, "UTF-8")}").mkString("&")
      case p if p.nonEmpty => urlWithPart + "?" + params.map(t => s"${t._1}=${URLEncoder.encode(t._2, "UTF-8")}").mkString("&")
      case p => urlWithPart
    }
    logger.debug(s"Verb and URL used : ${method.name} $finalUrl")
    builder = builder.url(finalUrl)
    val rb: RequestBody =  rbody.getOrElse(RequestBody.create(MediaType.parse(media), body.getOrElse(ClientHolder.empty)))
    method match {
      case GET => builder = builder.get()
      case PUT => builder = builder.put(rb)
      case POST => builder = builder.post(rb)
      case DELETE => builder = builder.delete()
      case PATCH => builder = builder.patch(rb)
      case HEAD => builder = builder.head()
      case OPTIONS => builder = builder.method("OPTIONS", rb)
    }
    builder.build()
  }

  private[this] def execute(client: OkHttpClient, request: Request): Future[Response] = {
    val breaker = CircuitBreaker.apply("") // TODO : change
    val p = Promise[Response]()
    val start = System.currentTimeMillis()
    client.newCall(request).enqueue(new Callback {
      override def onFailure(call: Call, e: IOException): Unit = {
        breaker.markFailure(Duration(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS))
        logger.error("Error while contacting Elastic", e)
        p.tryFailure(e)
      }
      override def onResponse(call: Call, response: Response): Unit = {
        breaker.markSuccess(Duration(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS))
        p.trySuccess(response)
      }
    })
    p.future
  }
}