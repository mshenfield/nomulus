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

package google.registry.ui.server.registrar;

import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.registrar.Registrar.loadByClientId;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.JUnitBackports.assertThrows;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import google.registry.config.RegistryEnvironment;
import google.registry.model.registry.Registry;
import google.registry.request.Action.Method;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.UserAuthInfo;
import google.registry.security.XsrfTokenManager;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.SendEmailUtils;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.util.Optional;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletRequest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class ConsoleOteSetupActionTest {

  @Rule
  public final AppEngineRule appEngineRule = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private final FakeResponse response = new FakeResponse();
  private final ConsoleOteSetupAction action = new ConsoleOteSetupAction();
  private final User user = new User("marla.singer@example.com", "gmail.com", "12345");

  @Mock HttpServletRequest request;
  @Mock SendEmailService emailService;

  @Before
  public void setUp() throws Exception {
    persistPremiumList("default_sandbox_list", "sandbox,USD 1000");

    action.req = request;
    action.method = Method.GET;
    action.response = response;
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of("unused", AuthenticatedRegistrarAccessor.Role.ADMIN));
    action.userService = UserServiceFactory.getUserService();
    action.xsrfTokenManager = new XsrfTokenManager(new FakeClock(), action.userService);
    action.authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    action.registryEnvironment = RegistryEnvironment.UNITTEST;
    action.sendEmailUtils =
        new SendEmailUtils(
            new InternetAddress("outgoing@registry.example"),
            "UnitTest Registry",
            ImmutableList.of("notification@test.example", "notification2@test.example"),
            emailService);
    action.logoFilename = "logo.png";
    action.productName = "Nomulus";
    action.clientId = Optional.empty();
    action.email = Optional.empty();
    action.analyticsConfig = ImmutableMap.of("googleAnalyticsId", "sampleId");

    action.optionalPassword = Optional.empty();
    action.passwordGenerator = new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");
  }

  @Test
  public void testNoUser_redirect() {
    when(request.getRequestURI()).thenReturn("/test");
    action.authResult = AuthResult.NOT_AUTHENTICATED;
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_MOVED_TEMPORARILY);
    assertThat(response.getHeaders().get(LOCATION)).isEqualTo("/_ah/login?continue=%2Ftest");
  }

  @Test
  public void testGet_authorized() {
    action.run();
    assertThat(response.getPayload()).contains("<h1>Setup OT&E</h1>");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
  }

  @Test
  public void testGet_authorized_onProduction() {
    action.registryEnvironment = RegistryEnvironment.PRODUCTION;
    assertThrows(IllegalStateException.class, action::run);
  }

  @Test
  public void testGet_unauthorized() {
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    action.run();
    assertThat(response.getPayload()).contains("<h1>You need permission</h1>");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
  }

  @Test
  public void testPost_authorized() {
    action.clientId = Optional.of("myclientid");
    action.email = Optional.of("contact@registry.example");
    action.method = Method.POST;
    action.run();

    // We just check some samples to make sure OteAccountBuilder was called successfully. We aren't
    // checking that all the entities are there or that they have the correct values.
    assertThat(loadByClientId("myclientid-3")).isPresent();
    assertThat(Registry.get("myclientid-ga")).isNotNull();
    assertThat(loadByClientId("myclientid-5").get().getContacts().asList().get(0).getEmailAddress())
        .isEqualTo("contact@registry.example");
    assertThat(response.getPayload())
        .contains("<h1>OT&E successfully created for registrar myclientid!</h1>");
    ArgumentCaptor<EmailMessage> contentCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emailService).sendEmail(contentCaptor.capture());
    EmailMessage emailMessage = contentCaptor.getValue();
    assertThat(emailMessage.subject())
        .isEqualTo("OT&E for registrar myclientid created in unittest");
    assertThat(emailMessage.body())
        .isEqualTo(
            ""
                + "The following entities were created in unittest by TestUserId:\n"
                + "   Registrar myclientid-1 with access to TLD myclientid-sunrise\n"
                + "   Registrar myclientid-3 with access to TLD myclientid-ga\n"
                + "   Registrar myclientid-4 with access to TLD myclientid-ga\n"
                + "   Registrar myclientid-5 with access to TLD myclientid-eap\n"
                + "Gave user contact@registry.example web access to these Registrars\n");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
  }

  @Test
  public void testPost_authorized_setPassword() {
    action.clientId = Optional.of("myclientid");
    action.email = Optional.of("contact@registry.example");
    action.optionalPassword = Optional.of("SomePassword");
    action.method = Method.POST;
    action.run();

    // We just check some samples to make sure OteAccountBuilder was called successfully. We aren't
    // checking that all the entities are there or that they have the correct values.
    assertThat(loadByClientId("myclientid-4").get().testPassword("SomePassword"))
        .isTrue();
    assertThat(response.getPayload())
        .contains("<h1>OT&E successfully created for registrar myclientid!</h1>");
    assertThat(response.getPayload())
        .contains("SomePassword");
  }

  @Test
  public void testPost_unauthorized() {
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    action.clientId = Optional.of("myclientid");
    action.email = Optional.of("contact@registry.example");
    action.method = Method.POST;
    action.run();
    assertThat(response.getPayload()).contains("<h1>You need permission</h1>");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
  }
}
