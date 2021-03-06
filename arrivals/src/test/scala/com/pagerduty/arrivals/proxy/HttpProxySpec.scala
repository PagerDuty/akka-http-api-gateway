package com.pagerduty.arrivals.proxy

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest, WebSocketUpgradeResponse}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.scaladsl.Flow
import com.pagerduty.akka.http.support.RequestMetadata
import com.pagerduty.arrivals.api.filter.{SyncRequestFilter, SyncResponseFilter}
import com.pagerduty.arrivals.api.proxy.Upstream
import com.pagerduty.metrics.NullMetrics
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

class HttpProxySpec extends AnyFreeSpecLike with Matchers with ScalaFutures {
  def buildHttpClient(requestExecutor: HttpRequest => Future[HttpResponse]): HttpClient = {
    new HttpClient {
      def executeRequest(req: HttpRequest): Future[HttpResponse] =
        requestExecutor(req)

      def executeWebSocketRequest[T](
          request: WebSocketRequest,
          clientFlow: Flow[Message, Message, T]
        ): (Future[WebSocketUpgradeResponse], T) = ???
    }
  }

  implicit val reqMeta = RequestMetadata.fromRequest(HttpRequest())

  "An HttpProxy" - {
    val headerKey = "x-test-header"
    val headerValue = "test"
    val additionalHeader = RawHeader(headerKey, headerValue)

    val authority = Authority(Uri.Host("localhost"), 1234)

    val upstream = new Upstream[String] {
      val metricsTag = "test"

      def addressRequest(request: HttpRequest, addressingConfig: String): HttpRequest = {
        val uri = request.uri.withAuthority(authority)
        request.withUri(uri)
      }

      override def requestFilter = new SyncRequestFilter[Any] {
        override def applySync(request: HttpRequest, reqData: Any): Right[Nothing, HttpRequest] =
          Right(request.addHeader(additionalHeader))
      }

      override def responseFilter = new SyncResponseFilter[Any] {
        override def applySync(request: HttpRequest, response: HttpResponse, reqData: Any): HttpResponse =
          response.addHeader(request.getHeader(headerKey).get)
      }
    }

    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val system = ActorSystem()
    implicit val metrics = NullMetrics
    val response = HttpResponse()

    "removes the Timeout-Header if it exists before proxying" in {
      val httpClient = buildHttpClient((req: HttpRequest) => {
        if (req.headers.exists(_.is("timeout-access"))) {
          throw new Exception("The Timeout-Access header is not being removed as we expect")
        }

        Future.successful(response)
      })

      val p = new HttpProxy("localhost", httpClient)

      p(HttpRequest().withHeaders(RawHeader("Timeout-Access", "foo")), upstream)
    }

    "prepares the request before proxying" in {
      val httpClient = buildHttpClient((req: HttpRequest) => {
        req.headers.find(_.is(headerKey)) match {
          case Some(h) if h.value() == headerValue => // it works!
          case _ =>
            throw new Exception("The front-end header is not being added as we expect")
        }

        Future.successful(response)
      })

      val p = new HttpProxy("localhost", httpClient)

      p(HttpRequest(), upstream)
    }

    "addresses the request before proxy" in {
      val httpClient = buildHttpClient((req: HttpRequest) => {
        if (req.uri.authority != authority) {
          throw new Exception("Authority on proxied request not being set as expected")
        }
        Future.successful(response)
      })

      val p = new HttpProxy("localhost", httpClient)

      p(HttpRequest(), upstream)
    }

    "transforms responses for an upstream" in {
      val httpClient = buildHttpClient((req: HttpRequest) => {
        Future.successful(response)
      })

      val p = new HttpProxy("localhost", httpClient)

      whenReady(p(HttpRequest(), upstream)) { response =>
        response.getHeader(headerKey).get.value shouldBe headerValue
      }
    }
  }
}
