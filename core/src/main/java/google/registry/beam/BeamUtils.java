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

package google.registry.beam;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import google.registry.util.ResourceUtils;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.io.gcp.bigquery.SchemaAndRecord;

/** Static utilities for {@code Beam} pipelines. */
public class BeamUtils {

  /** Extracts a string representation of a field in a {@link GenericRecord}. */
  public static String extractField(GenericRecord record, String fieldName) {
    return String.valueOf(record.get(fieldName));
  }

  /**
   * Checks that no expected fields in the record are missing.
   *
   * <p>Note that this simply makes sure the field is not null; it may still generate a parse error
   * when interpreting the string representation of an object.
   *
   * @throws IllegalStateException if the record returns null for any field in {@code fieldNames}
   */
  public static void checkFieldsNotNull(
      ImmutableList<String> fieldNames, SchemaAndRecord schemaAndRecord) {
    GenericRecord record = schemaAndRecord.getRecord();
    ImmutableList<String> nullFields =
        fieldNames
            .stream()
            .filter(fieldName -> record.get(fieldName) == null)
            .collect(ImmutableList.toImmutableList());
    String missingFieldList = Joiner.on(", ").join(nullFields);
    if (!nullFields.isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Read unexpected null value for field(s) %s for record %s",
              missingFieldList, record));
    }
  }

  /**
   * Returns the {@link String} contents for a file in the {@code sql/} directory relative to a
   * class.
   */
  public static String getQueryFromFile(Class<?> clazz, String filename) {
    return ResourceUtils.readResourceUtf8(Resources.getResource(clazz, "sql/" + filename));
  }
}
