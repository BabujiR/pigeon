package com.pigeon.metrics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pigeon.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /v1/metrics against real SQL. Each test writes its own messages on its own day in 2001,
 * then asks for exactly that day, so tests never see each other's data.
 */
class ReputationMetricsTests extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** Inserts {@code sent} SENT messages on the given day in one configuration set. */
    private void sent(String day, String configurationSet, int sent) {
        Instant at = Instant.parse(day + "T10:00:00Z");
        jdbc.update("""
                insert into message_record (id, correlation_id, template_id, template_version, recipient, stream,
                                            status, variables, configuration_set, sent_at)
                select gen_random_uuid(), 'metrics', 'payment-receipt', 1,
                       'm' || g || '-' || ? || '-' || ? || '@example.com',
                       case when ? = 'pigeon-bulk-prod' then 'BULK' else 'TRANSACTIONAL' end,
                       'SENT', '{}'::jsonb, ?, ?
                from generate_series(1, ?) g
                """, day, configurationSet, configurationSet, configurationSet, Timestamp.from(at), sent);
    }

    /** Adds an outcome event to {@code count} of that day's messages that have none yet. */
    private void outcome(String day, String configurationSet, String eventType, String detailJson, int count) {
        Instant from = Instant.parse(day + "T00:00:00Z");
        jdbc.update("""
                insert into message_event (message_id, event_type, detail, occurred_at, recorded_at)
                select m.id, ?, ?::jsonb, m.sent_at, now()
                from message_record m
                where m.sent_at >= ? and m.sent_at < ? and m.configuration_set = ?
                  and not exists (select 1 from message_event e where e.message_id = m.id
                                  and e.event_type in ('BOUNCE', 'COMPLAINT'))
                order by m.id
                limit ?
                """, eventType, detailJson, Timestamp.from(from), Timestamp.from(from.plusSeconds(86_400)),
                configurationSet, count);
    }

    private void hardBounces(String day, String cs, int n) {
        outcome(day, cs, "BOUNCE", "{\"bounceType\":\"Permanent\"}", n);
    }

    private org.springframework.test.web.servlet.ResultActions metricsFor(String day) throws Exception {
        return mvc.perform(get("/v1/metrics")
                .param("from", day + "T00:00:00Z")
                .param("to", day + "T23:59:59Z"));
    }

    @Test
    void healthyDayWithDeliveriesAndAFewBounces() throws Exception {
        String day = "2001-01-01";
        sent(day, "pigeon-txn-prod", 100);
        outcome(day, "pigeon-txn-prod", "DELIVERY", "{}", 98);
        hardBounces(day, "pigeon-txn-prod", 1);

        metricsFor(day)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.representative").value(true))
                .andExpect(jsonPath("$.totals.sent").value(100))
                .andExpect(jsonPath("$.totals.delivered").value(98))
                .andExpect(jsonPath("$.totals.hardBounced").value(1))
                .andExpect(jsonPath("$.totals.bounceRatePercent").value(1.0));
    }

    @Test
    void fivePercentHardBouncesPutsTheAccountUnderReview() throws Exception {
        String day = "2001-01-02";
        sent(day, "pigeon-txn-prod", 100);
        hardBounces(day, "pigeon-txn-prod", 6);

        metricsFor(day)
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.totals.bounceRatePercent").value(6.0));
    }

    @Test
    void dirtyBulkListPausesTheAccountAndShowsWhichStreamCausedIt() throws Exception {
        String day = "2001-01-03";
        sent(day, "pigeon-txn-prod", 100);
        sent(day, "pigeon-bulk-prod", 100);
        hardBounces(day, "pigeon-bulk-prod", 30);                     // 30 of 200 overall = 15%

        metricsFor(day)
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.totals.bounceRatePercent").value(15.0))
                .andExpect(jsonPath("$.streams[?(@.configurationSet=='pigeon-bulk-prod')].status").value("PAUSED"))
                .andExpect(jsonPath("$.streams[?(@.configurationSet=='pigeon-txn-prod')].status").value("HEALTHY"));
    }

    @Test
    void softBouncesDoNotCountTowardTheBounceRate() throws Exception {
        String day = "2001-01-04";
        sent(day, "pigeon-txn-prod", 100);
        outcome(day, "pigeon-txn-prod", "BOUNCE", "{\"bounceType\":\"Transient\"}", 20);

        metricsFor(day)
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.totals.softBounced").value(20))
                .andExpect(jsonPath("$.totals.bounceRatePercent").value(0.0));
    }

    @Test
    void complaintsAboveHalfAPercentPause() throws Exception {
        String day = "2001-01-05";
        sent(day, "pigeon-txn-prod", 1000);
        outcome(day, "pigeon-txn-prod", "COMPLAINT", "{}", 6);

        metricsFor(day)
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.totals.complaintRatePercent").value(0.6));
    }

    @Test
    void lowVolumeIsNotJudged() throws Exception {
        String day = "2001-01-06";
        sent(day, "pigeon-txn-prod", 5);
        hardBounces(day, "pigeon-txn-prod", 5);

        metricsFor(day)
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.representative").value(false))
                .andExpect(jsonPath("$.totals.bounceRatePercent").value(100.0));
    }

    @Test
    void defaultWindowWorksAndShowsThresholds() throws Exception {
        mvc.perform(get("/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholds.bouncePause").value(10.0))
                .andExpect(jsonPath("$.pipeline.outboxPending").isNumber());
        mvc.perform(get("/v1/metrics").param("window", "7d")).andExpect(status().isOk());
    }

    @Test
    void badWindowsAreRejected() throws Exception {
        mvc.perform(get("/v1/metrics").param("window", "soon")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/metrics").param("window", "90d")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/metrics").param("from", "yesterday")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/metrics").param("from", "2001-01-02T00:00:00Z").param("to", "2001-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }
}
