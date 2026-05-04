# Security

## Overview

This document covers authentication posture, input validation, data exposure, known vulnerabilities, and hardening recommendations for the `url-shortener` demo service.

---

## Authentication and Authorization

**Current state:** None implemented.

All API endpoints (`POST /shorten`, `GET /{shortCode}`) are publicly accessible without authentication. This is appropriate for a public URL shortener demo but is a blocker for production deployments where rate limiting, per-user quotas, or private URLs are required.

**Recommendations:**
- Add Spring Security with API key or JWT bearer token authentication for `POST /shorten` to prevent anonymous abuse.
- `GET /{shortCode}` can remain unauthenticated for public redirects.
- Restrict Actuator endpoints (`/actuator/health`, `/actuator/metrics`) to internal networks or an authenticated management port to prevent information disclosure.

---

## Input Entry Points

| Entry Point | Validation Applied | Risk |
|---|---|---|
| `POST /shorten` — `originalUrl` | `@NotBlank`, `@URL` (Hibernate Validator) | Moderate — URL validation details below |
| `POST /shorten` — `alias` | `@Size(1,20)`, `@Pattern(^[a-zA-Z0-9_-]*$)` | Low — character allowlist enforced |
| `POST /shorten` — `expiresAt` | None (raw `Instant`, parsed by Jackson) | Low — malformed timestamps produce 400 from Jackson |
| `GET /{shortCode}` — path variable | None beyond URL path parsing | Low — arbitrary strings hit the DB/cache lookup |

---

## URL Validation

The `@URL` annotation from Hibernate Validator checks that `originalUrl` is a syntactically valid URL. It does **not** enforce the scheme.

Known gaps:

| Gap | Severity | Impact |
|---|---|---|
| `javascript:` and `data:` URIs pass `@URL` validation | High | A user could create a short URL that executes JavaScript in the browser on redirect (XSS via redirect) |
| No SSRF protection — private/internal IPs (e.g., `http://169.254.169.254/`) are accepted | High | Redirect can be used to probe internal infrastructure or AWS metadata endpoints |
| No scheme allowlist | Medium | `ftp://`, `file://`, and other non-HTTP schemes are accepted |

**Recommendations:**
- Add explicit scheme validation in `UrlService.shorten()`: reject any URL whose scheme is not `http` or `https`.
- Add an SSRF blocklist: reject URLs that resolve to loopback (`127.x.x.x`), link-local (`169.254.x.x`), RFC-1918 private ranges, and `localhost`.

---

## Short Code Enumeration

Because short codes are Base62-encoded sequential integer IDs, an attacker can enumerate all short URLs by iterating `encode(1)`, `encode(2)`, etc. This leaks:
- The approximate total number of URLs ever created.
- All non-expired destination URLs.

**Severity:** Medium for a public demo; High for a service hosting private or sensitive URLs.

**Recommendation:** For production, switch to a cryptographically random 6-8 character token (using `SecureRandom`) to prevent enumeration.

---

## Credentials and Secrets

### Hardcoded credentials found: 2

| File | Property | Value | Severity |
|---|---|---|---|
| `application.properties` | `spring.datasource.password` | `postgres` | High |
| `application.properties` | `spring.datasource.username` | `postgres` | Medium |

**Recommendation:** Move all credentials to environment variables or a secrets manager (HashiCorp Vault, AWS Secrets Manager). Use Spring Boot's `${ENV_VAR}` substitution:

```
spring.datasource.password=${DB_PASSWORD}
```

Never commit production credentials to version control.

---

## Actuator Exposure

The following Actuator endpoints are exposed over the public HTTP port (8080):

| Endpoint | Data Exposed |
|---|---|
| `/actuator/health` | Full component health detail (`show-details=always`) — reveals DB and Redis connectivity status |
| `/actuator/info` | Application metadata |
| `/actuator/metrics` | JVM, HTTP request, datasource, and cache metrics |

**Severity:** Medium. Exposes operational information that aids reconnaissance.

**Recommendations:**
- Change `management.endpoint.health.show-details` to `when-authorized` or `never` on the public port.
- Move Actuator to a separate management port (`management.server.port=8081`) bound to an internal/private interface only.
- Restrict the exposed endpoints to `health` only on the public port.

---

## Error Response Information Disclosure

`GlobalExceptionHandler` masks internal errors behind "An unexpected error occurred" for `500` responses — this is correct. However:

- `404` and `409` responses include the short code or alias value from the exception message (e.g., `"No URL found for short code: abc123"`). This confirms that a given code does not exist, which aids enumeration.
- Validation error responses in `400` include the field names and constraint messages, which reveals the internal field structure of the request DTO.

**Severity:** Low for the 400/404 cases in a public API context. Acceptable for developer experience.

---

## Dependency Security

| Dependency | Version Source | Notes |
|---|---|---|
| Spring Boot | 3.2.5 (BOM) | Maintained release train; keep updated for CVE patches |
| PostgreSQL JDBC | Managed by Spring Boot BOM | Verify no known CVEs on deployment |
| Lettuce (Redis client) | Managed by Spring Boot BOM | Transitive via `spring-boot-starter-data-redis` |
| Hibernate Validator | Managed by Spring Boot BOM | `@URL` uses Apache Commons Validator internally |
| Lombok | Compile-time only; excluded from fat JAR | No runtime attack surface |

**Recommendation:** Run `mvn dependency-check:check` (OWASP Dependency Check plugin) in CI to catch known CVE regressions.

---

## Security Issues by Severity

| Severity | Count | Items |
|---|---|---|
| High | 3 | Hardcoded DB password, `javascript:`/`data:` URI bypass, SSRF via redirect |
| Medium | 3 | Sequential ID enumeration, Actuator public exposure, no scheme allowlist |
| Low | 3 | No authentication on `POST /shorten`, 404 confirms code absence, no OWASP dep-check in CI |

---

## Recommended Hardening Checklist

- [ ] Add Spring Security; protect `POST /shorten` with API key or JWT
- [ ] Enforce `http`/`https`-only scheme in URL validation
- [ ] Add SSRF IP blocklist for private/loopback ranges
- [ ] Move DB credentials to environment variables
- [ ] Switch Actuator to a private management port
- [ ] Set `management.endpoint.health.show-details=when-authorized`
- [ ] Add OWASP Dependency Check to Maven build
- [ ] Switch to random token generation for short codes in production
