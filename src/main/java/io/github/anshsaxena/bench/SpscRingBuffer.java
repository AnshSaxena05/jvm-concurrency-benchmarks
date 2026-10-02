package io.github.anshsaxena.bench;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded single-producer / single-consumer ring buffer with no locks and no CAS loops.
 *
 * <p>Correctness rests on one rule: exactly one thread calls {@link #offer} and exactly one thread
 * calls {@link #poll}. The producer owns {@code tail}, the consumer owns {@code head}. Each side
 * publishes its index with a release store ({@code lazySet}) after touching the slot, and reads the
 * other side's index with a volatile read, so a slot is never read before it is written and never
 * overwritten before it is read.
 *
 * <p>Capacity is rounded up to a power of two so the slot index is a mask, not a modulo.
 */
public final class SpscRingBuffer<E> {
    private final Object[] slots;
    private final int mask;
    private final AtomicLong head = new AtomicLong(); // next slot to read, written by the consumer
    private final AtomicLong tail = new AtomicLong(); // next slot to write, written by the producer

    public SpscRingBuffer(int minCapacity) {
        if (minCapacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2");
        }
        int cap = Integer.highestOneBit(minCapacity - 1) << 1;
        this.slots = new Object[cap];
        this.mask = cap - 1;
    }

    public int capacity() {
        return slots.length;
    }

    /** Producer thread only. Returns false when the buffer is full. */
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        long t = tail.get();
        if (t - head.get() == slots.length) {
            return false;
        }
        slots[(int) (t & mask)] = e;
        tail.lazySet(t + 1);
        return true;
    }

    /** Consumer thread only. Returns null when the buffer is empty. */
    @SuppressWarnings("unchecked")
    public E poll() {
        long h = head.get();
        if (h == tail.get()) {
            return null;
        }
        int i = (int) (h & mask);
        E e = (E) slots[i];
        slots[i] = null;
        head.lazySet(h + 1);
        return e;
    }

    public int size() {
        return (int) (tail.get() - head.get());
    }
}
