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

package google.registry.reporting.billing;

import static com.google.common.base.Throwables.getRootCause;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.registrar.Registrar;
import google.registry.reporting.billing.BillingModule.InvoiceDirectoryPrefix;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import google.registry.util.Retrier;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import javax.inject.Inject;

/** Copy all registrar detail reports in a given bucket's subdirectory from GCS to Drive. */
@Action(
    service = Action.Service.BACKEND,
    path = CopyDetailReportsAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class CopyDetailReportsAction implements Runnable {

  public static final String PATH = "/_dr/task/copyDetailReports";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final String billingBucket;
  private final String invoiceDirectoryPrefix;
  private final DriveConnection driveConnection;
  private final GcsUtils gcsUtils;
  private final Retrier retrier;
  private final Response response;
  private final BillingEmailUtils emailUtils;

  @Inject
  CopyDetailReportsAction(
      @Config("billingBucket") String billingBucket,
      @InvoiceDirectoryPrefix String invoiceDirectoryPrefix,
      DriveConnection driveConnection,
      GcsUtils gcsUtils,
      Retrier retrier,
      Response response,
      BillingEmailUtils emailUtils) {
    this.billingBucket = billingBucket;
    this.invoiceDirectoryPrefix = invoiceDirectoryPrefix;
    this.driveConnection = driveConnection;
    this.gcsUtils = gcsUtils;
    this.retrier = retrier;
    this.response = response;
    this.emailUtils = emailUtils;
  }

  @Override
  public void run() {
    ImmutableList<String> detailReportObjectNames;
    try {
      detailReportObjectNames =
          gcsUtils
              .listFolderObjects(billingBucket, invoiceDirectoryPrefix)
              .stream()
              .filter(objectName -> objectName.startsWith(BillingModule.DETAIL_REPORT_PREFIX))
              .collect(ImmutableList.toImmutableList());
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Copying registrar detail report failed");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Failure, encountered %s", e.getMessage()));
      return;
    }
    for (String detailReportName : detailReportObjectNames) {
      // The standard report format is "invoice_details_yyyy-MM_registrarId_tld.csv
      // TODO(larryruili): Determine a safer way of enforcing this.
      String registrarId = Iterables.get(Splitter.on('_').split(detailReportName), 3);
      Optional<Registrar> registrar = Registrar.loadByClientId(registrarId);
      if (!registrar.isPresent()) {
        logger.atWarning().log(
            "Registrar %s not found in database for file %s", registrar, detailReportName);
        continue;
      }
      String driveFolderId = registrar.get().getDriveFolderId();
      if (driveFolderId == null) {
        logger.atWarning().log("Drive folder id not found for registrar %s", registrarId);
        continue;
      }
      // Attempt to copy each detail report to its associated registrar's drive folder.
      try {
        retrier.callWithRetry(
            () -> {
              try (InputStream input =
                  gcsUtils.openInputStream(
                      new GcsFilename(billingBucket, invoiceDirectoryPrefix + detailReportName))) {
                driveConnection.createFile(
                    detailReportName,
                    MediaType.CSV_UTF_8,
                    driveFolderId,
                    ByteStreams.toByteArray(input));
                logger.atInfo().log(
                    "Published detail report for %s to folder %s using GCS file gs://%s/%s.",
                    registrarId, driveFolderId, billingBucket, detailReportName);
              }
            },
            IOException.class);
      } catch (Throwable e) {
        emailUtils.sendAlertEmail(
            String.format(
                "Warning: CopyDetailReportsAction failed.\nEncountered: %s on file: %s",
                getRootCause(e).getMessage(), detailReportName));
        throw e;
      }
    }
    response.setStatus(SC_OK);
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    response.setPayload("Copied detail reports.");
  }
}
