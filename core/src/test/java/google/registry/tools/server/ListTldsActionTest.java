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

package google.registry.tools.server;

import static google.registry.testing.DatastoreHelper.createTld;

import google.registry.testing.FakeClock;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ListTldsAction}. */
@RunWith(JUnit4.class)
public class ListTldsActionTest extends ListActionTestCase {

  ListTldsAction action;

  @Before
  public void init() {
    createTld("xn--q9jyb4c");
    action = new ListTldsAction();
    action.clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  }

  @Test
  public void testRun_noParameters() {
    testRunSuccess(action, Optional.empty(), Optional.empty(), Optional.empty(), "xn--q9jyb4c");
  }

  @Test
  public void testRun_withParameters() {
    testRunSuccess(
        action,
        Optional.of("tldType"),
        Optional.empty(),
        Optional.empty(),
        "TLD          tldType",
        "-----------  -------",
        "xn--q9jyb4c  REAL   ");
  }

  @Test
  public void testRun_withWildcard() {
    testRunSuccess(
        action,
        Optional.of("*"),
        Optional.empty(),
        Optional.empty(),
        "^TLD          .*tldType",
        "^-----------  .*-------",
        "^xn--q9jyb4c  .*REAL   ");
  }

  @Test
  public void testRun_withBadField_returnsError() {
    testRunError(
        action,
        Optional.of("badfield"),
        Optional.empty(),
        Optional.empty(),
        "^Field 'badfield' not found - recognized fields are:");
  }
}
