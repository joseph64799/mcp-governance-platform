
package io.github.mcpgov.identity.adapters;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import io.github.mcpgov.contracts.*;
import io.github.mcpgov.contracts.Model.Caller;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real production decoder and a local JWKS endpoint, not an authentication stub. */
class JwtIdentityProviderTest {
    RSAKey key() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).keyID("test").build();
    }
    String token(RSAKey key, String issuer, String audience, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("alice").issuer(issuer).audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(expires)).claim("roles", List.of("operator")).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
    @Test void onlyCorrectSignatureIssuerAudienceAndExpiryReachTheIdentityMapper() throws Exception {
        RSAKey trusted = key();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = Json.write(Map.of("keys", List.of(trusted.toPublicJWK().toJSONObject()))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var provider = new JwtIdentityProvider("https://identity.example", "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks", "mcp-platform",
                (issuer, subject, claims) -> new Caller(subject, "tenant", issuer, Set.of("operator")));
            String valid = token(trusted, "https://identity.example", "mcp-platform", Instant.now().plusSeconds(300));
            assertEquals("alice", provider.authenticate("Bearer " + valid).subject());
            for (String invalid : List.of(
                token(key(), "https://identity.example", "mcp-platform", Instant.now().plusSeconds(300)),
                token(trusted, "https://other.example", "mcp-platform", Instant.now().plusSeconds(300)),
                token(trusted, "https://identity.example", "other-service", Instant.now().plusSeconds(300)),
                token(trusted, "https://identity.example", "mcp-platform", Instant.now().minusSeconds(300)),
                "malformed")) {
                assertEquals(401, assertThrows(PlatformException.class, () -> provider.authenticate("Bearer " + invalid)).status());
            }
            assertThrows(PlatformException.class, () -> provider.authenticate(null));
        } finally { server.stop(0); }
    }
}
