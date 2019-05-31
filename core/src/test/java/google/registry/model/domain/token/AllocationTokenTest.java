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

package google.registry.model.domain.token;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.CANCELLED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.ENDED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.NOT_STARTED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.VALID;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.reporting.HistoryEntry;
import org.joda.time.DateTime;
import org.junit.Test;

/** Unit tests for {@link AllocationToken}. */
public class AllocationTokenTest extends EntityTestCase {

  @Test
  public void testPersistence() {
    AllocationToken unlimitedUseToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(UNLIMITED_USE)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("dev", "app"))
                .setAllowedClientIds(ImmutableSet.of("TheRegistrar, NewRegistrar"))
                .setDiscountFraction(0.5)
                .setTokenStatusTransitions(
                    ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                        .put(START_OF_TIME, NOT_STARTED)
                        .put(DateTime.now(UTC), TokenStatus.VALID)
                        .put(DateTime.now(UTC).plusWeeks(8), TokenStatus.ENDED)
                        .build())
                .build());
    assertThat(ofy().load().entity(unlimitedUseToken).now()).isEqualTo(unlimitedUseToken);

    AllocationToken singleUseToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setRedemptionHistoryEntry(Key.create(HistoryEntry.class, 1L))
                .setDomainName("foo.example")
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setTokenType(SINGLE_USE)
                .build());
    assertThat(ofy().load().entity(singleUseToken).now()).isEqualTo(singleUseToken);
  }

  @Test
  public void testIndexing() throws Exception {
    verifyIndexing(
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setRedemptionHistoryEntry(Key.create(HistoryEntry.class, 1L))
                .setDomainName("blahdomain.fake")
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .build()),
        "token",
        "redemptionHistoryEntry",
        "domainName");
  }

  @Test
  public void testCreationTime_autoPopulates() {
    AllocationToken tokenBeforePersisting =
        new AllocationToken.Builder().setToken("abc123").setTokenType(SINGLE_USE).build();
    assertThat(tokenBeforePersisting.getCreationTime()).isEmpty();
    AllocationToken tokenAfterPersisting = persistResource(tokenBeforePersisting);
    assertThat(tokenAfterPersisting.getCreationTime()).hasValue(clock.nowUtc());
  }

  @Test
  public void testSetCreationTime_cantCallMoreThanOnce() {
    AllocationToken.Builder builder =
        new AllocationToken.Builder()
            .setToken("foobar")
            .setTokenType(SINGLE_USE)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"));
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> builder.setCreationTimeForTest(DateTime.parse("2010-11-13T05:00:00Z")));
    assertThat(thrown).hasMessageThat().isEqualTo("Creation time can only be set once");
  }

  @Test
  public void testSetToken_cantCallMoreThanOnce() {
    AllocationToken.Builder builder = new AllocationToken.Builder().setToken("foobar");
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> builder.setToken("barfoo"));
    assertThat(thrown).hasMessageThat().isEqualTo("Token can only be set once");
  }

  @Test
  public void testSetTokenType_cantCallMoreThanOnce() {
    AllocationToken.Builder builder =
        new AllocationToken.Builder().setTokenType(TokenType.UNLIMITED_USE);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> builder.setTokenType(SINGLE_USE));
    assertThat(thrown).hasMessageThat().isEqualTo("Token type can only be set once");
  }

  @Test
  public void testBuild_domainNameOnlyOnSingleUse() {
    AllocationToken.Builder builder =
        new AllocationToken.Builder()
            .setToken("foobar")
            .setTokenType(TokenType.UNLIMITED_USE)
            .setDomainName("foo.example");
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name can only be specified for SINGLE_USE tokens");
  }

  @Test
  public void testBuild_redemptionHistoryEntryOnlyInSingleUse() {
    AllocationToken.Builder builder =
        new AllocationToken.Builder()
            .setToken("foobar")
            .setTokenType(TokenType.UNLIMITED_USE)
            .setRedemptionHistoryEntry(Key.create(HistoryEntry.class, "hi"));
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Redemption history entry can only be specified for SINGLE_USE tokens");
  }

  @Test
  public void testSetTransitions_notStartOfTime() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setTokenStatusTransitions(
                        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                            .put(DateTime.now(UTC), NOT_STARTED)
                            .put(DateTime.now(UTC).plusDays(1), TokenStatus.VALID)
                            .put(DateTime.now(UTC).plusDays(2), TokenStatus.ENDED)
                            .build()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("tokenStatusTransitions map must start at START_OF_TIME.");
  }

  @Test
  public void testSetTransitions_badInitialValue() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setTokenStatusTransitions(
                        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                            .put(START_OF_TIME, TokenStatus.VALID)
                            .put(DateTime.now(UTC), TokenStatus.ENDED)
                            .build()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("tokenStatusTransitions must start with NOT_STARTED");
  }

  @Test
  public void testSetTransitions_invalidInitialTransitions() {
    // NOT_STARTED can only go to VALID or CANCELLED
    assertBadInitialTransition(NOT_STARTED);
    assertBadInitialTransition(ENDED);
  }

  @Test
  public void testSetTransitions_badTransitionsFromValid() {
    // VALID can only go to ENDED or CANCELLED
    assertBadTransition(
        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
            .put(START_OF_TIME, NOT_STARTED)
            .put(DateTime.now(UTC), VALID)
            .put(DateTime.now(UTC).plusDays(1), VALID)
            .build(),
        VALID,
        VALID);
    assertBadTransition(
        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
            .put(START_OF_TIME, NOT_STARTED)
            .put(DateTime.now(UTC), VALID)
            .put(DateTime.now(UTC).plusDays(1), NOT_STARTED)
            .build(),
        VALID,
        NOT_STARTED);
  }

  @Test
  public void testSetTransitions_terminalTransitions() {
    // both ENDED and CANCELLED are terminal
    assertTerminal(ENDED);
    assertTerminal(CANCELLED);
  }

  @Test
  public void testBuild_noTokenType() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setToken("foobar").build());
    assertThat(thrown).hasMessageThat().isEqualTo("Token type must be specified");
  }

  @Test
  public void testBuild_noToken() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setTokenType(SINGLE_USE).build());
    assertThat(thrown).hasMessageThat().isEqualTo("Token must not be null or empty");
  }

  @Test
  public void testBuild_emptyToken() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setToken("").setTokenType(SINGLE_USE).build());
    assertThat(thrown).hasMessageThat().isEqualTo("Token must not be blank");
  }

  private void assertBadInitialTransition(TokenStatus status) {
    assertBadTransition(
        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
            .put(START_OF_TIME, NOT_STARTED)
            .put(DateTime.now(UTC), status)
            .build(),
        NOT_STARTED,
        status);
  }

  private void assertBadTransition(
      ImmutableSortedMap<DateTime, TokenStatus> map, TokenStatus from, TokenStatus to) {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AllocationToken.Builder().setTokenStatusTransitions(map));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("tokenStatusTransitions map cannot transition from %s to %s.", from, to));
  }

  private void assertTerminal(TokenStatus status) {
    // The "terminal" message is slightly different so it must be tested separately
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AllocationToken.Builder()
                    .setTokenStatusTransitions(
                        ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                            .put(START_OF_TIME, NOT_STARTED)
                            .put(DateTime.now(UTC), VALID)
                            .put(DateTime.now(UTC).plusDays(1), status)
                            .put(DateTime.now(UTC).plusDays(2), CANCELLED)
                            .build()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(String.format("tokenStatusTransitions map cannot transition from %s.", status));
  }
}
