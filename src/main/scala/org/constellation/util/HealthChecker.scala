package org.constellation.util
import cats.effect.{Concurrent, IO, LiftIO, Sync}
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import io.chrisdavenport.log4cats.Logger
import org.constellation.consensus.Snapshot
import org.constellation.p2p.{DownloadProcess, PeerData}
import org.constellation.primitives.Schema.{Id, NodeState, NodeType}
import org.constellation.storage._
import org.constellation.util.HealthChecker.compareSnapshotState
import org.constellation.{ConstellationContextShift, DAO}

class MetricFailure(message: String) extends Exception(message)
case class HeightEmpty(nodeId: String) extends MetricFailure(s"Empty height found for node: $nodeId")
case class CheckPointValidationFailures(nodeId: String)
    extends MetricFailure(
      s"Checkpoint validation failures found for node: $nodeId"
    )
case class InconsistentSnapshotHash(nodeId: String, hashes: Set[String])
    extends MetricFailure(s"Node: $nodeId last snapshot hash differs: $hashes")
case class SnapshotDiff(
  snapshotsToDelete: List[RecentSnapshot],
  snapshotsToDownload: List[RecentSnapshot],
  peers: List[Id]
)

object HealthChecker {

  private def choseMajorityState(clusterSnapshots: List[(Id, List[RecentSnapshot])]): (List[RecentSnapshot], Set[Id]) =
    clusterSnapshots
      .groupBy(_._2)
      .maxBy(_._2.size)
      .map(_.map(_._1).toSet)

  def compareSnapshotState(
    ownSnapshots: List[RecentSnapshot],
    clusterSnapshots: List[(Id, List[RecentSnapshot])]
  ): SnapshotDiff =
    choseMajorityState(clusterSnapshots) match {
      case (snapshots, peers) =>
        SnapshotDiff(ownSnapshots.diff(snapshots), snapshots.diff(ownSnapshots).reverse, peers.toList)
    }

  def checkAllMetrics(apis: Seq[APIClient]): Either[MetricFailure, Unit] = {
    var hashes: Set[String] = Set.empty
    val it = apis.iterator
    var lastCheck: Either[MetricFailure, Unit] = Right(())
    while (it.hasNext && lastCheck.isRight) {
      val a = it.next()
      val metrics = IO.fromFuture(IO { a.metricsAsync })(ConstellationContextShift.edge).unsafeRunSync() // TODO: wkoszycki revisit
      lastCheck = checkLocalMetrics(metrics, a.baseURI).orElse {
        hashes ++= Set(metrics.getOrElse(Metrics.lastSnapshotHash, "no_snap"))
        Either.cond(hashes.size == 1, (), InconsistentSnapshotHash(a.baseURI, hashes))
      }
    }
    lastCheck
  }

  def checkLocalMetrics(metrics: Map[String, String], nodeId: String): Either[MetricFailure, Unit] =
    hasEmptyHeight(metrics, nodeId)
      .orElse(hasCheckpointValidationFailures(metrics, nodeId))

  def hasEmptyHeight(metrics: Map[String, String], nodeId: String): Either[MetricFailure, Unit] =
    Either.cond(!metrics.contains(Metrics.heightEmpty), (), HeightEmpty(nodeId))

  def hasCheckpointValidationFailures(metrics: Map[String, String], nodeId: String): Either[MetricFailure, Unit] =
    Either.cond(!metrics.contains(Metrics.checkpointValidationFailure), (), CheckPointValidationFailures(nodeId))

}

