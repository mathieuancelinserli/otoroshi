package functional

import java.net.ServerSocket
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{Message, TextMessage, UpgradeToWebSocket}
import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import models.{ApiKey, GlobalConfig, ServiceDescriptor, ServiceGroup}
import modules.OtoroshiComponentsInstances
import org.scalatest.TestSuite
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.components.{OneServerPerSuiteWithComponents, OneServerPerTestWithComponents}
import org.slf4j.LoggerFactory
import play.api.ApplicationLoader.Context
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.libs.ws.{WSAuthScheme, WSClient}
import play.api.{BuiltInComponents, Configuration, Logger}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Random, Try}

trait AddConfiguration {
  def getConfiguration(configuration: Configuration): Configuration
}

class OtoroshiTestComponentsInstances(context: Context, conf: Configuration => Configuration)
  extends OtoroshiComponentsInstances(context) {
  override def configuration = conf(super.configuration)
}

trait OneServerPerSuiteWithMyComponents
  extends OneServerPerSuiteWithComponents
    with ScalaFutures
    with AddConfiguration { this: TestSuite =>

  val otoroshiComponents = {
    val components = new OtoroshiTestComponentsInstances(context, getConfiguration)
    println(s"Using env ${components.env}") // WARNING: important to keep, needed to switch env between suites
    components
  }

  override def components: BuiltInComponents = otoroshiComponents
}

//trait OneServerPerTestWithMyComponents extends OneServerPerTestWithComponents with ScalaFutures with AddConfiguration {
//  this: TestSuite =>
//  val otoroshiComponents = new OtoroshiTestComponentsInstances(context, getConfiguration)
//  override def components: BuiltInComponents = otoroshiComponents
//}

trait OtoroshiSpecHelper { suite: OneServerPerSuiteWithMyComponents =>

  lazy implicit val ec = otoroshiComponents.env.internalActorSystem.dispatcher
  lazy val logger = Logger("otoroshi-spec-helper")

  def await(duration: FiniteDuration): Unit = {
    val p = Promise[Unit]
    otoroshiComponents.env.internalActorSystem.scheduler.scheduleOnce(duration) {
      p.trySuccess(())
    }
    Await.result(p.future, duration + 1.second)
  }

  def awaitF(duration: FiniteDuration)(implicit system: ActorSystem): Future[Unit] = {
    val p = Promise[Unit]
    system.scheduler.scheduleOnce(duration) {
      p.trySuccess(())
    }
    p.future
  }

  def otoroshiApiCall(method: String, path: String, payload: Option[JsValue] = None, customPort: Option[Int] = None): Future[(JsValue, Int)] = {
    val headers = Seq(
      "Host" -> "otoroshi-api.foo.bar",
      "Accept" -> "application/json"
    )
    if (payload.isDefined) {
      suite.otoroshiComponents.wsClient.url(s"http://127.0.0.1:${customPort.getOrElse(port)}$path")
        .withHttpHeaders(headers :+ ("Content-Type" -> "application/json"): _*)
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .withFollowRedirects(false)
        .withMethod(method)
        .withBody(Json.stringify(payload.get))
        .execute()
        .map { response =>
          if (response.status != 200) {
            logger.error(response.body)
          }
          (response.json, response.status)
        }
    } else {
      suite.otoroshiComponents.wsClient.url(s"http://127.0.0.1:${customPort.getOrElse(port)}$path")
        .withHttpHeaders(headers: _*)
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .withFollowRedirects(false)
        .withMethod(method)
        .execute()
        .map { response =>
          if (response.status != 200) {
            logger.error(response.body)
          }
          (response.json, response.status)
        }
    }
  }

