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

package google.registry.rde;

import static com.google.common.base.Strings.repeat;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.keyring.api.PgpHelper.KeyRequirement.ENCRYPT;
import static google.registry.testing.JUnitBackports.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assume.assumeThat;

import com.google.common.io.ByteStreams;
import google.registry.keyring.api.Keyring;
import google.registry.testing.BouncyCastleProviderRule;
import google.registry.testing.FakeKeyringModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

/** Unit tests for {@link Ghostryde}. */
@RunWith(Theories.class)
@SuppressWarnings("resource")
public class GhostrydeTest {

  @Rule
  public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  @DataPoints
  public static Content[] contents = new Content[] {
    new Content("hi"),
    new Content("(◕‿◕)"),
    new Content(repeat("Fanatics have their dreams, wherewith they weave.\n", 1000)),
    new Content("\0yolo"),
    new Content(""),
  };

  @Theory
  public void testSimpleApi(Content content) throws Exception {
    Keyring keyring = new FakeKeyringModule().get();
    byte[] data = content.get().getBytes(UTF_8);
    PGPPublicKey publicKey = keyring.getRdeStagingEncryptionKey();
    PGPPrivateKey privateKey = keyring.getRdeStagingDecryptionKey();

    byte[] blob = Ghostryde.encode(data, publicKey);
    byte[] result = Ghostryde.decode(blob, privateKey);

    assertThat(new String(result, UTF_8)).isEqualTo(content.get());
  }

  @Theory
  public void testStreamingApi(Content content) throws Exception {
    Keyring keyring = new FakeKeyringModule().get();
    byte[] data = content.get().getBytes(UTF_8);
    PGPPublicKey publicKey = keyring.getRdeStagingEncryptionKey();
    PGPPrivateKey privateKey = keyring.getRdeStagingDecryptionKey();

    ByteArrayOutputStream bsOut = new ByteArrayOutputStream();
    try (OutputStream encoder = Ghostryde.encoder(bsOut, publicKey)) {
      encoder.write(data);
    }

    ByteArrayInputStream bsIn = new ByteArrayInputStream(bsOut.toByteArray());
    bsOut.reset();
    try (InputStream decoder = Ghostryde.decoder(bsIn, privateKey)) {
      ByteStreams.copy(decoder, bsOut);
    }
    assertThat(bsOut.size()).isEqualTo(data.length);

    assertThat(new String(bsOut.toByteArray(), UTF_8)).isEqualTo(content.get());
  }

  @Theory
  public void testStreamingApi_withSize(Content content) throws Exception {
    Keyring keyring = new FakeKeyringModule().get();
    byte[] data = content.get().getBytes(UTF_8);
    PGPPublicKey publicKey = keyring.getRdeStagingEncryptionKey();

    ByteArrayOutputStream bsOut = new ByteArrayOutputStream();
    ByteArrayOutputStream lenOut = new ByteArrayOutputStream();
    try (OutputStream encoder = Ghostryde.encoder(bsOut, publicKey, lenOut)) {
      encoder.write(data);
    }

    assertThat(Ghostryde.readLength(new ByteArrayInputStream(lenOut.toByteArray())))
        .isEqualTo(data.length);
    assertThat(Long.parseLong(new String(lenOut.toByteArray(), UTF_8))).isEqualTo(data.length);
  }

