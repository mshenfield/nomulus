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

import google.registry.keyring.api.Keyring;
import google.registry.rde.Ghostryde;
import google.registry.testing.BouncyCastleProviderRule;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.InjectRule;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link GhostrydeCommand}. */
public class GhostrydeCommandTest extends CommandTestCase<GhostrydeCommand> {

  private static final byte[] SONG_BY_CHRISTINA_ROSSETTI = (""
      + "When I am dead, my dearest,       \n"
      + "  Sing no sad songs for me;       \n"
      + "Plant thou no roses at my head,   \n"
      + "  Nor shady cypress tree:         \n"
      + "Be the green grass above me       \n"
      + "  With showers and dewdrops wet;  \n"
      + "And if thou wilt, remember,       \n"
      + "  And if thou wilt, forget.       \n"
      + "                                  \n"
      + "I shall not see the shadows,      \n"
      + "  I shall not feel the rain;      \n"
      + "I shall not hear the nightingale  \n"
      + "  Sing on, as if in pain:         \n"
      + "And dreaming through the twilight \n"
      + "  That doth not rise nor set,     \n"
      + "Haply I may remember,             \n"
      + "  And haply may forget.           \n").getBytes(UTF_8);

  @Rule
  public final InjectRule inject = new InjectRule();

  @Rule
  public final BouncyCastleProviderRule bouncy = new BouncyCastleProviderRule();

  private Keyring keyring;
  private PrintStream orgStdout;

  @Before
  public void before() {
    keyring = new FakeKeyringModule().get();
    command.rdeStagingDecryptionKey = keyring::getRdeStagingDecryptionKey;
    command.rdeStagingEncryptionKey = keyring::getRdeStagingEncryptionKey;
    orgStdout = System.out;
  }

  @After
  public void after() {
    System.setOut(orgStdout);
  }

  @Test
  public void testParameters_cantSpecifyBothEncryptAndDecrypt() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--encrypt", "--decrypt"));
    assertThat(thrown).hasMessageThat().isEqualTo("Please specify either --encrypt or --decrypt");
  }

  @Test
  public void testParameters_mustSpecifyOneOfEncryptOrDecrypt() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--input=" + tmpDir.newFile(), "--output=" + tmpDir.newFile()));
    assertThat(thrown).hasMessageThat().isEqualTo("Please specify either --encrypt or --decrypt");
  }

  @Test
  public void testEncrypt_outputPathIsRequired() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--encrypt", "--input=" + tmpDir.newFile()));
    assertThat(thrown).hasMessageThat().isEqualTo("--output path is required in --encrypt mode");
  }

  @Test
  public void testEncrypt_outputIsAFile_writesToFile() throws Exception {
    Path inFile = tmpDir.newFile("atrain.txt").toPath();
    Path outFile = tmpDir.newFile().toPath();
    Files.write(inFile, SONG_BY_CHRISTINA_ROSSETTI);
    runCommand("--encrypt", "--input=" + inFile, "--output=" + outFile);
    byte[] decoded =
        Ghostryde.decode(Files.readAllBytes(outFile), keyring.getRdeStagingDecryptionKey());
    assertThat(decoded).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }

  @Test
  public void testEncrypt_outputIsADirectory_appendsGhostrydeExtension() throws Exception {
    Path inFile = tmpDir.newFile("atrain.txt").toPath();
    Path outDir = tmpDir.newFolder().toPath();
    Files.write(inFile, SONG_BY_CHRISTINA_ROSSETTI);
    runCommand("--encrypt", "--input=" + inFile, "--output=" + outDir);
    Path lenOutFile = outDir.resolve("atrain.txt.length");
    assertThat(Ghostryde.readLength(Files.newInputStream(lenOutFile)))
        .isEqualTo(SONG_BY_CHRISTINA_ROSSETTI.length);
    Path outFile = outDir.resolve("atrain.txt.ghostryde");
    byte[] decoded =
        Ghostryde.decode(Files.readAllBytes(outFile), keyring.getRdeStagingDecryptionKey());
    assertThat(decoded).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }

  @Test
  public void testDecrypt_outputIsAFile_writesToFile() throws Exception {
    Path inFile = tmpDir.newFile().toPath();
    Path outFile = tmpDir.newFile().toPath();
    Files.write(
        inFile, Ghostryde.encode(SONG_BY_CHRISTINA_ROSSETTI, keyring.getRdeStagingEncryptionKey()));
    runCommand("--decrypt", "--input=" + inFile, "--output=" + outFile);
    assertThat(Files.readAllBytes(outFile)).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }

  @Test
  public void testDecrypt_outputIsADirectory_AppendsDecryptExtension() throws Exception {
    Path inFile = tmpDir.newFolder().toPath().resolve("atrain.ghostryde");
    Path outDir = tmpDir.newFolder().toPath();
    Files.write(
        inFile, Ghostryde.encode(SONG_BY_CHRISTINA_ROSSETTI, keyring.getRdeStagingEncryptionKey()));
    runCommand("--decrypt", "--input=" + inFile, "--output=" + outDir);
    Path outFile = outDir.resolve("atrain.ghostryde.decrypt");
    assertThat(Files.readAllBytes(outFile)).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }

  @Test
  public void testDecrypt_outputIsStdOut() throws Exception {
    Path inFile = tmpDir.newFolder().toPath().resolve("atrain.ghostryde");
    Files.write(
        inFile, Ghostryde.encode(SONG_BY_CHRISTINA_ROSSETTI, keyring.getRdeStagingEncryptionKey()));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out));
    runCommand("--decrypt", "--input=" + inFile);
    assertThat(out.toByteArray()).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }
}
