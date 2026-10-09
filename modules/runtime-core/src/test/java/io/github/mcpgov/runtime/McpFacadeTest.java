package io.github.mcpgov.runtime;

import io.github.mcpgov.connector.McpConnector;
import io.github.mcpgov.contracts.*;
import io.github.mcpgov.contracts.Model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class McpFacadeTest {
    final AtomicInteger invocations = new AtomicInteger();
    final McpFacade facade = new McpFacade(new McpConnector() {
        public List<ObservedTool> discover(ServiceRegistration service) { return List.of(); }
        public Map<String,Object> invoke(ServiceRegistration service, ToolEntry tool, Map<String,Object> args) {
            invocations.incrementAndGet(); return Map.of("content", List.of(Map.of("type", "text", "text", tool.upstreamName())));
        }
    });
    ToolEntry tool(String id) { return new ToolEntry(id, "source", "tenant", id, id + " description",
        Map.of("type", "object"), Map.of(), "hash", 1, ToolState.PUBLISHED, false); }
    Snapshot snapshot() { return new Snapshot(7, Instant.EPOCH,
        Map.of("source", new ServiceRegistration("source", "tenant", "http://localhost/mcp", "streamable-http", null, true)),
        Map.of("query", tool("query"), "refund", tool("refund")),
        Map.of("orders", new CapabilityGroup("orders", "tenant", "Orders", "Contains query and refund",
            Map.of("find_order", "query", "refund_order", "refund"))), Map.of()); }
    InvocationPermit permit(long version) { return new InvocationPermit("nonce", new Caller("alice", "tenant", "issuer", Set.of()),
        "orders", version, Set.of("query"), 1, 16, "hash"); }
    Map<String,Object> call(String name) { return Map.of("method", "tools/call", "params", Map.of("name", name)); }
    @Test void userSpecificDefinitionsAndInstructionsDoNotDiscloseHiddenTools() {
        String list = Json.write(facade.execute("orders", Map.of("method", "tools/list"), permit(7), snapshot()));
        assertTrue(list.contains("find_order")); assertFalse(list.contains("refund"));
        String init = Json.write(facade.execute("orders", Map.of("method", "initialize"), permit(7), snapshot()));
        assertTrue(init.contains("find_order")); assertFalse(init.contains("refund"));
    }
    @Test void onlyAnAuthorizedAliasCanInvokeAndVersionsMustAgree() {
        assertThrows(PlatformException.class, () -> facade.execute("orders", call("refund_order"), permit(7), snapshot()));
        assertThrows(PlatformException.class, () -> facade.execute("orders", call("guess"), permit(7), snapshot()));
        assertThrows(PlatformException.class, () -> facade.execute("orders", call("find_order"), permit(6), snapshot()));
        assertThrows(PlatformException.class, () -> facade.execute("another", call("find_order"), permit(7), snapshot()));
        assertEquals(0, invocations.get());
        facade.execute("orders", call("find_order"), permit(7), snapshot());
        assertEquals(1, invocations.get());
    }
}
