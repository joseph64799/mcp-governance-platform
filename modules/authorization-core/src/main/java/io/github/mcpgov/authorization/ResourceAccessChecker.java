package io.github.mcpgov.authorization;

import io.github.mcpgov.contracts.Model.*;
import java.util.Map;

/** Resolve actual ownership from trusted business data, not a caller-supplied tenant field. */
@FunctionalInterface
public interface ResourceAccessChecker {
    boolean allowed(Caller caller, ToolEntry tool, Map<String,Object> arguments);
    static ResourceAccessChecker denyProtectedResources() { return (caller, tool, arguments) -> false; }
}
