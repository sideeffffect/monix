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
package internal

import monix.eval.Task.Options
import monix.execution.Scheduler
import monix.execution.atomic.{AtomicAny, PaddingStrategy}
import scala.annotation.tailrec

private[eval] trait TaskConnection extends CancelableF[Task] {
  /**
    * Returns `true` if this connection was cancelled, 
    * or `false` if it's still active.
    */
  def isCanceled: Boolean
  
  /** Pushes a cancelable reference on the stack, to be
    * popped or cancelled later in FIFO order.
    */
  def push(value: CancelableF[Task]): Unit

  /** Removes a cancelable reference from the stack in FIFO order.
    *
    * @return the cancelable reference that was removed.
    */
  def pop(): CancelableF[Task]

  /**
    * Tries to reset a `TaskConnection`, from a cancelled state,
    * back to a pristine state, but only if possible.
    *
    * Returns `true` on success, or `false` if there was a race
    * condition (i.e. the connection wasn't cancelled) or if
    * the type of the connection cannot be reactivated.
    */
  def tryReactivate(): Boolean
}

private[eval] object TaskConnection {
  /** Builds an empty [[TaskConnection]] reference. */
  def apply(s: Scheduler, opts: Options): TaskConnection =
    new Impl(Nil)(s, opts)

  /** Reusable [[TaskConnection]] reference that is already
    * cancelled.
    */
  def alreadyCanceled(implicit s: Scheduler, opts: Options): TaskConnection =
    new AlreadyCanceled

  /** Reusable [[TaskConnection]] reference that cannot be
    * cancelled.
    */
  val uncancelable: TaskConnection =
    new Uncancelable

  /** Implementation for [[TaskConnection]] backed by a
    * cache-line padded atomic reference for synchronization.
    */
  private final class Impl(initial: List[CancelableF[Task]])
    (implicit sc: Scheduler, opts: Options)
    extends TaskConnection {

    /**
      * Biasing the implementation for single threaded usage
      * in push/pop — this value is caching the last value seen,
      * in order to safe a `state.get` instruction before the
      * `compareAndSet` happens.
      */
    private[this] var cache = initial

    private[this] val state =
      AtomicAny.withPadding(initial, PaddingStrategy.LeftRight128)

    override def isCanceled: Boolean =
      state.get == null
    
    private[this] def cancelAll(list: List[CancelableF[Task]]): Task[Unit] = {
      val aggregate = list.foldLeft(Task.unit) { (acc, e) =>
        acc.flatMap(_ => e.cancel.redeem(
          e => sc.reportFailure(e),
          _ => ()))
      }
      aggregate.uncancelable
    }

    override val cancel = Task.suspend[Unit] {
      // Using getAndSet, which on Java 8 should be faster than
      // a compare-and-set.
      state.getAndSet(null) match {
        case null => Task.unit
        case list => cancelAll(list)
      }
    }

    @tailrec
    private def pushLoop(current: List[CancelableF[Task]], value: CancelableF[Task]): Unit = {
      if (current eq null) {
        cache = null
        value.cancel.runAsyncOpt(Callback.empty)
      } else {
        val update = value :: current
        if (!state.compareAndSet(current, update))
          pushLoop(state.get, value) // retry
        else
          cache = update
      }
    }

    def push(value: CancelableF[Task]): Unit =
      pushLoop(cache, value)

    @tailrec
    private def popLoop(current: List[CancelableF[Task]], isFresh: Boolean = false): CancelableF[Task] = {
      current match {
        case null | Nil =>
          if (isFresh) emptyToken
          else popLoop(state.get, isFresh = true)
        case ref @ (head :: tail) =>
          if (state.compareAndSet(ref, tail)) {
            cache = tail
            head
          } else {
            popLoop(state.get, isFresh = true) // retry
          }
      }
    }

    def pop(): CancelableF[Task] =
      popLoop(cache)

    def tryReactivate(): Boolean =
      state.compareAndSet(null, Nil)
  }

  /** [[TaskConnection]] implementation that is already cancelled. */
  private final class AlreadyCanceled(implicit sc: Scheduler, opts: Options) 
    extends TaskConnection {
    
    override def cancel: Task[Unit] = Task.unit
    override def isCanceled: Boolean = true
    override def tryReactivate(): Boolean = false
    override def pop(): CancelableF[Task] = emptyToken
    override def push(value: CancelableF[Task]): Unit =
      value.cancel.runAsyncOpt(Callback.empty)
  }

  /** [[TaskConnection]] implementation that cannot be cancelled. */
  private final class Uncancelable extends TaskConnection {
    override def cancel: Task[Unit] = Task.unit
    override def isCanceled: Boolean = false
    override def tryReactivate(): Boolean = true
    override def pop(): CancelableF[Task] = emptyToken
    override def push(value: CancelableF[Task]): Unit = ()
  }
  
  private[this] val emptyToken = CancelableF(Task.unit)
}
