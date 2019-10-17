package org.constellation.domain.consensus

import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ContextShift, IO, Sync}
import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import org.constellation.ConstellationExecutionContext
import org.constellation.primitives.Schema.CheckpointCache
import org.constellation.primitives.concurrency.SingleLock
import ConsensusStatus.ConsensusStatus
import org.constellation.storage.algebra.{Lookup, MerkleStorageAlgebra}
import org.constellation.storage.{PendingMemPool, StorageService}

abstract class ConsensusService[F[_]: Concurrent: Logger, A <: ConsensusObject]
    extends MerkleStorageAlgebra[F, String, A] {
  val merklePool = new StorageService[F, Seq[String]]()

  implicit val cs: ContextShift[IO] = IO.contextShift(ConstellationExecutionContext.bounded)

  val semaphores: Map[String, Semaphore[F]] = Map(
    "inConsensusUpdate" -> Semaphore.in[IO, F](1).unsafeRunSync(),
    "acceptedUpdate" -> Semaphore.in[IO, F](1).unsafeRunSync(),
    "unknownUpdate" -> Semaphore.in[IO, F](1).unsafeRunSync(),
    "merklePoolUpdate" -> Semaphore.in[IO, F](1).unsafeRunSync()
  )

  protected[domain] def withLock[R](semaphoreName: String, thunk: F[R]): F[R] =
    new SingleLock[F, R](semaphoreName, semaphores(semaphoreName))
      .use(thunk)

  protected[domain] val pending: PendingMemPool[F, String, A]
  protected[domain] val inConsensus = new StorageService[F, A](Some(240))
  protected[domain] val accepted = new StorageService[F, A](Some(240))
  protected[domain] val unknown = new StorageService[F, A](Some(240))

  def put(a: A): F[A] = put(a, ConsensusStatus.Pending)

  def put(a: A, as: ConsensusStatus, cpc: Option[CheckpointCache] = None): F[A] = as match {
    case ConsensusStatus.Pending =>
      pending
        .put(a.hash, a)
        .flatTap(
          _ =>
            Logger[F].debug(s"ConsensusService pendingPut with hash=${a.hash} - with checkpoint hash=${cpc
              .map(c => c.checkpointBlock.map(_.baseHash))}")
        )
    case ConsensusStatus.Accepted =>
      withLock("acceptedUpdate", accepted.put(a.hash, a))
        .flatTap(
          _ =>
            Logger[F].debug(s"ConsensusService acceptedPut with hash=${a.hash} - with checkpoint hash=${cpc
              .map(c => c.checkpointBlock.map(_.baseHash))}")
        )
    case ConsensusStatus.Unknown =>
      withLock("unknownUpdate", unknown.put(a.hash, a))
        .flatTap(
          _ =>
            Logger[F].debug(s"ConsensusService unknownPut with hash=${a.hash} - with checkpoint hash=${cpc
              .map(c => c.checkpointBlock.map(_.baseHash))}")
        )
    case _ => new Exception("Unknown consensus status").raiseError[F, A]
  }

  def update(key: String, fn: A => A, empty: => A, as: ConsensusStatus): F[A] = as match {
    case ConsensusStatus.Pending =>
      pending.update(key, fn, empty).flatTap(_ => Logger[F].debug(s"ConsensusService pendingUpdate with hash=${key}"))
    case ConsensusStatus.InConsensus =>
      withLock("inConsensusUpdate", inConsensus.update(key, fn, empty))
        .flatTap(_ => Logger[F].debug(s"ConsensusService inConsensusUpdate with hash=${key}"))
    case ConsensusStatus.Accepted =>
      withLock("acceptedUpdate", accepted.update(key, fn, empty))
        .flatTap(_ => Logger[F].debug(s"ConsensusService acceptedUpdate with hash=${key}"))
    case ConsensusStatus.Unknown =>
      withLock("unknownUpdate", unknown.update(key, fn, empty))
        .flatTap(_ => Logger[F].debug(s"ConsensusService unknownUpdate with hash=${key}"))

    case _ => new Exception("Unknown consensus status").raiseError[F, A]
  }

  def update(key: String, fn: A => A): F[Option[A]] =
    for {
      p <- pending.update(key, fn).flatTap(_ => Logger[F].debug(s"ConsensusService pendingUpdate with hash=${key}"))
      i <- p
        .fold(
          Logger[F].debug(s"ConsensusService inConsensusUpdate with hash=${key}") >> withLock(
            "inConsensusUpdate",
            inConsensus.update(key, fn)
          )
        )(curr => Sync[F].pure(Some(curr)))
      ac <- i
        .fold(
          Logger[F].debug(s"ConsensusService acceptedUpdate with hash=${key}") >> withLock(
            "acceptedUpdate",
            accepted.update(key, fn)
          )
        )(curr => Sync[F].pure(Some(curr)))
      result <- ac
        .fold(
          Logger[F].debug(s"ConsensusService unknownUpdate with hash=${key}") >> withLock(
            "unknownUpdate",
            unknown.update(key, fn)
          )
        )(curr => Sync[F].pure(Some(curr)))
    } yield result

  def accept(a: A, cpc: Option[CheckpointCache] = None): F[Unit] =
    put(a, ConsensusStatus.Accepted, cpc) >>
      withLock("inConsensusUpdate", inConsensus.remove(a.hash)) >>
      withLock("unknownUpdate", unknown.remove(a.hash))
        .flatTap(
          _ =>
            Logger[F].debug(s"ConsensusService remove with hash=${a.hash} - with checkpoint hash=${cpc
              .map(c => c.checkpointBlock.map(_.baseHash))}")
        )

  def isAccepted(hash: String): F[Boolean] = accepted.contains(hash)

  def pullForConsensus(count: Int): F[List[A]] =
    pending
      .pull(count)
      .map(_.getOrElse(List()))
      .flatMap(
        _.traverse(
          a =>
            withLock("inConsensusUpdate", inConsensus.put(a.hash, a))
              .flatTap(_ => Logger[F].debug(s"ConsensusService pulling for consensus with hash=${a.hash}"))
        )
      )

  def lookup(key: String): F[Option[A]] =
    Lookup.extendedLookup[F, String, A](List(accepted, inConsensus, pending, unknown))(
      key
    )

  def lookup(hash: String, status: ConsensusStatus): F[Option[A]] =
    status match {
      case ConsensusStatus.Pending     => pending.lookup(hash)
      case ConsensusStatus.InConsensus => inConsensus.lookup(hash)
      case ConsensusStatus.Accepted    => accepted.lookup(hash)
      case ConsensusStatus.Unknown     => unknown.lookup(hash)
      case _                           => new Exception("Unknown consensus status").raiseError[F, Option[A]]
    }

  def contains(key: String): F[Boolean] =
    Lookup.extendedContains[F, String, A](List(accepted, inConsensus, pending, unknown))(
      key
    )

  def clearInConsensus(as: Seq[String]): F[List[A]] =
    as.toList
      .traverse(inConsensus.lookup)
      .map(_.flatten)
      .flatMap { txs =>
        txs.traverse(tx => withLock("inConsensusUpdate", inConsensus.remove(tx.hash))) >>
          txs.traverse(tx => put(tx, ConsensusStatus.Unknown))
      }
      .flatTap(txs => Logger[F].debug(s"ConsensusService clear and add to unknown  with hashes=${txs.map(_.hash)}"))

  def returnToPending(as: Seq[String]): F[List[A]] =
    as.toList
      .traverse(inConsensus.lookup)
      .map(_.flatten)
      .flatMap { txs =>
        txs.traverse(tx => withLock("inConsensusUpdate", inConsensus.remove(tx.hash))) >>
          txs.traverse(tx => put(tx))
      }
      .flatTap(txs => Logger[F].debug(s"ConsensusService returningToPending with hashes=${txs.map(_.hash)}"))

  def getLast20Accepted: F[List[A]] =
    accepted.getLast20()

  def findHashesByMerkleRoot(merkleRoot: String): F[Option[Seq[String]]] =
    merklePool.lookup(merkleRoot)

  def count: F[Long] =
    List(
      count(ConsensusStatus.Pending),
      count(ConsensusStatus.InConsensus),
      count(ConsensusStatus.Accepted),
      count(ConsensusStatus.Unknown)
    ).sequence.map(_.combineAll)

  def count(status: ConsensusStatus): F[Long] = status match {
    case ConsensusStatus.Pending     => pending.size()
    case ConsensusStatus.InConsensus => inConsensus.size()
    case ConsensusStatus.Accepted    => accepted.size()
    case ConsensusStatus.Unknown     => unknown.size()
  }

  def getMetricsMap: F[Map[String, Long]] =
    List(
      count(ConsensusStatus.Pending),
      count(ConsensusStatus.InConsensus),
      count(ConsensusStatus.Accepted),
      count(ConsensusStatus.Unknown)
    ).sequence
      .map(
        counts => {
          Map(
            "pending" -> counts.get(0).getOrElse(0),
            "inConsensus" -> counts.get(2).getOrElse(0),
            "accepted" -> counts.get(3).getOrElse(0),
            "unknown" -> counts.get(4).getOrElse(0)
          )
        }
      )
}
