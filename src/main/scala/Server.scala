import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}

import cats.effect.{Effect, IO}
import fs2.{Chunk, Pipe, Stream}
import fs2.Stream.eval
import fs2.io.tcp.{Socket, server}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect.ClassTag

object Example {
  def main(args: Array[String]): Unit = {
    implicit val group = AsynchronousChannelGroup.withThreadPool(Executors.newSingleThreadExecutor())
    implicit val ec = ExecutionContext.global
    val s = new Server[IO](8765, 100, 1000, FiniteDuration(200, TimeUnit.SECONDS))
    s.startServer {
      case Request(body) => Some(body)
      case Error(t) => {
        println(t)
        None
      }
    }.unsafeRunSync()
  }
}

sealed trait Action
case class Request(body: Array[Byte]) extends Action
case class Error(t: Throwable) extends Action

class Server[F[_]](addr: Int, maxConcurrent: Int, readChunkSize: Int, readTimeouts: FiniteDuration)(
  implicit AG: AsynchronousChannelGroup,
  F: Effect[F],
  ec: ExecutionContext
) {
  type Handler = Action => Option[Array[Byte]]
  var endingsOfTheLine = Array(Array[Byte](13, 10))

  def startServer(handler: Handler): F[Unit] = {
    val s = server(new InetSocketAddress(addr))
    s.map { _.flatMap { socket =>
      eval(fs2.async.signalOf(true)).flatMap { initial =>
        readWithTimeout[F](socket, readTimeouts, initial.get, readChunkSize)
          .through(parseBody)
          .attempt
          .evalMap {
            case Left(t) => {
              respond(socket, handler(Error(t)))
            }
            case Right(body) if endingsOfTheLine.find(body.endsWith(_)).isDefined => {
              respond(socket, handler(Request(body)))
            }
            case Right(_) => F.pure(())
          }.drain
      }
    }}.join(maxConcurrent).compile.drain
  }

  def respond(socket: Socket[F], resp: Option[Array[Byte]]): F[Unit] = {
    resp match {
      case Some(d) => socket.write(Chunk.bytes(d))
      case None => F.pure()
    }
  }

  def parseBody[F[_],O: ClassTag]: Pipe[F,O,Array[O]] =
    in => {
      in.scan(Array[O]()) { (acc, b) => {
        if (endingsOfTheLine.find(acc.endsWith(_)).isDefined) {
          Array[O](b)
        } else {
          acc :+ b
        }
      }}
    }

  def readWithTimeout[F[_]](socket: Socket[F],
                            timeout: FiniteDuration,
                            shallTimeout: F[Boolean],
                            chunkSize: Int
                           )(implicit F: Effect[F]) : Stream[F, Byte] = {
    def go(remains:FiniteDuration) : Stream[F, Byte] = {
      eval(shallTimeout).flatMap { shallTimeout =>
        if (!shallTimeout) {
          socket.reads(chunkSize, None)
        } else {
          if (remains <= 0.millis) {
            Stream.raiseError(new TimeoutException())
          } else {
            eval(F.delay(System.currentTimeMillis())).flatMap { start =>
              eval(socket.read(chunkSize, Some(remains))).flatMap { read =>
                eval(F.delay(System.currentTimeMillis())).flatMap { end => read match {
                  case Some(bytes) => {
                    Stream.chunk(bytes) ++ go(remains - (end - start).millis)
                  }
                  case None => Stream.empty
                }}}}
          }
        }
      }
    }

    go(timeout)
  }
}
