package com.pigeon.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs all Flyway migrations against a real Postgres 16, lets Hibernate validate the entity
 * mappings against that schema, and checks the seeded templates render end to end.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("local")
@Testcontainers
@Import({TemplateService.class, TemplateRenderer.class})
class TemplateCatalogTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TemplateService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allMigrationsApplyAndCreateEveryTable() {
        Integer count = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name in
                ('template','template_version','message_record','message_event','idempotency_key',
                 'outbox','suppression','soft_bounce','processed_event')
                """, Integer.class);

        assertThat(count).isEqualTo(9);
    }

    @Test
    void threeTemplatesAreSeeded() {
        assertThat(jdbc.queryForList("select id from template order by id", String.class))
                .containsExactly("enrollment-confirmation", "password-reset", "payment-receipt");
    }

    @Test
    void enrollmentConfirmationRendersItsActiveVersionTwo() {
        RenderedEmail email = service.render("enrollment-confirmation", Map.of(
                "firstName", "Dana",
                "planName", "Gold PPO 2027",
                "effectiveDate", "January 1, 2027",
                "memberId", "AB-448210"));

        assertThat(email.templateVersion()).isEqualTo(2);
        assertThat(email.fromAddress()).isEqualTo("enrollment@notify.pigeon.local");
        assertThat(email.subject()).isEqualTo("Your Gold PPO 2027 enrollment is confirmed");
        assertThat(email.text()).contains("AB-448210", "Sent by Pigeon");
        assertThat(email.html()).contains("<strong>Gold PPO 2027</strong>").doesNotContain("{{");
    }

    @Test
    void retiredVersionStillRendersForAudit() {
        RenderedEmail v1 = service.render("enrollment-confirmation", 1, Map.of(
                "firstName", "Dana", "planName", "Gold PPO 2027",
                "effectiveDate", "January 1, 2027", "memberId", "AB-448210"));

        assertThat(v1.templateVersion()).isEqualTo(1);
        assertThat(v1.text()).doesNotContain("Sent by Pigeon");
    }

    @Test
    void passwordResetAndReceiptRender() {
        assertThat(service.render("password-reset", Map.of(
                "firstName", "Dana",
                "resetLink", "https://member.example.com/reset/7f3c9a",
                "expiresMinutes", "30")).html())
                .contains("href=\"https://member.example.com/reset/7f3c9a\"");

        assertThat(service.render("payment-receipt", Map.of(
                "firstName", "Dana", "amount", "$412.60",
                "paidOn", "November 6, 2026", "last4", "4417")).subject())
                .isEqualTo("Payment received: $412.60");
    }

    @Test
    void fieldsNeededComesFromTheActiveVersion() {
        assertThat(service.fieldsNeeded("payment-receipt"))
                .containsExactly("amount", "firstName", "last4", "paidOn");
    }

    @Test
    void missingFieldOnSeededTemplateIsRejected() {
        assertThatThrownBy(() -> service.render("payment-receipt", Map.of("firstName", "Dana")))
                .isInstanceOfSatisfying(MissingMergeFieldsException.class, e ->
                        assertThat(e.missingFields()).containsExactly("amount", "last4", "paidOn"));
    }

    @Test
    void unknownTemplateIsRejected() {
        assertThatThrownBy(() -> service.render("no-such-template", Map.of()))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasMessage("Unknown template: no-such-template");
    }

    @Test
    void databaseRejectsMixedCaseRecipientInSuppression() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into suppression(address, reason, source) values ('Dana@Example.com','MANUAL','test')"))
                .hasMessageContaining("ck_suppression_address_lower");
    }
}
