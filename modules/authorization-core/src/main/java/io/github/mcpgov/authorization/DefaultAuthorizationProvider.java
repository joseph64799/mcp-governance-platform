package io.github.mcpgov.authorization;

import io.github.mcpgov.contracts.Model.*;
import io.github.mcpgov.contracts.PlatformException;
import java.util.*;
import java.util.stream.Collectors;

/** Default deny. Group membership alone never grants permission. */
public final class DefaultAuthorizationProvider implements AuthorizationProvider {
    private final ResourceAccessChecker resourceChecker;
    public DefaultAuthorizationProvider(ResourceAccessChecker resourceChecker) { this.resourceChecker = resourceChecker; }

    @Override public Set<String> visibleToolIds(Caller caller, CapabilityGroup group, Snapshot snapshot) {
        if (group == null || !caller.tenant().equals(group.tenant())) return Set.of();
        return group.tools().values().stream()
            .filter(id -> permitted(caller, group, snapshot.tools().get(id), snapshot))
            .collect(Collectors.toUnmodifiableSet());
    }
    @Override public ToolEntry authorizeCall(Caller caller, CapabilityGroup group, String alias,
                                             Map<String,Object> arguments, Snapshot snapshot) {
        if (group == null || alias == null) throw denied();
        String toolId = group.tools().get(alias);
                ToolEntry tool = toolId == null ? null : snapshot.tools().get(toolId);
        if (!permitted(caller, group, tool, snapshot)) throw denied();
        if (tool.resourceProtected() && !resourceChecker.allowed(caller, tool, arguments)) throw denied();
        return tool;
    }
    private boolean permitted(Caller caller, CapabilityGroup group, ToolEntry tool, Snapshot snapshot) {
        if (tool == null || group == null || tool.state() != ToolState.PUBLISHED ||
            !caller.tenant().equals(group.tenant()) || !caller.tenant().equals(tool.tenant()) ||
            !group.tools().containsValue(tool.id())) return false;
        ServiceRegistration service = snapshot.services().get(tool.serviceId());
        if (service == null || !service.enabled()) return false;
        return snapshot.grants().values().stream().anyMatch(grant ->
            grant.tenant().equals(caller.tenant()) &&
            grant.issuer().equals(caller.issuer()) &&
            grant.toolId().equals(tool.id()) &&
            grant.toolRevision() == tool.revision() &&
            (grant.groupId() == null || grant.groupId().equals(group.id())) &&
            (grant.subjectType() == SubjectType.USER
                ? grant.subject().equals(caller.subject())
                : caller.roles().contains(grant.subject())));
    }
    private PlatformException denied() { return new PlatformException(403, "tool_access_denied"); }
}
