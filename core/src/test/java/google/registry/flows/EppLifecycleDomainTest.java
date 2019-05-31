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

package google.registry.flows;

import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_AND_CLOSE;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACTION_PENDING;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.registry.Registry.TldState.PREDELEGATION;
import static google.registry.model.registry.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.testing.DatastoreHelper.assertBillingEventsForResource;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.EppMetricSubject.assertThat;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.testing.AppEngineRule;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for domain lifecycle. */
@RunWith(JUnit4.class)
public class EppLifecycleDomainTest extends EppTestCase {

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastore().withTaskQueue().build();

  @Before
  public void initTld() {
    createTlds("example", "tld");
  }

  @Test
  public void testDomainDeleteRestore() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00.0Z",
                "EXDATE", "2002-06-01T00:02:00.0Z"));

    // Delete domain example.tld after its add grace period has expired.
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-07-01T00:02:00Z")
        .hasResponse("generic_success_action_pending_response.xml");

    // Restore the domain.
    assertThatCommand("domain_update_restore_request.xml")
        .atTime("2000-07-01T00:03:00Z")
        .hasResponse("generic_success_response.xml");

    assertThatLogoutSucceeds();
  }

  @Test
  public void testDomainDeletion_withinAddGracePeriod_deletesImmediately() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Create domain example.tld
    DateTime createTime = DateTime.parse("2000-06-01T00:02:00Z");
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(createTime)
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00.0Z",
                "EXDATE", "2002-06-01T00:02:00.0Z"));

    DomainBase domain =
        loadByForeignKey(DomainBase.class, "example.tld", createTime.plusHours(1)).get();

    // Delete domain example.tld within the add grace period.
    DateTime deleteTime = createTime.plusDays(1);
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(deleteTime)
        .hasResponse("generic_success_response.xml");

    // Verify that it is immediately non-existent.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(deleteTime.plusSeconds(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2303", "MSG", "The domain with given ID (example.tld) doesn't exist."));

    // The expected one-time billing event, that should have an associated Cancellation.
    OneTime oneTimeCreateBillingEvent = makeOneTimeCreateBillingEvent(domain, createTime);
    // Verify that the OneTime billing event associated with the domain creation is canceled.
    assertBillingEventsForResource(
        domain,
        // Check the existence of the expected create one-time billing event.
        oneTimeCreateBillingEvent,
        makeRecurringCreateBillingEvent(domain, createTime.plusYears(2), deleteTime),
        // Check for the existence of a cancellation for the given one-time billing event.
        makeCancellationBillingEventFor(
            domain, oneTimeCreateBillingEvent, createTime, deleteTime));

    assertThatLogoutSucceeds();
  }

  @Test
  public void testDomainDeletion_outsideAddGracePeriod_showsRedemptionPeriod() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    DateTime createTime = DateTime.parse("2000-06-01T00:02:00Z");
    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(createTime)
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00.0Z",
                "EXDATE", "2002-06-01T00:02:00.0Z"));

    DateTime deleteTime = DateTime.parse("2000-07-07T00:02:00Z"); // 1 month and 6 days after
    // Delete domain example.tld after its add grace period has expired.
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(deleteTime)
        .hasResponse("generic_success_action_pending_response.xml");

    // Verify that domain shows redemptionPeriod soon after deletion.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-07-08T00:00:00Z")
        .hasResponse(
            "domain_info_response_wildcard.xml", ImmutableMap.of("STATUS", "redemptionPeriod"));

    // Verify that the domain shows pendingDelete next.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-08-08T00:00:00Z")
        .hasResponse(
            "domain_info_response_wildcard_after_redemption.xml",
            ImmutableMap.of("STATUS", "pendingDelete"));

    // Verify that the domain is non-existent (available for registration) later.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-09-01T00:00:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2303", "MSG", "The domain with given ID (example.tld) doesn't exist."));

    DomainBase domain =
        loadByForeignKey(
                DomainBase.class, "example.tld", DateTime.parse("2000-08-01T00:02:00Z"))
            .get();
    // Verify that the autorenew was ended and that the one-time billing event is not canceled.
    assertBillingEventsForResource(
        domain,
        makeOneTimeCreateBillingEvent(domain, createTime),
        makeRecurringCreateBillingEvent(domain, createTime.plusYears(2), deleteTime));

    assertThatLogoutSucceeds();
  }

  @Test
  public void testEapDomainDeletion_withinAddGracePeriod_eapFeeIsNotRefunded() throws Exception {
    assertThatCommand("login_valid_fee_extension.xml").hasResponse("generic_success_response.xml");
    createContacts(DateTime.parse("2000-06-01T00:00:00Z"));

    // Set the EAP schedule.
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 0),
                    DateTime.parse("2000-06-01T00:00:00Z"), Money.of(USD, 100),
                    DateTime.parse("2000-06-02T00:00:00Z"), Money.of(USD, 0)))
            .build());

    // Create domain example.tld, which should have an EAP fee of USD 100.
    DateTime createTime = DateTime.parse("2000-06-01T00:02:00Z");
    assertThatCommand("domain_create_eap_fee.xml")
        .atTime(createTime)
        .hasResponse("domain_create_response_eap_fee.xml");

    DomainBase domain =
        loadByForeignKey(
                DomainBase.class, "example.tld", DateTime.parse("2000-06-01T00:03:00Z"))
            .get();

    // Delete domain example.tld within the add grade period.
    DateTime deleteTime = createTime.plusDays(1);
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime(deleteTime)
        .hasResponse("domain_delete_response_fee.xml");

    // Verify that the OneTime billing event associated with the base fee of domain registration and
    // is canceled and the autorenew is ended, but that the EAP fee is not canceled.
    OneTime expectedCreateEapBillingEvent =
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.FEE_EARLY_ACCESS)
            .setTargetId("example.tld")
            .setClientId("NewRegistrar")
            .setPeriodYears(1)
            .setCost(Money.parse("USD 100.00"))
            .setEventTime(createTime)
            .setBillingTime(createTime.plus(Registry.get("tld").getRenewGracePeriodLength()))
            .setParent(getOnlyHistoryEntryOfType(domain, Type.DOMAIN_CREATE))
            .build();

    // The expected one-time billing event, that should have an associated Cancellation.
    OneTime expectedOneTimeCreateBillingEvent = makeOneTimeCreateBillingEvent(domain, createTime);
    assertBillingEventsForResource(
        domain,
        // Check for the expected create one-time billing event ...
        expectedOneTimeCreateBillingEvent,
        // ... and the expected one-time EAP fee billing event ...
        expectedCreateEapBillingEvent,
        makeRecurringCreateBillingEvent(domain, createTime.plusYears(2), deleteTime),
        // ... and verify that the create one-time billing event was canceled ...
        makeCancellationBillingEventFor(
            domain, expectedOneTimeCreateBillingEvent, createTime, deleteTime));
    // ... but there was NOT a Cancellation for the EAP fee, as this would fail if additional
    // billing events were present.

    assertThatLogoutSucceeds();
  }

  @Test
  public void testDomainDeletionWithSubordinateHost_fails() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    createSubordinateHost();
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2305", "MSG", "Domain to be deleted has subordinate hosts"));
    assertThatLogoutSucceeds();
  }

  @Test
  public void testDeletionOfDomain_afterRenameOfSubordinateHost_succeeds() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasNoTld()
        .and()
        .hasCommandName("Login")
        .and()
        .hasStatus(SUCCESS);
    createFakesite();
    createSubordinateHost();
    // Update the ns3 host to no longer be on fakesite.example domain.
    assertThatCommand("host_update_fakesite.xml")
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_response.xml");
    // Add assert about EppMetric
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("HostUpdate")
        .and()
        .hasStatus(SUCCESS);
    // Delete the fakesite.example domain (which should succeed since it no longer has subords).
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-05-30T01:02:00Z")
        .hasResponse("generic_success_action_pending_response.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasTld("example")
        .and()
        .hasCommandName("DomainDelete")
        .and()
        .hasStatus(SUCCESS_WITH_ACTION_PENDING);
    // Check info on the renamed host and verify that it's still around and wasn't deleted.
    assertThatCommand("host_info_ns9000_example.xml")
        .atTime("2002-06-30T01:03:00Z")
        .hasResponse("host_info_response_ns9000_example.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("HostInfo")
        .and()
        .hasStatus(SUCCESS);
    assertThatLogoutSucceeds();
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("Logout")
        .and()
        .hasStatus(SUCCESS_AND_CLOSE);
  }

  @Test
  public void testDeletionOfDomain_afterUpdateThatCreatesSubordinateHost_fails() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-02T00:00:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-02T00:00:00.0Z",
                "EXDATE", "2002-06-02T00:00:00.0Z"));

    // Create nameserver ns1.example.tld
    assertThatCommand("host_create_example.xml")
        .atTime("2000-06-02T00:01:00Z")
        .hasResponse("host_create_response_example.xml");

    // Update the ns1 host to be on the fakesite.example domain.
    assertThatCommand("host_update_ns1_to_fakesite.xml")
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_response.xml");
    // Attempt to delete the fakesite.example domain (which should fail since it now has a
    // subordinate host).
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-05-30T01:02:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2305", "MSG", "Domain to be deleted has subordinate hosts"));
    // Check info on the renamed host and verify that it's still around and wasn't deleted.
    assertThatCommand("host_info_fakesite.xml")
        .atTime("2002-06-30T01:03:00Z")
        .hasResponse("host_info_response_fakesite_post_update.xml");
    // Verify that fakesite.example domain is still around and wasn't deleted.
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:00:00Z")
        .hasResponse("domain_info_response_fakesite_ok_post_host_update.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  public void testDomainCreation_failsBeforeSunrise() throws Exception {
    DateTime sunriseDate = DateTime.parse("2000-05-30T00:00:00Z");
    createTld(
        "example",
        new ImmutableSortedMap.Builder<DateTime, TldState>(Ordering.natural())
            .put(START_OF_TIME, PREDELEGATION)
            .put(sunriseDate, START_DATE_SUNRISE)
            .put(sunriseDate.plusMonths(2), GENERAL_AVAILABILITY)
            .build());

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");

    createContactsAndHosts();

    assertThatCommand("domain_create_sunrise_encoded_mark.xml")
        .atTime(sunriseDate.minusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2002",
                "MSG", "The current registry phase does not allow for general registrations"));

    assertThatCommand("domain_info_testvalidate.xml")
        .atTime(sunriseDate.plusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2303",
                "MSG", "The domain with given ID (test-validate.example) doesn't exist."));

    assertThatLogoutSucceeds();
  }

  @Test
  public void testDomainCheckFee_succeeds() throws Exception {
    DateTime gaDate = DateTime.parse("2000-05-30T00:00:00Z");
    createTld(
        "example",
        ImmutableSortedMap.of(
            START_OF_TIME, PREDELEGATION,
            gaDate, GENERAL_AVAILABILITY));

    assertThatCommand("login_valid_fee_extension.xml").hasResponse("generic_success_response.xml");

    assertThatCommand("domain_check_fee_premium.xml")
        .atTime(gaDate.plusDays(1))
        .hasResponse("domain_check_fee_premium_response.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("DomainCheck")
        .and()
        .hasTld("example")
        .and()
        .hasStatus(SUCCESS);

    assertThatLogoutSucceeds();
  }

  @Test
  public void testDomainCreate_annualAutoRenewPollMessages_haveUniqueIds() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Create the domain.
    createFakesite();

    // The first autorenew poll message isn't seen until after the initial two years of registration
    // are up.
    assertThatCommand("poll.xml")
        .atTime("2001-01-01T00:01:00Z")
        .hasResponse("poll_response_empty.xml");
    assertThatCommand("poll.xml")
        .atTime("2002-07-01T00:01:00Z")
        .hasResponse(
            "poll_response_autorenew.xml",
            ImmutableMap.of(
                "ID", "1-C-EXAMPLE-13-16-2002",
                "QDATE", "2002-06-01T00:04:00Z",
                "DOMAIN", "fakesite.example",
                "EXDATE", "2003-06-01T00:04:00Z"));
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", "1-C-EXAMPLE-13-16-2002"))
        .atTime("2002-07-01T00:02:00Z")
        .hasResponse("poll_ack_response_empty.xml");

    // The second autorenew poll message isn't seen until after another year, and it should have a
    // different ID.
    assertThatCommand("poll.xml")
        .atTime("2002-07-01T00:05:00Z")
        .hasResponse("poll_response_empty.xml");
    assertThatCommand("poll.xml")
        .atTime("2003-07-01T00:05:00Z")
        .hasResponse(
            "poll_response_autorenew.xml",
            ImmutableMap.of(
                "ID", "1-C-EXAMPLE-13-16-2003", // Note -- Year is different from previous ID.
                "QDATE", "2003-06-01T00:04:00Z",
                "DOMAIN", "fakesite.example",
                "EXDATE", "2004-06-01T00:04:00Z"));

    // Ack the second poll message and verify that none remain.
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", "1-C-EXAMPLE-13-16-2003"))
        .atTime("2003-07-01T00:05:05Z")
        .hasResponse("poll_ack_response_empty.xml");
    assertThatCommand("poll.xml")
        .atTime("2003-07-01T00:05:10Z")
        .hasResponse("poll_response_empty.xml");

    assertThatLogoutSucceeds();
  }

  @Test
  public void testDomainTransferPollMessage_serverApproved() throws Exception {
    // As the losing registrar, create the domain.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    assertThatLogoutSucceeds();

    // As the winning registrar, request a transfer. Capture the server trid; we'll need it later.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    String response =
        assertThatCommand("domain_transfer_request_1_year.xml")
            .atTime("2001-01-01T00:00:00Z")
            .hasResponse("domain_transfer_response_1_year.xml");
    Matcher matcher = Pattern.compile("<svTRID>(.*)</svTRID>").matcher(response);
    matcher.find();
    String transferRequestTrid = matcher.group(1);
    assertThatLogoutSucceeds();

    // As the losing registrar, read the request poll message, and then ack it.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("poll.xml")
        .atTime("2001-01-01T00:01:00Z")
        .hasResponse("poll_response_domain_transfer_request.xml");
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", "1-C-EXAMPLE-17-23-2001"))
        .atTime("2001-01-01T00:01:00Z")
        .hasResponse("poll_ack_response_empty.xml");

    // Five days in the future, expect a server approval poll message to the loser, and ack it.
    assertThatCommand("poll.xml")
        .atTime("2001-01-06T00:01:00Z")
        .hasResponse("poll_response_domain_transfer_server_approve_loser.xml");
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", "1-C-EXAMPLE-17-22-2001"))
        .atTime("2001-01-06T00:01:00Z")
        .hasResponse("poll_ack_response_empty.xml");
    assertThatLogoutSucceeds();

    // Also expect a server approval poll message to the winner, with the transfer request trid.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("poll.xml")
        .atTime("2001-01-06T00:02:00Z")
        .hasResponse(
            "poll_response_domain_transfer_server_approve_winner.xml",
            ImmutableMap.of("SERVER_TRID", transferRequestTrid));
    assertThatCommand("poll_ack.xml", ImmutableMap.of("ID", "1-C-EXAMPLE-17-21-2001"))
        .atTime("2001-01-06T00:02:00Z")
        .hasResponse("poll_ack_response_empty.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  public void testTransfer_autoRenewGraceActive_onlyAtAutomaticTransferTime_getsSubsumed()
      throws Exception {
    // Register the domain as the first registrar.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    assertThatLogoutSucceeds();

    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request.xml")
        .atTime("2002-05-30T00:00:00Z")
        .hasResponse("domain_transfer_response.xml");
    assertThatLogoutSucceeds();

    // Log back in as the first registrar and verify things.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:00:00Z")
        .hasResponse("domain_info_response_fakesite_pending_transfer.xml");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-06-02T00:00:00Z")
        .hasResponse("domain_info_response_fakesite_pending_transfer_autorenew.xml");
    assertThatLogoutSucceeds();

    // Log back in as the second registrar and verify transfer details.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    // Verify that domain is in the transfer period now with expiration date still one year out,
    // since the transfer should subsume the autorenew that happened during the transfer window.
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-06-06T00:00:00Z")
        .hasResponse("domain_info_response_fakesite_transfer_period.xml");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-06-12T00:00:00Z")
        .hasResponse("domain_info_response_fakesite_transfer_complete.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  public void testNameserversTransferWithDomain_successfully() throws Exception {
    // Log in as the first registrar and set up domains with hosts.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    createSubordinateHost();
    assertThatLogoutSucceeds();

    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request.xml")
        .atTime("2002-05-30T00:00:00Z")
        .hasResponse("domain_transfer_response.xml");
    assertThatLogoutSucceeds();

    // Log back in as the first registrar and verify domain is pending transfer.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:00:00Z")
        .hasResponse("domain_info_response_fakesite_3_nameservers_pending_transfer.xml");
    assertThatLogoutSucceeds();

    // Log back in as second registrar and verify transfer was successful.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    // Expect transfer complete with all three nameservers on it.
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-06-09T00:00:00Z")
        .hasResponse("domain_info_response_fakesite_3_nameservers_transfer_successful.xml");
    // Verify that host's client ID was set to the new registrar and has the transfer date set.
    assertThatCommand("host_info_fakesite.xml")
        .atTime("2002-06-09T00:01:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer.xml",
            ImmutableMap.of("TRDATE", "2002-06-04T00:00:00Z"));
    assertThatLogoutSucceeds();
  }

  @Test
  public void testRenewalFails_whenTotalTermExceeds10Years() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Creates domain with 2 year expiration.
    createFakesite();
    // Attempt to renew for 9 years, adding up to a total greater than the allowed max of 10 years.
    assertThatCommand(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "fakesite.example", "EXPDATE", "2002-06-01", "YEARS", "9"))
        .atTime("2000-06-07T00:00:00Z")
        .hasResponse("domain_renew_response_exceeds_max_years.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  public void testDomainDeletionCancelsPendingTransfer() throws Exception {
    // Register the domain as the first registrar.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    assertThatLogoutSucceeds();

    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request.xml")
        .atTime("2002-05-30T00:00:00Z")
        .hasResponse("domain_transfer_response.xml");
    assertThatLogoutSucceeds();

    // Log back in as the first registrar and delete then restore the domain while the transfer
    // is still pending.
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:00:00Z")
        .hasResponse("domain_info_response_fakesite_pending_transfer.xml");
    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "fakesite.example"))
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_action_pending_response.xml");
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:02:00Z")
        .hasResponse("domain_info_response_fakesite_pending_delete.xml");
    assertThatCommand("domain_update_restore_fakesite.xml")
        .atTime("2002-05-30T01:03:00Z")
        .hasResponse("generic_success_response.xml");

    // Expect domain is ok now, not pending delete or transfer, and has been extended by a year from
    // the date of the restore.  (Not from the original expiration date.)
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2002-05-30T01:04:00Z")
        .hasResponse("domain_info_response_fakesite_restored_ok.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  public void testDomainTransfer_subordinateHost_showsChangeInTransferQuery() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createFakesite();
    createSubordinateHost();
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2000-09-02T00:00:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2002", "MSG", "Object has no transfer history"));
    assertThatLogoutSucceeds();

    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request_1_year.xml")
        .atTime("2001-01-01T00:00:00Z")
        .hasResponse("domain_transfer_response_1_year.xml");
    assertThatLogoutSucceeds();

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Verify that reID is set correctly.
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2001-01-02T00:00:00Z")
        .hasResponse("domain_transfer_query_response_fakesite.xml");
    // Verify that status went from 'pending' to 'serverApproved'.
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2001-01-08T00:00:00Z")
        .hasResponse("domain_transfer_query_response_completed_fakesite.xml");
    assertThatLogoutSucceeds();
  }

  /**
   * Tests that when a superordinate domain of a host is transferred, and then the host is updated
   * to be subordinate to a different domain, that the host retains the transfer time of the first
   * superordinate domain, not whatever the transfer time from the second domain is.
   */
  @Test
  public void testSuccess_lastTransferTime_superordinateDomainTransferFollowedByHostUpdate()
      throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Create fakesite.example with subordinate host ns3.fakesite.example
    createFakesite();
    createSubordinateHost();
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2000-09-02T00:00:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2002", "MSG", "Object has no transfer history"));
    assertThatLogoutSucceeds();
    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request_1_year.xml")
        .atTime("2001-01-01T00:00:00Z")
        .hasResponse("domain_transfer_response_1_year.xml");
    // Verify that the lastTransferTime now reflects the superordinate domain's transfer.
    assertThatCommand("host_info.xml", ImmutableMap.of("HOSTNAME", "ns3.fakesite.example"))
        .atTime("2001-01-07T00:00:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer.xml",
            ImmutableMap.of("TRDATE", "2001-01-06T00:00:00.000Z"));
    assertThatCommand("domain_create_secondsite.xml")
        .atTime("2001-01-08T00:00:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "secondsite.example",
                "CRDATE", "2001-01-08T00:00:00.0Z",
                "EXDATE", "2003-01-08T00:00:00.0Z"));
    // Update the host to be subordinate to a different domain by renaming it to
    // ns3.secondsite.example
    assertThatCommand(
            "host_update_rename_only.xml",
            ImmutableMap.of("oldName", "ns3.fakesite.example", "newName", "ns3.secondsite.example"))
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_response.xml");
    // The last transfer time on the host should still be what it was from the transfer.
    assertThatCommand("host_info.xml", ImmutableMap.of("HOSTNAME", "ns3.secondsite.example"))
        .atTime("2003-01-07T00:00:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer_and_update.xml",
            ImmutableMap.of(
                "HOSTNAME", "ns3.secondsite.example",
                "TRDATE", "2001-01-06T00:00:00.000Z"));
    assertThatLogoutSucceeds();
  }

  /**
   * Tests that when a superordinate domain of a host is transferred, and then the host is updated
   * to be external, that the host retains the transfer time of the first superordinate domain.
   */
  @Test
  public void testSuccess_lastTransferTime_superordinateDomainTransferThenHostUpdateToExternal()
      throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Create fakesite.example with subordinate host ns3.fakesite.example
    createFakesite();
    createSubordinateHost();
    assertThatCommand("domain_transfer_query_fakesite.xml")
        .atTime("2000-09-02T00:00:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2002", "MSG", "Object has no transfer history"));
    assertThatLogoutSucceeds();
    // Request a transfer of the domain to the second registrar.
    assertThatLoginSucceeds("TheRegistrar", "password2");
    assertThatCommand("domain_transfer_request_1_year.xml")
        .atTime("2001-01-01T00:00:00Z")
        .hasResponse("domain_transfer_response_1_year.xml");
    // Verify that the lastTransferTime now reflects the superordinate domain's transfer.
    assertThatCommand("host_info_fakesite.xml")
        .atTime("2001-01-07T00:00:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer.xml",
            ImmutableMap.of("TRDATE", "2001-01-06T00:00:00.000Z"));
    // Update the host to be external by renaming it to ns3.notarealsite.external
    assertThatCommand(
            "host_update_rename_and_remove_addresses.xml",
            ImmutableMap.of(
                "oldName", "ns3.fakesite.example",
                "newName", "ns3.notarealsite.external"))
        .atTime("2002-05-30T01:01:00Z")
        .hasResponse("generic_success_response.xml");
    // The last transfer time on the host should still be what it was from the transfer.
    assertThatCommand("host_info.xml", ImmutableMap.of("HOSTNAME", "ns3.notarealsite.external"))
        .atTime("2001-01-07T00:00:00Z")
        .hasResponse(
            "host_info_response_fakesite_post_transfer_and_update_no_addresses.xml",
            ImmutableMap.of(
                "HOSTNAME", "ns3.notarealsite.external",
                "TRDATE", "2001-01-06T00:00:00.000Z"));
    assertThatLogoutSucceeds();
  }

  @Test
  public void testSuccess_multipartTldsWithSharedSuffixes() throws Exception {
    createTlds("bar.foo.tld", "foo.tld");

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00.000Z"));

    // Create domain example.bar.foo.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml",
            ImmutableMap.of("DOMAIN", "example.bar.foo.tld"))
        .atTime("2000-06-01T00:02:00.001Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.bar.foo.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Create domain example.foo.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.foo.tld"))
        .atTime("2000-06-01T00:02:00.002Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.foo.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00.003Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    assertThatLogoutSucceeds();
  }

  @Test
  public void testSuccess_multipartTldsWithSharedPrefixes() throws Exception {
    createTld("tld.foo");

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    createContacts(DateTime.parse("2000-06-01T00:00:00.000Z"));

    // Create domain example.tld.foo
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld.foo"))
        .atTime("2000-06-01T00:02:00.001Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld.foo",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00.002Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    assertThatLogoutSucceeds();
  }

  /**
   * Test a full launch of start-date sunrise.
   *
   * We show that we can't create during pre-delegation, can only create with an encoded mark during
   * start-date sunrise - which we can then delete "as normal" (no need for a signed mark or
   * anything for delete), and then use "regular" create during general-availability.
   */
  @Test
  public void testDomainCreation_startDateSunriseFull() throws Exception {
    // The signed mark is valid between 2013 and 2017
    DateTime sunriseDate = DateTime.parse("2014-09-08T09:09:09Z");
    DateTime gaDate = sunriseDate.plusDays(30);
    createTld(
        "example",
        ImmutableSortedMap.of(
            START_OF_TIME, PREDELEGATION,
            sunriseDate, START_DATE_SUNRISE,
            gaDate, GENERAL_AVAILABILITY));

    assertThatLogin("NewRegistrar", "foo-BAR2")
        .atTime(sunriseDate.minusDays(3))
        .hasResponse("generic_success_response.xml");

    createContactsAndHosts();

    // During pre-delegation, any create should fail both with and without mark
    assertThatCommand("domain_create_sunrise_encoded_mark.xml")
        .atTime(sunriseDate.minusDays(2))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2002",
                "MSG", "The current registry phase does not allow for general registrations"));

    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "general.example"))
        .atTime(sunriseDate.minusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2002",
                "MSG", "The current registry phase does not allow for general registrations"));

    // During sunrise, verify that the launch phase must be set to sunrise.
    assertThatCommand("domain_create_start_date_sunrise_encoded_mark_wrong_phase.xml")
        .atTime(sunriseDate)
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2306",
                "MSG",
                    "Declared launch extension phase does not match the current registry phase"));

    // During sunrise, create with mark will succeed but without will fail.
    // We also test we can delete without a mark.
    assertThatCommand("domain_create_sunrise_encoded_mark.xml")
        .atTime(sunriseDate.plusDays(1))
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "test-validate.example",
                "CRDATE", "2014-09-09T09:09:09Z",
                "EXDATE", "2015-09-09T09:09:09Z"));

    assertThatCommand("domain_delete.xml", ImmutableMap.of("DOMAIN", "test-validate.example"))
        .atTime(sunriseDate.plusDays(1).plusMinutes(1))
        .hasResponse("generic_success_response.xml");

    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml",
            ImmutableMap.of("DOMAIN", "general.example"))
        .atTime(sunriseDate.plusDays(2))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2002",
                "MSG", "The current registry phase requires a signed mark for registrations"));

    // During general availability, sunrise creates will fail but regular creates succeed
    assertThatCommand("domain_create_sunrise_encoded_mark.xml")
        .atTime(gaDate.plusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2306",
                "MSG",
                    "Declared launch extension phase does not match the current registry phase"));

    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml",
            ImmutableMap.of("DOMAIN", "general.example"))
        .atTime(gaDate.plusDays(2))
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "general.example",
                "CRDATE", "2014-10-10T09:09:09Z",
                "EXDATE", "2016-10-10T09:09:09Z"));

    assertThatLogoutSucceeds();
  }

  /** Test that missing type= argument on launch create works in start-date sunrise. */
  @Test
  public void testDomainCreation_startDateSunrise_noType() throws Exception {
    // The signed mark is valid between 2013 and 2017
    DateTime sunriseDate = DateTime.parse("2014-09-08T09:09:09Z");
    DateTime gaDate = sunriseDate.plusDays(30);
    createTld(
        "example",
        ImmutableSortedMap.of(
            START_OF_TIME, PREDELEGATION,
            sunriseDate, START_DATE_SUNRISE,
            gaDate, GENERAL_AVAILABILITY));

    assertThatLogin("NewRegistrar", "foo-BAR2")
        .atTime(sunriseDate.minusDays(3))
        .hasResponse("generic_success_response.xml");

    createContactsAndHosts();

    // During start-date sunrise, create with mark will succeed but without will fail.
    // We also test we can delete without a mark.
    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "test-validate.example"))
        .atTime(sunriseDate.plusDays(1))
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2303",
                "MSG", "The domain with given ID (test-validate.example) doesn't exist."));

    assertThatCommand("domain_create_start_date_sunrise_encoded_mark_no_type.xml")
        .atTime(sunriseDate.plusDays(1).plusMinutes(1))
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "test-validate.example",
                "CRDATE", "2014-09-09T09:10:09Z",
                "EXDATE", "2015-09-09T09:10:09Z"));

    assertThatCommand("domain_info.xml", ImmutableMap.of("DOMAIN", "test-validate.example"))
        .atTime(sunriseDate.plusDays(1).plusMinutes(2))
        .hasResponse(
            "domain_info_response_ok_wildcard.xml",
            ImmutableMap.of(
                "DOMAIN", "test-validate.example",
                "CRDATE", "2014-09-09T09:10:09Z",
                "EXDATE", "2015-09-09T09:10:09Z"));

    assertThatLogoutSucceeds();
  }
}
