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

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;
import static com.google.appengine.tools.cloudstorage.GcsServiceFactory.createGcsService;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static google.registry.model.common.Cursor.getCursorTimeOrStartOfTime;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.ReducerInput;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.keyring.api.KeyModule;
import google.registry.keyring.api.PgpHelper;
import google.registry.model.common.Cursor;
import google.registry.model.rde.RdeMode;
import google.registry.model.rde.RdeNamingUtils;
import google.registry.model.rde.RdeRevision;
import google.registry.model.registry.Registry;
import google.registry.request.RequestParameters;
import google.registry.request.lock.LockHandler;
import google.registry.tldconfig.idn.IdnTableEnum;
import google.registry.util.TaskQueueUtils;
import google.registry.xjc.rdeheader.XjcRdeHeader;
import google.registry.xjc.rdeheader.XjcRdeHeaderElement;
import google.registry.xml.ValidationMode;
import google.registry.xml.XmlException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.Security;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Reducer for {@link RdeStagingAction}. */
public final class RdeStagingReducer extends Reducer<PendingDeposit, DepositFragment, Void> {

  private static final long serialVersionUID = 60326234579091203L;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final TaskQueueUtils taskQueueUtils;
  private final LockHandler lockHandler;
  private final int gcsBufferSize;
  private final String bucket;
  private final Duration lockTimeout;
  private final byte[] stagingKeyBytes;
  private final RdeMarshaller marshaller;

  private RdeStagingReducer(
      TaskQueueUtils taskQueueUtils,
      LockHandler lockHandler,
      int gcsBufferSize,
      String bucket,
      Duration lockTimeout,
      byte[] stagingKeyBytes,
      ValidationMode validationMode) {
    this.taskQueueUtils = taskQueueUtils;
    this.lockHandler = lockHandler;
    this.gcsBufferSize = gcsBufferSize;
    this.bucket = bucket;
    this.lockTimeout = lockTimeout;
    this.stagingKeyBytes = stagingKeyBytes;
    this.marshaller = new RdeMarshaller(validationMode);
  }

  @Override
  public void reduce(final PendingDeposit key, final ReducerInput<DepositFragment> fragments) {
    Callable<Void> lockRunner =
        () -> {
          reduceWithLock(key, fragments);
          return null;
        };
    String lockName = String.format("RdeStaging %s", key.mode());
    if (!lockHandler.executeWithLocks(lockRunner, key.tld(), lockTimeout, lockName)) {
      logger.atWarning().log("Lock in use: %s", lockName);
    }
  }

