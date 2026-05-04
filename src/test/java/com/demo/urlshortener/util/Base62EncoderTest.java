package com.demo.urlshortener.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62EncoderTest {

    private Base62Encoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new Base62Encoder();
    }

    @Test
    void encode_smallId_returnsShortCode() {
        String code = encoder.encode(1L);
        assertThat(code).isEqualTo("1");
    }

    @Test
    void encode_knownId_returnsExpectedCode() {
        // 62 in base62 is "10" (1*62 + 0)
        String code = encoder.encode(62L);
        assertThat(code).isEqualTo("10");
    }

    @Test
    void encode_largeId_returnsCompactCode() {
        String code = encoder.encode(1_000_000L);
        assertThat(code).isNotNull();
        assertThat(code.length()).isLessThanOrEqualTo(4);
    }

    @Test
    void encode_differentIds_returnsDifferentCodes() {
        String code1 = encoder.encode(100L);
        String code2 = encoder.encode(101L);
        assertThat(code1).isNotEqualTo(code2);
    }

    @Test
    void encode_zeroOrNegative_throwsException() {
        assertThatThrownBy(() -> encoder.encode(0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> encoder.encode(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
