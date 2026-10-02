package io.github.anshsaxena.bench;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class SpscRingBufferTest {

    @Test
    void capacityRoundsUpToPowerOfTwo() {
        assertEquals(8, new SpscRingBuffer<Integer>(5).capacity());
        assertEquals(1024, new SpscRingBuffer<Integer>(1024).capacity());
    }

    @Test
    void offerFailsWhenFullAndPollFailsWhenEmpty() {
        SpscRingBuffer<Integer> q = new SpscRingBuffer<>(4);
        assertNull(q.poll());
        for (int i = 0; i < 4; i++) {
            assertTrue(q.offer(i));
        }
        assertFalse(q.offer(99));
        assertEquals(0, q.poll());
        assertTrue(q.offer(99));
    }

    @Test
    void producerAndConsumerThreadsSeeEveryItemInOrder() throws Exception {
        final int n = 2_000_000;
        SpscRingBuffer<Integer> q = new SpscRingBuffer<>(1024);
        CountDownLatch done = new CountDownLatch(1);
        int[] failures = {0};
        long[] sum = {0};

        Thread consumer = new Thread(() -> {
            int expected = 0;
            while (expected < n) {
                Integer v = q.poll();
                if (v == null) {
                    Thread.onSpinWait();
                    continue;
                }
                if (v != expected) {
                    failures[0]++;
                }
                sum[0] += v;
                expected++;
            }
            done.countDown();
        });
        Thread producer = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                while (!q.offer(i)) {
                    Thread.onSpinWait();
                }
            }
        });
        consumer.start();
        producer.start();
        done.await();
        assertEquals(0, failures[0]);
        assertEquals((long) n * (n - 1) / 2, sum[0]);
    }
}
