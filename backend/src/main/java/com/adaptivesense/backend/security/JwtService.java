package com.adaptivesense.backend.security;

import com.adaptivesense.backend.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs) {

        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be at least 32 characters. "
                            + "Set the JWT_SECRET environment variable."
            );
        }

        this.key =
                Keys.hmacShaKeyFor(
                        secret.getBytes(StandardCharsets.UTF_8)
                );

        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim("name", user.getName())
                .issuedAt(new Date())
                .expiration(
                        new Date(
                                System.currentTimeMillis()
                                        + expirationMs
                        )
                )
                .signWith(key)
                .compact();
    }

    public Claims getClaimsIfValid(String token) {
        try {
            Claims claims = parseClaims(token);
            if (claims.getExpiration().after(new Date())) {
                return claims;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isTokenValid(String token) {
        return getClaimsIfValid(token) != null;
    }

    public Long extractUserId(String token) {

        Claims claims = parseClaims(token);

        Object userId = claims.get("userId");

        if (userId instanceof Number number) {
            return number.longValue();
        }

        throw new IllegalArgumentException(
                "Token is missing userId claim."
        );
    }

    public String extractEmail(String token) {

        return parseClaims(token).getSubject();
    }

    private Claims parseClaims(String token) {

        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
