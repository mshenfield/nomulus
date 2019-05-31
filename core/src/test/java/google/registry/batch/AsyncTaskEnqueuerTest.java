// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESAVE_TIMES;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.batch.AsyncTaskEnqueuer.PATH_RESAVE_ENTITY;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_DELETE;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_HOST_RENAME;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.testing.TestLogHandlerUtils.assertLogMessage;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardHours;
import static org.joda.time.Duration.standardSeconds;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.flogger.LoggerConfig;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectRule;
import google.registry.testing.ShardableTestCase;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.CapturingLogHandler;
import google.registry.util.Retrier;
import java.util.logging.Level;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link AsyncTaskEnqueuer}. */
@RunWith(JUnit4.class)
public class AsyncTaskEnqueuerTest extends ShardableTestCase {

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastore().withTaskQueue().build();

  @Rule public final InjectRule inject = new InjectRule();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private AppEngineServiceUtils appEngineServiceUtils;

  private AsyncTaskEnqueuer asyncTaskEnqueuer;
  private final CapturingLogHandler logHandler = new CapturingLogHandler();
  private final FakeClock clock = new FakeClock(DateTime.parse("2015-05-18T12:34:56Z"));

  @Before
  public void setUp() {
    LoggerConfig.getConfig(AsyncTaskEnqueuer.class).addHandler(logHandler);
    when(appEngineServiceUtils.getServiceHostname("backend")).thenReturn("backend.hostname.fake");
    asyncTaskEnqueuer =
        new AsyncTaskEnqueuer(
            getQueue(QUEUE_ASYNC_ACTIONS),
            getQueue(QUEUE_ASYNC_DELETE),
            getQueue(QUEUE_ASYNC_HOST_RENAME),
            standardSeconds(90),
            appEngineServiceUtils,
            new Retrier(new FakeSleeper(clock), 1));
  }

  @Test
  public void test_enqueueAsyncResave_success() {
    ContactResource contact = persistActiveContact("jd23456");
    asyncTaskEnqueuer.enqueueAsyncResave(contact, clock.nowUtc(), clock.nowUtc().plusDays(5));
    assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(PATH_RESAVE_ENTITY)
            .method("POST")
            .header("Host", "backend.hostname.fake")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, Key.create(contact).getString())
            .param(PARAM_REQUESTED_TIME, clock.nowUtc().toString())
            .etaDelta(
                standardDays(5).minus(standardSeconds(30)),
                standardDays(5).plus(standardSeconds(30))));
  }

  @Test
  public void test_enqueueAsyncResave_multipleResaves() {
    ContactResource contact = persistActiveContact("jd23456");
    DateTime now = clock.nowUtc();
    asyncTaskEnqueuer.enqueueAsyncResave(
        contact,
        now,
        ImmutableSortedSet.of(now.plusHours(24), now.plusHours(50), now.plusHours(75)));
    assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(PATH_RESAVE_ENTITY)
            .method("POST")
            .header("Host", "backend.hostname.fake")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, Key.create(contact).getString())
            .param(PARAM_REQUESTED_TIME, now.toString())
            .param(
                PARAM_RESAVE_TIMES,
                "2015-05-20T14:34:56.000Z,2015-05-21T15:34:56.000Z")
            .etaDelta(
                standardHours(24).minus(standardSeconds(30)),
                standardHours(24).plus(standardSeconds(30))));
  }

  @Test
  public void test_enqueueAsyncResave_ignoresTasksTooFarIntoFuture() throws Exception {
    ContactResource contact = persistActiveContact("jd23456");
    asyncTaskEnqueuer.enqueueAsyncResave(contact, clock.nowUtc(), clock.nowUtc().plusDays(31));
    assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
    assertLogMessage(logHandler, Level.INFO, "Ignoring async re-save");
  }
}
