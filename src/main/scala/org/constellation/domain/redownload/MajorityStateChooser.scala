package org.constellation.domain.redownload

import cats.effect.Concurrent
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.constellation.domain.redownload.MajorityStateChooser.Occurrences
import org.constellation.domain.redownload.RedownloadService.SnapshotsAtHeight
import org.constellation.schema.Id

class MajorityStateChooser {

  // TODO: Use RedownloadService type definitions
  def chooseMajorityState(
    createdSnapshots: SnapshotsAtHeight,
    peersProposals: Map[Id, SnapshotsAtHeight]
  ): SnapshotsAtHeight = {
    val peersSize = peersProposals.size + 1 // +1 - it's an own node
    val proposals = mergeByHeights(createdSnapshots, peersProposals)

    val flat = proposals
      .mapValues(mapToOccurrences)
      .mapValues { occurrences =>
        val sorted = occurrences.toList.sortBy(_.value)
        sorted
          .find(o => isInClearMajority(o.n, peersSize))
          .orElse(getTheMostQuantity(sorted, peersSize))
      }
      .mapValues(_.map(_.value))

    for ((k, Some(v)) <- flat) yield k -> v
  }

  private def isInClearMajority(occurrences: Int, totalPeers: Int): Boolean =
    if (totalPeers > 0)
      occurrences / totalPeers.toDouble >= 0.5
    else false

  private def getTheMostQuantity[A](occurrences: List[Occurrences[A]], totalPeers: Int): Option[Occurrences[A]] =
    if (occurrences.map(_.n).sum == totalPeers) {
      occurrences.sortBy(-_.percentage).headOption
    } else None

  private def mapValuesToList[K, V](a: Map[K, V]): Map[K, List[V]] =
    a.mapValues(List(_))

  private def mergeByHeights(
    createdSnapshots: SnapshotsAtHeight,
    peersProposals: Map[Id, SnapshotsAtHeight]
  ): Map[Long, List[String]] =
    (peersProposals.mapValues(mapValuesToList).values.toList ++ List(mapValuesToList(createdSnapshots)))
      .fold(Map.empty)(_ |+| _)

  private def mapToOccurrences(values: List[String]): Set[Occurrences[String]] =
    values.toSet.map((a: String) => Occurrences(a, values.count(_ == a), values.size))

}

object MajorityStateChooser {
  def apply(): MajorityStateChooser = new MajorityStateChooser()

  case class Occurrences[A](value: A, n: Int, of: Int) {
    val percentage: Double = n / of.toDouble
  }
}
