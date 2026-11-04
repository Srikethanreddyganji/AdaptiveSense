package com.adaptivesense.backend.controller;

import com.adaptivesense.backend.entity.User;
import com.adaptivesense.backend.repository.UserRepository;
import com.adaptivesense.backend.security.AuthenticatedUser;
import com.adaptivesense.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.Callable;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ChatSecurityIntegrationTest.AsyncIdentityTestConfiguration.class)
class ChatSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    private String jwt;

    private String email;

    @BeforeEach
    void createAuthenticatedUser() {
        email = "async-" + java.util.UUID.randomUUID() + "@example.test";
        User user = userRepository.save(new User("Async Test", email, "unused"));
        jwt = jwtService.generateToken(user);
    }

    @Test
    void chatRequiresAuthentication() throws Exception {

        mockMvc.perform(
                post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Hello"}
                                """)
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void historyRequiresAuthentication() throws Exception {

        mockMvc.perform(
                get("/api/chat/history")
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void voiceRequiresAuthentication() throws Exception {

        mockMvc.perform(
                post("/api/chat/voice")
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void voiceStreamRequiresAuthenticationOnInitialRequest() throws Exception {
        mockMvc.perform(post("/api/chat/voice/stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void directErrorRequestIsNotPublic() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedRequestMayResumeAsAsyncAndRetainsJwtIdentity() throws Exception {
        MvcResult initial = mockMvc.perform(
                        get("/api/test/async-security-identity")
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(request -> org.junit.jupiter.api.Assertions.assertTrue(
                        request.getRequest().isAsyncStarted()))
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().string(email));
    }

    @TestConfiguration
    static class AsyncIdentityTestConfiguration {
        @Bean
        AsyncIdentityController asyncIdentityController() {
            return new AsyncIdentityController();
        }
    }

    @RestController
    static class AsyncIdentityController {
        @GetMapping("/api/test/async-security-identity")
        Callable<String> identity(@AuthenticationPrincipal AuthenticatedUser user) {
            return () -> {
                // Exercise both the captured controller principal and the propagated
                // SecurityContext used by asynchronous service code.
                Object asyncPrincipal = SecurityContextHolder.getContext()
                        .getAuthentication().getPrincipal();
                if (!(asyncPrincipal instanceof AuthenticatedUser authenticated)
                        || !authenticated.getUserId().equals(user.getUserId())) {
                    throw new IllegalStateException("JWT identity was not propagated to async work");
                }
                return authenticated.getEmail();
            };
        }
    }
}
