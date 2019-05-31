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

package google.registry.model.eppcommon;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.EppXmlTransformer.unmarshal;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.testing.TestDataHelper.loadBytes;

import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppOutput;
import google.registry.testing.ShardableTestCase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link EppXmlTransformer}. */
@RunWith(JUnit4.class)
public class EppXmlTransformerTest extends ShardableTestCase {

  @Test
  public void testUnmarshalingEppInput() throws Exception {
    EppInput input = unmarshal(EppInput.class, loadBytes(getClass(), "contact_info.xml").read());
    assertThat(input.getCommandType()).isEqualTo("info");
  }

  @Test
  public void testUnmarshalingWrongClassThrows() {
    assertThrows(
        ClassCastException.class,
        () -> unmarshal(EppOutput.class, loadBytes(getClass(), "contact_info.xml").read()));
  }
}
