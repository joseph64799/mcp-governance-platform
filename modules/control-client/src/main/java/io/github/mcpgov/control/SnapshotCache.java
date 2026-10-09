package io.github.mcpgov.control;

import io.github.mcpgov.contracts.Model.Snapshot;
import io.github.mcpgov.contracts.PlatformException;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Background refresh with a bounded lease. Request processing never synchronously calls the admin service. */
public final class SnapshotCache implements AutoCloseable {
    private record Cached(Snapshot snapshot, Instant received) {}
    private final AtomicReference<Cached> cache = new AtomicReference<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "catalog-refresh"); thread.setDaemon(true); return thread;
    });
    private final Supplier<Snapshot> loader;
    private final Duration maxAge;
    private final Clock clock;
    public SnapshotCache(Supplier<Snapshot> loader, Duration period, Duration maxAge) {
        this(loader, period, maxAge, Clock.systemUTC());
    }
    public SnapshotCache(Supplier<Snapshot> loader, Duration period, Duration maxAge, Clock clock) {
        if (period.isZero() || period.isNegative() || maxAge.compareTo(period) < 0) throw new IllegalArgumentException("Invalid snapshot lease");
        this.loader = loader; this.maxAge = maxAge; this.clock = clock;
        executor.scheduleWithFixedDelay(this::refresh, 0, period.toMillis(), TimeUnit.MILLISECONDS);
    }
    public synchronized void refresh() {
        try {
            Snapshot next = loader.get();
            Cached previous = cache.get();
            if (previous != null && next.version() < previous.snapshot().version()) return;
            cache.set(new Cached(next, clock.instant()));
        } catch (RuntimeException ignored) { /* Preserve last complete snapshot; its lease still expires. */ }
    }
    public Snapshot current() {
        Cached value = cache.get();
        if (value == null || !clock.instant().isBefore(value.received().plus(maxAge)))
            throw new PlatformException(503, "configuration_unavailable");
        return value.snapshot();
    }
    @Override public void close() { executor.shutdownNow(); }
}
