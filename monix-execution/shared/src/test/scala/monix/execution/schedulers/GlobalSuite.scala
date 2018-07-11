package monix.execution
package schedulers

import minitest.SimpleTestSuite

object GlobalSuite extends SimpleTestSuite {
  test("global Scheduler is a subtype of IsGlobal") {
    val global: Scheduler = Scheduler.Implicits.global
    assert(global.isInstanceOf[AsyncScheduler])
    assert(global.isInstanceOf[IsGlobal])
  }
}
