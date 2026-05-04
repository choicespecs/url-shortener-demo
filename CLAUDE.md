# CLAUDE.md — url-shortener

This file is the primary reference for AI agents working on this codebase.
Read it fully before making any changes.

---

## Project identity

- **Name:** url-shortener
- **Language:** Java 21
- **Framework:** Spring Boot 3.2.5
- **Build tool:** Maven (pom.xml at project root)
- **Purpose:** Demo URL shortener service (~1M user scale) with Redis caching and PostgreSQL persistence

---

## Repository layout

```
src/
  main/
    java/com/demo/urlshortener/
      UrlShortenerApplication.java   # Entry point (@SpringBootApplication)
      controller/
        UrlController.java           # POST /shorten, GET /{shortCode}
      service/
        UrlService.java              # Business logic + cache-aside pattern
      model/
        UrlMapping.java              # JPA entity -> url_mappings table
      repository/
        UrlRepository.java           # Spring Data JPA + custom JPQL queries
      config/
        RedisConfig.java             # CacheManager bean (24h TTL, JSON values)
      util/
        Base62Encoder.java           # long ID -> Base62 string
      scheduler/
        ExpirationCleanupJob.java    # Nightly cron: bulk delete expired rows
      dto/
        ShortenRequest.java          # POST /shorten request body (validated)
        ShortenResponse.java         # POST /shorten response body
      exception/
        GlobalExceptionHandler.java  # @RestControllerAdvice: 404/409/400/500
        UrlNotFoundException.java    # Thrown for unknown/expired short codes
        AliasAlreadyExistsException.java  # Thrown for duplicate alias
  test/
    java/com/demo/urlshortener/
      service/UrlServiceTest.java    # Unit tests (Mockito) for UrlService
      util/Base62EncoderTest.java    # Unit tests for Base62Encoder
  resources/
    application.properties           # All runtime configuration
docs/
  ARCHITECTURE.md                    # Layer diagrams, components, DB schema
  FLOW.md                            # Sequence diagrams for all request flows
  DESIGN.md                          # Design decisions, patterns, tech debt
  SECURITY.md                        # Security posture and hardening checklist
  context/                           # Per-run pipeline audit trail
```

---

## Build and run

```bash
# Build and run all tests
mvn clean verify

# Run the application (requires PostgreSQL and Redis to be running)
mvn spring-boot:run

# Package as a fat JAR
mvn clean package
java -jar target/url-shortener-0.0.1-SNAPSHOT.jar
```

---

## External dependencies (runtime)

| Service | Default connection | Purpose |
|---|---|---|
| PostgreSQL | `localhost:5432/urlshortener` | Persistent storage of all URL mappings |
| Redis | `localhost:6379` | Read cache for the redirect hot-path |

Both must be running before the application starts. Connection details are in
`src/main/resources/application.properties`.

---

## API reference

### POST /shorten

Creates a short URL.

**Request body (JSON):**
```json
{
  "originalUrl": "https://example.com/long-path",
  "alias": "my-link",
  "expiresAt": "2026-12-31T23:59:59Z"
}
```
- `originalUrl` — required; must be a valid URL
- `alias` — optional; 1–20 chars, `[a-zA-Z0-9_-]` only; auto-generated if absent
- `expiresAt` — optional; ISO-8601 UTC instant; never-expiring if absent

**Response (201 Created):**
```json
{
  "shortUrl":    "http://localhost:8080/my-link",
  "shortCode":   "my-link",
  "originalUrl": "https://example.com/long-path",
  "createdAt":   "2026-05-04T12:00:00Z",
  "expiresAt":   "2026-12-31T23:59:59Z"
}
```

**Error responses:**
- `400` — validation failure (blank URL, invalid alias characters, etc.)
- `409` — alias already in use

### GET /{shortCode}

Redirects to the original URL.

**Response:** `302 Found` with `Location` header set to the original URL.

**Error responses:**
- `404` — short code unknown or expired

---

## Key implementation details

### Short code generation (no alias)

1. Save a `UrlMapping` with `shortCode = "PENDING"` and `saveAndFlush()` to get the DB-assigned `id`.
2. Pass `id` to `Base62Encoder.encode(id)` to produce the short code.
3. Update and save the record with the real short code.

This is a two-write pattern. See `DESIGN.md` Decision 1 for trade-offs and alternatives.

### Cache-aside (UrlService.resolve)

1. `GET url:{shortCode}` from Redis.
2. On hit: return immediately.
3. On miss: query PostgreSQL, check expiry, write to Redis with TTL, return.

Redis keys: `url:{shortCode}` (plain string values, no JSON wrapper).
TTL: `expiresAt - now` if expiry is set; 24 hours otherwise.

### Expiration cleanup

The `ExpirationCleanupJob` runs at 03:00 daily (configurable via `app.cleanup.cron`).
It issues a single `DELETE FROM url_mappings WHERE expires_at IS NOT NULL AND expires_at < now`.
Redis keys are not explicitly purged — they expire via their own TTL.

---

## Testing

Tests use JUnit 5 + Mockito. No Spring context is loaded — all dependencies are mocked.

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=UrlServiceTest
mvn test -Dtest=Base62EncoderTest
```

Test files:
- `UrlServiceTest` — covers shorten (with/without alias, duplicate alias) and resolve (cache hit, miss, expired, not found)
- `Base62EncoderTest` — covers known encodings, large IDs, and invalid input rejection

---

## Configuration reference

All properties in `src/main/resources/application.properties`:

| Property | Default | Notes |
|---|---|---|
| `app.base-url` | `http://localhost:8080` | Prepended to short codes in responses |
| `app.cleanup.cron` | `0 0 3 * * *` | Cron for nightly expiry cleanup |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/urlshortener` | |
| `spring.datasource.username` | `postgres` | Move to env var before production |
| `spring.datasource.password` | `postgres` | Move to env var before production |
| `spring.jpa.hibernate.ddl-auto` | `update` | Replace with Flyway/Liquibase for production |
| `spring.data.redis.host` | `localhost` | |
| `spring.data.redis.port` | `6379` | |
| `management.endpoints.web.exposure.include` | `health,info,metrics` | Actuator endpoints |

---

## Known tech debt (priority order)

1. **TD-1 HIGH** — Replace `ddl-auto=update` with Flyway/Liquibase
2. **TD-2 HIGH** — Configure Redis `maxmemory` + `maxmemory-policy=allkeys-lru`
3. **TD-3 MEDIUM** — Add index on `url_mappings.expires_at` for cleanup query efficiency
4. **TD-4 MEDIUM** — Add reserved-alias denylist (prevent shadowing `/actuator`, etc.)
5. **TD-5 MEDIUM** — Add ShedLock for multi-replica cleanup job safety
6. **TD-6 LOW** — Switch short-code generation to random tokens for non-enumerable codes
7. **TD-7 LOW** — Add OpenTelemetry trace IDs to 500 error responses
8. **TD-8 LOW** — Remove or use the CacheManager bean (currently unused)

See `docs/DESIGN.md` for full decision rationale.
See `docs/SECURITY.md` for the security hardening checklist.

---

## Diagram policy

All documentation diagrams must use Mermaid. Never use ASCII art.

| Content | Mermaid type |
|---|---|
| Request/response flows | `sequenceDiagram` |
| Architecture / components | `flowchart TD` with `subgraph` |
| Decision / branching flows | `flowchart TD` |
| State machines | `stateDiagram-v2` |
| Data models | `erDiagram` |
