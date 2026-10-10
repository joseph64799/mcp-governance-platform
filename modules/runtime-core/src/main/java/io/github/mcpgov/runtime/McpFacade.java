package io.github.mcpgov.runtime;

import io.github.mcpgov.connector.McpConnector;
import io.github.mcpgov.contracts.*;
import io.github.mcpgov.contracts.Model.*;
import java.util.*;

/** Stateless tool-facing facade. User authorization decisions come only from the gateway permit. */
public final class McpFacade {
    private final McpConnector connector;
    public McpFacade(McpConnector connector) { this.connector = connector; }

    @SuppressWarnings("unchecked")
    public Map<String,Object> execute(String groupId, Map<String,Object> request, InvocationPermit permit, Snapshot snapshot) {
        if (!groupId.equals(permit.groupId()) || snapshot.version() != permit.snapshotVersion())
            throw new PlatformException(409, "configuration_version_mismatch");
        CapabilityGroup group = snapshot.groups().get(groupId);
        if (group == null || group.clientType() != permit.caller().clientType() || !group.tenant().equals(permit.caller().tenant()))
            throw new PlatformException(403, "group_access_denied");
        String method = String.valueOf(request.get("method"));
        Map<String,Object> params = request.get("params") instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
        return switch (method) {
            case "initialize" -> initialize(group, snapshot, permit, params);
            case "ping" -> Map.of();
            case "tools/list" -> {
                if (params.get("cursor") != null) throw new PlatformException(400, "unsupported_cursor");
                yield Map.of("tools", tools(group, snapshot, permit));
            }
            case "tools/call" -> {
                String alias = String.valueOf(params.get("name"));
                String toolId = group.tools().get(alias);
                ToolEntry tool = toolId == null ? null : snapshot.tools().get(toolId);
                if (!available(tool, permit, snapshot)) throw new PlatformException(403, "tool_access_denied");
                Map<String,Object> arguments = params.get("arguments") instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
                yield connector.invoke(snapshot.services().get(tool.serviceId()), tool, arguments, permit.caller());
            }
            default -> throw new PlatformException(400, "unsupported_mcp_method");
        };
    }
    private Map<String,Object> initialize(CapabilityGroup group, Snapshot snapshot, InvocationPermit permit, Map<String,Object> params) {
        String requested = String.valueOf(params.get("protocolVersion"));
        String protocol = Set.of("2025-03-26", "2025-06-18", "2025-11-25").contains(requested) ? requested : "2025-11-25";
        List<Map<String,Object>> visible = tools(group, snapshot, permit);
        String instructions = group.title() + "。当前可用工具：" + visible.stream()
            .map(tool -> String.valueOf(tool.get("name"))).reduce((a,b) -> a + "、" + b).orElse("无");
        return Map.of("protocolVersion", protocol, "capabilities", Map.of("tools", Map.of("listChanged", false)),
            "serverInfo", Map.of("name", group.id(), "title", group.title(), "version", "0.1.0"),
            "instructions", instructions);
    }
    private List<Map<String,Object>> tools(CapabilityGroup group, Snapshot snapshot, InvocationPermit permit) {
        List<Map<String,Object>> result = new ArrayList<>();
        group.tools().forEach((alias, id) -> {
            ToolEntry tool = snapshot.tools().get(id);
            if (available(tool, permit, snapshot)) {
                Map<String,Object> definition = new LinkedHashMap<>(tool.metadata());
                definition.put("name", alias); definition.put("description", tool.description()); definition.put("inputSchema", tool.inputSchema());
                result.add(definition);
            }
        });
        return List.copyOf(result);
    }
    private boolean available(ToolEntry tool, InvocationPermit permit, Snapshot snapshot) {
        if (tool == null || tool.state() != ToolState.PUBLISHED || !tool.tenant().equals(permit.caller().tenant()) ||
            !permit.allowedToolIds().contains(tool.id())) return false;
        ServiceRegistration source = snapshot.services().get(tool.serviceId());
        return source != null && source.enabled();
    }
}
