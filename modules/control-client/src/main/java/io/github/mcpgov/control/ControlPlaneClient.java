package io.github.mcpgov.control;

import io.github.mcpgov.contracts.*;
import io.github.mcpgov.contracts.Model.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Internal management API client with bounded calls and explicit credentials. */
public final class ControlPlaneClient {
    private final String baseUrl;
    private final String readToken;
    private final String discoveryToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public ControlPlaneClient(String baseUrl, String readToken, String discoveryToken) {
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.readToken = readToken; this.discoveryToken = discoveryToken;
    }
    public Snapshot snapshot() { return Json.read(send("/internal/snapshot", "GET", "", readToken), Snapshot.class); }
    public Snapshot observe(DiscoveryReport report) {
        return Json.read(send("/internal/discovery", "POST", Json.write(report), discoveryToken), Snapshot.class);
    }
    private String send(String path, String method, String body, String token) {
        if (token == null || token.length() < 32) throw new IllegalStateException("Internal token is not configured");
        try {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
            var request = builder.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new PlatformException(503, "control_plane_unavailable");
            return response.body();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new PlatformException(503, "control_plane_unavailable"); }
        catch (Exception e) { throw new PlatformException(503, "control_plane_unavailable"); }
    }
}
