package com.pigeon.suppression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pigeon.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class SuppressionApiTests extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static String address() {
        return "supp+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private void insert(String address, String reason) {
        jdbc.update("insert into suppression(address, reason, source) values (?, ?, 'test')", address, reason);
    }

    @Test
    void addManuallyThenLookUp() throws Exception {
        String a = address();

        mvc.perform(post("/v1/suppressions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"" + a.toUpperCase() + "\",\"detail\":\"customer asked by phone\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.address").value(a))
                .andExpect(jsonPath("$.reason").value("MANUAL"))
                .andExpect(jsonPath("$.source").value("api:manual"));

        mvc.perform(get("/v1/suppressions/{a}", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value("customer asked by phone"));
    }

    @Test
    void addingAnExistingAddressKeepsTheOriginalReason() throws Exception {
        String a = address();
        insert(a, "HARD_BOUNCE");

        mvc.perform(post("/v1/suppressions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"" + a + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("HARD_BOUNCE"));
    }

    @Test
    void invalidAddressIsRejected() throws Exception {
        mvc.perform(post("/v1/suppressions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAddressIsNotFound() throws Exception {
        mvc.perform(get("/v1/suppressions/{a}", address()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://pigeon.local/problems/suppression-not-found"));
    }

    @Test
    void listFiltersByReasonNewestFirst() throws Exception {
        insert(address(), "REPEATED_SOFT_BOUNCE");
        insert(address(), "REPEATED_SOFT_BOUNCE");

        mvc.perform(get("/v1/suppressions").param("reason", "REPEATED_SOFT_BOUNCE").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200))   // capped
                .andExpect(jsonPath("$.items[*].reason", everyItem(is("REPEATED_SOFT_BOUNCE"))));
    }

    @Test
    void removingAHardBounceAlsoResetsSoftBounceHistory() throws Exception {
        String a = address();
        insert(a, "HARD_BOUNCE");
        jdbc.update("insert into soft_bounce(address, occurred_at) values (?, now())", a);

        mvc.perform(delete("/v1/suppressions/{a}", a)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from suppression where address = ?", Integer.class, a)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from soft_bounce where address = ?", Integer.class, a)).isZero();
    }

    @Test
    void complaintsNeedForceToRemove() throws Exception {
        String a = address();
        insert(a, "COMPLAINT");

        mvc.perform(delete("/v1/suppressions/{a}", a))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("COMPLAINT"));
        assertThat(jdbc.queryForObject("select count(*) from suppression where address = ?", Integer.class, a)).isOne();

        mvc.perform(delete("/v1/suppressions/{a}", a).param("force", "true")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from suppression where address = ?", Integer.class, a)).isZero();
    }

    @Test
    void removingAnUnknownAddressIsNotFound() throws Exception {
        mvc.perform(delete("/v1/suppressions/{a}", address())).andExpect(status().isNotFound());
    }
}
