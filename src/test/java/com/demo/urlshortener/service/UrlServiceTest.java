package com.demo.urlshortener.service;

import com.demo.urlshortener.dto.ShortenRequest;
import com.demo.urlshortener.dto.ShortenResponse;
import com.demo.urlshortener.exception.AliasAlreadyExistsException;
import com.demo.urlshortener.exception.UrlNotFoundException;
import com.demo.urlshortener.model.UrlMapping;
import com.demo.urlshortener.repository.UrlRepository;
import com.demo.urlshortener.util.Base62Encoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    private UrlRepository repository;

    @Mock
    private Base62Encoder encoder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private UrlService urlService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(urlService, "baseUrl", "http://localhost:8080");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shorten_withoutAlias_generatesShortCode() {
        ShortenRequest request = new ShortenRequest();
        request.setOriginalUrl("https://example.com/some/very/long/url");

        UrlMapping saved = UrlMapping.builder()
                .id(1L)
                .shortCode("PENDING")
                .originalUrl(request.getOriginalUrl())
                .build();

        UrlMapping updated = UrlMapping.builder()
                .id(1L)
                .shortCode("1")
                .originalUrl(request.getOriginalUrl())
                .createdAt(Instant.now())
                .build();

        when(repository.saveAndFlush(any())).thenReturn(saved);
        when(encoder.encode(1L)).thenReturn("1");
        when(repository.save(any())).thenReturn(updated);

        ShortenResponse response = urlService.shorten(request);

        assertThat(response.getShortCode()).isEqualTo("1");
        assertThat(response.getShortUrl()).isEqualTo("http://localhost:8080/1");
        assertThat(response.getOriginalUrl()).isEqualTo(request.getOriginalUrl());
        verify(encoder).encode(1L);
    }

    @Test
    void shorten_withAlias_usesAlias() {
        ShortenRequest request = new ShortenRequest();
        request.setOriginalUrl("https://example.com/long-url");
        request.setAlias("my-link");

        UrlMapping saved = UrlMapping.builder()
                .id(10L)
                .shortCode("my-link")
                .originalUrl(request.getOriginalUrl())
                .createdAt(Instant.now())
                .build();

        when(repository.existsByShortCode("my-link")).thenReturn(false);
        when(repository.save(any())).thenReturn(saved);

        ShortenResponse response = urlService.shorten(request);

        assertThat(response.getShortCode()).isEqualTo("my-link");
        verify(encoder, never()).encode(anyLong());
    }

    @Test
    void shorten_withDuplicateAlias_throwsException() {
        ShortenRequest request = new ShortenRequest();
        request.setOriginalUrl("https://example.com/long-url");
        request.setAlias("taken");

        when(repository.existsByShortCode("taken")).thenReturn(true);

        assertThatThrownBy(() -> urlService.shorten(request))
                .isInstanceOf(AliasAlreadyExistsException.class)
                .hasMessageContaining("taken");
    }

    @Test
    void resolve_cacheHit_returnsUrlWithoutHittingDatabase() {
        when(valueOperations.get("url:abc123")).thenReturn("https://example.com");

        String result = urlService.resolve("abc123");

        assertThat(result).isEqualTo("https://example.com");
        verify(repository, never()).findByShortCode(any());
    }

    @Test
    void resolve_cacheMiss_queriesDbAndPopulatesCache() {
        when(valueOperations.get("url:abc123")).thenReturn(null);

        UrlMapping mapping = UrlMapping.builder()
                .shortCode("abc123")
                .originalUrl("https://example.com")
                .createdAt(Instant.now())
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        String result = urlService.resolve("abc123");

        assertThat(result).isEqualTo("https://example.com");
        verify(repository).findByShortCode("abc123");
        verify(valueOperations).set(eq("url:abc123"), eq("https://example.com"), any());
    }

    @Test
    void resolve_expiredUrl_throwsUrlNotFoundException() {
        when(valueOperations.get("url:old")).thenReturn(null);

        UrlMapping expired = UrlMapping.builder()
                .shortCode("old")
                .originalUrl("https://example.com")
                .createdAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600))
                .build();

        when(repository.findByShortCode("old")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> urlService.resolve("old"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolve_unknownCode_throwsUrlNotFoundException() {
        when(valueOperations.get("url:unknown")).thenReturn(null);
        when(repository.findByShortCode("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.resolve("unknown"))
                .isInstanceOf(UrlNotFoundException.class);
    }
}
