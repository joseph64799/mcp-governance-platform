package io.github.mcpgov.connector;
import io.github.mcpgov.contracts.Model.*;
import java.util.List;
import java.util.Map;

/** Discovery returns a complete list or throws; invocation must never be automatically retried. */
public interface McpConnector {
    List<ObservedTool> discover(ServiceRegistration service);
    Map<String,Object> invoke(ServiceRegistration service, ToolEntry tool, Map<String,Object> arguments);
}
