package com.demo.urlshortener.util;

import org.springframework.stereotype.Component;

/**
 * Encodes positive {@code long} values into compact Base62 strings.
 *
 * <p>Base62 uses the 62-character alphabet {@code 0-9a-zA-Z}, producing URL-safe
 * strings without padding or special characters. The encoding is used to convert
 * auto-increment PostgreSQL IDs into short codes:
 *
 * <pre>
 *   1  → "1"
 *   62 → "10"
 *   1_000_000 → "4c92" (4 characters)
 * </pre>
 *
 * <p>Code length grows logarithmically with the input:
 * <ul>
 *   <li>Up to 62^4 (~14.8 million) IDs produce codes of at most 4 characters.</li>
 *   <li>Up to 62^6 (~56 billion) IDs produce codes of at most 6 characters.</li>
 *   <li>Up to 62^7 (~3.5 trillion) IDs produce codes of at most 7 characters.</li>
 * </ul>
 *
 * <p><strong>Security note:</strong> because IDs are sequential, the resulting codes
 * are also predictable. Do not use this encoder when short codes must be
 * non-enumerable (e.g., for private URLs in a production environment).
 */
@Component
public class Base62Encoder {

    /**
     * The 62-character Base62 alphabet ordered as digits, lowercase letters, uppercase letters.
     * The position of each character is its numeric value in the encoding.
     */
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** The base of the numeral system — equal to the length of {@link #ALPHABET}. */
    private static final int BASE = ALPHABET.length(); // 62

    /**
     * Encodes a positive {@code long} ID into a Base62 string.
     *
     * <p>Algorithm: repeatedly take {@code id % 62} to select the least-significant
     * Base62 digit, then divide {@code id} by 62. Digits are appended in
     * least-significant-first order, then the result is reversed to produce the
     * canonical big-endian representation.
     *
     * @param id a strictly positive database record ID
     * @return the Base62-encoded string; never null, never empty
     * @throws IllegalArgumentException if {@code id} is zero or negative, as these
     *         values have no meaningful encoding and indicate a logic error at the call site
     */
    public String encode(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("ID must be a positive number");
        }

        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(ALPHABET.charAt((int) (id % BASE)));
            id /= BASE;
        }
        return sb.reverse().toString();
    }
}
