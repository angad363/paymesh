package com.paymesh.merchant.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MerchantControllerTest {

    private final MockMvc mockMvc;

    @Autowired
    MerchantControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void registersMerchant() throws Exception {
        mockMvc.perform(
                post("/api/v1/merchants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                                        {
                                          "businessName": " FreshBrew Cafe ",
                                          "email": " Owner@FreshBrew.Example ",
                                          "country": "in",
                                          "defaultCurrency": "inr"
                                        }
                                        """)
            )
            .andExpect(status().isCreated())
            .andExpect(
                jsonPath("$.id")
                    .value(matchesPattern(
                        "mrc_[0-9a-fA-F-]{36}"
                    ))
            )
            .andExpect(
                jsonPath("$.businessName")
                    .value("FreshBrew Cafe")
            )
            .andExpect(
                jsonPath("$.email")
                    .value("owner@freshbrew.example")
            )
            .andExpect(
                jsonPath("$.country")
                    .value("IN")
            )
            .andExpect(
                jsonPath("$.defaultCurrency")
                    .value("INR")
            )
            .andExpect(
                jsonPath("$.status")
                    .value("PENDING_VERIFICATION")
            )
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void returnsConflictWhenEmailAlreadyExists() throws Exception {
        String requestBody = """
            {
              "businessName": "Duplicate Merchant",
              "email": "owner@duplicate.example",
              "country": "IN",
              "defaultCurrency": "INR"
            }
            """;

        mockMvc.perform(
                post("/api/v1/merchants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isCreated());

        mockMvc.perform(
                post("/api/v1/merchants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody)
            )
            .andExpect(status().isConflict())
            .andExpect(
                jsonPath("$.code")
                    .value("MERCHANT_EMAIL_ALREADY_EXISTS")
            )
            .andExpect(
                jsonPath("$.message")
                    .value(
                        "A merchant already exists with email "
                            + "owner@duplicate.example"
                    )
            );
    }

    @Test
    void returnsBadRequestWhenRequestIsInvalid() throws Exception {
        mockMvc.perform(
                post("/api/v1/merchants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "businessName": "   ",
                          "email": "owner@invalid.example",
                          "country": "IN",
                          "defaultCurrency": "INR"
                        }
                        """)
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.code")
                    .value("VALIDATION_FAILED")
            )
            .andExpect(
                jsonPath("$.message")
                    .value("Request validation failed.")
            )
            .andExpect(
                jsonPath("$.fieldErrors.businessName")
                    .value("Business name is required.")
            );
    }
}
