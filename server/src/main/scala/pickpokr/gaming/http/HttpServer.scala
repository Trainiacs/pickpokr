package pickpokr.gaming.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Directives, Route}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, Sink}
import akka.stream.typed.scaladsl.{ActorMaterializer, ActorSink, ActorSource}
import akka.stream.OverflowStrategy
import akka.NotUsed
import pickpokr.gaming.{Client, Lobby, Nick}
import pickpokr.gaming.Client.{Completed, Failed}
import ujson.Js

object HttpServer extends App with JsonSupport with Directives {
  implicit val lobby: ActorSystem[Lobby.Command] = ActorSystem(Lobby.behavior(), "lobby")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = lobby.executionContext

  lazy val routes: Route = {
    pathPrefix(IntNumber) { trainId =>
      path(Segment) { nick =>
        handleWebSocketMessages(webSocketFlow(trainId, Nick(nick), lobby))
      }
    }
  }

  def lobbySink(trainId: Int, nick: Nick) =
    ActorSink.actorRef[Lobby.Command](lobby, Lobby.Cancel(trainId, nick), _ => Lobby.Cancel(trainId, nick))

  def webSocketFlow(trainId: Int, nick: Nick, system: ActorSystem[Lobby.Command]): Flow[Message, Message, NotUsed] = {
    Flow[Message].
      collect {
        case TextMessage.Strict(msg) => msg
      }.
      via {
        val in = Flow[String].
          map { data =>
            val js = ujson.read(data)
            val kind = js.obj("type").formatted("%s")
            (kind, js)
          }.
          collect {
            case ("guess", js) =>
              Lobby.Guess(trainId, nick, js.str)
            case ("requestExchange", js) ⇒
              Lobby.RequestExchange(trainId, nick)
          }.
          to(lobbySink(trainId, nick))

        val out = ActorSource.actorRef[Message](
          { case msg => println(msg.toString) }, // Todo complete
          { case msg => new RuntimeException(s"bad:$msg") },
          1,
          OverflowStrategy.backpressure).
          mapMaterializedValue { clientRef =>
            system ! Lobby.ClientConnected(trainId, nick, clientRef)
            NotUsed
          }

        Flow.fromSinkAndSource(in, out)
      }
  }

  val serverBinding: Future[Http.ServerBinding] =
    Http().bindAndHandle(routes, "localhost", 8080)

  serverBinding.onComplete {
    case Success(bound) =>
      println(s"pickpokr http server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
    case Failure(e) =>
      Console.err.println(s"Server could not start!")
      e.printStackTrace()
      lobby.terminate()
  }

  Await.result(lobby.whenTerminated, Duration.Inf)
}