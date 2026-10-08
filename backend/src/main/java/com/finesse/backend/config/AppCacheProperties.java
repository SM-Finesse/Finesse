package com.finesse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cache")
public record AppCacheProperties(int ttlMinutes) {
}
