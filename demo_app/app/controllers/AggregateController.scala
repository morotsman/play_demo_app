package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import akka.actor.ActorSystem
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException


import akka.util.Timeout
import play.api.libs.json._
import akka.pattern.ask

import play.api.libs.ws._

class AggregateController @Inject() (ws: WSClient)(implicit exec: ExecutionContext)extends Controller{
  
  val request1: WSRequest = ws.url("http://localhost:9000/mock-service/important").withRequestTimeout(2000.millis)
  val request2: WSRequest = ws.url("http://localhost:9000/mock-service/not_important").withRequestTimeout(2000.millis)
  
  
  def aggregate = Action.async {
    val r1 = request1.get().map(r => r.body)
    val r2 = request2.get().map(r => r.body).recoverWith{
      case e => Future("Nothing important anyway") 
    }
    
    val aggregatedResponse = for(
        response1 <- r1;
        response2 <- r2
    ) yield (response1, response2)
     
    aggregatedResponse.map { aggregated =>
      Ok(aggregated._1 + " ; " +  aggregated._2)
    }.recoverWith {
      case e: TimeoutException => Future(RequestTimeout)
    }
  }
  
}