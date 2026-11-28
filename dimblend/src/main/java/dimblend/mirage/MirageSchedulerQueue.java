package dimblend.mirage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Pure scheduling state of the mirage worker: which pending order runs next, and which chunk keys
 * that order gets this time.
 *
 * <p><b>Why it is a separate, minecraft-free class.</b> The three properties that decide whether a
 * streaming run is smooth are all decided here, and none of them can be observed from a live
 * server without a 20 second flight: a <code>primary</code> order must strictly beat an
 * <code>opposite</code> prefetch between mini-batches, chunks already in the {@code MirageServerManager}
 * cache must never be generated twice, and every mini-batch must respect a fixed size so the send
 * queue can drain it inside one tick. Keeping the picker pure lets the tests in
 * {@code MirageServerStreamingTest} pin all three.</p>
 *
 * <p><b>Orders, not jobs.</b> Each channel (player + seam side) owns at most one order. Submitting
 * again for the same channel replaces its remaining key list — that is how a same-seam cap growth
 * extends the work instead of restarting it — while a chunk that the worker is already generating
 * is filtered by the {@code alreadyDone} predicate, so it is never queued twice.</p>
 *
 * <p>Not thread safe on purpose: {@code MirageServerManager} mutates it under its own
 * {@code schedulerLock} only.</p>
 *
 * @param <K> channel key of the caller (the manager uses player + seam side)
 */
public final class MirageSchedulerQueue<K> {
    private final int batchSize;
    private final Map<K, Order<K>> orders = new LinkedHashMap<>();
    private long sequence;

    public MirageSchedulerQueue(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch size must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /** One mini-batch handed to the caller: the order to run and the keys to generate, in order. */
    public record Batch<K>(K channel, long seamId, boolean primary, List<MirageChunkKey> keys) {
    }

    /**
     * Replaces the order of {@code channel}: its seam, its priority and its remaining keys, ordered
     * front-to-back. An empty key list simply drops the order.
     */
    public void submit(K channel, long seamId, boolean primary, Collection<MirageChunkKey> orderedKeys) {
        Objects.requireNonNull(channel, "channel");
        Order<K> order = this.orders.get(channel);
        if (order == null) {
            order = new Order<>(channel);
            this.orders.put(channel, order);
        }
        order.seamId = seamId;
        order.primary = primary;
        order.sequence = ++this.sequence;
        order.remaining.clear();
        order.remaining.addAll(orderedKeys);
        if (order.remaining.isEmpty()) {
            this.orders.remove(channel);
        }
    }

    public void remove(K channel) {
        this.orders.remove(channel);
    }

    public boolean contains(K channel) {
        return this.orders.containsKey(channel);
    }

    public boolean isEmpty() {
        return this.orders.isEmpty();
    }

    public int size() {
        return this.orders.size();
    }

    /** Remaining keys of one order, for diagnostics and tests; {@code 0} when none is pending. */
    public int remaining(K channel) {
        Order<K> order = this.orders.get(channel);
        return order == null ? 0 : order.remaining.size();
    }

    public void clear() {
        this.orders.clear();
    }

    /**
     * Pops the next mini-batch: the highest-priority order (every {@code primary} order beats every
     * {@code opposite} one, then the most recently submitted), up to
     * {@code batchSize} keys that {@code alreadyDone} does not reject.
     *
     * <p>Exhausted orders are removed. Returns {@code null} when nothing is left to generate, in
     * which case the caller should park and wait for the next {@link #submit}.</p>
     */
    public Batch<K> takeNext(Predicate<MirageChunkKey> alreadyDone) {
        while (true) {
            Order<K> order = this.best();
            if (order == null) {
                return null;
            }
            List<MirageChunkKey> batch = new ArrayList<>(this.batchSize);
            while (batch.size() < this.batchSize && !order.remaining.isEmpty()) {
                MirageChunkKey key = order.remaining.pollFirst();
                if (alreadyDone.test(key)) {
                    continue; // generated (or being generated) elsewhere: zero wasted work
                }
                batch.add(key);
            }
            if (order.remaining.isEmpty()) {
                this.orders.remove(order.channel);
            }
            if (!batch.isEmpty()) {
                return new Batch<>(order.channel, order.seamId, order.primary, batch);
            }
        }
    }

    private Order<K> best() {
        Order<K> bestPrimary = null;
        Order<K> bestAny = null;
        for (Order<K> order : this.orders.values()) {
            if (bestAny == null || order.sequence > bestAny.sequence) {
                bestAny = order;
            }
            if (order.primary && (bestPrimary == null || order.sequence > bestPrimary.sequence)) {
                bestPrimary = order;
            }
        }
        return bestPrimary != null ? bestPrimary : bestAny;
    }

    private static final class Order<K> {
        private final K channel;
        private final Deque<MirageChunkKey> remaining = new ArrayDeque<>();
        private long seamId;
        private boolean primary;
        private long sequence;

        private Order(K channel) {
            this.channel = channel;
        }
    }
}
