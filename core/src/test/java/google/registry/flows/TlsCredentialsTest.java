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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.joda.time.DateTimeZone.UTC;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import google.registry.model.registrar.Registrar;
import google.registry.request.HttpException.BadRequestException;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ShardableTestCase;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TlsCredentials}. */
@RunWith(JUnit4.class)
public final class TlsCredentialsTest extends ShardableTestCase {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Test
  public void testProvideClientCertificateHash() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-SSL-Certificate")).thenReturn("data");
    assertThat(TlsCredentials.EppTlsModule.provideClientCertificateHash(req)).isEqualTo("data");
  }

  @Test
  public void testProvideClientCertificateHash_missing() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> TlsCredentials.EppTlsModule.provideClientCertificateHash(req));
    assertThat(thrown).hasMessageThat().contains("Missing header: X-SSL-Certificate");
  }

  @Test
  public void test_validateCertificate_canBeConfiguredToBypassCertHashes() throws Exception {
    TlsCredentials tls = new TlsCredentials(false, "certHash", Optional.of("192.168.1.1"));
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientCertificate(null, DateTime.now(UTC))
            .setFailoverClientCertificate(null, DateTime.now(UTC))
            .build());
    // This would throw a RegistrarCertificateNotConfiguredException if cert hashes wren't bypassed.
    tls.validateCertificate(Registrar.loadByClientId("TheRegistrar").get());
  }
}
