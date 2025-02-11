package io.chrisdavenport.rediculous

import cats.syntax.all._
import cats.effect._
import munit.CatsEffectSuite
import munit.catseffect._
import scala.concurrent.duration._
import _root_.io.chrisdavenport.whaletail.Docker
import _root_.io.chrisdavenport.whaletail.manager._
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port

class RedisStreamSpec extends CatsEffectSuite {
  val resource = Docker.default[IO].flatMap(client => 
    WhaleTailContainer.build(client, "redis", "latest".some, Map(6379 -> None), Map.empty, Map.empty)
      .evalTap(
        ReadinessStrategy.checkReadiness(
          client,
          _, 
          ReadinessStrategy.LogRegex(".*Ready to accept connections.*\\s".r),
          30.seconds
        )
      )
  ).flatMap(container => 
    for {
      t <- Resource.eval(
        container.ports.get(6379).liftTo[IO](new Throwable("Missing Port"))
      )
      (hostS, portI) = t
      host <- Resource.eval(Host.fromString(hostS).liftTo[IO](new Throwable("Invalid Host")))
      port <- Resource.eval(Port.fromInt(portI).liftTo[IO](new Throwable("Invalid Port")))
      connection <- RedisConnection.queued[IO].withHost(host).withPort(port).build
    } yield connection 
    
  )
  // Not available on scala.js
  val redisConnection = UnsafeResourceSuiteLocalDeferredFixture(
      "redisconnection",
      resource
    )
  override def munitFixtures: Seq[IOFixture[_]] = Seq(
    redisConnection
  )
  test("send a single message"){ //connection => 
    val messages = fs2.Chunk.singleton(
      RedisStream.XAddMessage("foo", List("bar" -> "baz", "zoom" -> "zad"))
    )
    redisConnection().flatMap{connection => 
      
      val rStream = RedisStream.fromConnection(connection)
      rStream.append(messages) >>
      rStream.read(Set("foo")).take(1).compile.lastOrError

    }.map{ xrr => 
      assertEquals(xrr.stream, "foo")
      val i2 = xrr.records.flatMap(sr => sr.keyValues)
      assertEquals(i2.toSet, messages.toList.flatMap(_.body).toSet)
    }
  }

  test("consume messages from offset"){ //connection => 
    val messages = fs2.Chunk(
      RedisStream.XAddMessage("fee", List("1" -> "1")),
      RedisStream.XAddMessage("fee", List("2" -> "2")),
      RedisStream.XAddMessage("fee", List("3" -> "3")),
    )
    redisConnection().flatMap{connection => 
      
      val rStream = RedisStream.fromConnection(connection)
      rStream.append(messages) >>
      rStream
        .read(Set("fee"), (_ => RedisCommands.StreamOffset.From("fee", "0-0")), Duration.Zero, 1L.some)
        .take(3)
        .compile
        .toList

    }.map{ resps => 
      val records = resps.flatMap(_.records).flatMap(_.keyValues.map(_._1))
      assertEquals(records, List("1", "2", "3"))
    }
  }

  test("consume messages from multiple streams"){ //connection => 
    val messages = fs2.Chunk(
      RedisStream.XAddMessage("baf", List("1" -> "1")),
      RedisStream.XAddMessage("baz", List("2" -> "2")),
      RedisStream.XAddMessage("bar", List("3" -> "3")),
      RedisStream.XAddMessage("baf", List("4" -> "4")),
      RedisStream.XAddMessage("baz", List("5" -> "5")),
      RedisStream.XAddMessage("bar", List("6" -> "6")),
    )
    redisConnection().flatMap{connection => 
      
      val rStream = RedisStream.fromConnection(connection)
      rStream.append(messages) >>
      rStream
        .read(Set("baf", "baz", "bar"), stream => RedisCommands.StreamOffset.From(stream, "0"), Duration.Zero, 1L.some)
        .take(6)
        .compile
        .toList

    }.map{ resps => 
      val records = resps.flatMap(_.records).flatMap(_.keyValues.map(_._1)).toSet
      assertEquals(records, Set("1", "2", "3", "4", "5", "6"))
    }
  }
}