class HealthChecker[F[_]: Concurrent: Logger](
  dao: DAO,
  downloader: DownloadProcess
) extends StrictLogging {

  def checkClusterConsistency(ownSnapshots: List[RecentSnapshot]): F[Option[List[RecentSnapshot]]] = {
    val check = for {
      _ <- Logger[F].info(s"[${dao.id.short}] re-download checking cluster consistency")
      peers <- LiftIO[F].liftIO(dao.readyPeers(NodeType.Full))
      majoritySnapshots <- LiftIO[F].liftIO(collectSnapshot(peers))
      diff = compareSnapshotState(ownSnapshots, majoritySnapshots)
      _ <- Logger[F].debug(s"[${dao.id.short}] re-download cluster diff $diff and own $ownSnapshots")
      result <- if (shouldReDownload(ownSnapshots, diff)) {
        startReDownload(diff, peers.filterKeys(diff.peers.contains))
          .flatMap(
            _ =>
              Sync[F].delay[Option[List[RecentSnapshot]]](Some(HealthChecker.choseMajorityState(majoritySnapshots)._1))
          )
      } else {
        Sync[F].pure[Option[List[RecentSnapshot]]](None)
      }
    } yield result

    check.recoverWith {
      case err =>
        Logger[F]
          .error(err)(s"Unexpected error during re-download process: ${err.getMessage}")
          .flatMap(_ => Sync[F].pure[Option[List[RecentSnapshot]]](None))
    }
  }

  def shouldReDownload(ownSnapshots: List[RecentSnapshot], diff: SnapshotDiff): Boolean =
    diff match {
      case SnapshotDiff(_, _, Nil) => false
      case SnapshotDiff(_, Nil, _) => false
      case SnapshotDiff(snapshotsToDelete, snapshotsToDownload, _) =>
        isBelowInterval(ownSnapshots, snapshotsToDownload) || isMisaligned(
          ownSnapshots,
          (snapshotsToDelete ++ snapshotsToDownload).map(r => (r.height, r.hash)).toMap
        )
    }

  private def isMisaligned(ownSnapshots: List[RecentSnapshot], recent: Map[Long, String]) =
    ownSnapshots.exists(r => recent.get(r.height).exists(_ != r.hash))

  private def isBelowInterval(ownSnapshots: List[RecentSnapshot], snapshotsToDownload: List[RecentSnapshot]) =
    (maxOrZero(ownSnapshots) + dao.processingConfig.snapshotHeightRedownloadDelayInterval) < maxOrZero(
      snapshotsToDownload
    )

  private def maxOrZero(list: List[RecentSnapshot]): Long =
    list match {
      case Nil      => 0
      case nonEmpty => nonEmpty.map(_.height).max
    }

  def startReDownload(diff: SnapshotDiff, peers: Map[Id, PeerData]): F[Unit] = {
    val reDownload = for {
      _ <- Logger[F].info(s"[${dao.id.short}] starting re-download process with diff: $diff")
      _ <- LiftIO[F].liftIO(downloader.setNodeState(NodeState.DownloadInProgress))
      _ <- LiftIO[F].liftIO(
        downloader.reDownload(diff.snapshotsToDownload.map(_.hash).filterNot(_ == Snapshot.snapshotZeroHash),
                              peers.filterKeys(diff.peers.contains))
      )
      _ <- Sync[F].delay {
        Snapshot.removeSnapshots(diff.snapshotsToDelete.map(_.hash), dao.snapshotPath.pathAsString)(dao)
      }
      _ <- Logger[F].info(s"[${dao.id.short}] re-download process finished")
      _ <- dao.metrics.incrementMetricAsync(Metrics.reDownloadFinished)
    } yield ()

    reDownload.recoverWith {
      case err =>
        for {
          _ <- LiftIO[F].liftIO(downloader.setNodeState(NodeState.Ready))
          _ <- Logger[F].error(err)(s"[${dao.id.short}] re-download process error: ${err.getMessage}")
          _ <- dao.metrics.incrementMetricAsync(Metrics.reDownloadError)
          _ <- Sync[F].raiseError[Unit](err)
        } yield ()
    }
  }

  private def collectSnapshot(peers: Map[Id, PeerData]) =
    peers.toList.traverse(p => (p._1, p._2.client.getNonBlockingIO[List[RecentSnapshot]]("snapshot/recent")).sequence)

}
