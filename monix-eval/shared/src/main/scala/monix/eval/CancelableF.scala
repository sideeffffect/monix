/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.eval

import cats.effect.{Effect, IO}
import monix.execution.Cancelable
import monix.execution.UncaughtExceptionReporter.{default => Logger}

/**
  * Part of the [[Fiber]] interface, a cancelation token represents
  * a `F[_]` value (e.g. a [[Task]]) that can be used for cancelation
  * purposes of a process.
  *
  * Similar with [[monix.execution.Cancelable]], however `CancelToken`
  * is a pure value.
  */
trait CancelableF[F[_]] extends Serializable {
  def cancel: F[Unit]
}

object CancelableF {
  def apply[F[_]](task: F[Unit]): CancelableF[F] =
    new Wrapper(task)

  def raw[F[_]]()

  implicit final class Extensions[F[_]](val self: CancelableF[F])
    extends AnyVal {

    /**
      * Converts the source [[CancelableF]] to a [[monix.execution.Cancelable]].
      */
    def toCancelable(implicit eff: Effect[F]): Cancelable =
      Cancelable(() => {
        eff.runAsync(self.cancel) {
          case Right(_) => IO.unit
          case Left(e) => IO(Logger.reportFailure(e))
        }.unsafeRunSync()
      })
  }

  private final class Wrapper[F[_]](val cancel: F[Unit])
    extends CancelableF[F]
}