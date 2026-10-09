package io.github.mcpgov.catalog;

import io.github.mcpgov.contracts.*;
import io.github.mcpgov.contracts.Model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogServiceTest {
    @TempDir Path directory;
    ServiceRegistration service = new ServiceRegistration("orders", "acme", "http://localhost:9999/mcp", "streamable-http", null, true);
    CatalogService catalog() { return new CatalogService(new FileCatalogStore(directory.resolve("catalog.json")), Set.of("localhost")); }
    ObservedTool observed(String description) {
        return new ObservedTool("query", description, Map.of("type", "object", "properties", Map.of()), Map.of());
    }
    DiscoveryReport report(List<ObservedTool> tools) {
        return new DiscoveryReport(service.id(), Json.sha256(Json.write(service)), true, tools);
    }
    @Test void discoveryRequiresReviewAndChangedDefinitionsRequireNewGrants() {
        var catalog = catalog();
        catalog.register(service);
        var entry = catalog.observe(report(List.of(observed("old")))).tools().values().iterator().next();
        assertEquals(ToolState.PENDING, entry.state());
        var grant = new Grant("alice-query", "acme", SubjectType.USER, "alice", "company", null, entry.id(), entry.revision());
        assertThrows(IllegalArgumentException.class, () -> catalog.grant(grant));
        catalog.publish(entry.id(), false); catalog.grant(grant);
        long stable = catalog.snapshot().version();
        assertEquals(stable, catalog.observe(report(List.of(observed("old")))).version());
        var changed = catalog.observe(report(List.of(observed("new")))).tools().get(entry.id());
        assertEquals(ToolState.PENDING, changed.state());
        assertTrue(changed.revision() > entry.revision());
        catalog.publish(entry.id(), false);
        assertThrows(IllegalArgumentException.class, () -> catalog.grant(grant));
        assertEquals(catalog.snapshot(), catalog().snapshot(), "File store must survive a management process restart");
    }
    @Test void onlyCompleteCurrentDiscoveryMayRemoveAndReappearanceIsUnpublished() {
        var catalog = catalog(); catalog.register(service);
        var entry = catalog.observe(report(List.of(observed("query")))).tools().values().iterator().next();
        catalog.publish(entry.id(), false);
        long before = catalog.snapshot().version();
        assertThrows(IllegalArgumentException.class, () -> catalog.observe(
            new DiscoveryReport("orders", Json.sha256(Json.write(service)), false, List.of())));
        assertThrows(PlatformException.class, () -> catalog.observe(new DiscoveryReport("orders", "stale", true, List.of())));
        assertEquals(before, catalog.snapshot().version());
        assertEquals(ToolState.REMOVED, catalog.observe(report(List.of())).tools().get(entry.id()).state());
        var reappeared = catalog.observe(report(List.of(observed("query")))).tools().get(entry.id());
        assertEquals(ToolState.PENDING, reappeared.state());
        assertTrue(reappeared.revision() > entry.revision());
    }
    @Test void destinationChangeCannotRepublishAnUndiscoveredDefinition() {
        var catalog = catalog(); catalog.register(service);
        var entry = catalog.observe(report(List.of(observed("query")))).tools().values().iterator().next();
        catalog.publish(entry.id(), false);
        catalog.register(new ServiceRegistration("orders", "acme", "http://localhost:9998/mcp", "streamable-http", null, true));
        assertThrows(IllegalArgumentException.class, () -> catalog.publish(entry.id(), false));
        assertThrows(PlatformException.class, () -> catalog.observe(report(List.of())));
    }
    @Test void rejectCrossTenantGroupsAndUnapprovedDestinationsAndExternalSchemas() {
        var catalog = catalog(); catalog.register(service);
        var entry = catalog.observe(report(List.of(observed("query")))).tools().values().iterator().next();
        assertThrows(IllegalArgumentException.class, () -> catalog.group(new CapabilityGroup("ops", "other", "ops", "", Map.of("query", entry.id()))));
        assertThrows(IllegalArgumentException.class, () -> catalog.register(
            new ServiceRegistration("bad", "acme", "http://unapproved.example/mcp", "streamable-http", null, true)));
        assertThrows(IllegalArgumentException.class, () -> new ObservedTool("bad", "",
            Map.of("type", "object", "properties", Map.of("item", Map.of("$ref", "https://unapproved.example/schema"))), Map.of()));
    }
}
