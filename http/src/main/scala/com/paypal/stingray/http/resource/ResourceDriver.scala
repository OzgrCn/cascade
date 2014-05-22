package com.paypal.stingray.http.resource

import spray.http._
import spray.http.StatusCodes.{Success => _, _}
import com.paypal.stingray.common.constants.ValueConstants.charsetUtf8
import scala.util._
import spray.routing.RequestContext
import akka.actor.{ActorRef, ActorRefFactory}
import scala.concurrent.duration.Duration

/**
 * Implementation of a basic HTTP request handling pipeline.
 *
 * Used to push along HTTP requests
 *
 * See https://confluence.paypal.com/cnfl/display/stingray/AbstractResource%2C+ResourceDriver%2C+and+ResourceService
 * for more information.
 */

object ResourceDriver {

  type RewriteFunction[ParsedRequest] = HttpRequest => Try[(HttpRequest, ParsedRequest)]

  /**
   * Run the request on this resource, first applying a rewrite. This should not be overridden.
   * @param resource this resource
   * @param rewrite a method by which to rewrite the request
   * @tparam ParsedRequest the request after parsing
   * @tparam AuthInfo the authorization container
   * @return the rewritten request execution
   */
  final def serveWithRewrite[ParsedRequest, AuthInfo](resource: AbstractResource[AuthInfo],
                                                processFunction: ResourceActor.RequestProcessor[ParsedRequest],
                                                mbResponseActor: Option[ActorRef] = None,
                                                recvTimeout: Duration = ResourceActor.defaultRecvTimeout,
                                                processRecvTimeout: Duration = ResourceActor.defaultProcessRecvTimeout)
                                               (rewrite: RewriteFunction[ParsedRequest])
                                               (implicit actorRefFactory: ActorRefFactory): RequestContext => Unit = {
    ctx: RequestContext =>
      rewrite(ctx.request).map {
        case (request, parsed) =>
          val serveFn = serve(resource, processFunction, r => Success(parsed), mbResponseActor, recvTimeout, processRecvTimeout)
          serveFn(ctx.copy(request = request))
      }.recover {
        case e: Exception =>
          ctx.complete(HttpResponse(InternalServerError, resource.coerceError(Option(e.getMessage).getOrElse("").getBytes(charsetUtf8))))
      }
  }

  /**
   * Run the request on this resource
   * @param resource this resource
   * @param processFunction the function to be executed to process the request
   * @tparam ParsedRequest the request after parsing
   * @tparam AuthInfo the authorization container
   * @return the request execution
   */
  final def serve[ParsedRequest, AuthInfo](resource: AbstractResource[AuthInfo],
                                           processFunction: ResourceActor.RequestProcessor[ParsedRequest],
                                           requestParser: ResourceActor.RequestParser[ParsedRequest],
                                           mbResponseActor: Option[ActorRef] = None,
                                           recvTimeout: Duration = ResourceActor.defaultRecvTimeout,
                                           processRecvTimeout: Duration = ResourceActor.defaultProcessRecvTimeout)
                                          (implicit actorRefFactory: ActorRefFactory): RequestContext => Unit = {
    { ctx: RequestContext =>
      val actor = actorRefFactory.actorOf(
        ResourceActor.props(resource, ctx, requestParser, processFunction, mbResponseActor, recvTimeout, processRecvTimeout))
      actor ! ResourceActor.Start
    }
  }
}
