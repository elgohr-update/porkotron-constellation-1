package org.constellation.infrastructure.endpoints.middlewares

import cats.data.{Kleisli, OptionT}
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, Resource, Sync}
import cats.implicits._
import fs2.{Chunk, Stream}
import org.constellation.keytool.KeyUtils
import org.constellation.primitives.IPManager.IP
import org.constellation.schema.Id
import org.http4s.Uri.{Authority, RegName, Scheme}
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.client.Client
import org.http4s.client.middleware.Logger
import org.http4s.headers.Host
import pl.abankowski.httpsigner.SignatureValid
import pl.abankowski.httpsigner.http4s.{
  Http4sRequestSigner,
  Http4sRequestVerifier,
  Http4sResponseSigner,
  Http4sResponseVerifier
}
import pl.abankowski.httpsigner.signature.generic.GenericVerifier

object PeerAuthMiddleware {

  def whitelistingMiddleware[F[_]: Sync](
    whitelisting: Map[IP, Id],
    knownPeer: IP => F[Option[Id]]
  )(
    http: HttpRoutes[F]
  ): HttpRoutes[F] =
    Kleisli { (req: Request[F]) =>
      val ip = req.remoteAddr.getOrElse("unknown")
      val knownId = knownPeer(ip)
      val whitelistedId = whitelisting.get(ip)
      val isWhitelisted = knownId.map(_.exists(whitelistedId.contains))

      OptionT
        .liftF(isWhitelisted)
        .ifM(
          http(req), {
            OptionT.pure[F](
              Response(status = Unauthorized)
                .withHeaders(
                  Header("Middleware-Result", s"Peer IP $ip is not whitelisted.")
                )
            )
          }
        )
    }

  def responseSignerMiddleware[F[_]: Sync](signer: Http4sResponseSigner[F])(http: HttpRoutes[F]): HttpRoutes[F] =
    http
  /*
    Kleisli { (req: Request[F]) =>
      http(req).flatMap { res =>
        OptionT.liftF(signer.sign(res))
      }
    }
   */

  def responseVerifierMiddleware[F[_]](peerId: Id)(
    client: Client[F]
  )(implicit F: Concurrent[F], C: ContextShift[F]): Client[F] =
    client
  /*
    Client { (req: Request[F]) =>
      val crypto = GenericVerifier(KeyUtils.DefaultSignFunc, KeyUtils.provider, peerId.toPublicKey)
      val verifier = new Http4sResponseVerifier[F](crypto)

      import fs2._

      client.run(req).flatMap { response =>
        Resource.suspend {
          Ref[F].of(Vector.empty[Chunk[Byte]]).map { vec =>
            Resource.liftF {

              val newBody = Stream
                .eval(vec.get)
                .flatMap(v => Stream.emits(v).covary[F])
                .flatMap(c => Stream.chunk(c).covary[F])

              F.pure {
                response.copy(
                  body = response.body
                    .observe(_.chunks.flatMap(s => Stream.eval_(vec.update(_ :+ s))))
                )
              }.flatMap(verifier.verify).flatMap {
                case SignatureValid => F.pure(response.withBodyStream(newBody))
                case _ =>
                  F.pure(
                    Response[F](status = Unauthorized)
                      .withHeaders(Header("Middleware-Result", "Invalid response signature"))
                  )
              }
            }
          }
        }
      }
    }
   */

  def requestSignerMiddleware[F[_]](
    client: Client[F],
    signer: Http4sRequestSigner[F]
  )(implicit F: Concurrent[F], C: ContextShift[F]): Client[F] =
    client
  /*
    Client { (req: Request[F]) =>
      import fs2._

      Resource.suspend {
        Ref[F].of(Vector.empty[Chunk[Byte]]).map { vec =>
          Resource.liftF {

            val newBody = Stream
              .eval(vec.get)
              .flatMap(v => Stream.emits(v).covary[F])
              .flatMap(c => Stream.chunk(c).covary[F])

            val newReq = req.withBodyStream(req.body.observe(_.chunks.flatMap(s => Stream.eval_(vec.update(_ :+ s)))))

            signer.sign(newReq).map { r =>
              req.withBodyStream(newBody).withHeaders(r.headers)
            }
          }
        }
      } >>= client.run
    }
   */

  def requestVerifierMiddleware[F[_]: Sync](
    knownPeer: IP => F[Option[Id]],
    usingKnownPeers: Boolean
  )(http: HttpRoutes[F])(implicit C: ContextShift[F]): HttpRoutes[F] =
    http
  /*
    Kleisli { (req: Request[F]) =>
      val ip = req.remoteAddr.getOrElse("unknown")
      val responseOnError = Response[F](status = Unauthorized)

      OptionT.liftF(knownPeer(ip)).flatMap {
        _.map(_.toPublicKey)
          .map(GenericVerifier(KeyUtils.DefaultSignFunc, KeyUtils.provider, _))
          .map { crypto =>
            val verifier = new Http4sRequestVerifier[F](crypto)

            OptionT.liftF(verifier.verify(req)).flatMap {
              case SignatureValid => http(req)
              case sig => {
                OptionT.pure[F](
                  responseOnError
                    .withHeaders(
                      Header("Middleware-Result", s"Invalid request signature, usingKnownPeers=$usingKnownPeers")
                    )
                )
              }
            }
          }
          .getOrElse(
            OptionT.pure[F](
              responseOnError
                .withHeaders(
                  Header(
                    "Middleware-Result",
                    s"ID of $ip doesn't exist (Peer is not on PeerList probably). usingKnownPeers=$usingKnownPeers"
                  )
                )
            )
          )
      }
    }
 */
}
