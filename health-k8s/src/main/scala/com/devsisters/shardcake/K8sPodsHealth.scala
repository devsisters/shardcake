package com.devsisters.shardcake

import com.coralogix.zio.k8s.client.{
  CodingFailure,
  DecodedFailure,
  DeserializationFailure,
  Gone,
  HttpFailure,
  InvalidEvent,
  K8sFailure,
  K8sRequestInfo,
  NotFound,
  RequestFailure,
  Unauthorized,
  UndefinedField
}
import com.coralogix.zio.k8s.client.model.{ FieldSelector, LabelSelector }
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status
import com.devsisters.shardcake.interfaces.PodsHealth
import zio._
import zio.cache.{ Cache, Lookup }

import java.lang

object K8sPodsHealth {

  /**
   * A layer for PodsHealth that checks if the node exists in Kubernetes.
   */
  val live: URLayer[Pods with K8sConfig, PodsHealth] =
    ZLayer {
      for {
        pods   <- ZIO.service[Pods.Service]
        config <- ZIO.service[K8sConfig]
        cache  <- Cache
                    .make(
                      config.cacheSize,
                      config.cacheDuration,
                      Lookup { (podAddress: PodAddress) =>
                        pods
                          .getAll(
                            config.namespace,
                            1,
                            Some(FieldSelector.FieldEquals(Chunk("status", "podIP"), podAddress.host)),
                            config.labelSelector
                          )
                          .runHead
                          .map(_.isDefined)
                          .tap(ZIO.unless(_)(ZIO.logWarning(s"$podAddress is not found in k8s")))
                          .catchAllCause(cause =>
                            ZIO.logErrorCause(s"Error communicating with k8s", cause.map(asException)).as(true)
                          )
                      }
                    )
      } yield new PodsHealth {
        def isAlive(podAddress: PodAddress): UIO[Boolean] = cache.get(podAddress)
      }
    }

  // K8sFailure is not an Exception which means an attempt to directly log it as a Cause will, by default, lose transitive causal information
  // because we only log k8s communication failures and then report the pod as healthy, we want to propagate as much information as possible to users
  // therefore we convert K8sFailure to an exception, keeping transitive causes in some form whenever they're available, and creating friendlier messages for the rest
  private def asException(k8sFailure: K8sFailure) =
    k8sFailure match {
      case Unauthorized(requestInfo, message) =>
        new K8sException(s"unauthorized trying to ${toLogString(requestInfo)}: $message")

      case HttpFailure(requestInfo, message, code) =>
        new K8sException(s"http failure $code trying to ${toLogString(requestInfo)}: $message")

      case DecodedFailure(requestInfo, status, code) =>
        new K8sException(s"decoded failure $code trying to ${toLogString(requestInfo)}: ${toLogString(status)}")

      case CodingFailure(requestInfo, failure) =>
        new K8sException(s"coding failure trying to ${toLogString(requestInfo)}", failure)

      case DeserializationFailure(requestInfo, error) =>
        error.tail.foldLeft(
          new K8sException(s"deserialization failure trying to ${toLogString(requestInfo)}", error.head)
        ) { (result, e) =>
          result.addSuppressed(e)
          result
        }

      case RequestFailure(requestInfo, reason) =>
        new K8sException(s"request failure trying to ${toLogString(requestInfo)}", reason)

      case InvalidEvent(requestInfo, eventType) =>
        new K8sException(s"invalid event $eventType trying to ${toLogString(requestInfo)}")

      case UndefinedField(field) =>
        new K8sException(s"undefined field $field")

      case Gone =>
        new K8sException("gone")

      case NotFound =>
        new K8sException("not found")
    }

  private def toLogString(requestInfo: K8sRequestInfo) = {
    val sb = new lang.StringBuilder()

    sb.append(requestInfo.operation)

    sb.append(' ')

    val typ = requestInfo.resourceType

    sb.append(typ.group)
    sb.append('/')
    sb.append(typ.version)

    sb.append(' ')

    requestInfo.namespace.foreach { ns =>
      sb.append(ns)
      sb.append('/')
    }
    sb.append(typ.resourceType)
    requestInfo.name.foreach { n =>
      sb.append('/')
      sb.append(n)
    }

    requestInfo.labelSelector.foreach { ls =>
      sb.append(' ')
      sb.append(ls.asQuery)
    }

    requestInfo.fieldSelector.foreach { fs =>
      sb.append(' ')
      sb.append(fs.asQuery)
    }

    sb.toString
  }

  private def toLogString(status: Status) =
    status.message.getOrElse(status.reason.getOrElse("no explanation from k8s"))

  private class K8sException(message: String, cause: Throwable = null)
      extends RuntimeException(message, cause, true, false)
}
