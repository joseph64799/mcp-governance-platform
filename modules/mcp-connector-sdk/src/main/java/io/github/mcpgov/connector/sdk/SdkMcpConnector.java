package io.github.mcpgov.connector.sdk;

import io.github.mcpgov.connector.*;
import io.github.mcpgov.contracts.*;
import io.github.mcpgov.contracts.Model.*;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Uses the official Java SDK for handshake, pagination, schema validation and upstream transport. */
public final class SdkMcpConnector implements McpConnector {
    private final CredentialProvider credentials;
    public SdkMcpConnector(CredentialProvider credentials) { this.credentials = credentials; }

    private McpSyncClient connect(ServiceRegistration service) {
        if (!service.enabled() || !"streamable-http".equals(service.transport())) throw new IllegalArgumentException("Unsupported service");
        URI uri = URI.create(service.endpoint());
        HttpRequest.Builder request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(15));
        credentials.headers(service.credentialRef()).forEach(request::header);
        var transport = HttpClientStreamableHttpTransport.builder(uri.getScheme() + "://" + uri.getRawAuthority())
            .endpoint(uri.getRawPath().isBlank() ? "/mcp" : uri.getRawPath())
            .clientBuilder(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER))
            .requestBuilder(request).build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build();
    }
    @Override public List<ObservedTool> discover(ServiceRegistration service) {
        McpSyncClient client = connect(service);
        try {
            client.initialize();
            List<ObservedTool> result = new ArrayList<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            do {
                var page = cursor == null ? client.listTools() : client.listTools(cursor);
                for (var tool : page.tools()) {
                    var metadata = Json.object(McpJsonDefaults.getMapper().writeValueAsString(tool));
                    result.add(new ObservedTool(tool.name(), tool.description(), tool.inputSchema(), metadata));
                }
                if (result.size() > 2000) throw new IllegalArgumentException("Upstream tool limit exceeded");
                cursor = page.nextCursor();
                if (cursor != null && (!cursors.add(cursor) || cursors.size() > 64))
                    throw new IllegalArgumentException("Invalid or excessive upstream pagination");
            } while (cursor != null);
            return List.copyOf(result);
        } catch (Exception e) { throw new PlatformException(502, "upstream_discovery_failed"); }
        finally { close(client); }
    }
    @Override public Map<String,Object> invoke(ServiceRegistration service, ToolEntry tool, Map<String,Object> arguments) {
        if (!McpJsonDefaults.getSchemaValidator().validate(tool.inputSchema(), arguments).valid())
            throw new PlatformException(400, "invalid_tool_arguments");
        McpSyncClient client = connect(service);
        try {
            client.initialize();
            // Exactly one invocation: a timeout may mean the business operation already executed.
            var result = client.callTool(new McpSchema.CallToolRequest(tool.upstreamName(), arguments, null));
            return Json.object(McpJsonDefaults.getMapper().writeValueAsString(result));
        } catch (Exception e) { throw new PlatformException(502, "upstream_call_failed"); }
        finally { close(client); }
    }
    private void close(McpSyncClient client) {
        try { client.closeGracefully(); } catch (RuntimeException ignored) { /* Do not overwrite a completed call result. */ }
    }
}
