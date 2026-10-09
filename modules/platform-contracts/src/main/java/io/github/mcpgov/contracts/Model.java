package io.github.mcpgov.contracts;

import java.time.Instant;
import java.util.*;

/** Stable cross-process contracts. Never store access tokens or client secrets here. */
public final class Model {
    private Model() {}
    public enum ToolState { PENDING, PUBLISHED, DISABLED, REMOVED }
    public enum SubjectType { USER, ROLE }

    public record Caller(String subject, String tenant, String issuer, Set<String> roles) {
        public Caller {
            requireText(subject, "subject"); requireText(tenant, "tenant"); requireText(issuer, "issuer");
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }
    public record ServiceRegistration(String id, String tenant, String endpoint, String transport,
                                      String credentialRef, boolean enabled) {
        public ServiceRegistration {
            requireId(id); requireId(tenant); requireText(endpoint, "endpoint");
            requireText(transport, "transport");
        }
    }
    public record ObservedTool(String name, String description, Map<String,Object> inputSchema,
                               Map<String,Object> metadata) {
        public ObservedTool {
            requireText(name, "name");
            description = description == null ? "" : description;
            inputSchema = Json.freeze(Objects.requireNonNull(inputSchema, "inputSchema"));
            if (!"object".equals(inputSchema.get("type"))) throw new IllegalArgumentException("Tool inputSchema must be an object schema");
            rejectExternalReferences(inputSchema);
            metadata = metadata == null ? Map.of() : Json.freeze(metadata);
        }
    }
    public record ToolEntry(String id, String serviceId, String tenant, String upstreamName,
                            String description, Map<String,Object> inputSchema, Map<String,Object> metadata,
                            String fingerprint, long revision, ToolState state, boolean resourceProtected) {
        public ToolEntry {
            inputSchema = Json.freeze(inputSchema); metadata = Json.freeze(metadata);
            Objects.requireNonNull(state);
        }
        public ToolEntry state(ToolState next, boolean protectResources) {
            return new ToolEntry(id, serviceId, tenant, upstreamName, description, inputSchema, metadata,
                fingerprint, revision, next, protectResources);
        }
    }
    public record CapabilityGroup(String id, String tenant, String title, String description,
                                  Map<String,String> tools) {
        public CapabilityGroup {
            requireId(id); requireId(tenant); requireText(title, "title");
            description = description == null ? "" : description;
            tools = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(tools)));
            tools.keySet().forEach(Model::requireId);
        }
    }
    /** toolRevision is explicit: a schema/meaning change cannot silently inherit the old grant. */
    public record Grant(String id, String tenant, SubjectType subjectType, String subject,
                        String issuer, String groupId, String toolId, long toolRevision) {
        public Grant {
            requireId(id); requireId(tenant); Objects.requireNonNull(subjectType);
            requireText(subject, "subject"); requireText(issuer, "issuer"); requireText(toolId, "toolId");
        }
    }
    public record Snapshot(long version, Instant updatedAt,
                           Map<String,ServiceRegistration> services, Map<String,ToolEntry> tools,
                           Map<String,CapabilityGroup> groups, Map<String,Grant> grants) {
        public Snapshot {
            services = Map.copyOf(services); tools = Map.copyOf(tools);
            groups = Map.copyOf(groups); grants = Map.copyOf(grants);
        }
        public static Snapshot empty() { return new Snapshot(0, Instant.EPOCH, Map.of(), Map.of(), Map.of(), Map.of()); }
    }
    /** A complete report is submitted only after all MCP pages have been fetched successfully. */
    public record DiscoveryReport(String serviceId, String registrationHash, boolean complete, List<ObservedTool> tools) {
        public DiscoveryReport { tools = List.copyOf(tools); }
    }
    public record InvocationPermit(String requestId, Caller caller, String groupId, long snapshotVersion,
                                   Set<String> allowedToolIds, long issuedAt, long expiresAt, String requestHash) {
        public InvocationPermit { allowedToolIds = Set.copyOf(allowedToolIds); }
    }
    /** Offline schemas only: discovery must not turn schema validation into arbitrary outbound requests. */
    private static void rejectExternalReferences(Object value) {
        if (value instanceof Map<?,?> map) {
            for (String key : List.of("$ref", "$dynamicRef", "$recursiveRef")) {
                Object ref = map.get(key);
                if (ref != null && (!(ref instanceof String text) || !text.startsWith("#")))
                    throw new IllegalArgumentException("Only local JSON Schema references are supported");
            }
            map.values().forEach(Model::rejectExternalReferences);
        } else if (value instanceof Collection<?> collection) collection.forEach(Model::rejectExternalReferences);
    }
    public static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
    public static void requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}"))
            throw new IllegalArgumentException("Identifier must contain 1-128 letters, digits, dot, underscore or dash");
    }
}
