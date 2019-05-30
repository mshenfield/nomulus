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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.beust.jcommander.ParameterException;
import com.google.appengine.tools.remoteapi.RemoteApiException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.Files;
import com.googlecode.objectify.Key;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.DeterministicStringGenerator.Rule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import google.registry.util.StringGenerator.Alphabets;
import java.io.File;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

/** Unit tests for {@link GenerateAllocationTokensCommand}. */
public class GenerateAllocationTokensCommandTest
    extends CommandTestCase<GenerateAllocationTokensCommand> {

  @Before
  public void init() {
    command.stringGenerator = new DeterministicStringGenerator(Alphabets.BASE_58);
    command.retrier =
        new Retrier(new FakeSleeper(new FakeClock(DateTime.parse("2000-01-01TZ"))), 3);
  }

  @Test
  public void testSuccess_oneToken() throws Exception {
    runCommand("--prefix", "blah", "--number", "1", "--length", "9");
    assertAllocationTokens(createToken("blah123456789", null, null));
    assertInStdout("blah123456789");
  }

  @Test
  public void testSuccess_threeTokens() throws Exception {
    runCommand("--prefix", "foo", "--number", "3", "--length", "10");
    assertAllocationTokens(
        createToken("foo123456789A", null, null),
        createToken("fooBCDEFGHJKL", null, null),
        createToken("fooMNPQRSTUVW", null, null));
    assertInStdout("foo123456789A\nfooBCDEFGHJKL\nfooMNPQRSTUVW");
  }

  @Test
  public void testSuccess_defaults() throws Exception {
    runCommand("--number", "1");
    assertAllocationTokens(createToken("123456789ABCDEFG", null, null));
    assertInStdout("123456789ABCDEFG");
  }

  @Test
  public void testSuccess_retry() throws Exception {
    GenerateAllocationTokensCommand spyCommand = spy(command);
    RemoteApiException fakeException = new RemoteApiException("foo", "foo", "foo", new Exception());
    doThrow(fakeException)
        .doThrow(fakeException)
        .doCallRealMethod()
        .when(spyCommand)
        .saveTokens(ArgumentMatchers.any());
    runCommand("--number", "1");
    assertAllocationTokens(createToken("123456789ABCDEFG", null, null));
    assertInStdout("123456789ABCDEFG");
  }

  @Test
  public void testSuccess_tokenCollision() throws Exception {
    AllocationToken existingToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("DEADBEEF123456789ABC")
                .setTokenType(SINGLE_USE)
                .build());
    runCommand("--number", "1", "--prefix", "DEADBEEF", "--length", "12");
    assertAllocationTokens(existingToken, createToken("DEADBEEFDEFGHJKLMNPQ", null, null));
    assertInStdout("DEADBEEFDEFGHJKLMNPQ");
  }

  @Test
  public void testSuccess_dryRun_outputsButDoesntSave() throws Exception {
    runCommand("--prefix", "foo", "--number", "2", "--length", "10", "--dry_run");
    assertAllocationTokens();
    assertInStdout("foo123456789A\nfooBCDEFGHJKL");
  }

  @Test
  public void testSuccess_largeNumberOfTokens() throws Exception {
    command.stringGenerator =
        new DeterministicStringGenerator(Alphabets.BASE_58, Rule.PREPEND_COUNTER);
    runCommand("--prefix", "ooo", "--number", "100", "--length", "16");
    // The deterministic string generator makes it too much hassle to assert about each token, so
    // just assert total number.
    assertThat(ofy().load().type(AllocationToken.class).count()).isEqualTo(100);
  }

  @Test
  public void testSuccess_domainNames() throws Exception {
    File domainNamesFile = tmpDir.newFile("domain_names.txt");
    Files.asCharSink(domainNamesFile, UTF_8).write("foo1.tld\nboo2.tld\nbaz9.tld\n");
    runCommand("--domain_names_file", domainNamesFile.getPath());
    assertAllocationTokens(
        createToken("123456789ABCDEFG", null, "foo1.tld"),
        createToken("HJKLMNPQRSTUVWXY", null, "boo2.tld"),
        createToken("Zabcdefghijkmnop", null, "baz9.tld"));
    assertInStdout(
        "foo1.tld,123456789ABCDEFG\nboo2.tld,HJKLMNPQRSTUVWXY\nbaz9.tld,Zabcdefghijkmnop");
  }

  @Test
  public void testSuccess_promotionToken() throws Exception {
    DateTime promoStart = DateTime.now(UTC);
    DateTime promoEnd = promoStart.plusMonths(1);
    runCommand(
        "--number", "1",
        "--prefix", "promo",
        "--type", "UNLIMITED_USE",
        "--allowed_client_ids", "TheRegistrar,NewRegistrar",
        "--allowed_tlds", "tld,example",
        "--discount_fraction", "0.5",
        "--token_status_transitions",
            String.format(
                "\"%s=NOT_STARTED,%s=VALID,%s=ENDED\"", START_OF_TIME, promoStart, promoEnd));
    assertAllocationTokens(
        new AllocationToken.Builder()
            .setToken("promo123456789ABCDEFG")
            .setTokenType(UNLIMITED_USE)
            .setAllowedClientIds(ImmutableSet.of("TheRegistrar", "NewRegistrar"))
            .setAllowedTlds(ImmutableSet.of("tld", "example"))
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(promoStart, TokenStatus.VALID)
                    .put(promoEnd, TokenStatus.ENDED)
                    .build())
            .build());
  }

  @Test
  public void testFailure_mustSpecifyNumberOfTokensOrDomainsFile() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--prefix", "FEET"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must specify either --number or --domain_names_file, but not both");
  }

  @Test
  public void testFailure_mustNotSpecifyBothNumberOfTokensAndDomainsFile() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--prefix", "FEET",
                    "--number", "999",
                    "--domain_names_file", "/path/to/blaaaaah"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must specify either --number or --domain_names_file, but not both");
  }

  @Test
  public void testFailure_invalidTokenType() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommand("--number", "999", "--type", "INVALID_TYPE"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Invalid value for -t parameter. Allowed values:[SINGLE_USE, UNLIMITED_USE]");
  }

  @Test
  public void testFailure_invalidTokenStatusTransition() {
    assertThat(
            assertThrows(
                ParameterException.class,
                () ->
                    runCommand(
                        "--number",
                        "999",
                        String.format(
                            "--token_status_transitions=\"%s=INVALID_STATUS\"", START_OF_TIME))))
        .hasCauseThat()
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void testFailure_unlimitedUseMustHaveTransitions() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommand("--number", "999", "--type", "UNLIMITED_USE")))
        .hasMessageThat()
        .isEqualTo("For UNLIMITED_USE tokens, must specify --token_status_transitions");
  }

  private void assertAllocationTokens(AllocationToken... expectedTokens) {
    // Using ImmutableObject comparison here is tricky because the creation/updated timestamps are
    // neither easy nor valuable to test here.
    ImmutableMap<String, AllocationToken> actualTokens =
        ofy().load().type(AllocationToken.class).list().stream()
            .collect(ImmutableMap.toImmutableMap(AllocationToken::getToken, Function.identity()));
    assertThat(actualTokens).hasSize(expectedTokens.length);
    for (AllocationToken expectedToken : expectedTokens) {
      AllocationToken match = actualTokens.get(expectedToken.getToken());
      assertThat(match).isNotNull();
      assertThat(match.getRedemptionHistoryEntry())
          .isEqualTo(expectedToken.getRedemptionHistoryEntry());
      assertThat(match.getAllowedClientIds()).isEqualTo(expectedToken.getAllowedClientIds());
      assertThat(match.getAllowedTlds()).isEqualTo(expectedToken.getAllowedTlds());
      assertThat(match.getDiscountFraction()).isEqualTo(expectedToken.getDiscountFraction());
      assertThat(match.getTokenStatusTransitions())
          .isEqualTo(expectedToken.getTokenStatusTransitions());
      assertThat(match.getTokenType()).isEqualTo(expectedToken.getTokenType());
    }
  }

  private AllocationToken createToken(
      String token,
      @Nullable Key<HistoryEntry> redemptionHistoryEntry,
      @Nullable String domainName) {
    AllocationToken.Builder builder =
        new AllocationToken.Builder().setToken(token).setTokenType(SINGLE_USE);
    if (redemptionHistoryEntry != null) {
      builder.setRedemptionHistoryEntry(redemptionHistoryEntry);
    }
    builder.setDomainName(domainName);
    return builder.build();
  }
}
