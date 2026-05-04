package com.demo.urlshortener.config;

import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Spring configuration for the Redis-backed {@link CacheManager}.
 *
 * <p>Configures a {@link RedisCacheManager} with the following defaults:
 * <ul>
 *   <li><strong>TTL:</strong> 24 hours for all cache entries.</li>
 *   <li><strong>Key serialization:</strong> plain UTF-8 strings via {@link StringRedisSerializer}.</li>
 *   <li><strong>Value serialization:</strong> JSON via {@link GenericJackson2JsonRedisSerializer},
 *       which embeds type metadata so objects can be deserialized without knowing the target
 *       class at configuration time.</li>
 *   <li><strong>Null caching disabled:</strong> {@code disableCachingNullValues()} prevents
 *       null results from being stored in Redis, which could otherwise mask real data
 *       after a transient null response.</li>
 * </ul>
 *
 * <p><strong>Note:</strong> {@link com.demo.urlshortener.service.UrlService} drives Redis
 * directly via {@code StringRedisTemplate} (imperative cache-aside) rather than
 * {@code @Cacheable} annotations. This {@code CacheManager} bean is registered for
 * infrastructure completeness but is not actively used by any annotated cache operations
 * in the current codebase.
 */
@Configuration
public class RedisConfig {

    /**
     * Declares the application-wide {@link CacheManager} bean backed by Redis.
     *
     * @param connectionFactory auto-configured Redis connection factory provided by
     *                          {@code spring-boot-starter-data-redis}
     * @return a fully configured {@link RedisCacheManager} instance
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(24))
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
                );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
