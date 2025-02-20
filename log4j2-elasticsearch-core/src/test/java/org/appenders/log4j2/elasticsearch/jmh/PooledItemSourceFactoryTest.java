package org.appenders.log4j2.elasticsearch.jmh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import net.openhft.affinity.AffinityLock;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.appenders.log4j2.elasticsearch.ItemSource;
import org.appenders.log4j2.elasticsearch.ItemSourceFactory;
import org.appenders.log4j2.elasticsearch.JacksonJsonLayout;
import org.appenders.log4j2.elasticsearch.Log4j2Lookup;
import org.appenders.log4j2.elasticsearch.PooledItemSourceFactory;
import org.appenders.log4j2.elasticsearch.ValueResolver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(jvmArgsAppend = {
        "-ea",
        "-Xmx40g",
        "-Xms40g",
        "-XX:+AlwaysPreTouch",
        "-Djmh.pinned=true"
})
public class PooledItemSourceFactoryTest {

    @Param(value = {
            "1",
            "10",
            "1000",
            "100000",
            "1000000",
    })
    public int poolSize;

    @Param(value = {
            "512",
            "2048",
            "8192",
            "16384",
    })
    public int itemSizeInBytes;

    private PooledItemSourceFactory itemPool;
    private ObjectWriter objectWriter;
    private byte[] bytes;

    private AffinityLock al;

    @Setup
    public void prepare() {

        this.itemPool = new PooledItemSourceFactory.Builder()
                .withItemSizeInBytes(itemSizeInBytes + (itemSizeInBytes / 2))
                .withInitialPoolSize(poolSize)
                .withPoolName("itemPool")
                .build();

        this.objectWriter = new JhmJacksonJsonLayout.Builder()
                .withSingleThread(true)
                .createConfiguredWriter();

        this.bytes = new byte[itemSizeInBytes];

        new Random().nextBytes(bytes);

        itemPool.start();

    }

    @Benchmark
    public void smokeTest(Blackhole fox) {
        final ItemSource itemSource = itemPool.create(bytes, objectWriter);
        itemSource.release();
        fox.consume(itemSource);
    }

    private static class JhmJacksonJsonLayout extends JacksonJsonLayout {
        protected JhmJacksonJsonLayout(Configuration config, ObjectWriter configuredWriter, ItemSourceFactory itemSourceFactory) {
            super(config, configuredWriter, itemSourceFactory);
        }

        static class Builder extends JacksonJsonLayout.Builder {

            @Override
            protected ValueResolver createValueResolver() {
                return new Log4j2Lookup(LoggerContext.getContext().getConfiguration().getStrSubstitutor());
            }

            @Override
            protected ObjectWriter createConfiguredWriter() {
                return super.createConfiguredWriter();
            }

            @Override
            protected ObjectMapper createDefaultObjectMapper() {
                return super.createDefaultObjectMapper();
            }

            @Override
            public Builder withSingleThread(boolean singleThread) {
                super.withSingleThread(singleThread);
                return this;
            }

        }
    }

}