  @Theory
  public void testFailure_tampering(Content content) throws Exception {
    assumeThat(content.get().length(), is(greaterThan(100)));

    Keyring keyring = new FakeKeyringModule().get();
    PGPPublicKey publicKey = keyring.getRdeStagingEncryptionKey();
    PGPPrivateKey privateKey = keyring.getRdeStagingDecryptionKey();
    byte[] data = content.get().getBytes(UTF_8);

    ByteArrayOutputStream bsOut = new ByteArrayOutputStream();
    try (OutputStream encoder = Ghostryde.encoder(bsOut, publicKey)) {
      encoder.write(data);
    }

    byte[] ciphertext = bsOut.toByteArray();
    korruption(ciphertext, ciphertext.length - 1);

    ByteArrayInputStream bsIn = new ByteArrayInputStream(ciphertext);
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> {
              try (InputStream decoder = Ghostryde.decoder(bsIn, privateKey)) {
                ByteStreams.copy(decoder, ByteStreams.nullOutputStream());
              }
            });
    assertThat(thrown).hasMessageThat().contains("tampering");
  }

  @Theory
  public void testFailure_corruption(Content content) throws Exception {
    assumeThat(content.get().length(), is(lessThan(100)));

    Keyring keyring = new FakeKeyringModule().get();
    PGPPublicKey publicKey = keyring.getRdeStagingEncryptionKey();
    PGPPrivateKey privateKey = keyring.getRdeStagingDecryptionKey();
    byte[] data = content.get().getBytes(UTF_8);

    ByteArrayOutputStream bsOut = new ByteArrayOutputStream();
    try (OutputStream encoder = Ghostryde.encoder(bsOut, publicKey)) {
      encoder.write(data);
    }

    byte[] ciphertext = bsOut.toByteArray();
    korruption(ciphertext, ciphertext.length / 2);

    ByteArrayInputStream bsIn = new ByteArrayInputStream(ciphertext);
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> {
              try (InputStream decoder = Ghostryde.decoder(bsIn, privateKey)) {
                ByteStreams.copy(decoder, ByteStreams.nullOutputStream());
              }
            });
    assertThat(thrown).hasCauseThat().isInstanceOf(PGPException.class);
  }

  @Test
  public void testFullEncryption() throws Exception {
    // Check that the full encryption hasn't changed. All the other tests check that encrypting and
    // decrypting results in the original data, but not whether the encryption method has changed.
    FakeKeyringModule keyringModule = new FakeKeyringModule();
    PGPKeyPair dsa = keyringModule.get("rde-unittest@registry.test", ENCRYPT);
    PGPPrivateKey privateKey = dsa.getPrivateKey();

    // Encryption is inconsistent because it uses a random state. But decryption is consistent!
    //
    // If the encryption has legitimately changed - uncomment the following code, and copy the new
    // encryptedInputBase64 from the test error:
    //
    // assertThat(
    //         Base64.getMimeEncoder()
    //             .encodeToString(
    //                 Ghostryde.encode("Some data!!!111!!!".getBytes(UTF_8), dsa.getPublicKey())))
    //     .isEqualTo("expect error");

    String encryptedInputBase64 =
        "    hQEMA6WcEy81iaHVAQgAnn9bS6IOCTW2uZnITPWH8zIYr6K7YJslv38c4YU5eQqVhHC5PN0NhM2l\n"
            + "    i89U3lUE6gp3DdEEbTbugwXCHWyRL4fYTlpiHZjBn2vZdSS21EAG+q1XuTaD8DTjkC2G060/sW6i\n"
            + "    0gSIkksqgubbSVZTxHEqh92tv35KCqiYc52hjKZIIGI8FHhpJOtDa3bhMMad8nrMy3vbv5LiYNh5\n"
            + "    j3DUCFhskU8Ldi1vBfXIonqUNLBrD/R471VVJyQ3NoGQTVUF9uXLoy+2dL0oBLc1Avj1XNP5PQ08\n"
            + "    MWlqmezkLdY0oHnQqTHYhYDxRo/Sw7xO1GLwWR11rcx/IAJloJbKSHTFeNJUAcKFnKvPDwBk3nnr\n"
            + "    uR505HtOj/tZDT5weVjhrlnmWXzaBRmYASy6PXZu6KzTbPUQTf4JeeJWdyw7glLMr2WPdMVPGZ8e\n"
            + "    gcFAjSJZjZlqohZyBUpP\n";

    byte[] result =
        Ghostryde.decode(Base64.getMimeDecoder().decode(encryptedInputBase64), privateKey);

    assertThat(new String(result, UTF_8)).isEqualTo("Some data!!!111!!!");
  }

  @Test
  public void testFailure_keyMismatch() throws Exception {
    FakeKeyringModule keyringModule = new FakeKeyringModule();
    byte[] data = "Fanatics have their dreams, wherewith they weave.".getBytes(UTF_8);
    PGPKeyPair dsa1 = keyringModule.get("rde-unittest@registry.test", ENCRYPT);
    PGPKeyPair dsa2 = keyringModule.get("rde-unittest-dsa@registry.test", ENCRYPT);
    PGPPublicKey publicKey = dsa1.getPublicKey();
    PGPPrivateKey privateKey = dsa2.getPrivateKey();

    ByteArrayOutputStream bsOut = new ByteArrayOutputStream();
    try (OutputStream encoder = Ghostryde.encoder(bsOut, publicKey)) {
      encoder.write(data);
    }

    ByteArrayInputStream bsIn = new ByteArrayInputStream(bsOut.toByteArray());
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> {
              try (InputStream decoder = Ghostryde.decoder(bsIn, privateKey)) {
                ByteStreams.copy(decoder, ByteStreams.nullOutputStream());
              }
            });
    assertThat(thrown).hasCauseThat().isInstanceOf(PGPException.class);
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .contains(
            "Message was encrypted for keyids [a59c132f3589a1d5] but ours is c9598c84ec70b9fd");
  }

  @Test
  @Ignore("Intentionally corrupting a PGP key is easier said than done >_>")
  public void testFailure_keyCorruption() throws Exception {
    FakeKeyringModule keyringModule = new FakeKeyringModule();
    byte[] data = "Fanatics have their dreams, wherewith they weave.".getBytes(UTF_8);
    PGPKeyPair rsa = keyringModule.get("rde-unittest@registry.test", ENCRYPT);
    PGPPublicKey publicKey = rsa.getPublicKey();

    // Make the last byte of the private key off by one. muahahaha
    byte[] keyData = rsa.getPrivateKey().getPrivateKeyDataPacket().getEncoded();
    keyData[keyData.length - 1]++;
    PGPPrivateKey privateKey = new PGPPrivateKey(
        rsa.getKeyID(),
        rsa.getPrivateKey().getPublicKeyPacket(),
        rsa.getPrivateKey().getPrivateKeyDataPacket());

    ByteArrayOutputStream bsOut = new ByteArrayOutputStream();
    try (OutputStream encoder = Ghostryde.encoder(bsOut, publicKey)) {
      encoder.write(data);
    }

    ByteArrayInputStream bsIn = new ByteArrayInputStream(bsOut.toByteArray());
    try (InputStream decoder = Ghostryde.decoder(bsIn, privateKey)) {
      ByteStreams.copy(decoder, ByteStreams.nullOutputStream());
    }
  }

  private void korruption(byte[] bytes, int position) {
    if (bytes[position] == 23) {
      bytes[position] = 7;
    } else {
      bytes[position] = 23;
    }
  }

  private static class Content {
    private final String value;

    Content(String value) {
      this.value = value;
    }

    String get() {
      return value;
    }
  }
}
