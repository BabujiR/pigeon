package com.pigeon.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * JSON unless the caller asks for XML.
 *
 * <p>Review note: adding jackson-dataformat-xml registers the XML converter ahead of JSON, so a
 * client sending {@code Accept: *&#47;*} (curl, most HTTP libraries) would suddenly get XML.
 * Making JSON the default for wildcard requests keeps the API predictable.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.defaultContentType(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML);
    }
}
