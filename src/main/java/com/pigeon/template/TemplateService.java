package com.pigeon.template;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a template id to its active version and renders it.
 *
 * <p>Used twice in the send path: the API calls {@link #fieldsNeeded} to reject a bad request
 * with 400 before anything is queued, and the sender worker calls {@link #render} to build the
 * email. Rendering always uses the version recorded on the message, so a template published
 * between accept and send does not change an email already in flight.
 */
@Service
@Transactional(readOnly = true)
public class TemplateService {

    private final TemplateRepository templates;
    private final TemplateVersionRepository versions;
    private final TemplateRenderer renderer;

    public TemplateService(TemplateRepository templates,
                           TemplateVersionRepository versions,
                           TemplateRenderer renderer) {
        this.templates = templates;
        this.versions = versions;
        this.renderer = renderer;
    }

    public Template template(String templateId) {
        return templates.findById(templateId).orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    public TemplateVersion activeVersion(String templateId) {
        return version(templateId, template(templateId).getActiveVersion());
    }

    public TemplateVersion version(String templateId, int version) {
        return versions.findById(new TemplateVersion.Key(templateId, version))
                .orElseThrow(() -> new TemplateNotFoundException(templateId + " v" + version));
    }

    public Set<String> fieldsNeeded(String templateId) {
        return renderer.fieldsNeeded(activeVersion(templateId).toContent());
    }

    /** Every template with the merge fields its active version needs, sorted by id. */
    public List<TemplateSummary> list() {
        return templates.findAll().stream()
                .sorted(java.util.Comparator.comparing(Template::getId))
                .map(t -> new TemplateSummary(t.getId(), t.getName(), t.getFromAddress(), t.getActiveVersion(),
                        List.copyOf(fieldsNeeded(t.getId()))))
                .toList();
    }

    public record TemplateSummary(String id, String name, String fromAddress, int activeVersion, List<String> fields) {
    }

    /** Renders the active version. */
    public RenderedEmail render(String templateId, Map<String, String> variables) {
        return render(templateId, template(templateId).getActiveVersion(), variables);
    }

    /** Renders a specific version, e.g. the one recorded when the request was accepted. */
    public RenderedEmail render(String templateId, int version, Map<String, String> variables) {
        Template template = template(templateId);
        TemplateVersion tv = version(templateId, version);
        RenderedContent content = renderer.render(tv.toContent(), variables);
        return new RenderedEmail(template.getId(), tv.getVersion(), template.getFromAddress(),
                content.subject(), content.html(), content.text());
    }
}
