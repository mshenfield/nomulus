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

package google.registry.tools;

import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.model.ofy.Ofy;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link CountDomainsCommand}. */
public class CountDomainsCommandTest extends CommandTestCase<CountDomainsCommand> {

  protected FakeClock clock = new FakeClock(DateTime.now(UTC));

  @Rule public final InjectRule inject = new InjectRule();

  @Before
  public final void before() {
    inject.setStaticField(Ofy.class, "clock", clock);
    command.clock = clock;
    createTlds("foo", "bar", "baz", "qux");
  }

  @Test
  public void testSuccess_singleTld() throws Exception {
    for (int i = 0; i < 51; i++) {
      persistActiveDomain(String.format("test-%d.foo", i));
      if (i > 31) {
        persistActiveDomain(String.format("test-%d.baz", i));
      }
    }
    runCommand("foo");
    assertStdoutIs("foo,51\n");
  }

  @Test
  public void testSuccess_multipleTlds() throws Exception {
    command.clock = clock;
    for (int i = 0; i < 29; i++) {
      persistActiveDomain(String.format("test-%d.foo", i));
    }
    for (int j = 0; j < 17; j++) {
      persistActiveDomain(String.format("test-%d.baz", j));
      persistDeletedDomain(String.format("del-%d.foo", j), clock.nowUtc().minusYears(1));
    }
    persistActiveDomain("not-counted.qux");
    runCommand("foo", "bar", "baz");
    assertStdoutIs("foo,29\nbar,0\nbaz,17\n");
  }
}
