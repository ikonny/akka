/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.unmarshalling

import scala.util.control.{ NoStackTrace, NonFatal }
import scala.concurrent.{ Future, ExecutionContext }
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.model._

trait Unmarshaller[-A, B] {

  def apply(value: A)(implicit ec: ExecutionContext): Future[B]

  def transform[C](f: ExecutionContext ⇒ Future[B] ⇒ Future[C]): Unmarshaller[A, C] =
    Unmarshaller { implicit ec ⇒ a ⇒ f(ec)(this(a)) }

  def map[C](f: B ⇒ C): Unmarshaller[A, C] =
    transform(implicit ec ⇒ _.fast map f)

  def flatMap[C](f: ExecutionContext ⇒ B ⇒ Future[C]): Unmarshaller[A, C] =
    transform(implicit ec ⇒ _.fast flatMap f(ec))

  def recover[C >: B](pf: ExecutionContext ⇒ PartialFunction[Throwable, C]): Unmarshaller[A, C] =
    transform(implicit ec ⇒ _.fast recover pf(ec))

  def withDefaultValue[BB >: B](defaultValue: BB): Unmarshaller[A, BB] =
    recover(_ ⇒ { case Unmarshaller.NoContentException ⇒ defaultValue })
}

object Unmarshaller
  extends GenericUnmarshallers
  with PredefinedFromEntityUnmarshallers
  with PredefinedFromStringUnmarshallers {

  /**
   * Creates an `Unmarshaller` from the given function.
   */
  def apply[A, B](f: ExecutionContext ⇒ A ⇒ Future[B]): Unmarshaller[A, B] =
    new Unmarshaller[A, B] {
      def apply(a: A)(implicit ec: ExecutionContext) =
        try f(ec)(a)
        catch { case NonFatal(e) ⇒ FastFuture.failed(e) }
    }

  /**
   * Helper for creating a synchronous `Unmarshaller` from the given function.
   */
  def strict[A, B](f: A ⇒ B): Unmarshaller[A, B] = Unmarshaller(_ ⇒ a ⇒ FastFuture.successful(f(a)))

  /**
   * Helper for creating a "super-unmarshaller" from a sequence of "sub-unmarshallers", which are tried
   * in the given order. The first successful unmarshalling of a "sub-unmarshallers" is the one produced by the
   * "super-unmarshaller".
   */
  def firstOf[A, B](unmarshallers: Unmarshaller[A, B]*): Unmarshaller[A, B] =
    Unmarshaller { implicit ec ⇒
      a ⇒
        def rec(ix: Int, supported: Set[ContentTypeRange]): Future[B] =
          if (ix < unmarshallers.size) {
            unmarshallers(ix)(a).fast.recoverWith {
              case Unmarshaller.UnsupportedContentTypeException(supp) ⇒ rec(ix + 1, supported ++ supp)
            }
          } else FastFuture.failed(Unmarshaller.UnsupportedContentTypeException(supported))
        rec(0, Set.empty)
    }

  implicit def identityUnmarshaller[T]: Unmarshaller[T, T] = Unmarshaller(_ ⇒ FastFuture.successful)

  // we don't define these methods directly on `Unmarshaller` due to variance constraints
  implicit class EnhancedUnmarshaller[A, B](val um: Unmarshaller[A, B]) extends AnyVal {
    def mapWithInput[C](f: (A, B) ⇒ C): Unmarshaller[A, C] =
      Unmarshaller(implicit ec ⇒ a ⇒ um(a).fast.map(f(a, _)))

    def flatMapWithInput[C](f: (A, B) ⇒ Future[C]): Unmarshaller[A, C] =
      Unmarshaller(implicit ec ⇒ a ⇒ um(a).fast.flatMap(f(a, _)))
  }

  implicit class EnhancedFromEntityUnmarshaller[A](val underlying: FromEntityUnmarshaller[A]) extends AnyVal {
    def mapWithCharset[B](f: (A, HttpCharset) ⇒ B): FromEntityUnmarshaller[B] =
      underlying.mapWithInput { (entity, data) ⇒ f(data, entity.contentType.charset) }

    /**
     * Modifies the underlying [[Unmarshaller]] to only accept content-types matching one of the given ranges.
     * If the underlying [[Unmarshaller]] already contains a content-type filter (also wrapped at some level),
     * this filter is *replaced* by this method, not stacked!
     */
    def forContentTypes(ranges: ContentTypeRange*): FromEntityUnmarshaller[A] =
      Unmarshaller { implicit ec ⇒
        entity ⇒
          if (entity.contentType == ContentTypes.NoContentType || ranges.exists(_ matches entity.contentType)) {
            underlying(entity).fast recoverWith retryWithPatchedContentType(underlying, entity)
          } else FastFuture.failed(UnsupportedContentTypeException(ranges: _*))
      }
  }

  // must be moved out of the the [[EnhancedFromEntityUnmarshaller]] value class due to bug in scala 2.10:
  // https://issues.scala-lang.org/browse/SI-8018
  private def retryWithPatchedContentType[T](underlying: FromEntityUnmarshaller[T], entity: HttpEntity)(
    implicit ec: ExecutionContext): PartialFunction[Throwable, Future[T]] = {
    case UnsupportedContentTypeException(supported) ⇒ underlying(entity withContentType supported.head.specimen)
  }

  /**
   * Signals that unmarshalling failed because the entity was unexpectedly empty.
   */
  case object NoContentException extends RuntimeException("Message entity must not be empty") with NoStackTrace

  /**
   * Signals that unmarshalling failed because the entity content-type did not match one of the supported ranges.
   * This error cannot be thrown by custom code, you need to use the `forContentTypes` modifier on a base
   * [[akka.http.scaladsl.unmarshalling.Unmarshaller]] instead.
   */
  final case class UnsupportedContentTypeException(supported: Set[ContentTypeRange])
    extends RuntimeException(supported.mkString("Unsupported Content-Type, supported: ", ", ", ""))

  object UnsupportedContentTypeException {
    def apply(supported: ContentTypeRange*): UnsupportedContentTypeException = UnsupportedContentTypeException(Set(supported: _*))
  }
}