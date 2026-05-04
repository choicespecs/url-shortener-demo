# Data Flow

## Overview

This document describes the request/response flows, cache interaction patterns, and background job behavior of the `url-shortener` service.

---

## 1. URL Shortening Flow — POST /shorten

### 1a. Auto-generated short code (no alias provided)

```mermaid
sequenceDiagram
    participant Client
    participant UrlController
    participant UrlService
    participant UrlRepository
    participant Base62Encoder
    participant Redis

    Client->>UrlController: POST /shorten {originalUrl, expiresAt?}
    UrlController->>UrlController: Bean validation (@Valid ShortenRequest)
    UrlController->>UrlService: shorten(request)

    UrlService->>UrlRepository: saveAndFlush(UrlMapping{shortCode="PENDING", originalUrl, expiresAt})
    UrlRepository-->>UrlService: UrlMapping{id=42, ...}

    UrlService->>Base62Encoder: encode(42)
    Base62Encoder-->>UrlService: "g" (Base62 of 42)

    UrlService->>UrlRepository: save(mapping{shortCode="g"})
    UrlRepository-->>UrlService: UrlMapping{id=42, shortCode="g", createdAt=...}

    UrlService->>Redis: SET url:g <originalUrl> EX <ttl>
    Redis-->>UrlService: OK

    UrlService-->>UrlController: ShortenResponse{shortUrl, shortCode, originalUrl, createdAt, expiresAt}
    UrlController-->>Client: 201 Created {shortUrl, shortCode, originalUrl, createdAt, expiresAt}
```

### 1b. Custom alias provided

```mermaid
sequenceDiagram
    participant Client
    participant UrlController
    participant UrlService
    participant UrlRepository
    participant Redis

    Client->>UrlController: POST /shorten {originalUrl, alias="my-link", expiresAt?}
    UrlController->>UrlService: shorten(request)

    UrlService->>UrlRepository: existsByShortCode("my-link")
    alt alias already taken
        UrlRepository-->>UrlService: true
        UrlService-->>UrlController: throws AliasAlreadyExistsException
        UrlController-->>Client: 409 Conflict {error: "Alias already in use: my-link"}
    else alias is available
        UrlRepository-->>UrlService: false
        UrlService->>UrlRepository: save(UrlMapping{shortCode="my-link", ...})
        UrlRepository-->>UrlService: UrlMapping{id=99, shortCode="my-link", ...}
        UrlService->>Redis: SET url:my-link <originalUrl> EX <ttl>
        UrlService-->>UrlController: ShortenResponse
        UrlController-->>Client: 201 Created {shortUrl, ...}
    end
```

---

## 2. Redirect Flow — GET /{shortCode}

```mermaid
sequenceDiagram
    participant Client
    participant UrlController
    participant UrlService
    participant Redis
    participant UrlRepository

    Client->>UrlController: GET /abc123
    UrlController->>UrlService: resolve("abc123")

    UrlService->>Redis: GET url:abc123
    alt Cache HIT
        Redis-->>UrlService: "https://example.com/long-url"
        UrlService-->>UrlController: "https://example.com/long-url"
    else Cache MISS
        Redis-->>UrlService: null
        UrlService->>UrlRepository: findByShortCode("abc123")
        alt Short code not found
            UrlRepository-->>UrlService: Optional.empty()
            UrlService-->>UrlController: throws UrlNotFoundException
            UrlController-->>Client: 404 Not Found {error: "No URL found for short code: abc123"}
        else Short code found
            UrlRepository-->>UrlService: Optional[UrlMapping]
            alt URL is expired
                UrlService-->>UrlController: throws UrlNotFoundException
                UrlController-->>Client: 404 Not Found
            else URL is valid
                UrlService->>Redis: SET url:abc123 <originalUrl> EX <remaining_ttl>
                UrlService-->>UrlController: "https://example.com/long-url"
            end
        end
    end

    UrlController-->>Client: 302 Found\nLocation: https://example.com/long-url
```

---

## 3. Cache-Aside Pattern Detail

