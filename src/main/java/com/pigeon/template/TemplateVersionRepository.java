package com.pigeon.template;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, TemplateVersion.Key> {
}
