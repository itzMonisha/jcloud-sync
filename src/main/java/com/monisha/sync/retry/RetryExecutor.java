package com.monisha.sync.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes tasks with exponential backoff and randomized full jitter to handle transient network errors.
 */
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double backoffMultiplier;
    private final double jitterFactor;

    public RetryExecutor() {
        this(3, 150, 3000, 2.0, 0.25);
    }

    public RetryExecutor(int maxAttempts, long initialBackoffMs, long maxBackoffMs, double backoffMultiplier, double jitterFactor) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(10, initialBackoffMs);
        this.maxBackoffMs = Math.max(initialBackoffMs, maxBackoffMs);
        this.backoffMultiplier = Math.max(1.0, backoffMultiplier);
        this.jitterFactor = Math.max(0.0, Math.min(1.0, jitterFactor));
    }

    /**
     * Executes the given callable with exponential backoff and jitter.
     *
     * @param task callable task
     * @param operationName name of operation for logging
     * @param <T> result type
     * @return task result
     * @throws Exception if all attempts fail or task is interrupted
     */
    public <T> T execute(Callable<T> task, String operationName) throws Exception {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                return task.call();
            } catch (Exception e) {
                lastException = e;
                if (attempt >= maxAttempts) {
                    log.error("Operation [{}] failed after {} attempts: {}", operationName, attempt, e.getMessage());
                    break;
                }

                long delay = calculateBackoffWithJitter(attempt);
                log.warn("Operation [{}] failed (attempt {}/{}), retrying in {}ms: {}",
                    operationName, attempt, maxAttempts, delay, e.getMessage());

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted for operation: " + operationName, ie);
                }
            }
        }

        throw lastException != null ? lastException : new RuntimeException("Operation failed: " + operationName);
    }

    @FunctionalInterface
    public interface VoidTask {
        void run() throws Exception;
    }

    public void executeVoid(VoidTask task, String operationName) throws Exception {
        execute(() -> {
            task.run();
            return null;
        }, operationName);
    }

    public long calculateBackoffWithJitter(int attempt) {
        long exponential = (long) (initialBackoffMs * Math.pow(backoffMultiplier, attempt - 1));
        long bounded = Math.min(maxBackoffMs, exponential);
        double jitterRange = bounded * jitterFactor;
        double jitter = (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * jitterRange;
        return Math.max(10, Math.round(bounded + jitter));
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }
}
