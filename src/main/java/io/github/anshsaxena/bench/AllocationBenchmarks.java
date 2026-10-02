package io.github.anshsaxena.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Allocation on the hot path.
 *
 * <p>Run with {@code -prof gc} to see bytes allocated per operation, and with different collectors
 * ({@code -jvmArgs "-XX:+UseSerialGC"}, {@code "-XX:+UseParallelGC"}, {@code "-XX:+UseG1GC"},
 * {@code "-XX:+UseZGC"}) to see how the collector changes the tail latency in SampleTime mode.
 *
 * <p>Two pairs: a fresh event object per tick versus one reused mutable event, and a price ladder
 * kept in a boxed {@code HashMap<Long, Long>} versus a primitive {@code long[]}.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AllocationBenchmarks {

    static final class Tick {
        long price;
        long quantity;
        long timestamp;

        Tick(long price, long quantity, long timestamp) {
            this.price = price;
            this.quantity = quantity;
            this.timestamp = timestamp;
        }
    }

    private static final int LEVELS = 1024;

    private final Tick reusable = new Tick(0, 0, 0);
    private final Map<Long, Long> boxedLadder = new HashMap<>();
    private final long[] primitiveLadder = new long[LEVELS];
    private long seq;

    @Setup
    public void setup() {
        for (long i = 0; i < LEVELS; i++) {
            boxedLadder.put(i, 0L);
        }
    }

    private long handle(Tick t) {
        return t.price * t.quantity + t.timestamp;
    }

    // Each tick is handed to the Blackhole so it ESCAPES. Without that, the JIT's escape analysis can
    // scalar-replace `new Tick(...)` and the "allocating" variant allocates nothing (seen on the first run).
    @Benchmark
    public long newTickPerEvent(Blackhole bh) {
        seq++;
        Tick t = new Tick(seq & 1023, 100, seq);
        bh.consume(t);
        return handle(t);
    }

    @Benchmark
    public long reusedTick(Blackhole bh) {
        seq++;
        reusable.price = seq & 1023;
        reusable.quantity = 100;
        reusable.timestamp = seq;
        bh.consume(reusable);
        return handle(reusable);
    }

    @Benchmark
    public void boxedLadderUpdate(Blackhole bh) {
        seq++;
        Long level = seq & (LEVELS - 1);
        boxedLadder.merge(level, 100L, Long::sum);
        bh.consume(level);
    }

    @Benchmark
    public void primitiveLadderUpdate(Blackhole bh) {
        seq++;
        int level = (int) (seq & (LEVELS - 1));
        primitiveLadder[level] += 100L;
        bh.consume(level);
    }
}
