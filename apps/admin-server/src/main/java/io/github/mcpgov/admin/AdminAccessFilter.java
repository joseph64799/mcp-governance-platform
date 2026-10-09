package io.github.mcpgov.admin;

import io.github.mcpgov.contracts.*;
import io.github.mcpgov.security.ServiceTokens;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The console assets are public; every data or mutation endpoint requires an explicit credential. */
@Component
public class AdminAccessFilter implements WebFilter {
    private final String adminToken, readToken, discoveryToken;
    public AdminAccessFilter(Environment env) {
        adminToken = ServiceTokens.configured(env.getProperty("mcp.admin.token"));
        readToken = ServiceTokens.configured(env.getProperty("mcp.control.read-token"));
        discoveryToken = ServiceTokens.configured(env.getProperty("mcp.control.discovery-token"));
        if (adminToken.equals(readToken) || adminToken.equals(discoveryToken) || readToken.equals(discoveryToken))
            throw new IllegalArgumentException("API scopes must use different credentials");
    }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String expected = path.equals("/internal/snapshot") ? readToken :
            path.equals("/internal/discovery") ? discoveryToken : path.startsWith("/api/") ? adminToken : null;
        if (expected == null && !path.startsWith("/internal/")) return chain.filter(exchange);
        try {
            String origin = exchange.getRequest().getHeaders().getOrigin();
            String own = exchange.getRequest().getURI().getScheme() + "://" + exchange.getRequest().getURI().getRawAuthority();
            if (origin != null && !origin.equals(own)) throw new PlatformException(403, "origin_rejected");
            if (expected == null) throw new PlatformException(404, "not_found");
            ServiceTokens.require(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION), expected);
            return chain.filter(exchange);
        } catch (PlatformException e) {
            exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(e.status()));
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
                .wrap(Json.write(Map.of("error", e.code())).getBytes(StandardCharsets.UTF_8))));
        }
    }
}
