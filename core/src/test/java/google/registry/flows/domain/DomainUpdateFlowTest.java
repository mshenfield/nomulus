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

package google.registry.flows.domain;

import static com.google.common.collect.Sets.union;
import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.assertBillingEvents;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistActiveSubordinateHost;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig;
import google.registry.flows.EppException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppRequestSource;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.AddRemoveSameValueException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.ResourceFlowUtils.StatusNotClientSettableException;
import google.registry.flows.domain.DomainFlowUtils.DuplicateContactForRoleException;
import google.registry.flows.domain.DomainFlowUtils.EmptySecDnsUpdateException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForNonFreeOperationException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourcesDoNotExistException;
import google.registry.flows.domain.DomainFlowUtils.MaxSigLifeChangeNotSupportedException;
import google.registry.flows.domain.DomainFlowUtils.MissingAdminContactException;
import google.registry.flows.domain.DomainFlowUtils.MissingContactTypeException;
import google.registry.flows.domain.DomainFlowUtils.MissingRegistrantException;
import google.registry.flows.domain.DomainFlowUtils.MissingTechnicalContactException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotAllowedForTldException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotSpecifiedForTldWithNameserverWhitelistException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.RegistrantNotAllowedException;
import google.registry.flows.domain.DomainFlowUtils.SecDnsAllUsageException;
import google.registry.flows.domain.DomainFlowUtils.TooManyDsRecordsException;
import google.registry.flows.domain.DomainFlowUtils.TooManyNameserversException;
import google.registry.flows.domain.DomainFlowUtils.UrgentAttributeNotSupportedException;
import google.registry.flows.exceptions.OnlyToolCanPassMetadataException;
import google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import org.joda.money.Money;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainUpdateFlow}. */
public class DomainUpdateFlowTest extends ResourceFlowTestCase<DomainUpdateFlow, DomainBase> {

  private static final DelegationSignerData SOME_DSDATA =
      DelegationSignerData.create(1, 2, 3, base16().decode("0123"));
  private static final ImmutableMap<String, String> OTHER_DSDATA_TEMPLATE_MAP =
      ImmutableMap.of(
          "KEY_TAG", "12346",
          "ALG", "3",
          "DIGEST_TYPE", "1",
          "DIGEST", "38EC35D5B3A34B44C39B");

  ContactResource sh8013Contact;
  ContactResource mak21Contact;
  ContactResource unusedContact;
  HistoryEntry historyEntryDomainCreate;

  @Before
  public void initDomainTest() {
    createTld("tld");
    // Note that "domain_update.xml" tests adding and removing the same contact type.
    setEppInput("domain_update.xml");
  }

  private void persistReferencedEntities() {
    for (int i = 1; i <= 14; ++i) {
      persistActiveHost(String.format("ns%d.example.foo", i));
    }
    sh8013Contact = persistActiveContact("sh8013");
    mak21Contact = persistActiveContact("mak21");
    unusedContact = persistActiveContact("unused");
  }

  private DomainBase persistDomainWithRegistrant() throws Exception {
    HostResource host =
        loadByForeignKey(HostResource.class, "ns1.example.foo", clock.nowUtc()).get();
    DomainBase domain =
        persistResource(
            newDomainBase(getUniqueIdFromCommand())
                .asBuilder()
                .setContacts(
                    ImmutableSet.of(
                        DesignatedContact.create(Type.TECH, Key.create(mak21Contact)),
                        DesignatedContact.create(Type.ADMIN, Key.create(mak21Contact)),
                        DesignatedContact.create(Type.BILLING, Key.create(mak21Contact))))
                .setRegistrant(Key.create(mak21Contact))
                .setNameservers(ImmutableSet.of(Key.create(host)))
                .build());
    historyEntryDomainCreate =
        persistResource(
            new HistoryEntry.Builder()
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setParent(domain)
                .build());
    clock.advanceOneMilli();
    return domain;
  }

  private DomainBase persistDomain() throws Exception {
    HostResource host =
        loadByForeignKey(HostResource.class, "ns1.example.foo", clock.nowUtc()).get();
    DomainBase domain =
        persistResource(
            newDomainBase(getUniqueIdFromCommand())
                .asBuilder()
                .setContacts(
                    ImmutableSet.of(
                        DesignatedContact.create(Type.TECH, Key.create(sh8013Contact)),
                        DesignatedContact.create(Type.ADMIN, Key.create(unusedContact))))
                .setNameservers(ImmutableSet.of(Key.create(host)))
                .build());
    historyEntryDomainCreate =
        persistResource(
            new HistoryEntry.Builder()
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setParent(domain)
                .build());
    clock.advanceOneMilli();
    return domain;
  }

  private void doSuccessfulTest() throws Exception {
    doSuccessfulTest("generic_success_response.xml");
  }

