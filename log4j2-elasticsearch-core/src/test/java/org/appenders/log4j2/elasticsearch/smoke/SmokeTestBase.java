package org.appenders.log4j2.elasticsearch.smoke;

/*-
 * #%L
 * log4j2-elasticsearch
 * %%
 * Copyright (C) 2019 Rafal Foltynski
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.async.AsyncLoggerConfigDelegate;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.appenders.log4j2.elasticsearch.ElasticsearchAppender;
import org.appenders.log4j2.elasticsearch.FailoverPolicy;
import org.appenders.log4j2.elasticsearch.NoopFailoverPolicy;
import org.appenders.log4j2.elasticsearch.failover.ChronicleMapRetryFailoverPolicy;
import org.appenders.log4j2.elasticsearch.failover.KeySequenceSelector;
import org.appenders.log4j2.elasticsearch.failover.Log4j2SingleKeySequenceSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.Thread.sleep;
import static org.appenders.core.logging.InternalLogging.setLogger;
import static org.appenders.core.util.PropertiesUtil.getInt;
import static org.appenders.log4j2.elasticsearch.failover.ChronicleMapUtil.resolveChronicleMapFilePath;

public abstract class SmokeTestBase {

    public static final long ONE_SECOND = TimeUnit.MILLISECONDS.toNanos(1000);

    final TestConfig config = new TestConfig();
    private static final DockerImageName ELASTICSEARCH_IMAGE = DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch").withTag("7.13.2");

    // will be started on-demand
    public static final ElasticsearchContainer ELASTICSEARCH_CONTAINER = new ElasticsearchContainer(ELASTICSEARCH_IMAGE)
        // In addition to this setter, reuse needs to be enabled, see org.testcontainers.utility.TestcontainersConfiguration.environmentSupportsReuse().
        // If reuse is not fully enabled, the container will automatically be killed once the JVM exits. This is what the ryuk container does.
        .withReuse(true);

    private final Random random = new Random();
    private final AtomicInteger localCounter = new AtomicInteger();
    private final AtomicInteger totalCounter = new AtomicInteger();

    public abstract ElasticsearchAppender.Builder createElasticsearchAppenderBuilder(boolean messageOnly, boolean buffered, boolean secured);

    protected final TestConfig getConfig() {
        return config;
    }

    protected TestConfig configure(final TestConfig target) {
        return target.add("limitTotal", getInt("smokeTest.limitTotal", 10))
            .add("limitPerSec", getInt("smokeTest.limitPerSec", 10000))
            .add("pooled", Boolean.parseBoolean(System.getProperty("smokeTest.pooled", "true")))
            .add("secure", Boolean.parseBoolean(System.getProperty("smokeTest.secure", "false")))
            .add("logSizeInBytes", getInt("smokeTest.logSizeInBytes", 1))
            .add("lifecycleStopDelayMillis", getInt("smokeTest.lifecycleStopDelayMillis", 10000))
            .add("exitDelayMillis", getInt("smokeTest.exitDelayMillis", 10000))
            .add("numberOfProducers", getInt("smokeTest.noOfProducers", 5))
            .add("producerBatchSize", getInt("smokeTest.producerBatchSize", 10))
            .add("producerSleepMillis", getInt("smokeTest.initialProducerSleepMillis", 20))
            .add("loggerName", System.getProperty("smokeTest.loggerName", "elasticsearch-logger"))
            .add("appenderName", System.getProperty("smokeTest.appenderName", "elasticsearch-appender"))
            .add("singleThread", Boolean.parseBoolean(System.getProperty("smokeTest.singleThread", "true")))
            .add("chroniclemap.enabled", Boolean.parseBoolean(System.getProperty("smokeTest.chroniclemap.enabled", "false")));
    }

    protected static String getSystemPropertyOrStartElasticsearchContainer(String property, String scheme, int port) {
        String value = System.getProperty(property);
        if (value != null) {
            return value;
        }

        ELASTICSEARCH_CONTAINER.start();
        String url = String.format("%s://%s:%s", scheme, ELASTICSEARCH_CONTAINER.getContainerIpAddress(),
                ELASTICSEARCH_CONTAINER.getMappedPort(port));
        System.out.println("Elasticsearch running at " + url);
        return url;
    }

    protected Function<Configuration, AsyncLoggerConfigDelegate> createAsyncLoggerConfigDelegateProvider() {
        return Configuration::getAsyncLoggerConfigDelegate;
    }

    protected String createLog() {
        final int logSizeInBytes = getConfig().getProperty("logSizeInBytes", Integer.class);
        byte[] bytes = new byte[logSizeInBytes];
        random.nextBytes(bytes);

        return new String(bytes);
    }

    public final void createLoggerProgrammatically(Supplier<ElasticsearchAppender.Builder> appenderBuilder) {

        LoggerContext ctx = LoggerContext.getContext(false);

        Appender appender = appenderBuilder.get().build();
        appender.start();

        final String loggerName = getConfig().getProperty("loggerName", String.class);
        final LoggerConfig loggerConfig = ctx.getConfiguration().getLoggerConfig(loggerName);
        loggerConfig.addAppender(appender, Level.INFO, null);

        ctx.updateLoggers();

    }

    @BeforeEach
    public void beforeEach() {
        configure(config);
    }

    @Test
    public void publicDocsExampleTest() throws InterruptedException {

        System.setProperty("log4j.configurationFile", "log4j2-test.xml");

        System.setProperty("log4j2.enable.threadlocals", "true");
        System.setProperty("log4j2.enable.direct.encoders", "true");
        System.setProperty("Log4jContextSelector", "org.apache.logging.log4j.core.async.AsyncLoggerContextSelector");

        createLoggerProgrammatically(
                () -> createElasticsearchAppenderBuilder(
                        false,
                        getConfig().getProperty("pooled", Boolean.class),
                        getConfig().getProperty("secure", Boolean.class)));

        String loggerThatReferencesElasticsearchAppender = "elasticsearch";
        Logger log = LogManager.getLogger(loggerThatReferencesElasticsearchAppender);
        log.info("Hello, World!");
        sleep(5000);
    }

    @Test
    public void programmaticConfigTest() throws InterruptedException {

        System.setProperty("log4j.configurationFile", "log4j2-test.xml");

        System.setProperty("log4j2.enable.threadlocals", "true");
        System.setProperty("log4j2.enable.direct.encoders", "true");
        System.setProperty("log4j2.garbagefree.threadContextMap", "true");
        System.setProperty("Log4jContextSelector", "org.apache.logging.log4j.core.async.AsyncLoggerContextSelector");

        System.setProperty("AsyncLogger.RingBufferSize", "16384");
        System.setProperty("AsyncLogger.WaitStrategy", "sleep");

        System.setProperty("AsyncLoggerConfig.RingBufferSize", "16384");
        System.setProperty("AsyncLoggerConfig.WaitStrategy", "sleep");

        setLogger(new Log4j2Delegate(LogManager.getLogger("org.appenders.logging")));

        createLoggerProgrammatically(
                () -> createElasticsearchAppenderBuilder(
                        false,
                        getConfig().getProperty("pooled", Boolean.class),
                        getConfig().getProperty("secure", Boolean.class)));

        Logger logger = LogManager.getLogger(getConfig().getProperty("loggerName", String.class));

        final String log = createLog();
        indexLogs(logger, null, getConfig().getProperty("numberOfProducers", Integer.class), () -> log);
    }

    @Test
    @Disabled
    public void fileOutputTest() throws InterruptedException {

        System.setProperty("log4j.configurationFile", "log4j2-file.xml");
        System.setProperty("log4j2.enable.threadlocals", "true");
        System.setProperty("log4j2.enable.direct.encoders", "true");
        System.setProperty("Log4jContextSelector", "org.apache.logging.log4j.core.async.AsyncLoggerContextSelector");
        System.setProperty("AsyncLogger.RingBufferSize", "16384");

        Logger logger = LogManager.getLogger("file");

        final String log = createLog();
        indexLogs(logger, null, getConfig().getProperty("numberOfProducers", Integer.class), () -> log);
    }

    @Disabled
    @Test
    public void xmlConfigTest() throws InterruptedException {

        // let's test https://github.com/rfoltyns/log4j2-elasticsearch/issues/15
        URI uri = URI.create("log4j2.xml");
        LoggerContext context = (org.apache.logging.log4j.core.LoggerContext) LogManager.getFactory().getContext(
                LogManager.class.getName(),
                SmokeTestBase.class.getClassLoader(),
                null,
                false,
                uri,
                null
        );

        context.setConfigLocation(uri);

        Logger logger = LogManager.getLogger(getConfig().getProperty("loggerName", String.class));
        final String log = createLog();
        indexLogs(logger, null, getConfig().getProperty("numberOfProducers", Integer.class), () -> log);
    }

    @Test
    public void propertiesConfigTest() throws InterruptedException {

        String originalProperty = System.getProperty("smokeTest.elasticsearchUrl");

        System.setProperty("smokeTest.elasticsearchUrl", getSystemPropertyOrStartElasticsearchContainer("smokeTest.elasticsearchUrl", "http", 9200));
        System.setProperty("log4j.configurationFile", "log4j2.properties");
        AtomicInteger counter = new AtomicInteger();

        Logger logger = LogManager.getLogger("elasticsearch");
        indexLogs(logger, null, getConfig().getProperty("numberOfProducers", Integer.class), () -> "Message " + counter.incrementAndGet());

        if (originalProperty != null) {
            System.setProperty("smokeTest.elasticsearchUrl", originalProperty);
        } else {
            System.clearProperty("smokeTest.elasticsearchUrl");
        }

    }

    protected FailoverPolicy resolveFailoverPolicy() {

        if (!getConfig().getProperty("chroniclemap.enabled", Boolean.class)) {
            return new NoopFailoverPolicy.Builder().build();
        }

        KeySequenceSelector keySequenceSelector =
                new Log4j2SingleKeySequenceSelector.Builder()
                        .withSequenceId(getConfig().getProperty("chroniclemap.sequenceId", Integer.class))
                        .build();

        return new ChronicleMapRetryFailoverPolicy.Builder()
                .withKeySequenceSelector(keySequenceSelector)
                .withFileName(resolveChronicleMapFilePath(getConfig().getProperty("indexName", String.class) + ".chronicleMap"))
                .withNumberOfEntries(1000000)
                .withAverageValueSize(2048)
                .withBatchSize(5000)
                .withRetryDelay(4000)
                .withMonitored(true)
                .withMonitorTaskInterval(1000)
                .build();
    }

    <T> void indexLogs(Logger logger, Marker marker, int numberOfProducers, Supplier<T> logSupplier) throws InterruptedException {

        final AtomicInteger limitTotal = new AtomicInteger(getConfig().getProperty("limitTotal", Integer.class));
        final AtomicInteger producerSleepMillis = new AtomicInteger(getConfig().getProperty("producerSleepMillis", Integer.class));
        final AtomicInteger producerBatchSize = new AtomicInteger(getConfig().getProperty("producerBatchSize", Integer.class));
        final int limitPerSec = getConfig().getProperty("limitPerSec", Integer.class);

        CountDownLatch latch = new CountDownLatch(numberOfProducers);

        int numberOfLogsToDeliver = limitTotal.get();
        for (int thIndex = 0; thIndex < numberOfProducers; thIndex++) {
            new Thread(() -> {
                while (limitTotal.get() >= 0) {
                    logMicroBatch(producerBatchSize.get(), limitTotal, logger, marker, logSupplier);
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(producerSleepMillis.get()));
                }
                latch.countDown();
            }).start();
        }

        while (latch.getCount() != 0) {

            LockSupport.parkNanos(ONE_SECOND);

            int count = localCounter.getAndSet(0);
            int sleepMillis = producerSleepMillis.get();

            if (count > limitPerSec && sleepMillis != 1) {
                producerSleepMillis.incrementAndGet();
            } else if (sleepMillis > 1) {
                producerSleepMillis.decrementAndGet();
            } else if (count < limitPerSec) {
                producerBatchSize.incrementAndGet();
            }

            String stats = String.format(
                    "Sleep millis per producer: %d, Producer batch size: %d, Current throughput: %d/s; Progress: %d/%d",
                    sleepMillis,
                    producerBatchSize.get(),
                    count,
                    totalCounter.get(),
                    numberOfLogsToDeliver);

            System.out.println(stats);
        }

        sleep(getConfig().getProperty("lifecycleStopDelayMillis", Integer.class));

        System.out.println("Shutting down");
        LogManager.shutdown();

        sleep(getConfig().getProperty("exitDelayMillis", Integer.class));

    }

    private <T> void logMicroBatch(final int batchSize, final AtomicInteger limitTotal, Logger logger, Marker marker, Supplier<T> logSupplier) {
        for (int i = 0; i < batchSize && limitTotal.decrementAndGet() >= 0; i++) {
            logger.info(marker, logSupplier.get());
            localCounter.incrementAndGet();
            totalCounter.incrementAndGet();
        }
    }

}
