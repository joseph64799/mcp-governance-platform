import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

/** Black-box compatibility probe: an official MCP client talks to the public gateway. */
public class SdkProbe {
    public static void main(String[] args) {
        URI endpoint = URI.create(System.getenv("PROBE_ENDPOINT"));
        var transport = HttpClientStreamableHttpTransport.builder(endpoint.getScheme() + "://" + endpoint.getRawAuthority())
            .endpoint(endpoint.getPath())
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + System.getenv("PROBE_TOKEN")))
            .build();
        var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build();
        try {
            client.initialize();
            var tools = client.listTools().tools();
            if (tools.size() != 1 || !"find_order".equals(tools.getFirst().name()))
                throw new AssertionError("Gateway did not return the expected user-specific tools");
            var result = client.callTool(new McpSchema.CallToolRequest("find_order", Map.of("orderId", "sdk-probe"), null));
            if (Boolean.TRUE.equals(result.isError()) || result.content().isEmpty())
                throw new AssertionError("Gateway tool invocation failed");
            System.out.println("PASS: official MCP Java client initialization, listing and invocation through gateway");
        } finally { client.closeGracefully(); }
    }
}
