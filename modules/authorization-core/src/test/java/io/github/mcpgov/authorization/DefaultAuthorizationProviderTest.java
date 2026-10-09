package io.github.mcpgov.authorization;

import io.github.mcpgov.contracts.*;
import io.github.mcpgov.contracts.Model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultAuthorizationProviderTest {
    final Caller alice = new Caller("alice", "tenant", "issuer", Set.of("operator"));
    final ServiceRegistration service = new ServiceRegistration("source", "tenant", "http://localhost/mcp", "streamable-http", null, true);
    final CapabilityGroup group = new CapabilityGroup("orders", "tenant", "Orders", "", Map.of("query", "tool"));
    final DefaultAuthorizationProvider auth = new DefaultAuthorizationProvider(ResourceAccessChecker.denyProtectedResources());
    ToolEntry tool(long revision, boolean protectedResource) {
        return new ToolEntry("tool", "source", "tenant", "find", "", Map.of("type", "object"), Map.of(), "hash",
            revision, ToolState.PUBLISHED, protectedResource);
    }
    Grant grant(String issuer, String groupId, long revision) {
        return new Grant("grant", "tenant", SubjectType.ROLE, "operator", issuer, groupId, "tool", revision);
    }
    Snapshot snapshot(ToolEntry tool, Grant grant) {
        return new Snapshot(1, Instant.now(), Map.of("source", service), Map.of("tool", tool), Map.of("orders", group),
            grant == null ? Map.of() : Map.of("grant", grant));
    }
    @Test void groupMembershipAndGuessedNamesDoNotAuthorize() {
        var state = snapshot(tool(1, false), null);
        assertTrue(auth.visibleToolIds(alice, group, state).isEmpty());
        assertThrows(PlatformException.class, () -> auth.authorizeCall(alice, group, "query", Map.of(), state));
        assertEquals(403, assertThrows(PlatformException.class,
            () -> auth.authorizeCall(alice, group, "guess", Map.of(), state)).status());
    }
    @Test void issuerTenantRevisionAndGroupScopeAreAllEnforced() {
        var state = snapshot(tool(1, false), grant("issuer", "orders", 1));
        assertEquals(Set.of("tool"), auth.visibleToolIds(alice, group, state));
        assertEquals("tool", auth.authorizeCall(alice, group, "query", Map.of(), state).id());
        assertTrue(auth.visibleToolIds(new Caller("alice", "other", "issuer", Set.of("operator")), group, state).isEmpty());
        assertTrue(auth.visibleToolIds(alice, group, snapshot(tool(1, false), grant("other", "orders", 1))).isEmpty());
        assertTrue(auth.visibleToolIds(alice, group, snapshot(tool(2, false), grant("issuer", "orders", 1))).isEmpty());
        var reused = new CapabilityGroup("another", "tenant", "Another", "", Map.of("query", "tool"));
        assertTrue(auth.visibleToolIds(alice, reused, state).isEmpty());
        assertEquals(Set.of("tool"), auth.visibleToolIds(alice, reused, snapshot(tool(1, false), grant("issuer", null, 1))));
    }
    @Test void protectedResourcesFailClosedUnlessAnAdapterGrantsTheSpecificArguments() {
        var state = snapshot(tool(1, true), grant("issuer", "orders", 1));
        assertEquals(Set.of("tool"), auth.visibleToolIds(alice, group, state));
        assertThrows(PlatformException.class, () -> auth.authorizeCall(alice, group, "query", Map.of("account", "other"), state));
        var scoped = new DefaultAuthorizationProvider((caller, tool, arguments) -> "mine".equals(arguments.get("account")));
        assertEquals("tool", scoped.authorizeCall(alice, group, "query", Map.of("account", "mine"), state).id());
        assertThrows(PlatformException.class, () -> scoped.authorizeCall(alice, group, "query", Map.of("account", "other"), state));
    }
}