  private void reduceWithLock(final PendingDeposit key, Iterator<DepositFragment> fragments) {
    logger.atInfo().log("RdeStagingReducer %s", key);

    // Normally this is done by BackendServlet but it's not present in MapReduceServlet.
    Security.addProvider(new BouncyCastleProvider());

    // Construct things that Dagger would inject if this wasn't serialized.
    PGPPublicKey stagingKey = PgpHelper.loadPublicKeyBytes(stagingKeyBytes);
    GcsUtils cloudStorage =
        new GcsUtils(createGcsService(RetryParams.getDefaultInstance()), gcsBufferSize);
    RdeCounter counter = new RdeCounter();

    // Determine some basic things about the deposit.
    final RdeMode mode = key.mode();
    final String tld = key.tld();
    final DateTime watermark = key.watermark();
    final int revision =
        Optional.ofNullable(key.revision())
            .orElse(RdeRevision.getNextRevision(tld, watermark, mode));
    String id = RdeUtil.timestampToId(watermark);
    String prefix = RdeNamingUtils.makeRydeFilename(tld, watermark, mode, 1, revision);
    if (key.manual()) {
      checkState(key.directoryWithTrailingSlash() != null, "Manual subdirectory not specified");
      prefix = "manual/" + key.directoryWithTrailingSlash() + prefix;
    }
    GcsFilename xmlFilename = new GcsFilename(bucket, prefix + ".xml.ghostryde");
    // This file will contain the byte length (ASCII) of the raw unencrypted XML.
    //
    // This is necessary because RdeUploadAction creates a tar file which requires that the length
    // be outputted. We don't want to have to decrypt the entire ghostryde file to determine the
    // length, so we just save it separately.
    GcsFilename xmlLengthFilename = new GcsFilename(bucket, prefix + ".xml.length");
    GcsFilename reportFilename = new GcsFilename(bucket, prefix + "-report.xml.ghostryde");

    // These variables will be populated as we write the deposit XML and used for other files.
    boolean failed = false;
    XjcRdeHeader header;

    // Write a gigantic XML file to GCS. We'll start by opening encrypted out/err file handles.

    logger.atInfo().log("Writing %s and %s", xmlFilename, xmlLengthFilename);
    try (OutputStream gcsOutput = cloudStorage.openOutputStream(xmlFilename);
        OutputStream lengthOutput = cloudStorage.openOutputStream(xmlLengthFilename);
        OutputStream ghostrydeEncoder = Ghostryde.encoder(gcsOutput, stagingKey, lengthOutput);
        Writer output = new OutputStreamWriter(ghostrydeEncoder, UTF_8)) {

      // Output the top portion of the XML document.
      output.write(marshaller.makeHeader(id, watermark, RdeResourceType.getUris(mode), revision));

      // Output XML fragments emitted to us by RdeStagingMapper while counting them.
      while (fragments.hasNext()) {
        DepositFragment fragment = fragments.next();
        if (!fragment.xml().isEmpty()) {
          output.write(fragment.xml());
          counter.increment(fragment.type());
        }
        if (!fragment.error().isEmpty()) {
          failed = true;
          logger.atSevere().log("Fragment error: %s", fragment.error());
        }
      }
      for (IdnTableEnum idn : IdnTableEnum.values()) {
        output.write(marshaller.marshalIdn(idn.getTable()));
        counter.increment(RdeResourceType.IDN);
      }

      // Output XML that says how many resources were emitted.
      header = counter.makeHeader(tld, mode);
      output.write(marshaller.marshalOrDie(new XjcRdeHeaderElement(header)));

      // Output the bottom of the XML document.
      output.write(marshaller.makeFooter());

    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // If an entity was broken, abort after writing as much logs/deposit data as possible.
    verify(!failed, "RDE staging failed for TLD %s", tld);

    // Write a tiny XML file to GCS containing some information about the deposit.
    //
    // This will be sent to ICANN once we're done uploading the big XML to the escrow provider.
    if (mode == RdeMode.FULL) {
      logger.atInfo().log("Writing %s", reportFilename);
      try (OutputStream gcsOutput = cloudStorage.openOutputStream(reportFilename);
          OutputStream ghostrydeEncoder = Ghostryde.encoder(gcsOutput, stagingKey)) {
        counter.makeReport(id, watermark, header, revision).marshal(ghostrydeEncoder, UTF_8);
      } catch (IOException | XmlException e) {
        throw new RuntimeException(e);
      }
    }

    // Now that we're done, kick off RdeUploadAction and roll forward the cursor transactionally.
    if (key.manual()) {
      logger.atInfo().log("Manual operation; not advancing cursor or enqueuing upload task");
      return;
    }
    ofy()
        .transact(
            () -> {
              Registry registry = Registry.get(tld);
              DateTime position =
                  getCursorTimeOrStartOfTime(
                      ofy().load().key(Cursor.createKey(key.cursor(), registry)).now());
              checkState(key.interval() != null, "Interval must be present");
              DateTime newPosition = key.watermark().plus(key.interval());
              if (!position.isBefore(newPosition)) {
                logger.atWarning().log("Cursor has already been rolled forward.");
                return;
              }
              verify(
                  position.equals(key.watermark()),
                  "Partial ordering of RDE deposits broken: %s %s",
                  position,
                  key);
              ofy().save().entity(Cursor.create(key.cursor(), newPosition, registry)).now();
              logger.atInfo().log(
                  "Rolled forward %s on %s cursor to %s", key.cursor(), tld, newPosition);
              RdeRevision.saveRevision(tld, watermark, mode, revision);
              if (mode == RdeMode.FULL) {
                taskQueueUtils.enqueue(
                    getQueue("rde-upload"),
                    withUrl(RdeUploadAction.PATH).param(RequestParameters.PARAM_TLD, tld));
              } else {
                taskQueueUtils.enqueue(
                    getQueue("brda"),
                    withUrl(BrdaCopyAction.PATH)
                        .param(RequestParameters.PARAM_TLD, tld)
                        .param(RdeModule.PARAM_WATERMARK, watermark.toString()));
              }
            });
  }

  /** Injectible factory for creating {@link RdeStagingReducer}. */
  static class Factory {
    @Inject TaskQueueUtils taskQueueUtils;
    @Inject LockHandler lockHandler;
    @Inject @Config("gcsBufferSize") int gcsBufferSize;
    @Inject @Config("rdeBucket") String bucket;
    @Inject @Config("rdeStagingLockTimeout") Duration lockTimeout;
    @Inject @KeyModule.Key("rdeStagingEncryptionKey") byte[] stagingKeyBytes;

    @Inject Factory() {}

    RdeStagingReducer create(ValidationMode validationMode) {
      return new RdeStagingReducer(
          taskQueueUtils,
          lockHandler,
          gcsBufferSize,
          bucket,
          lockTimeout,
          stagingKeyBytes,
          validationMode);
    }
  }
}