```mermaid
flowchart TD
    A["resolve(shortCode) called"] --> B{"Redis GET\nurl:{shortCode}"}
    B -->|"value present"| C["Return cached URL\n(no DB call)"]
    B -->|"null"| D["Query PostgreSQL\nfindByShortCode"]
    D -->|"not found"| E["Throw UrlNotFoundException\n→ 404"]
    D -->|"found"| F{"isExpired()?"}
    F -->|"yes"| E
    F -->|"no"| G["Compute TTL\n(expiresAt - now)\nor 24h default"]
    G --> H{"ttl <= 0?"}
    H -->|"yes — already expired"| I["Skip cache write\nThrow UrlNotFoundException"]
    H -->|"no"| J["Redis SET url:{shortCode}\nwith TTL"]
    J --> C
```

---

## 4. TTL Computation Logic

```mermaid
flowchart TD
    A["cacheUrl(shortCode, url, expiresAt)"] --> B{"expiresAt\nprovided?"}
    B -->|"yes"| C["ttl = expiresAt - Instant.now()"]
    C --> D{"ttl <= 0?"}
    D -->|"yes"| E["Return — do not cache\n(URL is already expired)"]
    D -->|"no"| F["Redis SET with computed ttl"]
    B -->|"no"| G["ttl = 24 hours (DEFAULT_CACHE_TTL)"]
    G --> F
```

---

## 5. Short Code Generation

```mermaid
flowchart TD
    A["shorten() called\nno alias"] --> B["Save placeholder record\nshortCode = 'PENDING'"]
    B --> C["saveAndFlush()\nDB assigns auto-increment id"]
    C --> D["Base62Encoder.encode(id)"]
    D --> E{"id > 0?"}
    E -->|"no"| F["Throw IllegalArgumentException"]
    E -->|"yes"| G["Divide id by 62 repeatedly\nbuild char array from ALPHABET"]
    G --> H["Reverse StringBuilder"]
    H --> I["shortCode string returned"]
    I --> J["Update record:\nshortCode = encoded value"]
    J --> K["Save updated record"]
```

Base62 alphabet: `0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ`

Capacity by code length:

| Length | Unique codes |
|---|---|
| 1 | 62 |
| 2 | 3,844 |
| 4 | ~14.8 million |
| 6 | ~56 billion |
| 7 | ~3.5 trillion |

Because IDs are sequential database integers, the encoded codes are also monotonically increasing in lexicographic order for the same length.

---

## 6. Expiration Cleanup Job

```mermaid
sequenceDiagram
    participant Scheduler
    participant ExpirationCleanupJob
    participant UrlRepository
    participant PostgreSQL

    Scheduler->>ExpirationCleanupJob: trigger (cron: 0 0 3 * * *)
    ExpirationCleanupJob->>ExpirationCleanupJob: log "Starting expired URL cleanup job"
    ExpirationCleanupJob->>UrlRepository: deleteAllExpired(Instant.now())
    UrlRepository->>PostgreSQL: DELETE FROM url_mappings\nWHERE expires_at IS NOT NULL\nAND expires_at < :now
    PostgreSQL-->>UrlRepository: rows deleted count
    UrlRepository-->>ExpirationCleanupJob: int deleted
    ExpirationCleanupJob->>ExpirationCleanupJob: log "deleted N records"
```

Note: the cleanup job removes rows from PostgreSQL but does **not** evict corresponding keys from Redis. Stale Redis keys expire naturally via their TTL. This is safe because `UrlService.resolve()` also checks `isExpired()` at read time before re-caching.

---

## 7. Error Response Flow

```mermaid
flowchart TD
    E["Exception thrown in UrlService\nor bean validation failure"]
    E --> GEH["GlobalExceptionHandler\n@RestControllerAdvice"]

    GEH --> A{"Exception type?"}
    A -->|"UrlNotFoundException"| B["404 Not Found\n{timestamp, status, error}"]
    A -->|"AliasAlreadyExistsException"| C["409 Conflict\n{timestamp, status, error}"]
    A -->|"MethodArgumentNotValidException"| D["400 Bad Request\n{timestamp, status, error, fields:{...}}"]
    A -->|"Exception (generic)"| F["500 Internal Server Error\n{timestamp, status, error}"]
```

---

## 8. Data Model

```mermaid
erDiagram
    URL_MAPPINGS {
        BIGINT id PK
        VARCHAR_20 short_code UK
        VARCHAR_2048 original_url
        TIMESTAMPTZ created_at
        TIMESTAMPTZ expires_at
    }
```

The service uses a single table. There are no foreign keys or joins; the `short_code` unique index is the only secondary access path.
