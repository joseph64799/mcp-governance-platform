package io.github.mcpgov.gateway;

import io.github.mcpgov.audit.AuditSink;
import io.github.mcpgov.authorization.AuthorizationProvider;
import io.github.mcpgov.contracts.*;
import io.github.mcpgov.contracts.Model.*;
import io.github.mcpgov.control.SnapshotCache;
import io.github.mcpgov.identity.IdentityProvider;
import io.github.mcpgov.security.PermitSigner;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.http.server.reactive.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Central user policy enforcement before Spring Cloud Gateway routes the request. */
@Component
public class McpAdmissionFilter implements WebFilter, Ordered {
    private final IdentityProvider identities;
    private final AuthorizationProvider permissions;
    private final SnapshotCache snapshots;
    private final PermitSigner signer;
    private final AuditSink audit;
    private final Set<String> allowedOrigins;
    public McpAdmissionFilter(IdentityProvider identities, AuthorizationProvider permissions, SnapshotCache snapshots,
                              PermitSigner signer, AuditSink audit, Environment env) {
        this.identities = identities; this.permissions = permissions; this.snapshots = snapshots;
        this.signer = signer; this.audit = audit;
        allowedOrigins = Arrays.stream(env.getProperty("mcp.allowed-origins", "").split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 20; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (!path.startsWith("/mcp")) return chain.filter(exchange);
        return Mono.defer(() -> {
            if (!path.matches("/mcp/[A-Za-z0-9_.-]{1,128}") || exchange.getRequest().getURI().getRawQuery() != null)
                throw new PlatformException(404, "unknown_mcp_endpoint");
            if (exchange.getRequest().getMethod() != HttpMethod.POST)
                throw new PlatformException(405, "method_not_allowed");
            String origin = exchange.getRequest().getHeaders().getOrigin();
            if (origin != null && !allowedOrigins.contains(origin)) throw new PlatformException(403, "origin_rejected");
            MediaType contentType = exchange.getRequest().getHeaders().getContentType();
            if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType))
                throw new PlatformException(415, "json_content_required");
            return DataBufferUtils.join(exchange.getRequest().getBody(), 262144)
                .switchIfEmpty(Mono.error(new PlatformException(400, "empty_request")))
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    try { buffer.read(bytes); return bytes; } finally { DataBufferUtils.release(buffer); }
                })
                .flatMap(bytes -> Mono.fromCallable(() -> admit(exchange, path, bytes))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(token -> chain.filter(exchange.mutate().request(forward(exchange, bytes, token)).build())));
        }).onErrorResume(error -> reject(exchange, error));
    }
    private String admit(ServerWebExchange exchange, String path, byte[] bytes) {
        Caller caller = identities.authenticate(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        String body = new String(bytes, StandardCharsets.UTF_8);
        Map<String,Object> request = Rpc.request(body);
        Snapshot snapshot = snapshots.current();
        String groupId = path.substring("/mcp/".length());
        CapabilityGroup group = snapshot.groups().get(groupId);
        if (group == null || !group.tenant().equals(caller.tenant())) throw new PlatformException(403, "group_access_denied");
        Set<String> allowed;
        if ("tools/call".equals(request.get("method"))) {
            ToolEntry selected = permissions.authorizeCall(caller, group, (String) Rpc.params(request).get("name"),
                Rpc.arguments(request), snapshot);
            allowed = Set.of(selected.id());
        } else {
            allowed = permissions.visibleToolIds(caller, group, snapshot);
        }
        long now = Instant.now().getEpochSecond();
        String requestId = UUID.randomUUID().toString();
        InvocationPermit permit = new InvocationPermit(requestId, caller, groupId, snapshot.version(), allowed,
            now, now + 15, PermitSigner.hashRequest("POST", path, body));
        audit.record("mcp.admit", "allowed", Map.of("requestId", requestId, "subject", caller.subject(),
            "tenant", caller.tenant(), "issuer", caller.issuer(), "group", groupId,
            "method", request.get("method"), "snapshotVersion", snapshot.version()));
        String signed = signer.sign(permit);
        if (signed.length() > 120000) throw new PlatformException(413, "capability_group_too_large");
        return signed;
    }
    private ServerHttpRequest forward(ServerWebExchange exchange, byte[] bytes, String permit) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.addAll(super.getHeaders());
                // User credentials and caller-supplied execution context must never reach runtime/upstreams.
                headers.remove(HttpHeaders.AUTHORIZATION); headers.remove(HttpHeaders.COOKIE);
                headers.remove(PermitSigner.HEADER); headers.remove(HttpHeaders.TRANSFER_ENCODING);
                headers.set(PermitSigner.HEADER, permit); headers.setContentLength(bytes.length);
                return headers;
            }
            @Override public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            }
        };
    }
    private Mono<Void> reject(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) return Mono.error(error);
        int status = error instanceof PlatformException p ? p.status() :
            error instanceof DataBufferLimitException ? 413 : error instanceof IllegalArgumentException ? 400 : 503;
        String code = error instanceof PlatformException p ? p.code() :
            status == 413 ? "request_too_large" : status == 400 ? "invalid_request" : "gateway_unavailable";
        audit.record("mcp.admit", "rejected", Map.of("reason", code));
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == 401) exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        if (status == 405) exchange.getResponse().getHeaders().set(HttpHeaders.ALLOW, "POST");
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
            .wrap(Json.write(Map.of("error", code)).getBytes(StandardCharsets.UTF_8))));
    }
}
