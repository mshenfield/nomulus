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

package google.registry.export.datastore;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;

import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.common.collect.ImmutableList;
import google.registry.testing.TestDataHelper;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the instantiation, marshalling and unmarshalling of {@link EntityFilter}. */
@RunWith(JUnit4.class)
public class EntityFilterTest {

  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

  @Test
  public void testEntityFilter_create_nullKinds() {
    assertThrows(NullPointerException.class, () -> new EntityFilter(null));
  }

  @Test
  public void testEntityFilter_marshall() throws IOException {
    EntityFilter entityFilter =
        new EntityFilter(ImmutableList.of("Registry", "Registrar", "DomainBase"));
    assertThat(JSON_FACTORY.toString(entityFilter))
        .isEqualTo(loadJsonString("entity_filter.json").replaceAll("[\\s\\n]+", ""));
  }

  @Test
  public void testEntityFilter_unmarshall() throws IOException {
    EntityFilter entityFilter = loadJson("entity_filter.json", EntityFilter.class);
    assertThat(entityFilter.getKinds())
        .containsExactly("Registry", "Registrar", "DomainBase")
        .inOrder();
  }

  @Test
  public void testEntityFilter_unmarshall_noKinds() throws IOException {
    EntityFilter entityFilter = JSON_FACTORY.fromString("{}", EntityFilter.class);
    assertThat(entityFilter.getKinds()).isEmpty();
  }

  @Test
  public void testEntityFilter_unmarshall_emptyKinds() throws IOException {
    EntityFilter entityFilter = JSON_FACTORY.fromString("{ \"kinds\" : [] }", EntityFilter.class);
    assertThat(entityFilter.getKinds()).isEmpty();
  }

  private static <T> T loadJson(String fileName, Class<T> type) throws IOException {
    return JSON_FACTORY.fromString(loadJsonString(fileName), type);
  }

  private static String loadJsonString(String fileName) {
    return TestDataHelper.loadFile(EntityFilterTest.class, fileName);
  }
}
