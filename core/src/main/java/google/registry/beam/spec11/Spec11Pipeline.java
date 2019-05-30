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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.beam.BeamUtils.getQueryFromFile;

import com.google.auto.value.AutoValue;
import google.registry.beam.spec11.SafeBrowsingTransforms.EvaluateSafeBrowsingFn;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.Retrier;
import google.registry.util.SqlTemplate;
import java.io.Serializable;
import javax.inject.Inject;
import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.NestedValueProvider;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.joda.time.LocalDate;
import org.joda.time.YearMonth;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Definition of a Dataflow pipeline template, which generates a given month's spec11 report.
 *
 * <p>To stage this template on GCS, run the {@link
 * google.registry.tools.DeploySpec11PipelineCommand} Nomulus command.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 *
 * @see <a href="https://cloud.google.com/dataflow/docs/templates/overview">Dataflow Templates</a>
 */
public class Spec11Pipeline implements Serializable {

  /**
   * Returns the subdirectory spec11 reports reside in for a given local date in yyyy-MM-dd format.
   *
   * @see google.registry.beam.spec11.Spec11Pipeline
   * @see google.registry.reporting.spec11.Spec11EmailUtils
   */
  public static String getSpec11ReportFilePath(LocalDate localDate) {
    YearMonth yearMonth = new YearMonth(localDate);
    return String.format("icann/spec11/%s/SPEC11_MONTHLY_REPORT_%s", yearMonth, localDate);
  }

  /** The JSON object field into which we put the registrar's e-mail address for Spec11 reports. */
  public static final String REGISTRAR_EMAIL_FIELD = "registrarEmailAddress";
  /** The JSON object field into which we put the registrar's name for Spec11 reports. */
  public static final String REGISTRAR_CLIENT_ID_FIELD = "registrarClientId";
  /** The JSON object field we put the threat match array for Spec11 reports. */
  public static final String THREAT_MATCHES_FIELD = "threatMatches";

  @Inject
  @Config("projectId")
  String projectId;

  @Inject
  @Config("beamStagingUrl")
  String beamStagingUrl;

  @Inject
  @Config("spec11TemplateUrl")
  String spec11TemplateUrl;

  @Inject
  @Config("reportingBucketUrl")
  String reportingBucketUrl;

  @Inject Retrier retrier;

  @Inject
  Spec11Pipeline() {}

  /** Custom options for running the spec11 pipeline. */
  interface Spec11PipelineOptions extends DataflowPipelineOptions {
    /** Returns the local date we're generating the report for, in yyyy-MM-dd format. */
    @Description("The local date we generate the report for, in yyyy-MM-dd format.")
    ValueProvider<String> getDate();

    /**
     * Sets the local date we generate invoices for.
     *
     * <p>This is implicitly set when executing the Dataflow template, by specifying the "date"
     * parameter.
     */
    void setDate(ValueProvider<String> value);

    /** Returns the SafeBrowsing API key we use to evaluate subdomain health. */
    @Description("The API key we use to access the SafeBrowsing API.")
    ValueProvider<String> getSafeBrowsingApiKey();

    /**
     * Sets the SafeBrowsing API key we use.
     *
     * <p>This is implicitly set when executing the Dataflow template, by specifying the
     * "safeBrowsingApiKey" parameter.
     */
    void setSafeBrowsingApiKey(ValueProvider<String> value);
  }

