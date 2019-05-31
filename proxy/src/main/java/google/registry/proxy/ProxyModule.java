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

package google.registry.proxy;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.proxy.ProxyConfig.getProxyConfig;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.util.Utils;
import com.google.api.services.cloudkms.v1.CloudKMS;
import com.google.api.services.cloudkms.v1.model.DecryptRequest;
import com.google.api.services.storage.Storage;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.flogger.LoggerConfig;
import com.google.monitoring.metrics.MetricReporter;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.proxy.EppProtocolModule.EppProtocol;
import google.registry.proxy.HealthCheckProtocolModule.HealthCheckProtocol;
import google.registry.proxy.Protocol.FrontendProtocol;
import google.registry.proxy.ProxyConfig.Environment;
import google.registry.proxy.WebWhoisProtocolsModule.HttpWhoisProtocol;
import google.registry.proxy.WebWhoisProtocolsModule.HttpsWhoisProtocol;
import google.registry.proxy.WhoisProtocolModule.WhoisProtocol;
import google.registry.proxy.handler.ProxyProtocolHandler;
import google.registry.util.Clock;
import google.registry.util.SystemClock;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * A module that provides the port-to-protocol map and other configs that are used to bootstrap the
 * server.
 */
@Module
public class ProxyModule {

  @Parameter(names = "--whois", description = "Port for WHOIS")
  private Integer whoisPort;

  @Parameter(names = "--epp", description = "Port for EPP")
  private Integer eppPort;

  @Parameter(names = "--health_check", description = "Port for health check")
  private Integer healthCheckPort;

  @Parameter(names = "--http_whois", description = "Port for HTTP WHOIS")
  private Integer httpWhoisPort;

  @Parameter(names = "--https_whois", description = "Port for HTTPS WHOIS")
  private Integer httpsWhoisPort;

  @Parameter(names = "--env", description = "Environment to run the proxy in")
  private Environment env = Environment.LOCAL;

  @Parameter(
      names = "--log",
      description =
          "Whether to log activities for debugging. "
              + "This cannot be enabled for production as logs contain PII.")
  boolean log;

  /**
   * Configure logging parameters depending on the {@link Environment}.
   *
   * <p>If not running locally, set the logging formatter to {@link GcpJsonFormatter} that formats
   * the log in a single-line json string printed to {@code STDOUT} or {@code STDERR}, will be
   * correctly parsed by Stackdriver logging.
   *
   * @see <a href="https://cloud.google.com/kubernetes-engine/docs/how-to/logging#best_practices">
   *     Logging Best Practices</a>
   */
  private void configureLogging() {
    // Remove all other handlers on the root logger to avoid double logging.
    LoggerConfig rootLoggerConfig = LoggerConfig.getConfig("");
    Arrays.asList(rootLoggerConfig.getHandlers()).forEach(rootLoggerConfig::removeHandler);

    // If running on in a non-local environment, use GCP JSON formatter.
    Handler rootHandler = new ConsoleHandler();
    rootHandler.setLevel(Level.FINE);
    if (env != Environment.LOCAL) {
      rootHandler.setFormatter(new GcpJsonFormatter());
    }
    rootLoggerConfig.addHandler(rootHandler);

    if (log) {
      // The LoggingHandler records logs at LogLevel.DEBUG (internal Netty log level), which
      // corresponds to Level.FINE (JUL log level). It uses a JUL logger with the name
      // "io.netty.handler.logging.LoggingHandler" to actually process the logs. This JUL logger is
      // set to Level.FINE if the --log parameter is passed, so that it does not filter out logs
      // that the LoggingHandler writes. Otherwise the logs are silently ignored because the default
      // JUL logger level is Level.INFO.
      LoggerConfig.getConfig(LoggingHandler.class).setLevel(Level.FINE);
      // Log source IP information if --log parameter is passed. This is considered PII and should
      // only be used in non-production environment for debugging purpose.
      LoggerConfig.getConfig(ProxyProtocolHandler.class).setLevel(Level.FINE);
    }
  }

  /**
   * Parses command line arguments. Show usage if wrong arguments are given.
   *
   * @param args list of {@code String} arguments
   * @return this {@code ProxyModule} object
   */
  ProxyModule parse(String[] args) {
    JCommander jCommander = new JCommander(this);
    jCommander.setProgramName("proxy_server");
    try {
      jCommander.parse(args);
    } catch (ParameterException e) {
      jCommander.usage();
      throw e;
    }
    checkArgument(
        !log || (env != Environment.PRODUCTION && env != Environment.PRODUCTION_CANARY),
        "Logging cannot be enabled for production environment");
    configureLogging();
    return this;
  }

  @Provides
  @WhoisProtocol
  int provideWhoisPort(ProxyConfig config) {
    return Optional.ofNullable(whoisPort).orElse(config.whois.port);
  }

  @Provides
  @EppProtocol
  int provideEppPort(ProxyConfig config) {
    return Optional.ofNullable(eppPort).orElse(config.epp.port);
  }

  @Provides
  @HealthCheckProtocol
  int provideHealthCheckPort(ProxyConfig config) {
    return Optional.ofNullable(healthCheckPort).orElse(config.healthCheck.port);
  }

  @Provides
  @HttpWhoisProtocol
  int provideHttpWhoisProtocol(ProxyConfig config) {
    return Optional.ofNullable(httpWhoisPort).orElse(config.webWhois.httpPort);
  }

