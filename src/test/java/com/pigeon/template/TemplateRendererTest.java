package com.pigeon.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plain unit tests: no Spring, no database. */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    private static final TemplateContent ENROLLMENT = new TemplateContent(
            "Your {{planName}} enrollment is confirmed",
            "<p>Hi {{firstName}}, you are enrolled in <b>{{planName}}</b> from {{effectiveDate}}.</p>",
            "Hi {{firstName}}, you are enrolled in {{planName}} from {{effectiveDate}}.",
            List.of("firstName", "planName", "effectiveDate"));

    private static Map<String, String> enrollmentVars() {
        Map<String, String> vars = new HashMap<>();
        vars.put("firstName", "Dana");
        vars.put("planName", "Gold PPO 2027");
        vars.put("effectiveDate", "January 1, 2027");
        return vars;
    }

    @Test
    void fillsEveryPartWhenAllFieldsArePresent() {
        RenderedContent out = renderer.render(ENROLLMENT, enrollmentVars());

        assertThat(out.subject()).isEqualTo("Your Gold PPO 2027 enrollment is confirmed");
        assertThat(out.html()).isEqualTo(
                "<p>Hi Dana, you are enrolled in <b>Gold PPO 2027</b> from January 1, 2027.</p>");
        assertThat(out.text()).isEqualTo("Hi Dana, you are enrolled in Gold PPO 2027 from January 1, 2027.");
    }

    @Test
    void missingFieldIsRejected() {
        Map<String, String> vars = enrollmentVars();
        vars.remove("planName");

        assertThatThrownBy(() -> renderer.render(ENROLLMENT, vars))
                .isInstanceOf(MissingMergeFieldsException.class)
                .hasMessage("Missing merge fields: planName");
    }

    @Test
    void allMissingFieldsAreReportedTogetherInSortedOrder() {
        assertThatThrownBy(() -> renderer.render(ENROLLMENT, Map.of("firstName", "Dana")))
                .isInstanceOfSatisfying(MissingMergeFieldsException.class, e ->
                        assertThat(e.missingFields()).containsExactly("effectiveDate", "planName"));
    }

    @Test
    void blankValueCountsAsMissing() {
        Map<String, String> vars = enrollmentVars();
        vars.put("firstName", "   ");

        assertThatThrownBy(() -> renderer.render(ENROLLMENT, vars))
                .isInstanceOfSatisfying(MissingMergeFieldsException.class, e ->
                        assertThat(e.missingFields()).containsExactly("firstName"));
    }

    @Test
    void nullVariablesMeansEverythingIsMissing() {
        assertThatThrownBy(() -> renderer.render(ENROLLMENT, null))
                .isInstanceOfSatisfying(MissingMergeFieldsException.class, e ->
                        assertThat(e.missingFields()).containsExactly("effectiveDate", "firstName", "planName"));
    }

    @Test
    void placeholderUsedInContentButNotDeclaredRequiredIsStillEnforced() {
        TemplateContent content = new TemplateContent(
                "Hello", "<p>{{firstName}} {{lastName}}</p>", "{{firstName}} {{lastName}}", List.of("firstName"));

        assertThatThrownBy(() -> renderer.render(content, Map.of("firstName", "Dana")))
                .isInstanceOfSatisfying(MissingMergeFieldsException.class, e ->
                        assertThat(e.missingFields()).containsExactly("lastName"));
    }

    @Test
    void valuesAreHtmlEscapedOnlyInTheHtmlPart() {
        Map<String, String> vars = enrollmentVars();
        vars.put("firstName", "<script>alert('x')</script> & Co");

        RenderedContent out = renderer.render(ENROLLMENT, vars);

        assertThat(out.html())
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; Co")
                .doesNotContain("<script>");
        assertThat(out.text()).contains("<script>alert('x')</script> & Co");
    }

    @Test
    void lineBreaksInSubjectValuesAreRemovedToPreventHeaderInjection() {
        Map<String, String> vars = enrollmentVars();
        vars.put("planName", "Gold\r\nBcc: attacker@example.com");

        RenderedContent out = renderer.render(ENROLLMENT, vars);

        assertThat(out.subject())
                .isEqualTo("Your Gold Bcc: attacker@example.com enrollment is confirmed")
                .doesNotContain("\r", "\n");
    }

    @Test
    void placeholdersMayContainSpacesInsideTheBraces() {
        TemplateContent content = new TemplateContent("{{ amount }}", "{{amount}}", "{{  amount}}", List.of());

        RenderedContent out = renderer.render(content, Map.of("amount", "$412.60"));

        assertThat(out.subject()).isEqualTo("$412.60");
        assertThat(out.html()).isEqualTo("$412.60");
        assertThat(out.text()).isEqualTo("$412.60");
    }

    @Test
    void valuesContainingRegexCharactersAreInsertedLiterally() {
        TemplateContent content = new TemplateContent("x", "x", "Paid {{amount}}", List.of("amount"));

        RenderedContent out = renderer.render(content, Map.of("amount", "$1\\2 {{other}}"));

        assertThat(out.text()).isEqualTo("Paid $1\\2 {{other}}");
    }

    @Test
    void extraVariablesAreIgnored() {
        Map<String, String> vars = enrollmentVars();
        vars.put("unused", "value");

        assertThat(renderer.render(ENROLLMENT, vars).text()).doesNotContain("value");
    }

    @Test
    void fieldsNeededCombinesRequiredListAndPlaceholders() {
        TemplateContent content = new TemplateContent(
                "{{a}}", "{{b}}", "{{c}}", List.of("d", "a"));

        assertThat(renderer.fieldsNeeded(content)).containsExactly("a", "b", "c", "d");
    }
}
