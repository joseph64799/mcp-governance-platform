package io.github.mcpgov.discovery;

import io.github.mcpgov.connector.McpConnector;
import io.github.mcpgov.contracts.Json;
import io.github.mcpgov.contracts.Model.*;
import io.github.mcpgov.control.ControlPlaneClient;

/** Complete fetch before commit: partial pages, failures and timeouts never remove catalog entries. */
public final class DiscoveryCoordinator {
    private final ControlPlaneClient control;
    private final McpConnector connector;
    public DiscoveryCoordinator(ControlPlaneClient control, McpConnector connector) {
        this.control = control; this.connector = connector;
    }
    public Snapshot synchronize(ServiceRegistration service) {
        var tools = connector.discover(service);
        return control.observe(new DiscoveryReport(service.id(), Json.sha256(Json.write(service)), true, tools));
    }
}
