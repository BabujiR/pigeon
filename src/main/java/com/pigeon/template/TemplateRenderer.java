package com.pigeon.template;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Fills {@code {{mergeField}}} placeholders in a template's subject, HTML and text parts.
 *
 * <p>Review notes:
 * <ul>
 *   <li><b>All-or-nothing.</b> Every missing field is reported at once, before anything is
 *       rendered. A member must never receive "Hi {{firstName}}". A field counts as needed if it
 *       is declared required <i>or</i> appears in the content, so a template edit that adds a
 *       placeholder cannot slip past validation.</li>
 *   <li><b>Context-aware escaping.</b> Values are HTML-escaped in the HTML part (a name like
 *       {@code <script>} must not become markup), left as-is in the text part, and stripped of
 *       line breaks in the subject (prevents header injection).</li>
 *   <li><b>Stateless and pure</b>: no I/O, so it is unit-tested without Spring or a database.</li>
 * </ul>
 */
@Component
public class TemplateRenderer {

    /** {{name}}, {{ name }}; names start with a letter: letters, digits, underscore. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    public RenderedContent render(TemplateContent content, Map<String, String> variables) {
        Map<String, String> vars = variables == null ? Map.of() : variables;

        List<String> missing = fieldsNeeded(content).stream()
                .filter(field -> isBlank(vars.get(field)))
                .toList();
        if (!missing.isEmpty()) {
            throw new MissingMergeFieldsException(missing);
        }

        return new RenderedContent(
                fill(content.subject(), vars, TemplateRenderer::subjectSafe).strip(),
                fill(content.htmlBody(), vars, HtmlUtils::htmlEscape),
                fill(content.textBody(), vars, UnaryOperator.identity()));
    }

    /** Declared required fields plus every placeholder actually used, sorted for stable errors. */
    public Set<String> fieldsNeeded(TemplateContent content) {
        Set<String> needed = new TreeSet<>(content.requiredFields());
        needed.addAll(placeholdersIn(content.subject()));
        needed.addAll(placeholdersIn(content.htmlBody()));
        needed.addAll(placeholdersIn(content.textBody()));
        return needed;
    }

    static Set<String> placeholdersIn(String text) {
        Set<String> found = new TreeSet<>();
        if (text == null) {
            return found;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static String fill(String text, Map<String, String> vars, UnaryOperator<String> escape) {
        if (text == null) {
            return "";
        }
        return PLACEHOLDER.matcher(text)
                .replaceAll(match -> Matcher.quoteReplacement(escape.apply(vars.get(match.group(1)))));
    }

    private static String subjectSafe(String value) {
        return value.replaceAll("[\\r\\n]+", " ");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