  private void doSuccessfulTest(String expectedXmlFilename) throws Exception {
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile(expectedXmlFilename));
    // Check that the domain was updated. These values came from the xml.
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasStatusValue(StatusValue.CLIENT_HOLD)
        .and()
        .hasAuthInfoPwd("2BARfoo")
        .and()
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_CREATE, HistoryEntry.Type.DOMAIN_UPDATE)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateClientId("TheRegistrar");
    assertNoBillingEvents();
    assertDnsTasksEnqueued("example.tld");
  }

  @Test
  public void testDryRun() throws Exception {
    persistReferencedEntities();
    persistDomain();
    dryRunFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  public void testSuccess() throws Exception {
    persistReferencedEntities();
    persistDomain();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("domain_update_no_cltrid.xml");
    persistReferencedEntities();
    persistDomain();
    doSuccessfulTest("generic_success_response_no_cltrid.xml");
  }

  @Test
  public void testSuccess_cachingDisabled() throws Exception {
    boolean origIsCachingEnabled = RegistryConfig.isEppResourceCachingEnabled();
    try {
      RegistryConfig.overrideIsEppResourceCachingEnabledForTesting(false);
      persistReferencedEntities();
      persistDomain();
      doSuccessfulTest();
    } finally {
      RegistryConfig.overrideIsEppResourceCachingEnabledForTesting(origIsCachingEnabled);
    }
  }

  @Test
  public void testFailure_emptyRegistrant() throws Exception {
    setEppInput("domain_update_empty_registrant.xml");
    persistReferencedEntities();
    persistDomain();
    MissingRegistrantException thrown =
        assertThrows(MissingRegistrantException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void modifyDomainToHave13Nameservers() throws Exception {
    ImmutableSet.Builder<Key<HostResource>> nameservers = new ImmutableSet.Builder<>();
    for (int i = 1; i < 15; i++) {
      if (i != 2) { // Skip 2 since that's the one that the tests will add.
        nameservers.add(
            Key.create(
                loadByForeignKey(
                        HostResource.class, String.format("ns%d.example.foo", i), clock.nowUtc())
                    .get()));
      }
    }
    persistResource(
        reloadResourceByForeignKey().asBuilder().setNameservers(nameservers.build()).build());
    clock.advanceOneMilli();
  }

  @Test
  public void testSuccess_maxNumberOfNameservers() throws Exception {
    persistReferencedEntities();
    persistDomain();
    // Modify domain to have 13 nameservers. We will then remove one and add one in the test.
    modifyDomainToHave13Nameservers();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_addAndRemoveLargeNumberOfNameserversAndContacts() throws Exception {
    persistReferencedEntities();
    persistDomain();
    setEppInput("domain_update_max_everything.xml");
    // Create 26 hosts and 8 contacts. Start the domain with half of them.
    ImmutableSet.Builder<Key<HostResource>> nameservers = new ImmutableSet.Builder<>();
    for (int i = 0; i < 26; i++) {
      HostResource host = persistActiveHost(String.format("max_test_%d.example.tld", i));
      if (i < 13) {
        nameservers.add(Key.create(host));
      }
    }
    ImmutableList.Builder<DesignatedContact> contactsBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < 8; i++) {
      contactsBuilder.add(
          DesignatedContact.create(
              DesignatedContact.Type.values()[i % 4],
              Key.create(persistActiveContact(String.format("max_test_%d", i)))));
    }
    ImmutableList<DesignatedContact> contacts = contactsBuilder.build();
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setNameservers(nameservers.build())
            .setContacts(ImmutableSet.copyOf(contacts.subList(0, 3)))
            .setRegistrant(contacts.get(3).getContactKey())
            .build());
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    DomainBase domain = reloadResourceByForeignKey();
    assertAboutDomains()
        .that(domain)
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_CREATE, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(domain.getNameservers()).hasSize(13);
    // getContacts does not return contacts of type REGISTRANT, so check these separately.
    assertThat(domain.getContacts()).hasSize(3);
    assertThat(ofy().load().key(domain.getRegistrant()).now().getContactId())
        .isEqualTo("max_test_7");
    assertNoBillingEvents();
    assertDnsTasksEnqueued("example.tld");
  }

  @Test
  public void testSuccess_metadata() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_update_metadata.xml");
    persistReferencedEntities();
    persistDomain();
    runFlow();
    DomainBase domain = reloadResourceByForeignKey();
    assertAboutDomains()
        .that(domain)
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_CREATE, HistoryEntry.Type.DOMAIN_UPDATE);
    assertAboutHistoryEntries()
        .that(getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE))
        .hasMetadataReason("domain-update-test")
        .and()
        .hasMetadataRequestedByRegistrar(true);
  }

  @Test
  public void testSuccess_metadataNotFromTool() throws Exception {
    setEppInput("domain_update_metadata.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(OnlyToolCanPassMetadataException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_removeContact() throws Exception {
    setEppInput("domain_update_remove_contact.xml");
    persistReferencedEntities();
    persistDomain();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_addAndRemoveSubordinateHostNameservers() throws Exception {
    // Test that operations involving subordinate hosts as nameservers do not change the subordinate
    // host relationship itself.
    setEppInput("domain_update_subordinate_hosts.xml");
    persistReferencedEntities();
    DomainBase domain = persistDomain();
    persistActiveSubordinateHost("ns1.example.tld", domain);
    HostResource addedHost = persistActiveSubordinateHost("ns2.example.tld", domain);
    persistResource(
        domain
            .asBuilder()
            .addSubordinateHost("ns1.example.tld")
            .addSubordinateHost("ns2.example.tld")
            .setNameservers(
                ImmutableSet.of(
                    Key.create(
                        loadByForeignKey(HostResource.class, "ns1.example.tld", clock.nowUtc())
                            .get())))
            .build());
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    domain = reloadResourceByForeignKey();
    assertThat(domain.getNameservers()).containsExactly(Key.create(addedHost));
    assertThat(domain.getSubordinateHosts()).containsExactly("ns1.example.tld", "ns2.example.tld");
    HostResource existingHost =
        loadByForeignKey(HostResource.class, "ns1.example.tld", clock.nowUtc()).get();
    addedHost = loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc()).get();
    assertThat(existingHost.getSuperordinateDomain()).isEqualTo(Key.create(domain));
    assertThat(addedHost.getSuperordinateDomain()).isEqualTo(Key.create(domain));
  }

  @Test
  public void testSuccess_registrantMovedToTechContact() throws Exception {
    setEppInput("domain_update_registrant_to_tech.xml");
    persistReferencedEntities();
    ContactResource sh8013 =
        loadByForeignKey(ContactResource.class, "sh8013", clock.nowUtc()).get();
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setRegistrant(Key.create(sh8013))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  public void testSuccess_multipleReferencesToSameContactRemoved() throws Exception {
    setEppInput("domain_update_remove_multiple_contacts.xml");
    persistReferencedEntities();
    ContactResource sh8013 =
        loadByForeignKey(ContactResource.class, "sh8013", clock.nowUtc()).get();
    Key<ContactResource> sh8013Key = Key.create(sh8013);
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setRegistrant(sh8013Key)
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(Type.ADMIN, sh8013Key),
                    DesignatedContact.create(Type.BILLING, sh8013Key),
                    DesignatedContact.create(Type.TECH, sh8013Key)))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  public void testSuccess_removeClientUpdateProhibited() throws Exception {
    persistReferencedEntities();
    persistResource(
        persistDomain()
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    clock.advanceOneMilli();
    runFlow();
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .doesNotHaveStatusValue(StatusValue.CLIENT_UPDATE_PROHIBITED);
  }

  private void doSecDnsSuccessfulTest(
      String xmlFilename,
      ImmutableSet<DelegationSignerData> originalDsData,
      ImmutableSet<DelegationSignerData> expectedDsData)
      throws Exception {
    doSecDnsSuccessfulTest(xmlFilename, originalDsData, expectedDsData, OTHER_DSDATA_TEMPLATE_MAP);
  }

  private void doSecDnsSuccessfulTest(
      String xmlFilename,
      ImmutableSet<DelegationSignerData> originalDsData,
      ImmutableSet<DelegationSignerData> expectedDsData,
      ImmutableMap<String, String> substitutions)
      throws Exception {
    setEppInput(xmlFilename, substitutions);
    persistResource(
        newDomainBase(getUniqueIdFromCommand()).asBuilder().setDsData(originalDsData).build());
    assertTransactionalFlow(true);
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    DomainBase resource = reloadResourceByForeignKey();
    assertAboutDomains()
        .that(resource)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(resource.getDsData()).isEqualTo(expectedDsData);
    assertDnsTasksEnqueued("example.tld");
  }

  @Test
  public void testSuccess_secDnsAdd() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        null,
        ImmutableSet.of(
            DelegationSignerData.create(12346, 3, 1, base16().decode("38EC35D5B3A34B44C39B"))),
        ImmutableMap.of(
            "KEY_TAG", "12346", "ALG", "3", "DIGEST_TYPE", "1", "DIGEST", "38EC35D5B3A34B44C39B"));
  }

  @Test
  public void testSuccess_secDnsAddPreservesExisting() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(
            SOME_DSDATA,
            DelegationSignerData.create(12346, 3, 1, base16().decode("38EC35D5B3A34B44C39B"))),
        ImmutableMap.of(
            "KEY_TAG", "12346", "ALG", "3", "DIGEST_TYPE", "1", "DIGEST", "38EC35D5B3A34B44C39B"));
  }

  @Test
  public void testSuccess_secDnsAddSameDoesNothing() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(SOME_DSDATA),
        ImmutableMap.of("KEY_TAG", "1", "ALG", "2", "DIGEST_TYPE", "3", "DIGEST", "0123"));
  }

  @Test
  public void testSuccess_secDnsAddOnlyKeyTagRemainsSame() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(
            SOME_DSDATA, DelegationSignerData.create(1, 8, 4, base16().decode("4567"))),
        ImmutableMap.of("KEY_TAG", "1", "ALG", "8", "DIGEST_TYPE", "4", "DIGEST", "4567"));
  }

  // Changing any of the four fields in DelegationSignerData should result in a new object
  @Test
  public void testSuccess_secDnsAddOnlyChangeKeyTag() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(
            SOME_DSDATA, DelegationSignerData.create(12346, 2, 3, base16().decode("0123"))),
        ImmutableMap.of("KEY_TAG", "12346", "ALG", "2", "DIGEST_TYPE", "3", "DIGEST", "0123"));
  }

  @Test
  public void testSuccess_secDnsAddOnlyChangeAlgorithm() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(SOME_DSDATA, DelegationSignerData.create(1, 8, 3, base16().decode("0123"))),
        ImmutableMap.of("KEY_TAG", "1", "ALG", "8", "DIGEST_TYPE", "3", "DIGEST", "0123"));
  }

  @Test
  public void testSuccess_secDnsAddOnlyChangeDigestType() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(SOME_DSDATA, DelegationSignerData.create(1, 2, 4, base16().decode("0123"))),
        ImmutableMap.of("KEY_TAG", "1", "ALG", "2", "DIGEST_TYPE", "4", "DIGEST", "0123"));
  }

  @Test
  public void testSuccess_secDnsAddOnlyChangeDigest() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        ImmutableSet.of(SOME_DSDATA),
        ImmutableSet.of(SOME_DSDATA, DelegationSignerData.create(1, 2, 3, base16().decode("4567"))),
        ImmutableMap.of("KEY_TAG", "1", "ALG", "2", "DIGEST_TYPE", "3", "DIGEST", "4567"));
  }

  @Test
  public void testSuccess_secDnsAddToMaxRecords() throws Exception {
    ImmutableSet.Builder<DelegationSignerData> builder = new ImmutableSet.Builder<>();
    for (int i = 0; i < 7; ++i) {
      builder.add(DelegationSignerData.create(i, 2, 3, new byte[] {0, 1, 2}));
    }
    ImmutableSet<DelegationSignerData> commonDsData = builder.build();

    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add.xml",
        commonDsData,
        ImmutableSet.copyOf(
            union(
                commonDsData,
                ImmutableSet.of(
                    DelegationSignerData.create(
                        12346, 3, 1, base16().decode("38EC35D5B3A34B44C39B"))))));
  }

  @Test
  public void testSuccess_secDnsRemove() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_rem.xml",
        ImmutableSet.of(
            SOME_DSDATA,
            DelegationSignerData.create(12346, 3, 1, base16().decode("38EC35D5B3A34B44C39B"))),
        ImmutableSet.of(SOME_DSDATA));
  }

  @Test
  public void testSuccess_secDnsRemoveAll() throws Exception {
    // As an aside, this test also validates that it's ok to set the 'urgent' attribute to false.
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_rem_all.xml",
        ImmutableSet.of(
            SOME_DSDATA,
            DelegationSignerData.create(12346, 3, 1, base16().decode("38EC35D5B3A34B44C39B"))),
        ImmutableSet.of());
  }

  @Test
  public void testSuccess_secDnsAddRemove() throws Exception {
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add_rem.xml",
        ImmutableSet.of(
            SOME_DSDATA,
            DelegationSignerData.create(12345, 3, 1, base16().decode("38EC35D5B3A34B33C99B"))),
        ImmutableSet.of(
            SOME_DSDATA,
            DelegationSignerData.create(12346, 3, 1, base16().decode("38EC35D5B3A34B44C39B"))));
  }

  @Test
  public void testSuccess_secDnsAddRemoveToMaxRecords() throws Exception {
    ImmutableSet.Builder<DelegationSignerData> builder = new ImmutableSet.Builder<>();
    for (int i = 0; i < 7; ++i) {
      builder.add(DelegationSignerData.create(i, 2, 3, new byte[] {0, 1, 2}));
    }
    ImmutableSet<DelegationSignerData> commonDsData = builder.build();

    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add_rem.xml",
        ImmutableSet.copyOf(
            union(
                commonDsData,
                ImmutableSet.of(
                    DelegationSignerData.create(
                        12345, 3, 1, base16().decode("38EC35D5B3A34B33C99B"))))),
        ImmutableSet.copyOf(
            union(
                commonDsData,
                ImmutableSet.of(
                    DelegationSignerData.create(
                        12346, 3, 1, base16().decode("38EC35D5B3A34B44C39B"))))));
  }

  @Test
  public void testSuccess_secDnsAddRemoveSame() throws Exception {
    // Adding and removing the same dsData is a no-op because removes are processed first.
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_add_rem_same.xml",
        ImmutableSet.of(
            SOME_DSDATA,
            DelegationSignerData.create(12345, 3, 1, base16().decode("38EC35D5B3A34B33C99B"))),
        ImmutableSet.of(
            SOME_DSDATA,
            DelegationSignerData.create(12345, 3, 1, base16().decode("38EC35D5B3A34B33C99B"))));
  }

  @Test
  public void testSuccess_secDnsRemoveAlreadyNotThere() throws Exception {
    // Removing a dsData that isn't there is a no-op.
    doSecDnsSuccessfulTest(
        "domain_update_dsdata_rem.xml", ImmutableSet.of(SOME_DSDATA), ImmutableSet.of(SOME_DSDATA));
  }

  public void doServerStatusBillingTest(String xmlFilename, boolean isBillable) throws Exception {
    setEppInput(xmlFilename);
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));

    if (isBillable) {
      assertBillingEvents(
          new BillingEvent.OneTime.Builder()
              .setReason(Reason.SERVER_STATUS)
              .setTargetId("example.tld")
              .setClientId("TheRegistrar")
              .setCost(Money.of(USD, 19))
              .setEventTime(clock.nowUtc())
              .setBillingTime(clock.nowUtc())
              .setParent(
                  getOnlyHistoryEntryOfType(
                      reloadResourceByForeignKey(), HistoryEntry.Type.DOMAIN_UPDATE))
              .build());
    } else {
      assertNoBillingEvents();
    }
  }

  @Test
  public void testSuccess_addServerStatusBillingEvent() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    persistReferencedEntities();
    persistDomain();
    doServerStatusBillingTest("domain_update_add_server_status.xml", true);
  }

  @Test
  public void testSuccess_noBillingOnPreExistingServerStatus() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    DomainBase addStatusDomain = persistActiveDomain(getUniqueIdFromCommand());
    persistResource(
        addStatusDomain.asBuilder().addStatusValue(StatusValue.SERVER_RENEW_PROHIBITED).build());
    doServerStatusBillingTest("domain_update_add_server_status.xml", false);
  }

  @Test
  public void testSuccess_removeServerStatusBillingEvent() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    persistReferencedEntities();
    DomainBase removeStatusDomain = persistDomain();
    persistResource(
        removeStatusDomain.asBuilder().addStatusValue(StatusValue.SERVER_RENEW_PROHIBITED).build());
    doServerStatusBillingTest("domain_update_remove_server_status.xml", true);
  }

  @Test
  public void testSuccess_changeServerStatusBillingEvent() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    persistReferencedEntities();
    DomainBase changeStatusDomain = persistDomain();
    persistResource(
        changeStatusDomain.asBuilder().addStatusValue(StatusValue.SERVER_RENEW_PROHIBITED).build());
    doServerStatusBillingTest("domain_update_change_server_status.xml", true);
  }

  @Test
  public void testSuccess_noBillingEventOnNonServerStatusChange() throws Exception {
    persistActiveDomain(getUniqueIdFromCommand());
    doServerStatusBillingTest("domain_update_add_non_server_status.xml", false);
  }

  @Test
  public void testSuccess_noBillingEventOnServerHoldStatusChange() throws Exception {
    persistActiveDomain(getUniqueIdFromCommand());
    doServerStatusBillingTest("domain_update_add_server_hold_status.xml", false);
  }

  @Test
  public void testSuccess_noBillingEventOnServerStatusChangeNotFromRegistrar() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    persistActiveDomain(getUniqueIdFromCommand());
    doServerStatusBillingTest("domain_update_add_server_status_non_registrar.xml", false);
  }

  @Test
  public void testSuccess_superuserClientUpdateProhibited() throws Exception {
    setEppInput("domain_update_add_server_hold_status.xml");
    persistReferencedEntities();
    persistResource(
        persistActiveDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasStatusValue(StatusValue.CLIENT_UPDATE_PROHIBITED)
        .and()
        .hasStatusValue(StatusValue.SERVER_HOLD);
  }

  private void doSecDnsFailingTest(
      Class<? extends EppException> expectedException, String xmlFilename) throws Exception {
    setEppInput(xmlFilename);
    persistReferencedEntities();
    persistActiveDomain(getUniqueIdFromCommand());
    EppException thrown = assertThrows(expectedException, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_secDnsAllCannotBeFalse() throws Exception {
    doSecDnsFailingTest(SecDnsAllUsageException.class, "domain_update_dsdata_rem_all_false.xml");
  }

  @Test
  public void testFailure_secDnsEmptyNotAllowed() throws Exception {
    doSecDnsFailingTest(EmptySecDnsUpdateException.class, "domain_update_dsdata_empty.xml");
  }

  @Test
  public void testFailure_secDnsUrgentNotSupported() throws Exception {
    doSecDnsFailingTest(
        UrgentAttributeNotSupportedException.class, "domain_update_dsdata_urgent.xml");
  }

  @Test
  public void testFailure_secDnsChangeNotSupported() throws Exception {
    doSecDnsFailingTest(
        MaxSigLifeChangeNotSupportedException.class, "domain_update_maxsiglife.xml");
  }

  @Test
  public void testFailure_secDnsTooManyDsRecords() throws Exception {
    ImmutableSet.Builder<DelegationSignerData> builder = new ImmutableSet.Builder<>();
    for (int i = 0; i < 8; ++i) {
      builder.add(DelegationSignerData.create(i, 2, 3, new byte[] {0, 1, 2}));
    }

    setEppInput("domain_update_dsdata_add.xml", OTHER_DSDATA_TEMPLATE_MAP);
    persistResource(
        newDomainBase(getUniqueIdFromCommand()).asBuilder().setDsData(builder.build()).build());
    EppException thrown = assertThrows(TooManyDsRecordsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_tooManyNameservers() throws Exception {
    setEppInput("domain_update_add_nameserver.xml");
    persistReferencedEntities();
    persistDomain();
    // Modify domain so it has 13 nameservers. We will then try to add one in the test.
    modifyDomainToHave13Nameservers();
    EppException thrown = assertThrows(TooManyNameserversException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_wrongExtension() throws Exception {
    setEppInput("domain_update_wrong_extension.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(UnimplementedExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_neverExisted() throws Exception {
    persistReferencedEntities();
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  public void testFailure_existedButWasDeleted() throws Exception {
    persistReferencedEntities();
    persistDeletedDomain(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  public void testFailure_missingHost() throws Exception {
    persistActiveHost("ns1.example.foo");
    persistActiveContact("sh8013");
    persistActiveContact("mak21");
    persistActiveDomain(getUniqueIdFromCommand());
    LinkedResourcesDoNotExistException thrown =
        assertThrows(LinkedResourcesDoNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(ns2.example.foo)");
  }

  @Test
  public void testFailure_missingContact() throws Exception {
    persistActiveHost("ns1.example.foo");
    persistActiveHost("ns2.example.foo");
    persistActiveContact("mak21");
    persistActiveDomain(getUniqueIdFromCommand());
    LinkedResourcesDoNotExistException thrown =
        assertThrows(LinkedResourcesDoNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(sh8013)");
  }

  @Test
  public void testFailure_addingDuplicateContact() throws Exception {
    persistReferencedEntities();
    persistActiveContact("foo");
    persistDomain();
    // Add a tech contact to the persisted entity, which should cause the flow to fail when it tries
    // to add "mak21" as a second tech contact.
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setContacts(
                DesignatedContact.create(
                    Type.TECH,
                    Key.create(
                        loadByForeignKey(ContactResource.class, "foo", clock.nowUtc()).get())))
            .build());
    EppException thrown = assertThrows(DuplicateContactForRoleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    assertThat(thrown.getResult().getMsg())
        .isEqualTo(
            "More than one contact for a given role is not allowed: "
                + "role [tech] has contacts [foo, mak21]");
  }

  @Test
  public void testFailure_statusValueNotClientSettable() throws Exception {
    setEppInput("domain_update_prohibited_status.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(StatusNotClientSettableException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserStatusValueNotClientSettable() throws Exception {
    setEppInput("domain_update_prohibited_status.xml");
    persistReferencedEntities();
    persistDomain();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  public void testFailure_serverUpdateProhibited_prohibitsNonSuperuserUpdates() throws Exception {
    persistReferencedEntities();
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .addStatusValue(SERVER_UPDATE_PROHIBITED)
            .build());
    Exception e = assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(e).hasMessageThat().containsMatch("serverUpdateProhibited");
  }

  @Test
  public void testSuccess_serverUpdateProhibited_allowsSuperuserUpdates() throws Exception {
    persistReferencedEntities();
    persistResource(persistDomain().asBuilder().addStatusValue(SERVER_UPDATE_PROHIBITED).build());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  public void testFailure_serverUpdateProhibited_notSettableWithoutSuperuser() throws Exception {
    setEppInput("domain_update_add_registry_lock.xml");
    persistReferencedEntities();
    persistDomain();
    Exception e = assertThrows(StatusNotClientSettableException.class, this::runFlow);
    assertThat(e).hasMessageThat().containsMatch("serverUpdateProhibited");
  }

  @Test
  public void testSuccess_serverUpdateProhibited_isSettableWithSuperuser() throws Exception {
    setEppInput("domain_update_add_registry_lock.xml");
    persistReferencedEntities();
    persistDomain();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  public void testFailure_clientUpdateProhibited() throws Exception {
    createTld("com");
    setEppInput("domain_update_authinfo.xml");
    persistReferencedEntities();
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    EppException thrown =
        assertThrows(ResourceHasClientUpdateProhibitedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_serverUpdateProhibited() throws Exception {
    persistReferencedEntities();
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(SERVER_UPDATE_PROHIBITED))
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("serverUpdateProhibited");
  }

  @Test
  public void testFailure_pendingDelete() throws Exception {
    persistReferencedEntities();
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(1))
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("pendingDelete");
  }

  @Test
  public void testFailure_duplicateContactInCommand() throws Exception {
    setEppInput("domain_update_duplicate_contact.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(DuplicateContactForRoleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_multipleDuplicateContactInCommand() throws Exception {
    setEppInput("domain_update_multiple_duplicate_contacts.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(DuplicateContactForRoleException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "More than one contact for a given role is not allowed: "
                + "role [billing] has contacts [mak21, sh8013], "
                + "role [tech] has contacts [mak21, sh8013]");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_missingContactType() throws Exception {
    // We need to test for missing type, but not for invalid - the schema enforces that for us.
    setEppInput("domain_update_missing_contact_type.xml");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(MissingContactTypeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_unauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistReferencedEntities();
    persistDomain();
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  public void testFailure_notAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(NotAuthorizedForTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistReferencedEntities();
    persistDomain();
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  public void testFailure_sameNameserverAddedAndRemoved() throws Exception {
    setEppInput("domain_update_add_remove_same_host.xml");
    persistReferencedEntities();
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setNameservers(
                ImmutableSet.of(
                    Key.create(
                        loadByForeignKey(HostResource.class, "ns1.example.foo", clock.nowUtc())
                            .get())))
            .build());
    EppException thrown = assertThrows(AddRemoveSameValueException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_sameContactAddedAndRemoved() throws Exception {
    setEppInput("domain_update_add_remove_same_contact.xml");
    persistReferencedEntities();
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setContacts(
                DesignatedContact.create(
                    Type.TECH,
                    Key.create(
                        loadByForeignKey(ContactResource.class, "sh8013", clock.nowUtc()).get())))
            .build());
    EppException thrown = assertThrows(AddRemoveSameValueException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_removeAdmin() throws Exception {
    setEppInput("domain_update_remove_admin.xml");
    persistReferencedEntities();
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(Type.ADMIN, Key.create(sh8013Contact)),
                    DesignatedContact.create(Type.TECH, Key.create(sh8013Contact))))
            .build());
    EppException thrown = assertThrows(MissingAdminContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_removeTech() throws Exception {
    setEppInput("domain_update_remove_tech.xml");
    persistReferencedEntities();
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setContacts(
                ImmutableSet.of(
                    DesignatedContact.create(Type.ADMIN, Key.create(sh8013Contact)),
                    DesignatedContact.create(Type.TECH, Key.create(sh8013Contact))))
            .build());
    EppException thrown = assertThrows(MissingTechnicalContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_addPendingDeleteContact() throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistActiveHost("ns1.example.foo");
    persistActiveHost("ns2.example.foo");
    persistActiveContact("sh8013");
    persistResource(
        loadByForeignKey(ContactResource.class, "mak21", clock.nowUtc())
            .get()
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    clock.advanceOneMilli();
    LinkedResourceInPendingDeleteProhibitsOperationException thrown =
        assertThrows(LinkedResourceInPendingDeleteProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("mak21");
  }

  @Test
  public void testFailure_addPendingDeleteHost() throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistActiveHost("ns1.example.foo");
    persistActiveContact("mak21");
    persistActiveContact("sh8013");
    persistResource(
        loadByForeignKey(HostResource.class, "ns2.example.foo", clock.nowUtc())
            .get()
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    clock.advanceOneMilli();
    LinkedResourceInPendingDeleteProhibitsOperationException thrown =
        assertThrows(LinkedResourceInPendingDeleteProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns2.example.foo");
  }

  @Test
  public void testFailure_newRegistrantNotWhitelisted() throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("contact1234"))
            .build());
    clock.advanceOneMilli();
    EppException thrown = assertThrows(RegistrantNotAllowedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_addedNameserverDisallowedInTld()
      throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.foo"))
            .build());
    NameserversNotAllowedForTldException thrown =
        assertThrows(NameserversNotAllowedForTldException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns2.example.foo");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_newNameserverWhitelisted() throws Exception {
    setEppInput("domain_update_add_nameserver.xml");
    persistReferencedEntities();
    persistDomain();
    // No registrant is given but both nameserver and registrant whitelist exist.
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("sh8013"))
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.foo", "ns2.example.foo"))
            .build());
    assertThat(reloadResourceByForeignKey().getNameservers())
        .doesNotContain(
            Key.create(
                loadByForeignKey(HostResource.class, "ns2.example.foo", clock.nowUtc()).get()));
    runFlow();
    assertThat(reloadResourceByForeignKey().getNameservers())
        .contains(
            Key.create(
                loadByForeignKey(HostResource.class, "ns2.example.foo", clock.nowUtc()).get()));
  }

  @Test
  public void testSuccess_changeRegistrantWhitelisted() throws Exception {
    setEppInput("domain_update_registrant.xml");
    persistReferencedEntities();
    persistDomain();
    // Only changes registrant, with both nameserver and registrant whitelist on the TLD.
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("sh8013"))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.foo"))
            .build());
    runFlow();
    assertThat(ofy().load().key(reloadResourceByForeignKey().getRegistrant()).now().getContactId())
        .isEqualTo("sh8013");
  }

  @Test
  public void testSuccess_changeContactsAndRegistrant() throws Exception {
    setEppInput("domain_update_contacts_and_registrant.xml");
    persistReferencedEntities();
    persistDomainWithRegistrant();

    reloadResourceByForeignKey()
        .getContacts()
        .forEach(
            contact -> {
              assertThat(ofy().load().key(contact.getContactKey()).now().getContactId())
                  .isEqualTo("mak21");
            });
    assertThat(ofy().load().key(reloadResourceByForeignKey().getRegistrant()).now().getContactId())
        .isEqualTo("mak21");

    runFlow();

    reloadResourceByForeignKey()
        .getContacts()
        .forEach(
            contact -> {
              assertThat(ofy().load().key(contact.getContactKey()).now().getContactId())
                  .isEqualTo("sh8013");
            });
    assertThat(ofy().load().key(reloadResourceByForeignKey().getRegistrant()).now().getContactId())
        .isEqualTo("sh8013");
  }

  @Test
  public void testSuccess_nameserverAndRegistrantWhitelisted() throws Exception {
    persistReferencedEntities();
    persistDomain();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("sh8013"))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns2.example.foo"))
            .build());
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_tldWithNameserverWhitelist_removeNameserver() throws Exception {
    setEppInput("domain_update_remove_nameserver.xml");
    persistReferencedEntities();
    persistDomain();
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .addNameserver(
                Key.create(
                    loadByForeignKey(HostResource.class, "ns2.example.foo", clock.nowUtc()).get()))
            .build());
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.foo", "ns2.example.foo"))
            .build());
    assertThat(reloadResourceByForeignKey().getNameservers())
        .contains(
            Key.create(
                loadByForeignKey(HostResource.class, "ns1.example.foo", clock.nowUtc()).get()));
    clock.advanceOneMilli();
    runFlow();
    assertThat(reloadResourceByForeignKey().getNameservers())
        .doesNotContain(
            Key.create(
                loadByForeignKey(HostResource.class, "ns1.example.foo", clock.nowUtc()).get()));
  }

  @Test
  public void testFailure_tldWithNameserverWhitelist_removeLastNameserver() throws Exception {
    persistReferencedEntities();
    persistDomain();
    setEppInput("domain_update_remove_nameserver.xml");
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.foo"))
            .build());
    EppException thrown =
        assertThrows(
            NameserversNotSpecifiedForTldWithNameserverWhitelistException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_domainCreateNotRestricted_doNotApplyServerProhibitedStatusCodes()
      throws Exception {
    persistReferencedEntities();
    persistDomain();
    doSuccessfulTest();
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
  }

  @Test
  public void testFailure_freePremium_wrongFee() throws Exception {
    setEppInput("domain_update_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistReferencedEntities();
    persistDomain();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  // This test should throw an exception, because the fee extension is required when the fee is not
  // zero.
  @Test
  public void testFailure_missingFeeOnNonFreeUpdate() throws Exception {
    setEppInput("domain_update_wildcard.xml", ImmutableMap.of("DOMAIN", "non-free-update.tld"));
    persistReferencedEntities();
    persistDomain();
    EppException thrown =
        assertThrows(FeesRequiredForNonFreeOperationException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testIcannActivityReportField_getsLogged() throws Exception {
    persistReferencedEntities();
    persistDomain();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-update");
    assertTldsFieldLogged("tld");
  }
}
