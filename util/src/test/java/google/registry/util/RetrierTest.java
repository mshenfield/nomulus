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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;

import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier.FailureReporter;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Retrier}. */
@RunWith(JUnit4.class)
public class RetrierTest {

  Retrier retrier = new Retrier(new FakeSleeper(new FakeClock()), 3);

  /** An exception to throw from {@link CountingThrower}. */
  static class CountingException extends RuntimeException {
    CountingException(int count) {
      super("" + count);
    }
  }

  /** Test object that always throws an exception with the current count. */
  static class CountingThrower implements Callable<Integer> {

    int count = 0;

    final int numThrows;

    CountingThrower(int numThrows) {
      this.numThrows = numThrows;
    }

    @Override
    public Integer call() {
      if (count == numThrows) {
        return numThrows;
      }
      count++;
      throw new CountingException(count);
    }
  }

  static class TestReporter implements FailureReporter {
    int numBeforeRetry = 0;

    @Override
    public void beforeRetry(Throwable e, int failures, int maxAttempts) {
      numBeforeRetry++;
      assertThat(failures).isEqualTo(numBeforeRetry);
    }
  }

  @Test
  public void testRetryableException() {
    CountingException thrown =
        assertThrows(
            CountingException.class,
            () -> retrier.callWithRetry(new CountingThrower(3), CountingException.class));
    assertThat(thrown).hasMessageThat().contains("3");
  }

  @Test
  public void testUnretryableException() {
    CountingException thrown =
        assertThrows(
            CountingException.class,
            () -> retrier.callWithRetry(new CountingThrower(5), IllegalArgumentException.class));
    assertThat(thrown).hasMessageThat().contains("1");
  }

  @Test
  public void testRetrySucceeded() {
    assertThat(retrier.callWithRetry(new CountingThrower(2), CountingException.class))
        .isEqualTo(2);
  }

  @Test
  public void testRetryFailed_withReporter() {
    CountingException thrown =
        assertThrows(
            CountingException.class,
            () -> {
              TestReporter reporter = new TestReporter();
              try {
                retrier.callWithRetry(new CountingThrower(3), reporter, CountingException.class);
              } catch (CountingException expected) {
                assertThat(reporter.numBeforeRetry).isEqualTo(2);
                throw expected;
              }
            });
    assertThat(thrown).hasMessageThat().contains("3");
  }

  @Test
  public void testRetrySucceeded_withReporter() {
    TestReporter reporter = new TestReporter();
    assertThat(retrier.callWithRetry(new CountingThrower(2), reporter, CountingException.class))
        .isEqualTo(2);
    assertThat(reporter.numBeforeRetry).isEqualTo(2);
  }

  @Test
  public void testFirstTrySucceeded_withReporter() {
    TestReporter reporter = new TestReporter();
    assertThat(retrier.callWithRetry(new CountingThrower(0), reporter, CountingException.class))
        .isEqualTo(0);
    assertThat(reporter.numBeforeRetry).isEqualTo(0);
  }
}
