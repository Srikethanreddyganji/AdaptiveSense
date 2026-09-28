package com.adaptivesense.backend.controller;

import com.adaptivesense.backend.dto.LoginRequest;
import com.adaptivesense.backend.dto.LoginResponse;
import com.adaptivesense.backend.dto.RegisterRequest;
import com.adaptivesense.backend.dto.UserResponse;
import com.adaptivesense.backend.entity.User;
import com.adaptivesense.backend.service.AuthService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        User user = authService.register(request);

        UserResponse response = new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {

        return ResponseEntity.ok(
                authService.login(request)
        );
    }

    @GetMapping("/health")
    public ResponseEntity<java.util.Map<String, String>> health() {
        return ResponseEntity.ok(
                java.util.Map.of(
                        "status", "ok",
                        "service", "AdaptiveSense Backend"
                )
        );
    }
}
