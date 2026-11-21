package com.monisha.sync.retry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {

    @Test
    void testSuccessfulFirstAttempt() throws Exception {
        RetryExecutor executor = new RetryExecutor(3, 50, 500, 2.0, 0.1);
        AtomicInteger attempts = new AtomicInteger(0);

        String result = executor.execute(() -> {
            attempts.incrementAndGet();
            return "SUCCESS";
        }, "test-first-attempt");

        assertEquals("SUCCESS", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void testSuccessAfterRetries() throws Exception {
        RetryExecutor executor = new RetryExecutor(4, 30, 200, 2.0, 0.1);
        AtomicInteger attempts = new AtomicInteger(0);

        String result = executor.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new java.io.IOException("Transient network timeout");
            }
            return "RECOVERED";
        }, "test-recovered");

        assertEquals("RECOVERED", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void testMaxRetriesExhausted() {
        RetryExecutor executor = new RetryExecutor(3, 20, 100, 2.0, 0.1);
        AtomicInteger attempts = new AtomicInteger(0);

        assertThrows(java.io.IOException.class, () -> {
            executor.execute(() -> {
                attempts.incrementAndGet();
                throw new java.io.IOException("Permanent failure");
            }, "test-exhausted");
        });

        assertEquals(3, attempts.get());
    }

    @Test
    void testBackoffCalculationBounded() {
        RetryExecutor executor = new RetryExecutor(5, 100, 800, 2.0, 0.0);

        assertEquals(100, executor.calculateBackoffWithJitter(1));
        assertEquals(200, executor.calculateBackoffWithJitter(2));
        assertEquals(400, executor.calculateBackoffWithJitter(3));
        assertEquals(800, executor.calculateBackoffWithJitter(4));
        assertEquals(800, executor.calculateBackoffWithJitter(5), "Should cap at maxBackoffMs");
    }
}
