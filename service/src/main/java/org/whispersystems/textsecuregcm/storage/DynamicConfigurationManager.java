/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.whispersystems.textsecuregcm.metrics.MetricsUtil.name;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import org.whispersystems.textsecuregcm.util.Util;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationRequest;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionResponse;

public class DynamicConfigurationManager<T> {

  private final String application;
  private final String environment;
  private final String configurationName;
  private final AppConfigDataClient appConfigClient;
  private final Class<T> configurationClass;

  // Set on initial config fetch
  private final AtomicReference<T> configuration = new AtomicReference<>();
  private final CountDownLatch initialized = new CountDownLatch(1);
  private final ScheduledExecutorService scheduledExecutorService;
  private String configurationToken = null;

  private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

  private static final String ERROR_COUNTER_NAME = name(DynamicConfigurationManager.class, "error");
  private static final String ERROR_TYPE_TAG_NAME = "type";
  private static final String CONFIG_CLASS_TAG_NAME = "configClass";

  private static final Logger logger = LoggerFactory.getLogger(DynamicConfigurationManager.class);

  public DynamicConfigurationManager(String application, String environment, String configurationName,
      AwsCredentialsProvider awsCredentialsProvider, Class<T> configurationClass,
      ScheduledExecutorService scheduledExecutorService) {
    this(AppConfigDataClient
            .builder()
            .credentialsProvider(awsCredentialsProvider)
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(10))
                .apiCallAttemptTimeout(Duration.ofSeconds(10)).build())
            .build(),
        application, environment, configurationName, configurationClass, scheduledExecutorService);
  }

  @VisibleForTesting
  DynamicConfigurationManager(AppConfigDataClient appConfigClient, String application, String environment,
      String configurationName, Class<T> configurationClass, ScheduledExecutorService scheduledExecutorService) {
    this.appConfigClient = appConfigClient;
    this.application = application;
    this.environment = environment;
    this.configurationName = configurationName;
    this.configurationClass = configurationClass;
    this.scheduledExecutorService = scheduledExecutorService;
  }

  public T getConfiguration() {
    try {
      initialized.await();
    } catch (InterruptedException e) {
      logger.warn("Interrupted while waiting for initial configuration", e);
      throw new RuntimeException(e);
    }
    return configuration.get();
  }

  public void start() {
    if (initialized.getCount() == 0) {
      return;
    }
    configuration.set(retrieveInitialDynamicConfiguration());
    initialized.countDown();

    scheduledExecutorService.scheduleWithFixedDelay(() -> {
      try {
        retrieveDynamicConfiguration().ifPresent(configuration::set);
      } catch (Exception e) {
        logger.warn("Error retrieving dynamic configuration", e);
      }
    }, 0, 5, TimeUnit.SECONDS);
  }

  private Optional<T> retrieveDynamicConfiguration() throws JsonProcessingException {
    if (configurationToken == null) {
        logger.error("Invalid configuration token, will not be able to fetch configuration updates");
    }
    GetLatestConfigurationResponse latestConfiguration;
    try {
      latestConfiguration = appConfigClient.getLatestConfiguration(GetLatestConfigurationRequest.builder()
          .configurationToken(configurationToken)
          .build());
      // token to use in the next fetch
      configurationToken = latestConfiguration.nextPollConfigurationToken();
      logger.debug("next token: {}", configurationToken);
    } catch (final RuntimeException e) {
      Metrics.counter(ERROR_COUNTER_NAME, ERROR_TYPE_TAG_NAME, "fetch").increment();
      throw e;
    }

    if (!latestConfiguration.configuration().asByteBuffer().hasRemaining()) {
      // empty configuration means nothing has changed
      return Optional.empty();
    }
    logger.info("Received new config of length {}, next configuration token: {}",
        latestConfiguration.configuration().asByteBuffer().remaining(),
        configurationToken);

    try {
      return parseConfiguration(latestConfiguration.configuration().asUtf8String(), configurationClass);
    } catch (final JsonProcessingException e) {
      Metrics.counter(ERROR_COUNTER_NAME,
          ERROR_TYPE_TAG_NAME, "parse",
          CONFIG_CLASS_TAG_NAME, configurationClass.getName()).increment();
      throw e;
    }
  }

  @VisibleForTesting
  public static <T> Optional<T> parseConfiguration(final String configurationYaml, final Class<T> configurationClass)
      throws JsonProcessingException {
    final T configuration = SystemMapper.yamlMapper().readValue(configurationYaml, configurationClass);
    final Set<ConstraintViolation<T>> violations = VALIDATOR.validate(configuration);

    final Optional<T> maybeDynamicConfiguration;

    if (violations.isEmpty()) {
      maybeDynamicConfiguration = Optional.of(configuration);
    } else {
      logger.warn("Failed to validate configuration: {}", violations);
      maybeDynamicConfiguration = Optional.empty();
    }

    return maybeDynamicConfiguration;
  }

  private T retrieveInitialDynamicConfiguration() {
    for (;;) {
      try {
        if (configurationToken == null) {
          // first time around, start the configuration session
          final StartConfigurationSessionResponse startResponse = appConfigClient
              .startConfigurationSession(StartConfigurationSessionRequest.builder()
                  .applicationIdentifier(application)
                  .environmentIdentifier(environment)
                  .configurationProfileIdentifier(configurationName).build());
          configurationToken = startResponse.initialConfigurationToken();
        }
        return retrieveDynamicConfiguration().orElseThrow(() -> new IllegalStateException("No initial configuration available"));
      } catch (Exception e) {
        logger.warn("Error retrieving initial dynamic configuration", e);
        Util.sleep(1000);
      }
    }
  }
}
