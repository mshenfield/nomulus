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

package google.registry.reporting.icann;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link google.registry.reporting.icann.IcannReportingModule}. */
@RunWith(JUnit4.class)
public class IcannReportingModuleTest {

  @Test
  public void testProvideReportTypes() {
    HttpServletRequest req = mock(HttpServletRequest.class);

    when(req.getParameter("reportTypes")).thenReturn(null);
    assertThat(IcannReportingModule.provideReportTypes(req))
        .containsExactly(
            IcannReportingModule.ReportType.ACTIVITY, IcannReportingModule.ReportType.TRANSACTIONS);

    when(req.getParameter("reportTypes")).thenReturn("");
    assertThat(IcannReportingModule.provideReportTypes(req))
        .containsExactly(
            IcannReportingModule.ReportType.ACTIVITY, IcannReportingModule.ReportType.TRANSACTIONS);

    when(req.getParameter("reportTypes")).thenReturn("activity");
    assertThat(IcannReportingModule.provideReportTypes(req))
        .containsExactly(IcannReportingModule.ReportType.ACTIVITY);

    when(req.getParameter("reportTypes")).thenReturn("transactions");
    assertThat(IcannReportingModule.provideReportTypes(req))
        .containsExactly(IcannReportingModule.ReportType.TRANSACTIONS);

    when(req.getParameter("reportTypes")).thenReturn("activity,transactions");
    assertThat(IcannReportingModule.provideReportTypes(req))
        .containsExactly(
            IcannReportingModule.ReportType.ACTIVITY, IcannReportingModule.ReportType.TRANSACTIONS);
  }
}
