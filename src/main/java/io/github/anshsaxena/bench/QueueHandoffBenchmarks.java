package io.github.anshsaxena.bench;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Control;

/**
 * One producer thread hands items to one consumer thread through different queues.
 *
 * <p>The score is hand-offs per microsecond across the pair. Producer and consumer both spin on a
 * non-blocking offer/poll, so lock cost and cache-line traffic are what is being compared.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class QueueHandoffBenchmarks {

    interface Handoff {
        boolean offer(Integer v);

        Integer poll();
    }

    @State(Scope.Group)
    public static class Channel {
        @Param({"arrayBlocking", "linkedBlocking", "concurrentLinked", "spscRing"})
        public String impl;

        Handoff handoff;

        @Setup(Level.Iteration)
        public void setup() {
            handoff = switch (impl) {
                case "arrayBlocking" -> adapt(new ArrayBlockingQueue<>(1024));
                case "linkedBlocking" -> adapt(new LinkedBlockingQueue<>(1024));
                case "concurrentLinked" -> adapt(new ConcurrentLinkedQueue<>());
                case "spscRing" -> {
                    SpscRingBuffer<Integer> ring = new SpscRingBuffer<>(1024);
                    yield new Handoff() {
                        public boolean offer(Integer v) {
                            return ring.offer(v);
                        }

                        public Integer poll() {
                            return ring.poll();
                        }
                    };
                }
                default -> throw new IllegalStateException(impl);
            };
        }

        private static Handoff adapt(Queue<Integer> q) {
            return new Handoff() {
                public boolean offer(Integer v) {
                    return q.offer(v);
                }

                public Integer poll() {
                    return q.poll();
                }
            };
        }
    }

    @Benchmark
    @Group("handoff")
    @GroupThreads(1)
    public void produce(Channel c, Control ctl) {
        while (!ctl.stopMeasurement && !c.handoff.offer(1)) {
            Thread.onSpinWait();
        }
    }

    @Benchmark
    @Group("handoff")
    @GroupThreads(1)
    public Integer consume(Channel c, Control ctl) {
        Integer v = null;
        while (!ctl.stopMeasurement && (v = c.handoff.poll()) == null) {
            Thread.onSpinWait();
        }
        return v;
    }
}