  def getOtoroshiConfig(customPort: Option[Int] = None, ws: WSClient = suite.otoroshiComponents.wsClient): Future[GlobalConfig] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/globalconfig").withHttpHeaders(
      "Host" -> "otoroshi-api.foo.bar",
      "Accept" -> "application/json"
    ).withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC).get().map { response =>
      //if (response.status != 200) {
      //  println(response.body)
      //}
      GlobalConfig.fromJsons(response.json)
    }
  }

  def updateOtoroshiConfig(config: GlobalConfig, customPort: Option[Int] = None, ws: WSClient = suite.otoroshiComponents.wsClient): Future[GlobalConfig] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/globalconfig").withHttpHeaders(
      "Host" -> "otoroshi-api.foo.bar",
      "Content-Type" -> "application/json"
    ).withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC).put(Json.stringify(config.toJson)).map { response =>
      //if (response.status != 200) {
      //  println(response.body)
      //}
      GlobalConfig.fromJsons(response.json)
    }
  }

  def getOtoroshiServices(customPort: Option[Int] = None, ws: WSClient = suite.otoroshiComponents.wsClient): Future[Seq[ServiceDescriptor]] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/services").withHttpHeaders(
      "Host" -> "otoroshi-api.foo.bar",
      "Accept" -> "application/json"
    ).withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC).get().map { response =>
      //if (response.status != 200) {
      //  println(response.body)
      //}
      response.json.as[JsArray].value.map(e => ServiceDescriptor.fromJsons(e))
    }
  }

  def getOtoroshiServiceGroups(customPort: Option[Int] = None): Future[Seq[ServiceGroup]] = {
    suite.otoroshiComponents.wsClient.url(s"http://localhost:${customPort.getOrElse(port)}/api/groups").withHttpHeaders(
      "Host" -> "otoroshi-api.foo.bar",
      "Accept" -> "application/json"
    ).withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC).get().map { response =>
      response.json.as[JsArray].value.map(e => ServiceGroup.fromJsons(e))
    }
  }

  def getOtoroshiApiKeys(customPort: Option[Int] = None): Future[Seq[ApiKey]] = {
    suite.otoroshiComponents.wsClient.url(s"http://localhost:${customPort.getOrElse(port)}/api/apikeys").withHttpHeaders(
      "Host" -> "otoroshi-api.foo.bar",
      "Accept" -> "application/json"
    ).withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC).get().map { response =>
      response.json.as[JsArray].value.map(e => ApiKey.fromJsons(e))
    }
  }

  def createOtoroshiService(service: ServiceDescriptor, customPort: Option[Int] = None, ws: WSClient = suite.otoroshiComponents.wsClient): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/services")
      .withHttpHeaders(
        "Host" -> "otoroshi-api.foo.bar",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(service.toJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def createOtoroshiApiKey(apiKey: ApiKey, customPort: Option[Int] = None, ws: WSClient = suite.otoroshiComponents.wsClient): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/groups/default/apikeys")
      .withHttpHeaders(
        "Host" -> "otoroshi-api.foo.bar",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .post(Json.stringify(apiKey.toJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def deleteOtoroshiApiKey(apiKey: ApiKey, customPort: Option[Int] = None, ws: WSClient = suite.otoroshiComponents.wsClient): Future[(JsValue, Int)] = {
    ws.url(s"http://localhost:${customPort.getOrElse(port)}/api/groups/default/apikeys/${apiKey.clientId}")
      .withHttpHeaders(
        "Host" -> "otoroshi-api.foo.bar",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .delete()
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def updateOtoroshiService(service: ServiceDescriptor, customPort: Option[Int] = None): Future[(JsValue, Int)] = {
    suite.otoroshiComponents.wsClient.url(s"http://localhost:${customPort.getOrElse(port)}/api/services/${service.id}")
      .withHttpHeaders(
        "Host" -> "otoroshi-api.foo.bar",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .put(Json.stringify(service.toJson))
      .map { resp =>
        (resp.json, resp.status)
      }
  }

  def deleteOtoroshiService(service: ServiceDescriptor, customPort: Option[Int] = None): Future[(JsValue, Int)] = {
    suite.otoroshiComponents.wsClient.url(s"http://localhost:${customPort.getOrElse(port)}/api/services/${service.id}")
      .withHttpHeaders(
        "Host" -> "otoroshi-api.foo.bar",
        "Content-Type" -> "application/json"
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .delete()
      .map { resp =>
        (resp.json, resp.status)
      }
  }
}

object Implicits {
  implicit class BetterFuture[A](val fu: Future[A]) extends AnyVal {
    def await(): A = {
      Await.result(fu, 60.seconds)
    }
  }
  implicit class BetterOptional[A](val opt: Optional[A]) extends AnyVal {
    def asOption: Option[A] = {
      if (opt.isPresent) {
        Some(opt.get())
      } else {
        None
      }
    }
  }
}

object HttpResponses {

  def NotFound(path: String) = HttpResponse(
    404,
    entity =
      HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> s"$path not found")))
  )

  def GatewayTimeout() = HttpResponse(
    504,
    entity = HttpEntity(ContentTypes.`application/json`,
      Json.stringify(Json.obj("error-test" -> s"Target servers timeout")))
  )

  def BadGateway(message: String) = HttpResponse(
    502,
    entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> message)))
  )

  def BadRequest(message: String) = HttpResponse(
    400,
    entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> message)))
  )

  def Unauthorized(message: String) = HttpResponse(
    401,
    entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(Json.obj("error-test" -> message)))
  )

  def Ok(json: JsValue) = HttpResponse(
    200,
    entity = HttpEntity(ContentTypes.`application/json`, Json.stringify(json))
  )
}

