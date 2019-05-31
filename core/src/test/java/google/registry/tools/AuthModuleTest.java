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
import static google.registry.testing.JUnitBackports.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.AbstractDataStoreFactory;
import com.google.api.client.util.store.DataStore;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AuthModule}. */
@RunWith(JUnit4.class)
public class AuthModuleTest {

  private static final String CLIENT_ID = "UNITTEST-CLIENT-ID";
  private static final String CLIENT_SECRET = "UNITTEST-CLIENT-SECRET";
  private static final String ACCESS_TOKEN = "FakeAccessToken";
  private static final String REFRESH_TOKEN = "FakeReFreshToken";

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  private final Credential fakeCredential =
      new Credential.Builder(
              new Credential.AccessMethod() {
                @Override
                public void intercept(HttpRequest request, String accessToken) {}

                @Override
                public String getAccessTokenFromRequest(HttpRequest request) {
                  return ACCESS_TOKEN;
                }
              })
          // We need to set the following fields because they are checked when
          // Credential#setRefreshToken is called. However they are not actually persisted in the
          // DataStore and not actually used in tests.
          .setJsonFactory(new JacksonFactory())
          .setTransport(new NetHttpTransport())
          .setTokenServerUrl(new GenericUrl("https://accounts.google.com/o/oauth2/token"))
          .setClientAuthentication(new ClientParametersAuthentication(CLIENT_ID, CLIENT_SECRET))
          .build();

  @SuppressWarnings("unchecked")
  DataStore<StoredCredential> dataStore = mock(DataStore.class);

  class FakeDataStoreFactory extends AbstractDataStoreFactory {
    @Override
    protected <V extends Serializable> DataStore<V> createDataStore(String id) {
      @SuppressWarnings("unchecked")
      DataStore<V> result = (DataStore<V>) dataStore;
      return result;
    }
  }

  @Before
  public void setUp() throws Exception {
    fakeCredential.setRefreshToken(REFRESH_TOKEN);
    when(dataStore.get(CLIENT_ID + " scope1")).thenReturn(new StoredCredential(fakeCredential));
  }

  @Test
  public void test_clientScopeQualifier() {
    String simpleQualifier =
        AuthModule.provideClientScopeQualifier("client-id", ImmutableList.of("foo", "bar"));

    // If we change the way we encode client id and scopes, this assertion will break.  That's
    // probably ok and you can just change the text.  The things you have to be aware of are:
    // - Names in the new encoding should have a low risk of collision with the old encoding.
    // - Changing the encoding will force all OAuth users of the nomulus tool to do a new login
    //   (existing credentials will not be used).
    assertThat(simpleQualifier).isEqualTo("client-id bar foo");

    // Verify order independence.
    assertThat(simpleQualifier)
        .isEqualTo(
            AuthModule.provideClientScopeQualifier("client-id", ImmutableList.of("bar", "foo")));

    // Verify changing client id produces a different value.
    assertThat(simpleQualifier)
        .isNotEqualTo(
            AuthModule.provideClientScopeQualifier("new-client", ImmutableList.of("bar", "foo")));

    // Verify that adding/deleting/modifying scopes produces a different value.
    assertThat(simpleQualifier)
        .isNotEqualTo(
            AuthModule.provideClientScopeQualifier(
                "client id", ImmutableList.of("bar", "foo", "baz")));
    assertThat(simpleQualifier)
        .isNotEqualTo(
            AuthModule.provideClientScopeQualifier("client id", ImmutableList.of("barx", "foo")));
    assertThat(simpleQualifier)
        .isNotEqualTo(
            AuthModule.provideClientScopeQualifier("client id", ImmutableList.of("bar", "foox")));
    assertThat(simpleQualifier)
        .isNotEqualTo(AuthModule.provideClientScopeQualifier("client id", ImmutableList.of("bar")));

    // Verify that delimiting works.
    assertThat(simpleQualifier)
        .isNotEqualTo(
            AuthModule.provideClientScopeQualifier("client-id", ImmutableList.of("barf", "oo")));
    assertThat(simpleQualifier)
        .isNotEqualTo(
            AuthModule.provideClientScopeQualifier("client-idb", ImmutableList.of("ar", "foo")));
  }

  private Credential getCredential() {
    // Reconstruct the entire dependency graph, injecting FakeDatastoreFactory and credential
    // parameters.
    JacksonFactory jsonFactory = new JacksonFactory();
    GoogleClientSecrets clientSecrets = getSecrets();
    ImmutableList<String> scopes = ImmutableList.of("scope1");
    return AuthModule.provideCredential(
        AuthModule.provideAuthorizationCodeFlow(
            jsonFactory, clientSecrets, scopes, new FakeDataStoreFactory()),
        AuthModule.provideClientScopeQualifier(AuthModule.provideClientId(clientSecrets), scopes));
  }

  private GoogleClientSecrets getSecrets() {
    return new GoogleClientSecrets()
        .setInstalled(
            AuthModule.provideDefaultInstalledDetails()
                .setClientId(CLIENT_ID)
                .setClientSecret(CLIENT_SECRET));
  }

  @Test
  public void test_provideLocalCredentialJson() {
    String credentialJson =
        AuthModule.provideLocalCredentialJson(this::getSecrets, this::getCredential, null);
    Map<String, String> jsonMap =
        new Gson().fromJson(credentialJson, new TypeToken<Map<String, String>>() {}.getType());
    assertThat(jsonMap.get("type")).isEqualTo("authorized_user");
    assertThat(jsonMap.get("client_secret")).isEqualTo(CLIENT_SECRET);
    assertThat(jsonMap.get("client_id")).isEqualTo(CLIENT_ID);
    assertThat(jsonMap.get("refresh_token")).isEqualTo(REFRESH_TOKEN);
  }

  @Test
  public void test_provideExternalCredentialJson() throws Exception {
    File credentialFile = folder.newFile("credential.json");
    Files.write(credentialFile.toPath(), "{some_field: some_value}".getBytes(UTF_8));
    String credentialJson =
        AuthModule.provideLocalCredentialJson(
            this::getSecrets, this::getCredential, credentialFile.getCanonicalPath());
    assertThat(credentialJson).isEqualTo("{some_field: some_value}");
  }

  @Test
  public void test_provideCredential() {
    Credential cred = getCredential();
    assertThat(cred.getAccessToken()).isEqualTo(fakeCredential.getAccessToken());
    assertThat(cred.getRefreshToken()).isEqualTo(fakeCredential.getRefreshToken());
    assertThat(cred.getExpirationTimeMilliseconds())
        .isEqualTo(fakeCredential.getExpirationTimeMilliseconds());
  }

  @Test
  public void test_provideCredential_notStored() throws IOException {
    when(dataStore.get(CLIENT_ID + " scope1")).thenReturn(null);
    assertThrows(AuthModule.LoginRequiredException.class, this::getCredential);
  }
}
