package io.github.anshsaxena.bench;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import org.openjdk.jmh.annotations.*;

/**
 * Shared-counter contention: what does each primitive cost as the thread count grows?
 *
 * <p>Run with several thread counts to see the curve, for example {@code -t 1}, {@code -t 4} and
 * {@code -t 8}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CounterBenchmarks {
    private long plain;
    private final Object monitor = new Object();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong atomic = new AtomicLong();
    private final LongAdder adder = new LongAdder();

    @Benchmark
    public void synchronizedBlock() {
        synchronized (monitor) {
            plain++;
        }
    }

    @Benchmark
    public void reentrantLock() {
        lock.lock();
        try {
            plain++;
        } finally {
            lock.unlock();
        }
    }

    @Benchmark
    public long atomicLong() {
        return atomic.incrementAndGet();
    }

    @Benchmark
    public void longAdder() {
        adder.increment();
    }
}
