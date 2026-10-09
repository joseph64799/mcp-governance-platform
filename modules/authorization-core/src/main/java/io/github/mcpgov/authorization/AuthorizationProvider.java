package io.github.mcpgov.authorization;

import io.github.mcpgov.contracts.Model.*;
import java.util.Map;
import java.util.Set;

/** One policy contract for discovery and invocation. Enterprise policy adapters implement both. */
public interface AuthorizationProvider {
    Set<String> visibleToolIds(Caller caller, CapabilityGroup group, Snapshot snapshot);
    ToolEntry authorizeCall(Caller caller, CapabilityGroup group, String alias,
                            Map<String,Object> arguments, Snapshot snapshot);
}
