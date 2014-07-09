package com.paypal.stingray.http.tests.resource

import org.specs2.SpecificationLike
import akka.testkit.{TestActorRef, TestKit}
import akka.actor.ActorSystem
import com.paypal.stingray.http.resource.HttpResourceActor
import spray.http.{StatusCodes, HttpResponse, HttpRequest}
import scala.util.{Try, Failure, Success}
import scala.concurrent.Promise
import com.paypal.stingray.common.tests.util.CommonImmutableSpecificationContext
import com.paypal.stingray.http.tests.actor.RefAndProbe
import com.paypal.stingray.http.tests.matchers.RefAndProbeMatchers
import com.paypal.stingray.akka.tests.actor.ActorSpecification
import scala.concurrent.duration.Duration
import com.paypal.stingray.common.tests.future._
import java.util.concurrent.TimeUnit
import com.paypal.stingray.http.tests.resource.DummyResource.{SyncSleep, SleepRequest, GetRequest}
import com.paypal.stingray.http.resource.HttpResourceActor.ResourceContext
import com.fasterxml.jackson.core.{JsonLocation, JsonParseException}

class HttpResourceActorSpecs
  extends TestKit(ActorSystem("resource-actor-specs"))
  with SpecificationLike
  with ActorSpecification { override def is = s2"""

  ResourceActor is the individual actor that executes an entire request against an AbstractResource. One is created per request.

    After the ResourceActor succeeds, it writes the appropriate HttpResponse to the return actor and stops                   ${Succeeds().writesToReturnActor}
    After the ResourceActor fails, it writes the appropriate failure HttpResponse to the return actor and stops              ${Fails().writesToReturnActor}
    After the ResourceActor succeeds, it writes the appropriate HttpResponse to the DummyRequestContext and stops            ${Succeeds().writesToRequestContext}
    After the ResourceActor fails, it writes the appropriate HttpResponse to the DummyRequestContext and stops               ${Fails().writesToRequestContext}

    The ResourceActor should be start-able from the reference.conf file                                                      ${Start().succeeds}

    The ResourceActor should time out if the request processor takes too long in async code                                  ${Start().timesOutOnAsyncRequestProcessor}
    The ResourceActor will still succeed if blocking code takes too long. DON'T BLOCK in HttpActors!                         ${Start().timeOutFailsOnBlockingRequestProcessor}

    If the request parser is a failure due to malformed json, Status.Failure is called and a 400 is returned                 ${JsonParseFail().reqParserFail}
  """
  private val resourceGen = new DummyResource(_)

  sealed trait Context extends CommonImmutableSpecificationContext with RefAndProbeMatchers {

    protected lazy val reqParser: HttpResourceActor.RequestParser = _ => Success(GetRequest("bar"))

    protected lazy val req = HttpRequest()

    private val reqCtxHandlerPromise = Promise[HttpResponse]()
    protected lazy val (dummyReqCtx, reqCtxHandlerActor) = DummyRequestContext(req, reqCtxHandlerPromise)
    protected val reqCtxHandlerActorFuture = reqCtxHandlerPromise.future

    private val returnActorPromise = Promise[HttpResponse]()
    protected lazy val returnActorRefAndProbe = RefAndProbe(TestActorRef(new ResponseHandlerActor(returnActorPromise)))
    protected val returnActorFuture = returnActorPromise.future

    private lazy val dummyResourceContext = ResourceContext(dummyReqCtx, reqParser,  Some(returnActorRefAndProbe.ref))

    protected lazy val resourceActorRefAndProbe = RefAndProbe(TestActorRef(new DummyResource(dummyResourceContext)))

    override def before() {
      resourceActorRefAndProbe.ref ! HttpResourceActor.Start
    }
  }

  case class Start() extends Context {

    def succeeds = {
      val props = HttpResourceActor.props(resourceGen, dummyReqCtx, reqParser, None)
      val started = Try(system.actorOf(props))
      started.map { a =>
          system.stop(a)
      }
      started must beASuccessfulTry
    }

    def timesOut = {
      val refAndProbe = RefAndProbe(TestActorRef(new DummyResource(ResourceContext(dummyReqCtx, reqParser, None, Duration.Zero))))

      val stoppedRes = refAndProbe must beStopped
      val failedRes = reqCtxHandlerActorFuture.toTry must beASuccessfulTry.like {
        case HttpResponse(status, _, _, _) => status must beEqualTo(StatusCodes.ServiceUnavailable)
      }
      stoppedRes and failedRes
    }

    def timesOutOnAsyncRequestProcessor = {
      val processRecvTimeout = Duration(50, TimeUnit.MILLISECONDS)

      lazy val resourceActorCtor = new DummyResource(ResourceContext(
        reqContext = dummyReqCtx,
        reqParser = request => Success(SleepRequest(500)),
        mbReturnActor = None,
        processRecvTimeout = processRecvTimeout
      ))
      val refAndProbe = RefAndProbe(TestActorRef(resourceActorCtor))
      refAndProbe.ref ! HttpResourceActor.Start
      reqCtxHandlerActorFuture must beLike[HttpResponse] {
        case HttpResponse(statusCode, _, _, _) => statusCode must beEqualTo(StatusCodes.ServiceUnavailable)
      }.await
    }

    def timeOutFailsOnBlockingRequestProcessor = {
      val processRecvTimeout = Duration(50, TimeUnit.MILLISECONDS)

      lazy val resourceActorCtor = new DummyResource(ResourceContext(
        reqContext = dummyReqCtx,
        reqParser = request => Success(SyncSleep(500)),
        mbReturnActor = None,
        processRecvTimeout = processRecvTimeout
      ))
      val refAndProbe = RefAndProbe(TestActorRef(resourceActorCtor))
      refAndProbe.ref ! HttpResourceActor.Start
      reqCtxHandlerActorFuture must beLike[HttpResponse] {
        case HttpResponse(statusCode, _, _, _) => statusCode must beEqualTo(StatusCodes.OK)
      }.await
    }
  }

  case class Succeeds() extends Context {
    def writesToReturnActor = apply {
      val recvRes = returnActorFuture must beLike[HttpResponse] {
        case HttpResponse(statusCode, _, _, _) => statusCode must beEqualTo(StatusCodes.OK)
      }.await

      val stoppedRes = resourceActorRefAndProbe must beStopped

      recvRes and stoppedRes
    }

    def writesToRequestContext = apply {
      val recvRes = reqCtxHandlerActorFuture must beLike[HttpResponse] {
        case HttpResponse(statusCode, _, _, _) => statusCode must beEqualTo(StatusCodes.OK)
      }.await

      val stoppedRes = resourceActorRefAndProbe must beStopped

      recvRes and stoppedRes
    }
  }

  case class Fails() extends Context {
    private lazy val ex = new Exception("hello world")
    override protected lazy val reqParser: HttpResourceActor.RequestParser = { req: HttpRequest =>
      Failure(ex)
    }

    def writesToReturnActor = apply {
      val recvRes = returnActorFuture must beAnInstanceOf[HttpResponse].await
      val stoppedRes = resourceActorRefAndProbe must beStopped
      recvRes and stoppedRes
    }

    def writesToRequestContext = apply {
      val recvRes = reqCtxHandlerActorFuture must beAnInstanceOf[HttpResponse].await
      val stoppedRes = resourceActorRefAndProbe must beStopped
      recvRes and stoppedRes
    }
  }

  case class JsonParseFail() extends Context {
    override protected lazy val reqParser: HttpResourceActor.RequestParser = { req: HttpRequest =>
      Failure(new JsonParseException("could not parse json", JsonLocation.NA))
    }
    def reqParserFail = apply {
      val refAndProbe = RefAndProbe(TestActorRef(new DummyResource(ResourceContext(dummyReqCtx, reqParser))))
      refAndProbe.ref ! HttpResourceActor.Start
      val failedRes = reqCtxHandlerActorFuture.toTry must beASuccessfulTry.like {
        case HttpResponse(status, _, _, _) => status must beEqualTo(StatusCodes.BadRequest)
      }
      failedRes
    }
  }

}
