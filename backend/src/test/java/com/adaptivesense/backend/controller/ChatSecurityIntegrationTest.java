package com.adaptivesense.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ChatSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
}