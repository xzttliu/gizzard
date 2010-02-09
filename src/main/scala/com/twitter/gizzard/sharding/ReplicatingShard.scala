package com.twitter.gizzard.sharding

import java.sql.SQLException
import java.util.Random
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.util.Sorting
import net.lag.logging.{Logger, ThrottledLogger}
import com.twitter.gizzard.Conversions._
import com.twitter.ostrich.W3CStats


abstract class ReplicatingShard[ConcreteShard <: Shard]
  (val shardInfo: ShardInfo, val weight: Int, val replicas: Seq[ConcreteShard],
   log: ThrottledLogger[String], future: Future, eventLogger: Option[W3CStats])
  extends ReadWriteShard[ConcreteShard] {

  private val random = new Random

  def readOperation[A](method: (ConcreteShard => A)) = failover(method(_))
  def writeOperation[A](method: (ConcreteShard => A)) = fanoutWrite(method)

  private def fanoutWrite[A](method: (ConcreteShard => A)): A = {
    val exceptions = new mutable.ArrayBuffer[Exception]()
    val results = new mutable.ArrayBuffer[A]()

    replicas.map { replica => future(method(replica)) }.map { future =>
      try {
        results += future.get(6000, TimeUnit.MILLISECONDS)
      } catch {
        case e: Exception =>
          exceptions += e
      }
    }
    exceptions.map { throw _ }
    results.first
  }

  case class ShardWithWeight(shard: ConcreteShard, weight: Float)

  private def computeWeights(replicas: Seq[ConcreteShard]) = {
    val totalWeight = replicas.foldLeft(0) { _ + _.weight }
    replicas.map { shard => ShardWithWeight(shard, shard.weight.toFloat / totalWeight.toFloat) }
  }

  private def getNext(randomNumber: Float, skipped: List[ConcreteShard], replicas: Seq[ShardWithWeight]): (ConcreteShard, List[ConcreteShard]) = {
    val candidate = replicas.first
    if (replicas.size == 1 || randomNumber < candidate.weight) {
      (candidate.shard, skipped ++ replicas.drop(1).map { _.shard }.toList)
    } else {
      getNext(randomNumber - candidate.weight, skipped ++ List(candidate.shard), replicas.drop(1))
    }
  }

  def getNext(randomNumber: Float, replicas: Seq[ConcreteShard]): (ConcreteShard, List[ConcreteShard]) = getNext(randomNumber, Nil, computeWeights(replicas))

  private def failover[A](f: ConcreteShard => A): A = failover(f, replicas)

  private def failover[A](f: ConcreteShard => A, replicas: Seq[ConcreteShard]): A = {
    val (shard, remainder) = getNext(random.nextFloat, replicas)
    try {
      f(shard)
    } catch {
      case e: SQLException =>
        val shardInfo = shard.shardInfo
        val shardId = shardInfo.hostname + "/" + shardInfo.tablePrefix
        e match {
          case _: ShardRejectedOperationException =>
          case _ =>
            log.error(shardId, e, "Error on %s: %s", shardId, e)
            eventLogger.map { _.log("db_error-" + shardId, e.getClass.getName) }
        }
        failover(f, remainder)
      case e: NoSuchElementException =>
        throw new SQLException("All shard replicas are down")
    }
  }
}