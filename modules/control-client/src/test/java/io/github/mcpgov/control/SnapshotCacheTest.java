package io.github.mcpgov.control;

import io.github.mcpgov.contracts.Model.Snapshot;
import io.github.mcpgov.contracts.PlatformException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Map;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCacheTest {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    }
    @Test void failuresDoNotEraseSnapshotButCannotRenewItsLeaseOrRollbackVersion() {
        var clock = new MutableClock();
        var fail = new AtomicBoolean(false);
        var version = new AtomicLong(4);
        try (var cache = new SnapshotCache(() -> {
            if (fail.get()) throw new IllegalStateException("offline");
            return new Snapshot(version.get(), Instant.EPOCH, Map.of(), Map.of(), Map.of(), Map.of());
        }, Duration.ofHours(1), Duration.ofHours(2), clock)) {
            cache.refresh(); assertEquals(4, cache.current().version());
            clock.now.set(Instant.EPOCH.plusSeconds(3600)); fail.set(true); cache.refresh();
            assertEquals(4, cache.current().version());
            version.set(3); fail.set(false); cache.refresh();
            assertEquals(4, cache.current().version());
            clock.now.set(Instant.EPOCH.plusSeconds(7201));
            assertThrows(PlatformException.class, cache::current);
            version.set(5); cache.refresh(); assertEquals(5, cache.current().version());
        }
    }
}
