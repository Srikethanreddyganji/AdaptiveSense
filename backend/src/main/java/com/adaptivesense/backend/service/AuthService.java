package com.adaptivesense.backend.service;

import com.adaptivesense.backend.dto.LoginRequest;
import com.adaptivesense.backend.dto.LoginResponse;
import com.adaptivesense.backend.dto.RegisterRequest;
import com.adaptivesense.backend.entity.User;
import com.adaptivesense.backend.exception.ApiException;
import com.adaptivesense.backend.repository.UserRepository;
import com.adaptivesense.backend.security.JwtService;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public User register(RegisterRequest request) {

        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {

            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Email already registered"
            );
        }

        String hashedPassword =
                passwordEncoder.encode(request.getPassword());

        User user = new User(
                request.getName().trim(),
                normalizedEmail,
                hashedPassword
        );

        return userRepository.save(user);
    }

    public LoginResponse login(LoginRequest request) {

        String normalizedEmail = request.getEmail().trim().toLowerCase();

        User user = userRepository
                .findByEmail(normalizedEmail)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.UNAUTHORIZED,
                                "Invalid email or password"
                        ));

        if (!passwordEncoder.matches(
                request.getPassword(),
                user.getPassword())) {

            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid email or password"
            );
        }

        String token = jwtService.generateToken(user);

        return new LoginResponse(
                token,
                user.getId(),
                user.getName(),
                user.getEmail()
        );
    }
}
