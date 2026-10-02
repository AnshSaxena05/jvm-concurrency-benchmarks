# jvm-concurrency-benchmarks

JMH microbenchmarks for low-latency Java. Three questions, each with runnable code, a correctness test where one is needed, and measured results:

1. **Lock contention.** What does `synchronized`, `ReentrantLock`, `AtomicLong` and `LongAdder` cost as threads are added?
2. **Lock-free hand-off.** How much faster is a hand-written single-producer / single-consumer ring buffer than `ArrayBlockingQueue`, `LinkedBlockingQueue` and `ConcurrentLinkedQueue`?
3. **Allocation on the hot path.** What does one `new` per event, or one boxed `Long`, cost in bytes, GC cycles and time, under G1, Parallel and ZGC?

Java 21, JMH 1.37, Maven. MIT licensed.

## Run it

```bash
mvn clean verify                    # compiles, runs the SPSC correctness test, builds target/benchmarks.jar
java -jar target/benchmarks.jar CounterBenchmarks -t 1     # also -t 4, -t 8
java -jar target/benchmarks.jar QueueHandoffBenchmarks
java -jar target/benchmarks.jar AllocationBenchmarks -prof gc -jvmArgs "-XX:+UseZGC"   # or UseG1GC, UseParallelGC
```

Defaults are 3 warm-up and 5 measured iterations of 1 s, 1 fork. The numbers below used `-wi 3 -i 8 -w 1 -r 1 -f 2` (counters, hand-off) and `-wi 3 -i 6 -w 1 -r 1 -f 1 -prof gc` (allocation).

## Results

Machine: Intel Core i7-1255U (10 cores, 12 threads, hybrid P/E cores), 15.7 GB RAM, Windows 11, Microsoft OpenJDK 21.0.12, 2026-10-02.

**This is a thin-and-light laptop that was also running a browser and other background jobs, with no core pinning and no frequency control.** Treat the ratios as the finding, not the absolute figures, and re-run on your own hardware. Scores are `±` the 99.9% confidence interval JMH reports.

### 1. Shared counter, operations per microsecond (higher is better)

| Primitive | 1 thread | 4 threads | 8 threads |
|---|---:|---:|---:|
| `AtomicLong.incrementAndGet` | 152.3 ± 11.3 | 27.7 ± 4.4 | 22.8 ± 2.9 |
| `LongAdder.increment` | 91.9 ± 1.7 | 276.4 ± 49.5 | 439.9 ± 22.1 |
| `ReentrantLock` | 51.4 ± 2.8 | 28.6 ± 3.7 | 28.1 ± 4.3 |
| `synchronized` | 40.4 ± 2.8 | 13.3 ± 1.6 | 12.5 ± 1.5 |

Read: uncontended, a single CAS (`AtomicLong`) wins. As threads pile onto one cache line it collapses (about 7x slower at 8 threads), while `LongAdder`, which stripes the count across cells, scales up (about 19x faster than `AtomicLong` at 8 threads). The price is that `LongAdder.sum()` is not an atomic snapshot, so it fits metrics and counters, not sequence numbers.

### 2. One producer to one consumer, hand-offs per microsecond (higher is better)

| Queue | Throughput |
|---|---:|
| `SpscRingBuffer` (this repo, lock-free) | 77.4 ± 16.2 |
| `ConcurrentLinkedQueue` | 26.9 ± 3.9 |
| `ArrayBlockingQueue` | 10.8 ± 3.4 |
| `LinkedBlockingQueue` | 8.9 ± 2.4 |

Read: when exactly one thread writes and one reads, dropping the lock and the CAS loop gives roughly 3x over the best general-purpose queue and 7x to 9x over the blocking ones. Both sides spin, so this measures the hand-off itself and not park/unpark latency. [`SpscRingBuffer`](src/main/java/io/github/anshsaxena/bench/SpscRingBuffer.java) is only correct under that single-producer / single-consumer rule; the test pushes 2,000,000 items across two threads and checks order and sum.

### 3. Allocation on the hot path (`-prof gc`, lower is better)

Average time per operation, bytes allocated per operation, and GC cycles during the 6 measured seconds:

| Benchmark | G1 | Parallel | ZGC | Bytes/op |
|---|---:|---:|---:|---:|
| `newTickPerEvent` | 5.2 ns, 74 GCs | 5.2 ns, 137 GCs | 6.8 ns, 48 GCs | 40 |
| `reusedTick` | 1.1 ns, 0 GCs | 1.1 ns, 0 GCs | 1.5 ns, 0 GCs | 0 |
| `boxedLadderUpdate` (`HashMap<Long,Long>.merge`) | 17.5 ns, 38 GCs | 11.9 ns, 42 GCs | 20.1 ns, 16 GCs | 45 |
| `primitiveLadderUpdate` (`long[]`) | 1.5 ns, 0 GCs | 1.5 ns, 0 GCs | 1.8 ns, 0 GCs | 0 |

Read: reusing one mutable event removes 40 bytes of garbage per tick and about 5x of the time; a `long[]` ladder removes 45 bytes and about 8x to 12x against a boxed map. With zero allocation there is nothing for any collector to do, so the collector choice stops mattering, which is the point of allocation-free design. The `boxedLadderUpdate` error bars are wide (the time is dominated by cache misses and boxing that vary run to run).

Two cautions, both learned from this run:

- **Escape analysis can hide allocation.** My first version of `newTickPerEvent` did not hand the object to the `Blackhole`, the JIT scalar-replaced it, and the benchmark reported 0 bytes per operation. Each event now escapes through `Blackhole.consume`, and the result above is 40 bytes (a 16-byte header plus three longs).
- **`SampleTime` percentiles are not in the table on purpose.** For operations of a few nanoseconds the timer call costs more than the work, so sample-mode times and tail percentiles reflect the clock, not the code. Use the `AverageTime` figures, or move to a harness with a cheaper clock, before drawing tail-latency conclusions.

Raw JMH JSON and console output are in [`results/`](results/).

## Layout

```
src/main/java/.../SpscRingBuffer.java            lock-free SPSC ring buffer (release stores, power-of-two mask)
src/main/java/.../CounterBenchmarks.java         synchronized, ReentrantLock, AtomicLong, LongAdder
src/main/java/.../QueueHandoffBenchmarks.java    1 producer + 1 consumer across four queues
src/main/java/.../AllocationBenchmarks.java      new vs reused event, boxed vs primitive ladder
src/test/java/.../SpscRingBufferTest.java        capacity, full/empty, and a 2M-item two-thread order and sum check
results/                                         raw JMH output from the run above
```

## Why these three

They are the first things to check when a Java service has a latency problem: is one shared variable the bottleneck, is a queue between two threads the bottleneck, and is the garbage collector being fed by objects that never needed to exist. Each benchmark is small enough to read in a minute and change.
