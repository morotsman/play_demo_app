package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import akka.actor.ActorSystem
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException
import akka.pattern.CircuitBreakerOpenException

import akka.pattern.CircuitBreaker
import akka.pattern.pipe
import akka.actor.{ Actor, ActorLogging, ActorRef }

import akka.util.Timeout
import play.api.libs.json._
import akka.pattern.ask

import play.api.libs.ws._

class AggregateController @Inject() (ws: WSClient, actorSystem: ActorSystem, configuration: play.api.Configuration)(implicit exec: ExecutionContext) extends Controller {

  val importantUrl = configuration.getString("importantUrl").get
  val importantTimeOutInMillis = configuration.getInt("importantTimeOutInMillis").get
  val importantResetTimeOutInMillis = configuration.getInt("importantResetTimeOutInMillis").get

  val notImportantUrl = configuration.getString("notImportantUrl").get
  val notImportantTimeOutInMillis = configuration.getInt("notImportantTimeOutInMillis").get
  val nonImportantResetTimeOutInMillis = configuration.getInt("nonImportantResetTimeOutInMillis").get

  val importantRequest: WSRequest = ws.url(importantUrl).withRequestTimeout(importantTimeOutInMillis.millis)
  val notImportantRequest: WSRequest = ws.url(notImportantUrl).withRequestTimeout(notImportantTimeOutInMillis.millis)

  val importantBreaker =
    new CircuitBreaker(actorSystem.scheduler, maxFailures = 5, callTimeout = importantTimeOutInMillis.millis, resetTimeout = importantResetTimeOutInMillis.millis)
      .onClose(notifyMe("important", "closed"))
      .onOpen(notifyMe("important", "open"))
      .onHalfOpen(notifyMe("important", "half open"))

  val notImportantBreaker =
    new CircuitBreaker(actorSystem.scheduler, maxFailures = 5, callTimeout = notImportantTimeOutInMillis.millis, resetTimeout = nonImportantResetTimeOutInMillis.millis)
      .onClose(notifyMe("not_important", "closed"))
      .onOpen(notifyMe("not_important", "open"))
      .onHalfOpen(notifyMe("not_important", "half open"))

  //handles route /important
  def important = Action.async {
    importantBreaker
      .withCircuitBreaker(importantRequest.get().map(mapResponse).map(Ok(_)))
      .recoverWith(errorHandler)
  }

  def mapResponse(r: WSResponse): String =
    if (r.status == 200) r.body else throw new RuntimeException("Error when calling external service")

  val errorHandler: PartialFunction[Throwable, Future[Result]] = {
    case _ => Future(InternalServerError)
  }

  //handles route /aggregate
  def aggregate = Action.async {
    val important: Future[String] = makeImportantCall
    val notImportant = makeNonImportantCall

    val aggregatedResponse: Future[Result] = for (
      response1 <- important;
      response2 <- notImportant
    ) yield Ok(response1 + " ; " + response2)

    aggregatedResponse.recoverWith(errorHandler)
  }

  def makeImportantCall: Future[String] =
    importantBreaker
      .withCircuitBreaker(importantRequest.get().map(mapResponse))

  def makeNonImportantCall: Future[String] =
    notImportantBreaker
      .withCircuitBreaker(notImportantRequest.get().map(mapResponse))
      .recoverWith(notImportantErrorHandler)

  val notImportantErrorHandler: PartialFunction[Throwable, Future[String]] = {
    case e => Future("Nothing to important anyway")
  }

  def notifyMe(source: String, status: String): Unit =
    println(s"$source: CircuitBreaker is now $status")

}