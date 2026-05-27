package io.lexq.example;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LexQ connection settings, bound from environment variables via application.yml.
 *
 * @param apiUrl  base URL of the LexQ API (e.g. https://api.lexq.io)
 * @param apiKey  the API key — stays on the server, never shipped to a browser
 * @param groupId the policy group to evaluate against
 */
@ConfigurationProperties(prefix = "lexq")
public record LexqProperties(String apiUrl, String apiKey, String groupId) {}