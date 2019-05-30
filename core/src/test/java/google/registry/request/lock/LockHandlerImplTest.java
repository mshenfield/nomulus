// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.request.lock;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import google.registry.model.server.Lock;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.util.RequestStatusCheckerImpl;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LockHandler}. */
@RunWith(JUnit4.class)
public final class LockHandlerImplTest {

  private static final Duration ONE_DAY = Duration.standardDays(1);

  private final FakeClock clock = new FakeClock(DateTime.parse("2001-08-29T12:20:00Z"));

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().build();

  private static class CountingCallable implements Callable<Void> {
    int numCalled = 0;

    @Override
    public Void call() {
      numCalled += 1;
      return null;
    }
  }

  private static class ThrowingCallable implements Callable<Void> {
    Exception exception;
    FakeClock clock;

    ThrowingCallable(Exception exception, FakeClock clock) {
      this.exception = exception;
      this.clock = clock;
    }

    @Override
    public Void call() throws Exception {
      clock.advanceBy(Duration.standardSeconds(77));
      throw exception;
    }
  }

  private boolean executeWithLocks(Callable<Void> callable, final @Nullable Lock acquiredLock) {
    LockHandlerImpl lockHandler = new LockHandlerImpl(new RequestStatusCheckerImpl(), clock) {
      private static final long serialVersionUID = 0L;
      @Override
      Optional<Lock> acquire(String resourceName, String tld, Duration leaseLength) {
        assertThat(resourceName).isEqualTo("resourceName");
        assertThat(tld).isEqualTo("tld");
        assertThat(leaseLength).isEqualTo(ONE_DAY);
        return Optional.ofNullable(acquiredLock);
      }
    };

    return lockHandler.executeWithLocks(callable, "tld", ONE_DAY, "resourceName");
  }

  @Before public void setUp() {
  }

  @Test
  public void testLockSucceeds() {
    Lock lock = mock(Lock.class);
    CountingCallable countingCallable = new CountingCallable();
    assertThat(executeWithLocks(countingCallable, lock)).isTrue();
    assertThat(countingCallable.numCalled).isEqualTo(1);
    verify(lock, times(1)).release();
  }

  @Test
  public void testLockSucceeds_uncheckedException() {
    Lock lock = mock(Lock.class);
    Exception expectedException = new RuntimeException("test");
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> executeWithLocks(new ThrowingCallable(expectedException, clock), lock));
    assertThat(exception).isSameInstanceAs(expectedException);
    verify(lock, times(1)).release();
  }

  @Test
  public void testLockSucceeds_timeoutException() {
    Lock lock = mock(Lock.class);
    Exception expectedException = new TimeoutException("test");
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> executeWithLocks(new ThrowingCallable(expectedException, clock), lock));
    assertThat(thrown).hasCauseThat().isSameInstanceAs(expectedException);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Execution on locks 'resourceName' for TLD 'tld'"
                + " timed out after PT77S; started at 2001-08-29T12:20:00.000Z");
    verify(lock, times(1)).release();
  }

  @Test
  public void testLockSucceeds_checkedException() {
    Lock lock = mock(Lock.class);
    Exception expectedException = new Exception("test");
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> executeWithLocks(new ThrowingCallable(expectedException, clock), lock));
    assertThat(exception).hasCauseThat().isSameInstanceAs(expectedException);
    verify(lock, times(1)).release();
  }

  @Test
  public void testLockFailed() {
    Lock lock = null;
    CountingCallable countingCallable = new CountingCallable();
    assertThat(executeWithLocks(countingCallable, lock)).isFalse();
    assertThat(countingCallable.numCalled).isEqualTo(0);
  }
}
