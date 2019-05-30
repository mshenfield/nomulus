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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMultimap;
import google.registry.testing.AppEngineAdminApiHelper;
import google.registry.testing.InjectRule;
import google.registry.util.AppEngineServiceUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

/** Unit tests for {@link SetNumInstancesCommand}. */
public class SetNumInstancesCommandTest extends CommandTestCase<SetNumInstancesCommand> {

  @Rule public final InjectRule inject = new InjectRule();

  @Mock AppEngineServiceUtils appEngineServiceUtils;

  private final String projectId = "domain-registry-test";

  @Before
  public void before() {
    command = new SetNumInstancesCommand();
    command.appEngineServiceUtils = appEngineServiceUtils;
    command.projectId = projectId;
  }

  @Test
  public void test_missingService_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--versions=version", "--num_instances=5"));
    assertThat(thrown).hasMessageThat().contains("Service must be specified");
  }

  @Test
  public void test_emptyService_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--services=", "--versions=version", "--num_instances=5"));
    assertThat(thrown).hasMessageThat().contains("Invalid service ''");
  }

  @Test
  public void test_invalidService_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--services=INVALID,DEFAULT", "--versions=version", "--num_instances=5"));
    assertThat(thrown).hasMessageThat().contains("Invalid service 'INVALID'");
  }

  @Test
  public void test_missingVersion_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--services=DEFAULT", "--num_instances=5"));
    assertThat(thrown).hasMessageThat().contains("Version must be specified");
  }

  @Test
  public void test_emptyVersion_throwsException() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommand("--services=DEFAULT", "--num_instances=5", "--versions"));
    assertThat(thrown).hasMessageThat().contains("Expected a value after parameter --versions");
  }

  @Test
  public void test_missingNumInstances_throwsException() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class, () -> runCommand("--services=DEFAULT", "--versions=version"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("The following option is required: -n, --num_instances");
  }

  @Test
  public void test_invalidNumInstances_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--services=DEFAULT", "--versions=version", "--num_instances=-5"));
    assertThat(thrown).hasMessageThat().contains("Number of instances must be greater than zero");
  }

  @Test
  public void test_versionNotNullWhenSettingAllNonLiveVersions_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--services=DEFAULT", "--versions=version", "--num_instances=-5"));
    assertThat(thrown).hasMessageThat().contains("Number of instances must be greater than zero");
  }

  @Test
  public void test_settingNonManualScalingVersions_throwsException() {
    command.appengine =
        new AppEngineAdminApiHelper.Builder()
            .setAppId(projectId)
            .setManualScalingVersionsMap(ImmutableMultimap.of("default", "version1"))
            .build()
            .getAppengine();

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--non_live_versions=true",
                    "--services=DEFAULT",
                    "--versions=version",
                    "--num_instances=10"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("--versions cannot be set if --non_live_versions is set");
  }

  @Test
  public void test_validParameters_succeeds() throws Exception {
    command.appengine =
        new AppEngineAdminApiHelper.Builder()
            .setAppId(projectId)
            .setManualScalingVersionsMap(ImmutableMultimap.of("default", "version"))
            .build()
            .getAppengine();

    runCommand("--services=DEFAULT", "--versions=version", "--num_instances=10");
    verify(appEngineServiceUtils, times(1)).setNumInstances("default", "version", 10L);
  }

  @Test
  public void test_validShortParametersAndLowercaseService_succeeds() throws Exception {
    command.appengine =
        new AppEngineAdminApiHelper.Builder()
            .setAppId(projectId)
            .setManualScalingVersionsMap(ImmutableMultimap.of("default", "version"))
            .build()
            .getAppengine();

    runCommand("-s default", "-v version", "-n 10");
    verify(appEngineServiceUtils, times(1)).setNumInstances("default", "version", 10L);
  }

  @Test
  public void test_settingMultipleServicesAndVersions_succeeds() throws Exception {
    command.appengine =
        new AppEngineAdminApiHelper.Builder()
            .setAppId(projectId)
            .setManualScalingVersionsMap(
                ImmutableMultimap.of(
                    "default", "version1",
                    "default", "version2",
                    "backend", "version1",
                    "backend", "version2"))
            .build()
            .getAppengine();

    runCommand("--services=DEFAULT,BACKEND", "--versions=version1,version2", "--num_instances=10");
    verify(appEngineServiceUtils, times(1)).setNumInstances("default", "version1", 10L);
    verify(appEngineServiceUtils, times(1)).setNumInstances("default", "version2", 10L);
    verify(appEngineServiceUtils, times(1)).setNumInstances("backend", "version1", 10L);
    verify(appEngineServiceUtils, times(1)).setNumInstances("backend", "version2", 10L);
  }

  @Test
  public void test_settingAllNonLiveVersions_succeeds() throws Exception {
    command.appengine =
        new AppEngineAdminApiHelper.Builder()
            .setAppId(projectId)
            .setManualScalingVersionsMap(
                ImmutableMultimap.of(
                    "default", "version1", "default", "version2", "default", "version3"))
            .setLiveVersionsMap(ImmutableMultimap.of("default", "version2"))
            .build()
            .getAppengine();

    runCommand("--non_live_versions=true", "--services=DEFAULT", "--num_instances=10");
    verify(appEngineServiceUtils, times(1)).setNumInstances("default", "version1", 10L);
    verify(appEngineServiceUtils, times(0)).setNumInstances("default", "version2", 10L);
    verify(appEngineServiceUtils, times(1)).setNumInstances("default", "version3", 10L);
  }

  @Test
  public void test_noNonLiveVersions_succeeds() throws Exception {
    command.appengine =
        new AppEngineAdminApiHelper.Builder()
            .setAppId(projectId)
            .setManualScalingVersionsMap(
                ImmutableMultimap.of(
                    "default", "version1", "default", "version2", "default", "version3"))
            .setLiveVersionsMap(
                ImmutableMultimap.of(
                    "default", "version1", "default", "version2", "default", "version3"))
            .build()
            .getAppengine();

    runCommand("--non_live_versions=true", "--services=DEFAULT", "--num_instances=10");
    verify(appEngineServiceUtils, times(0)).setNumInstances("default", "version1", 10L);
    verify(appEngineServiceUtils, times(0)).setNumInstances("default", "version2", 10L);
    verify(appEngineServiceUtils, times(0)).setNumInstances("default", "version3", 10L);
  }
}
