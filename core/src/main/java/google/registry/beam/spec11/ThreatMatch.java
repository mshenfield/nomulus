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

package google.registry.beam.spec11;

import com.google.auto.value.AutoValue;
import java.io.Serializable;
import org.json.JSONException;
import org.json.JSONObject;

/** A POJO representing a threat match response from the {@code SafeBrowsing API}. */
@AutoValue
public abstract class ThreatMatch implements Serializable {

  private static final String THREAT_TYPE_FIELD = "threatType";
  private static final String PLATFORM_TYPE_FIELD = "platformType";
  private static final String METADATA_FIELD = "threatEntryMetadata";
  private static final String DOMAIN_NAME_FIELD = "fullyQualifiedDomainName";

  /** Returns what kind of threat it is (malware, phishing etc.) */
  public abstract String threatType();
  /** Returns what platforms it affects (Windows, Linux etc.) */
  abstract String platformType();
  /**
   * Returns a String representing a JSON Object containing arbitrary metadata associated with this
   * threat, or "NONE" if there is no metadata to retrieve.
   *
   * <p>This ideally would be a {@link JSONObject} type, but can't be due to serialization
   * requirements.
   */
  abstract String metadata();
  /** Returns the fully qualified domain name [SLD].[TLD] of the matched threat. */
  public abstract String fullyQualifiedDomainName();

  /**
   * Constructs a {@link ThreatMatch} by parsing a {@code SafeBrowsing API} response {@link
   * JSONObject}.
   *
   * @throws JSONException when encountering parse errors in the response format
   */
  static ThreatMatch create(JSONObject threatMatchJSON, String fullyQualifiedDomainName)
      throws JSONException {
    return new AutoValue_ThreatMatch(
        threatMatchJSON.getString(THREAT_TYPE_FIELD),
        threatMatchJSON.getString(PLATFORM_TYPE_FIELD),
        threatMatchJSON.has(METADATA_FIELD)
            ? threatMatchJSON.getJSONObject(METADATA_FIELD).toString()
            : "NONE",
        fullyQualifiedDomainName);
  }

  /** Returns a {@link JSONObject} representing a subset of this object's data. */
  JSONObject toJSON() throws JSONException {
    return new JSONObject()
        .put(THREAT_TYPE_FIELD, threatType())
        .put(PLATFORM_TYPE_FIELD, platformType())
        .put(METADATA_FIELD, metadata())
        .put(DOMAIN_NAME_FIELD, fullyQualifiedDomainName());
  }

  /** Parses a {@link JSONObject} and returns an equivalent {@link ThreatMatch}. */
  public static ThreatMatch fromJSON(JSONObject threatMatch) throws JSONException {
    return new AutoValue_ThreatMatch(
        threatMatch.getString(THREAT_TYPE_FIELD),
        threatMatch.getString(PLATFORM_TYPE_FIELD),
        threatMatch.getString(METADATA_FIELD),
        threatMatch.getString(DOMAIN_NAME_FIELD));
  }
}
