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

class AggregateController @Inject() (ws: WSClient,actorSystem: ActorSystem)(implicit exec: ExecutionContext)extends Controller{
  
  val importantRequest: WSRequest = ws.url("http://localhost:9000/mock-service/important").withRequestTimeout(2000.millis)
  val notImportantRequest: WSRequest = ws.url("http://localhost:9000/mock-service/not_important").withRequestTimeout(2000.millis)
  
  val importantBreaker =
    new CircuitBreaker(actorSystem.scheduler, maxFailures = 5, callTimeout = 2.seconds, resetTimeout = 1.minute).onOpen(notifyMeOnOpen("important"))
      
  val notImportantBreaker =
    new CircuitBreaker(actorSystem.scheduler, maxFailures = 5, callTimeout = 2.seconds, resetTimeout = 1.minute).onOpen(notifyMeOnOpen("not_important"))      
 
  def notifyMeOnOpen(source: String): Unit =
    println(s"$source: CircuitBreaker is now open, and will not close for one minute")
  
  
  def aggregate = Action.async {
    val important = importantBreaker.withCircuitBreaker(importantRequest.get().map(r => r.body))
    val notImportant = notImportantBreaker.withCircuitBreaker(notImportantRequest.get().map(r => r.body)).recoverWith{
      case e => Future("Nothing important anyway") 
    }
    
    val aggregatedResponse = for(
        response1 <- important;
        response2 <- notImportant
    ) yield (response1, response2)
     
    aggregatedResponse.map { aggregated =>
      Ok(aggregated._1 + " ; " +  aggregated._2)
    }.recoverWith {
      case e: TimeoutException => Future(RequestTimeout)
      case e: CircuitBreakerOpenException => Future(RequestTimeout)
    }
  }
  
}