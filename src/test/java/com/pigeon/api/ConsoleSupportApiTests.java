package com.pigeon.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pigeon.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The read endpoints the Pigeon Console uses, and the console page itself. */
class ConsoleSupportApiTests extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void templatesAreListedWithTheFieldsTheyNeed() throws Exception {
        mvc.perform(get("/v1/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].id", contains("enrollment-confirmation", "password-reset", "payment-receipt")))
                .andExpect(jsonPath("$[0].activeVersion").value(2))
                .andExpect(jsonPath("$[2].fields", contains("amount", "firstName", "last4", "paidOn")));
    }

    @Test
    void recentMessagesNewestFirstWithStatusFilterAndLimit() throws Exception {
        String to = "recent+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/v1/communications").header("Idempotency-Key", "recent-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"templateId":"payment-receipt","recipient":"%s","stream":"TRANSACTIONAL",
                                 "variables":{"firstName":"Dana","amount":"$1","paidOn":"today","last4":"0000"}}
                                """.formatted(to)))
                .andExpect(status().isAccepted());

        mvc.perform(get("/v1/communications").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].recipient").value(to))
                .andExpect(jsonPath("$[0].variables").doesNotExist());

        mvc.perform(get("/v1/communications").param("status", "QUEUED").param("limit", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", lessThanOrEqualTo(200)))
                .andExpect(jsonPath("$[*].status", everyItem(is("QUEUED"))));
    }

    @Test
    void unknownStatusFilterIsRejected() throws Exception {
        mvc.perform(get("/v1/communications").param("status", "LOST")).andExpect(status().isBadRequest());
    }

    @Test
    void consoleIsServedSeparatelyFromTheLab() throws Exception {
        mvc.perform(get("/console")).andExpect(redirectedUrl("/console/index.html"));
        mvc.perform(get("/console/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Pigeon Console</title>")));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pigeon")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Pigeon Console"))));
    }
}