class TargetService(host: Option[String], path: String, contentType: String, result: HttpRequest => String) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = ActorMaterializer.create(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    (request.method, request.uri.path) match {
      case (HttpMethods.GET, p) if host.isEmpty => {
        FastFuture.successful(
          HttpResponse(
            200,
            entity = HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`),
              ByteString(result(request)))
          )
        )
      }
      case (HttpMethods.GET, p) if TargetService.extractHost(request) == host.get => {
        FastFuture.successful(
          HttpResponse(
            200,
            entity = HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`),
              ByteString(result(request)))
          )
        )
      }
      case (_, p) => {
        FastFuture.successful(HttpResponses.NotFound(p.toString()))
      }
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): TargetService = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

class SimpleTargetService(host: Option[String], path: String, contentType: String, result: HttpRequest => String) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = ActorMaterializer.create(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    (request.method, request.uri.path) match {
      case (_,_) => {
        FastFuture.successful(
          HttpResponse(
            200,
            entity = HttpEntity(ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`),
              ByteString(result(request)))
          )
        )
      }
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): SimpleTargetService = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

class AlertServer(counter: AtomicInteger) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = ActorMaterializer.create(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    request.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bodyByteString =>
      val body = bodyByteString.utf8String
      //println(Json.prettyPrint(Json.parse(body)))
      counter.incrementAndGet()
      HttpResponse(
        200,
        entity = HttpEntity(ContentTypes.`application/json`, ByteString(Json.stringify(Json.obj("done" -> true))))
      )
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): AlertServer = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

class AnalyticsServer(counter: AtomicInteger) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = ActorMaterializer.create(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    request.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bodyByteString =>
      val body = bodyByteString.utf8String
      val events = Json.parse(body).as[JsArray].value
      counter.addAndGet(events.size)
      HttpResponse(
        200,
        entity = HttpEntity(ContentTypes.`application/json`, ByteString(Json.stringify(Json.obj("done" -> true))))
      )
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): AnalyticsServer = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

class WebsocketServer(counter: AtomicInteger) {

  val port = TargetService.freePort

  implicit val system = ActorSystem()
  implicit val ec     = system.dispatcher
  implicit val mat    = ActorMaterializer.create(system)
  implicit val http   = Http(system)

  val logger = LoggerFactory.getLogger("otoroshi-test")

  val greeterWebSocketService =
    Flow[Message]
      .map { message =>
        println("server received message")
        counter.incrementAndGet()
        TextMessage(Source.single("Hello ") ++ message.asTextMessage.getStreamedText)
      }

  def handler(request: HttpRequest): Future[HttpResponse] = {
    request.header[UpgradeToWebSocket] match {
      case Some(upgrade) => FastFuture.successful(upgrade.handleMessages(greeterWebSocketService))
      case None => FastFuture.successful(HttpResponse(400, entity = "Not a valid websocket request!"))
    }
  }

  val bound = http.bindAndHandleAsync(handler, "0.0.0.0", port)

  def await(): WebsocketServer = {
    Await.result(bound, 60.seconds)
    this
  }

  def stop(): Unit = {
    Await.result(bound, 60.seconds).unbind()
    Await.result(http.shutdownAllConnectionPools(), 60.seconds)
    Await.result(system.terminate(), 60.seconds)
  }
}

object TargetService {

  import Implicits._

  def apply(host: Option[String], path: String, contentType: String, result: HttpRequest => String): TargetService = {
    new TargetService(host, path, contentType, result)
  }

  def freePort: Int = {
    Try {
      val serverSocket = new ServerSocket(0)
      val port         = serverSocket.getLocalPort
      serverSocket.close()
      port
    }.toOption.getOrElse(Random.nextInt(1000) + 7000)
  }

  private val AbsoluteUri = """(?is)^(https?)://([^/]+)(/.*|$)""".r

  def extractHost(request: HttpRequest): String =
    request.getHeader("Otoroshi-Proxied-Host").asOption.map(_.value()).getOrElse("--")
}