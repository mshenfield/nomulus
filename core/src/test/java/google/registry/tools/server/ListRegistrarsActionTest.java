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

import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;

import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link ListRegistrarsAction}.
 */
@RunWith(JUnit4.class)
public class ListRegistrarsActionTest extends ListActionTestCase {

  ListRegistrarsAction action;

  @Before
  public void init() {
    action = new ListRegistrarsAction();
    createTlds("xn--q9jyb4c", "example");
    // Ensure that NewRegistrar only has access to xn--q9jyb4c and that TheRegistrar only has access
    // to example.
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
            .build());
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("example"))
            .build());
  }

  @Test
  public void testRun_noParameters() {
    testRunSuccess(
        action,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "^NewRegistrar$",
        "^TheRegistrar$");
  }

  @Test
  public void testRun_withParameters() {
    testRunSuccess(
        action,
        Optional.of("allowedTlds"),
        Optional.empty(),
        Optional.empty(),
        "^clientId\\s+allowedTlds\\s*$",
        "-+\\s+-+\\s*$",
        "^NewRegistrar\\s+\\[xn--q9jyb4c\\]\\s*$",
        "^TheRegistrar\\s+\\[example\\]\\s*$");
  }

  @Test
  public void testRun_withWildcard() {
    testRunSuccess(
        action,
        Optional.of("*"),
        Optional.empty(),
        Optional.empty(),
        "^clientId\\s+.*allowedTlds",
        "^-+\\s+-+",
        "^NewRegistrar\\s+.*\\[xn--q9jyb4c\\]",
        "^TheRegistrar\\s+.*\\[example\\]");
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