  @Provides
  @HttpsWhoisProtocol
  int provideHttpsWhoisProtocol(ProxyConfig config) {
    return Optional.ofNullable(httpsWhoisPort).orElse(config.webWhois.httpsPort);
  }

  @Provides
  ImmutableMap<Integer, FrontendProtocol> providePortToProtocolMap(
      Set<FrontendProtocol> protocolSet) {
    return Maps.uniqueIndex(protocolSet, Protocol::port);
  }

  @Provides
  Environment provideEnvironment() {
    return env;
  }

  /**
   * Provides shared logging handler.
   *
   * <p>Note that this handler always records logs at {@code LogLevel.DEBUG}, it is up to the JUL
   * logger that it contains to decide if logs at this level should actually be captured. The log
   * level of the JUL logger is configured in {@link #configureLogging()}.
   */
  @Singleton
  @Provides
  LoggingHandler provideLoggingHandler() {
    return new LoggingHandler(LogLevel.DEBUG);
  }

  @Singleton
  @Provides
  static GoogleCredential provideCredential(ProxyConfig config) {
    try {
      GoogleCredential credential = GoogleCredential.getApplicationDefault();
      if (credential.createScopedRequired()) {
        credential = credential.createScoped(config.gcpScopes);
      }
      return credential;
    } catch (IOException e) {
      throw new RuntimeException("Unable to obtain OAuth2 credential.", e);
    }
  }

  /** Access token supplier that auto refreshes 1 minute before expiry. */
  @Singleton
  @Provides
  @Named("accessToken")
  static Supplier<String> provideAccessTokenSupplier(
      GoogleCredential credential, ProxyConfig config) {
    return () -> {
      // If we never obtained an access token, the expiration time is null.
      if (credential.getExpiresInSeconds() == null
          // If we have an access token, make sure to refresh it ahead of time.
          || credential.getExpiresInSeconds() < config.accessTokenRefreshBeforeExpirationSeconds) {
        try {
          credential.refreshToken();
        } catch (IOException e) {
          throw new RuntimeException("Cannot refresh access token.", e);
        }
      }
      return credential.getAccessToken();
    };
  }

  @Singleton
  @Provides
  static CloudKMS provideCloudKms(GoogleCredential credential, ProxyConfig config) {
    return new CloudKMS.Builder(
            Utils.getDefaultTransport(), Utils.getDefaultJsonFactory(), credential)
        .setApplicationName(config.projectId)
        .build();
  }

  @Singleton
  @Provides
  static Storage provideStorage(GoogleCredential credential, ProxyConfig config) {
    return new Storage.Builder(
            Utils.getDefaultTransport(), Utils.getDefaultJsonFactory(), credential)
        .setApplicationName(config.projectId)
        .build();
  }

  // This binding should not be used directly. Use those provided in CertificateModule instead.
  @Provides
  @Named("encryptedPemBytes")
  static byte[] provideEncryptedPemBytes(Storage storage, ProxyConfig config) {
    try {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      storage
          .objects()
          .get(config.gcs.bucket, config.gcs.sslPemFilename)
          .executeMediaAndDownloadTo(outputStream);
      return Base64.getMimeDecoder().decode(outputStream.toByteArray());
    } catch (IOException e) {
      throw new RuntimeException(
          String.format(
              "Error reading encrypted PEM file %s from GCS bucket %s",
              config.gcs.sslPemFilename, config.gcs.bucket),
          e);
    }
  }

  // This binding should not be used directly. Use those provided in CertificateModule instead.
  @Provides
  @Named("pemBytes")
  static byte[] providePemBytes(
      CloudKMS cloudKms, @Named("encryptedPemBytes") byte[] encryptedPemBytes, ProxyConfig config) {
    String cryptoKeyUrl =
        String.format(
            "projects/%s/locations/%s/keyRings/%s/cryptoKeys/%s",
            config.projectId, config.kms.location, config.kms.keyRing, config.kms.cryptoKey);
    try {
      DecryptRequest decryptRequest = new DecryptRequest().encodeCiphertext(encryptedPemBytes);
      return cloudKms
          .projects()
          .locations()
          .keyRings()
          .cryptoKeys()
          .decrypt(cryptoKeyUrl, decryptRequest)
          .execute()
          .decodePlaintext();
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("PEM file decryption failed using CryptoKey: %s", cryptoKeyUrl), e);
    }
  }

  @Provides
  static SslProvider provideSslProvider() {
    // Prefer OpenSSL.
    return OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
  }

  @Provides
  @Singleton
  static Clock provideClock() {
    return new SystemClock();
  }

  @Provides
  static ExecutorService provideExecutorService() {
    return Executors.newWorkStealingPool();
  }

  @Provides
  static ScheduledExecutorService provideScheduledExecutorService() {
    return Executors.newSingleThreadScheduledExecutor();
  }

  @Singleton
  @Provides
  ProxyConfig provideProxyConfig(Environment env) {
    return getProxyConfig(env);
  }

  /** Root level component that exposes the port-to-protocol map. */
  @Singleton
  @Component(
      modules = {
        ProxyModule.class,
        CertificateModule.class,
        HttpsRelayProtocolModule.class,
        WhoisProtocolModule.class,
        WebWhoisProtocolsModule.class,
        EppProtocolModule.class,
        HealthCheckProtocolModule.class,
        MetricsModule.class
      })
  interface ProxyComponent {

    ImmutableMap<Integer, FrontendProtocol> portToProtocolMap();

    MetricReporter metricReporter();
  }
}
