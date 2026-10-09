package io.github.mcpgov.security;

import io.github.mcpgov.contracts.Json;
import io.github.mcpgov.contracts.Model.InvocationPermit;
import io.github.mcpgov.contracts.PlatformException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/** Request-bound gateway-to-runtime capability. Not a user login token and never sent to upstream services. */
public final class PermitSigner {
    public static final String HEADER = "X-Mcp-Execution-Permit";
    private final byte[] secret;
    private final Clock clock;
    private final ConcurrentHashMap<String,Long> seen = new ConcurrentHashMap<>();
    public PermitSigner(String secret) { this(secret, Clock.systemUTC()); }
    public PermitSigner(String secret, Clock clock) {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("Runtime signing secret must be at least 32 characters");
        this.secret = secret.getBytes(StandardCharsets.UTF_8); this.clock = clock;
    }
    public String sign(InvocationPermit permit) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(permit).getBytes(StandardCharsets.UTF_8));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(encoded));
    }
    public InvocationPermit verify(String token, String method, String path, String body) {
        try {
            if (token == null || token.length() > 131072) throw denied();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(mac(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) throw denied();
            InvocationPermit permit = Json.read(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8), InvocationPermit.class);
            long now = clock.instant().getEpochSecond();
            if (permit.expiresAt() <= now || permit.expiresAt() <= permit.issuedAt() || permit.issuedAt() > now + 2 || permit.expiresAt() - permit.issuedAt() > 30 ||
                !permit.requestHash().equals(hashRequest(method, path, body))) throw denied();
            seen.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (seen.size() >= 10000 || seen.putIfAbsent(permit.requestId(), permit.expiresAt()) != null) throw denied();
            return permit;
        } catch (RuntimeException e) { throw denied(); }
    }
    public static String hashRequest(String method, String path, String body) {
        return Json.sha256(method + "\n" + path + "\n" + body);
    }
    private byte[] mac(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private PlatformException denied() { return new PlatformException(401, "invalid_internal_permit"); }
}
