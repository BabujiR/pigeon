package com.pigeon.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes sending reputation for a time window, overall and per configuration set (stream).
 *
 * <p>Review notes: numbers come straight from the message records and their SES events, so the
 * API, the dashboard and a support engineer running SQL all see the same truth, and a restart
 * loses nothing. A message counts in the window by when it was <b>sent</b>; its bounce or
 * complaint counts against that send even if the event arrives hours later, which is how SES
 * attributes them too.
 */
@Service
@Transactional(readOnly = true)
public class ReputationService {

    private static final String COUNTS_BY_STREAM = """
            select m.configuration_set,
                   count(*) as sent,
                   count(*) filter (where exists (select 1 from message_event e
                        where e.message_id = m.id and e.event_type = 'DELIVERY')) as delivered,
                   count(*) filter (where exists (select 1 from message_event e
                        where e.message_id = m.id and e.event_type = 'BOUNCE'
                          and e.detail ->> 'bounceType' = 'Permanent')) as hard_bounced,
                   count(*) filter (where exists (select 1 from message_event e
                        where e.message_id = m.id and e.event_type = 'BOUNCE'
                          and coalesce(e.detail ->> 'bounceType', '') <> 'Permanent')) as soft_bounced,
                   count(*) filter (where exists (select 1 from message_event e
                        where e.message_id = m.id and e.event_type = 'COMPLAINT')) as complained
            from message_record m
            where m.sent_at >= ? and m.sent_at < ?
            group by m.configuration_set
            order by m.configuration_set
            """;

    private static final String PIPELINE = """
            select
              (select count(*) from message_record where created_at >= ? and created_at < ?) as accepted,
              (select count(*) from message_record where created_at >= ? and created_at < ? and status = 'SUPPRESSED') as suppressed,
              (select count(*) from message_record where created_at >= ? and created_at < ? and status = 'FAILED') as failed,
              (select count(*) from message_record where status = 'QUEUED') as queued_now,
              (select count(*) from outbox where status = 'PENDING') as outbox_pending,
              (select count(*) from suppression) as suppression_list_size
            """;

    private final JdbcTemplate jdbc;
    private final MetricsProperties props;

    public ReputationService(JdbcTemplate jdbc, MetricsProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    // --- Result shape (serialized as the /v1/metrics response) ------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(Window window, ReputationStatus status, boolean earlyWarning, boolean representative,
                         List<String> reasons, Rates totals, List<StreamRates> streams, Pipeline pipeline,
                         Thresholds thresholds) {
    }

    public record Window(Instant from, Instant to) {
    }

    public record Rates(long sent, long delivered, long hardBounced, long softBounced, long complained,
                        double bounceRatePercent, double complaintRatePercent) {
    }

    public record StreamRates(String configurationSet, ReputationStatus status, Rates rates) {
    }

    public record Pipeline(long acceptedInWindow, long suppressedInWindow, long failedInWindow,
                           long queuedNow, long outboxPending, long suppressionListSize) {
    }

    public record Thresholds(int minimumVolume, double bounceWarning, double bounceReview, double bouncePause,
                             double complaintWarning, double complaintReview, double complaintPause) {
    }

    private record Counts(String configurationSet, long sent, long delivered, long hardBounced,
                          long softBounced, long complained) {
    }

    // --- Computation ------------------------------------------------------------------------

    public Report report(Instant from, Instant to) {
        Timestamp f = Timestamp.from(from);
        Timestamp t = Timestamp.from(to);

        List<Counts> byStream = jdbc.query(COUNTS_BY_STREAM, (rs, i) -> new Counts(
                rs.getString("configuration_set") == null ? "none" : rs.getString("configuration_set"),
                rs.getLong("sent"), rs.getLong("delivered"), rs.getLong("hard_bounced"),
                rs.getLong("soft_bounced"), rs.getLong("complained")), f, t);

        long sent = 0, delivered = 0, hard = 0, soft = 0, complained = 0;
        List<StreamRates> streams = new ArrayList<>();
        for (Counts c : byStream) {
            sent += c.sent();
            delivered += c.delivered();
            hard += c.hardBounced();
            soft += c.softBounced();
            complained += c.complained();
            ReputationPolicy.Verdict v = ReputationPolicy.judge(c.sent(), c.hardBounced(), c.complained(), props);
            streams.add(new StreamRates(c.configurationSet(), v.status(), rates(c.sent(), c.delivered(),
                    c.hardBounced(), c.softBounced(), c.complained(), v)));
        }

        // The account-level verdict, which is what SES enforces.
        ReputationPolicy.Verdict overall = ReputationPolicy.judge(sent, hard, complained, props);

        Pipeline pipeline = jdbc.queryForObject(PIPELINE, (rs, i) -> new Pipeline(
                rs.getLong("accepted"), rs.getLong("suppressed"), rs.getLong("failed"),
                rs.getLong("queued_now"), rs.getLong("outbox_pending"), rs.getLong("suppression_list_size")),
                f, t, f, t, f, t);

        return new Report(new Window(from, to), overall.status(), overall.earlyWarning(), overall.representative(),
                overall.reasons(), rates(sent, delivered, hard, soft, complained, overall), streams, pipeline,
                new Thresholds(props.minimumVolume(), props.bounceWarning(), props.bounceReview(), props.bouncePause(),
                        props.complaintWarning(), props.complaintReview(), props.complaintPause()));
    }

    private static Rates rates(long sent, long delivered, long hard, long soft, long complained,
                               ReputationPolicy.Verdict v) {
        return new Rates(sent, delivered, hard, soft, complained, v.bounceRatePercent(), v.complaintRatePercent());
    }
}
