package com.adaptivesense.backend.security;

import com.adaptivesense.backend.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {

        jwtService = new JwtService(
                "unit-test-jwt-secret-key-min-32-chars",
                3600000
        );
    }

    @Test
    void generatesAndValidatesToken() throws Exception {

        User user = new User(
                "Test User",
                "test@example.com",
                "hashed"
        );

        Field idField =
                User.class.getDeclaredField("id");

        idField.setAccessible(true);
        idField.set(user, 42L);

        String token =
                jwtService.generateToken(user);

        assertTrue(jwtService.isTokenValid(token));
        assertEquals(42L, jwtService.extractUserId(token));
        assertEquals(
                "test@example.com",
                jwtService.extractEmail(token)
        );
    }

    @Test
    void rejectsInvalidToken() {

        assertFalse(
                jwtService.isTokenValid("not-a-real-token")
        );
    }
}
