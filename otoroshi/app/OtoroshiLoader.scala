import actions._
import controllers._
import modules._
import play.api._
import play.api.ApplicationLoader.Context
import play.api.routing.Router
import com.softwaremill.macwire._
import env.Env
import gateway.{CircuitBreakersHolder, ErrorHandler, GatewayRequestHandler, WebSocketHandler}
import play.api.http.{DefaultHttpFilters, HttpErrorHandler, HttpRequestHandler}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ControllerComponents, DefaultControllerComponents, EssentialFilter}
import play.filters.HttpFiltersComponents
import router.Routes

class OtoroshiLoader extends ApplicationLoader {

  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    new OtoroshiComponentsInstances(context).application
  }
}

package object modules {

  class OtoroshiComponentsInstances(context: Context)
      extends BuiltInComponentsFromContext(context)
      with AssetsComponents
      with HttpFiltersComponents
      with AhcWSComponents {

    // lazy val gzipFilterConfig                           = GzipFilterConfig.fromConfiguration(configuration)
    // lazy val gzipFilter                                 = wire[GzipFilter]
    override lazy val httpFilters: Seq[EssentialFilter] = Seq()

    lazy val circuitBreakersHolder: CircuitBreakersHolder = wire[CircuitBreakersHolder]

    implicit lazy val env: Env = wire[Env]

    lazy val webSocketHandler: WebSocketHandler = wire[WebSocketHandler]
    lazy val filters                            = new DefaultHttpFilters(httpFilters: _*)

    override lazy val httpRequestHandler: HttpRequestHandler = wire[GatewayRequestHandler]
    override lazy val httpErrorHandler: HttpErrorHandler     = wire[ErrorHandler]

    lazy val apiAction            = wire[ApiAction]
    lazy val backOfficeAction     = wire[BackOfficeAction]
    lazy val backOfficeAuthAction = wire[BackOfficeActionAuth]
    lazy val privateAppsAction    = wire[PrivateAppsAction]

    lazy val swaggerController     = wire[SwaggerController]
    lazy val apiController         = wire[ApiController]
    lazy val auth0Controller       = wire[Auth0Controller]
    lazy val backOfficeController  = wire[BackOfficeController]
    lazy val privateAppsController = wire[PrivateAppsController]
    lazy val u2fController         = wire[U2FController]

    override lazy val assets: Assets = wire[Assets]
    lazy val router: Router = {
      // add the prefix string in local scope for the Routes constructor
      val prefix: String = "/"
      wire[Routes]
    }
  }
}
