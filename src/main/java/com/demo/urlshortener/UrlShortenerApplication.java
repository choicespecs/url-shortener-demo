package com.demo.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the URL Shortener demo application.
 *
 * <p>Activates two Spring framework features beyond the standard
 * {@code @SpringBootApplication} auto-configuration:
 * <ul>
 *   <li>{@code @EnableCaching} — registers the {@code CacheManager} bean defined in
 *       {@link com.demo.urlshortener.config.RedisConfig} so that Spring's cache
 *       abstraction layer is active.</li>
 *   <li>{@code @EnableScheduling} — enables {@code @Scheduled} method processing,
 *       required by {@link com.demo.urlshortener.scheduler.ExpirationCleanupJob}.</li>
 * </ul>
 *
 * <p>Note: the primary caching path in this service uses {@code StringRedisTemplate}
 * directly (cache-aside pattern) rather than declarative {@code @Cacheable} annotations.
 * {@code @EnableCaching} is therefore present for infrastructure completeness.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class UrlShortenerApplication {

    /**
     * Bootstraps the Spring application context and starts the embedded Tomcat server.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
