package io.github.mcpgov.catalog;

import io.github.mcpgov.contracts.Json;
import io.github.mcpgov.contracts.Model.*;
import io.github.mcpgov.contracts.PlatformException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Single management owner: discoveries are observations, publication and grants are explicit decisions. */
public final class CatalogService {
    private final CatalogStore store;
    private final Set<String> allowedHosts;
    private volatile Snapshot snapshot;
    public CatalogService(CatalogStore store, Set<String> allowedHosts) {
        this.store = store; this.allowedHosts = Set.copyOf(allowedHosts); this.snapshot = store.load();
    }
    public Snapshot snapshot() { return snapshot; }

    public synchronized Snapshot register(ServiceRegistration service) {
        URI uri = URI.create(service.endpoint());
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null ||
            uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null ||
            !allowedHosts.contains(uri.getHost())) throw new IllegalArgumentException("Upstream endpoint is not allowed");
        if (!"streamable-http".equals(service.transport())) throw new IllegalArgumentException("Unsupported transport");
        var services = new HashMap<>(snapshot.services());
        var tools = new HashMap<>(snapshot.tools());
        ServiceRegistration previous = services.get(service.id());
        if (previous != null && !previous.tenant().equals(service.tenant()))
            throw new IllegalArgumentException("Service tenant is immutable");
        if (service.equals(previous)) return snapshot;
        services.put(service.id(), service);
        if (previous != null) {
            // Changing the destination invalidates old review decisions, even with identical tool names.
            tools.replaceAll((id, t) -> t.serviceId().equals(service.id())
                ? new ToolEntry(t.id(), t.serviceId(), t.tenant(), t.upstreamName(), t.description(),
                    t.inputSchema(), t.metadata(), t.fingerprint(), t.revision() + 1, ToolState.REMOVED, t.resourceProtected())
                : t);
        }
        return save(services, tools, snapshot.groups(), snapshot.grants());
    }
    public synchronized Snapshot observe(DiscoveryReport report) {
        if (!report.complete()) throw new IllegalArgumentException("Incomplete discovery is not a deletion signal");
        ServiceRegistration service = snapshot.services().get(report.serviceId());
        if (service == null || !service.enabled()) throw new IllegalArgumentException("Unknown or disabled service");
        if (!Json.sha256(Json.write(service)).equals(report.registrationHash()))
            throw new PlatformException(409, "service_configuration_changed");
        if (report.tools().size() > 2000) throw new IllegalArgumentException("Tool limit exceeded");
        Set<String> names = new HashSet<>();
        if (report.tools().stream().anyMatch(t -> !names.add(t.name()))) throw new IllegalArgumentException("Duplicate upstream tool names");
        var tools = new HashMap<>(snapshot.tools());
        Set<String> seen = new HashSet<>();
        for (ObservedTool observed : report.tools()) {
            String id = UUID.nameUUIDFromBytes((service.tenant() + "\n" + service.id() + "\n" + observed.name())
                .getBytes(StandardCharsets.UTF_8)).toString();
            seen.add(id);
            String fingerprint = Json.sha256(Json.write(Map.of("service", service, "tool", observed)));
            ToolEntry previous = tools.get(id);
            if (previous != null && previous.state() != ToolState.REMOVED && previous.fingerprint().equals(fingerprint)) continue;
            tools.put(id, new ToolEntry(id, service.id(), service.tenant(), observed.name(), observed.description(),
                observed.inputSchema(), observed.metadata(), fingerprint, previous == null ? 1 : previous.revision() + 1,
                ToolState.PENDING, previous != null && previous.resourceProtected()));
        }
        tools.replaceAll((id, tool) -> tool.serviceId().equals(service.id()) && !seen.contains(id)
            ? tool.state(ToolState.REMOVED, tool.resourceProtected()) : tool);
        if (tools.equals(snapshot.tools())) return snapshot;
        return save(snapshot.services(), tools, snapshot.groups(), snapshot.grants());
    }
    public synchronized Snapshot publish(String id, boolean resourceProtected) {
        ToolEntry tool = requireTool(id);
        ServiceRegistration source = snapshot.services().get(tool.serviceId());
        if (tool.state() == ToolState.REMOVED || source == null || !source.enabled())
            throw new IllegalArgumentException("Cannot publish unavailable tool");
        var tools = new HashMap<>(snapshot.tools());
        // Altering resource-security semantics is a version change and requires renewed grants.
        if (resourceProtected != tool.resourceProtected()) {
            tool = new ToolEntry(tool.id(), tool.serviceId(), tool.tenant(), tool.upstreamName(), tool.description(),
                tool.inputSchema(), tool.metadata(), tool.fingerprint(), tool.revision() + 1, ToolState.PENDING, resourceProtected);
        }
        tools.put(id, tool.state(ToolState.PUBLISHED, resourceProtected));
        return save(snapshot.services(), tools, snapshot.groups(), snapshot.grants());
    }
    public synchronized Snapshot disable(String id) {
        ToolEntry tool = requireTool(id);
        if (tool.state() == ToolState.REMOVED) throw new IllegalArgumentException("Tool is already removed");
        var tools = new HashMap<>(snapshot.tools()); tools.put(id, tool.state(ToolState.DISABLED, tool.resourceProtected()));
        return save(snapshot.services(), tools, snapshot.groups(), snapshot.grants());
    }
    public synchronized Snapshot group(CapabilityGroup group) {
        CapabilityGroup previous = snapshot.groups().get(group.id());
        if (previous != null && !previous.tenant().equals(group.tenant())) throw new IllegalArgumentException("Group tenant is immutable");
        group.tools().values().forEach(id -> {
            ToolEntry tool = requireTool(id);
            if (!tool.tenant().equals(group.tenant()) || tool.state() == ToolState.REMOVED)
                throw new IllegalArgumentException("Invalid group tool reference");
        });
        var groups = new HashMap<>(snapshot.groups()); groups.put(group.id(), group);
        return save(snapshot.services(), snapshot.tools(), groups, snapshot.grants());
    }
    public synchronized Snapshot grant(Grant grant) {
        ToolEntry tool = requireTool(grant.toolId());
        if (!tool.tenant().equals(grant.tenant()) || tool.state() != ToolState.PUBLISHED || tool.revision() != grant.toolRevision())
            throw new IllegalArgumentException("Grant must reference the current published revision");
        if (grant.groupId() != null) {
            CapabilityGroup group = snapshot.groups().get(grant.groupId());
            if (group == null || !group.tenant().equals(grant.tenant()) || !group.tools().containsValue(tool.id()))
                throw new IllegalArgumentException("Invalid grant group");
        }
        var grants = new HashMap<>(snapshot.grants()); grants.put(grant.id(), grant);
        return save(snapshot.services(), snapshot.tools(), snapshot.groups(), grants);
    }
    public synchronized Snapshot revoke(String id) {
        var grants = new HashMap<>(snapshot.grants()); grants.remove(id);
        return save(snapshot.services(), snapshot.tools(), snapshot.groups(), grants);
    }
    private ToolEntry requireTool(String id) {
        ToolEntry tool = snapshot.tools().get(id);
        if (tool == null) throw new IllegalArgumentException("Unknown tool");
        return tool;
    }
    private Snapshot save(Map<String,ServiceRegistration> services, Map<String,ToolEntry> tools,
                          Map<String,CapabilityGroup> groups, Map<String,Grant> grants) {
        Snapshot next = new Snapshot(snapshot.version() + 1, Instant.now(), services, tools, groups, grants);
        store.save(snapshot.version(), next); snapshot = next; return next;
    }
}
