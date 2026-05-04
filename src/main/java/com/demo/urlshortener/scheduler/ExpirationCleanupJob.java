package com.demo.urlshortener.scheduler;

import com.demo.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Scheduled maintenance job that purges expired URL mappings from PostgreSQL.
 *
 * <p>Runs on a cron schedule defined by the {@code app.cleanup.cron} property
 * (default: {@code 0 0 3 * * *} — daily at 03:00 server time). On each execution
 * it issues a single bulk {@code DELETE} via
 * {@link UrlRepository#deleteAllExpired(Instant)}, removing all rows where
 * {@code expires_at IS NOT NULL AND expires_at < now}.
 *
 * <p><strong>Redis interaction:</strong> this job does not explicitly remove keys
 * from Redis. Stale cache entries for deleted URLs expire naturally via their Redis
 * TTL. This is safe because {@link com.demo.urlshortener.service.UrlService#resolve}
 * re-checks {@link com.demo.urlshortener.model.UrlMapping#isExpired()} on every
 * cache miss before re-caching.
 *
 * <p><strong>Multi-replica warning:</strong> if the application runs as multiple
 * instances, every instance will execute this job at the scheduled time. The
 * deletes are idempotent so correctness is maintained, but duplicate work occurs.
 * Consider adding {@code ShedLock} or a similar leader-election mechanism for
 * multi-node deployments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpirationCleanupJob {

    private final UrlRepository repository;

    /**
     * Executes the expired URL cleanup.
     *
     * <p>The cron expression is read from {@code app.cleanup.cron}; the fallback
     * value {@code 0 0 3 * * *} fires at 03:00 every day.
     *
     * <p>Runs within a transaction so the bulk delete is atomic. If the delete
     * fails partway through (e.g., database connection loss), the transaction is
     * rolled back and no rows are partially removed.
     */
    @Scheduled(cron = "${app.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void deleteExpiredUrls() {
        log.info("Starting expired URL cleanup job");
        int deleted = repository.deleteAllExpired(Instant.now());
        log.info("Expired URL cleanup complete — deleted {} records", deleted);
    }
}