  /** Deploys the spec11 pipeline as a template on GCS. */
  public void deploy() {
    // We can't store options as a member variable due to serialization concerns.
    Spec11PipelineOptions options = PipelineOptionsFactory.as(Spec11PipelineOptions.class);
    options.setProject(projectId);
    options.setRunner(DataflowRunner.class);
    // This causes p.run() to stage the pipeline as a template on GCS, as opposed to running it.
    options.setTemplateLocation(spec11TemplateUrl);
    options.setStagingLocation(beamStagingUrl);

    Pipeline p = Pipeline.create(options);
    PCollection<Subdomain> domains =
        p.apply(
            "Read active domains from BigQuery",
            BigQueryIO.read(Subdomain::parseFromRecord)
                .fromQuery(
                    SqlTemplate.create(getQueryFromFile(Spec11Pipeline.class, "subdomains.sql"))
                        .put("PROJECT_ID", projectId)
                        .put("DATASTORE_EXPORT_DATASET", "latest_datastore_export")
                        .put("REGISTRAR_TABLE", "Registrar")
                        .put("DOMAIN_BASE_TABLE", "DomainBase")
                        .build())
                .withCoder(SerializableCoder.of(Subdomain.class))
                .usingStandardSql()
                .withoutValidation()
                .withTemplateCompatibility());

    evaluateUrlHealth(
        domains,
        new EvaluateSafeBrowsingFn(options.getSafeBrowsingApiKey(), retrier),
        options.getDate());
    p.run();
  }

  /**
   * Evaluate each {@link Subdomain} URL via the SafeBrowsing API.
   *
   * <p>This is factored out to facilitate testing.
   */
  void evaluateUrlHealth(
      PCollection<Subdomain> domains,
      EvaluateSafeBrowsingFn evaluateSafeBrowsingFn,
      ValueProvider<String> dateProvider) {
    PCollection<KV<Subdomain, ThreatMatch>> subdomains =
        domains.apply("Run through SafeBrowsingAPI", ParDo.of(evaluateSafeBrowsingFn));
    subdomains
        .apply(
            "Map registrar client ID to email/ThreatMatch pair",
            MapElements.into(
                    TypeDescriptors.kvs(
                        TypeDescriptors.strings(), TypeDescriptor.of(EmailAndThreatMatch.class)))
                .via(
                    (KV<Subdomain, ThreatMatch> kv) ->
                        KV.of(
                            kv.getKey().registrarClientId(),
                            EmailAndThreatMatch.create(
                                kv.getKey().registrarEmailAddress(), kv.getValue()))))
        .apply("Group by registrar client ID", GroupByKey.create())
        .apply(
            "Convert results to JSON format",
            MapElements.into(TypeDescriptors.strings())
                .via(
                    (KV<String, Iterable<EmailAndThreatMatch>> kv) -> {
                      String clientId = kv.getKey();
                      checkArgument(
                          kv.getValue().iterator().hasNext(),
                          String.format(
                              "Registrar with ID %s had no corresponding threats", clientId));
                      String email = kv.getValue().iterator().next().email();
                      JSONObject output = new JSONObject();
                      try {
                        output.put(REGISTRAR_CLIENT_ID_FIELD, clientId);
                        output.put(REGISTRAR_EMAIL_FIELD, email);
                        JSONArray threatMatchArray = new JSONArray();
                        for (EmailAndThreatMatch emailAndThreatMatch : kv.getValue()) {
                          threatMatchArray.put(emailAndThreatMatch.threatMatch().toJSON());
                        }
                        output.put(THREAT_MATCHES_FIELD, threatMatchArray);
                        return output.toString();
                      } catch (JSONException e) {
                        throw new RuntimeException(
                            String.format(
                                "Encountered an error constructing the JSON for %s", kv.toString()),
                            e);
                      }
                    }))
        .apply(
            "Output to text file",
            TextIO.write()
                .to(
                    NestedValueProvider.of(
                        dateProvider,
                        date ->
                            String.format(
                                "%s/%s",
                                reportingBucketUrl,
                                getSpec11ReportFilePath(LocalDate.parse(date)))))
                .withoutSharding()
                .withHeader("Map from registrar email / name to detected subdomain threats:"));
  }

  @AutoValue
  abstract static class EmailAndThreatMatch implements Serializable {

    abstract String email();

    abstract ThreatMatch threatMatch();

    static EmailAndThreatMatch create(String email, ThreatMatch threatMatch) {
      return new AutoValue_Spec11Pipeline_EmailAndThreatMatch(email, threatMatch);
    }
  }
}
