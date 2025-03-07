/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.opentelemetry.contrib.awsxray;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.testing.time.TestClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * This class was taken from Jaeger java client.
 * https://github.com/jaegertracing/jaeger-client-java/blob/master/jaeger-core/src/test/java/io/jaegertracing/internal/utils/RateLimiterTest.java
 */
class RateLimiterTest {

  @Test
  void testRateLimiterWholeNumber() {
    TestClock clock = TestClock.create();
    RateLimiter limiter = new RateLimiter(2.0, 2.0, clock);

    assertThat(limiter.trySpend(1.0)).isTrue();
    assertThat(limiter.trySpend(1.0)).isTrue();
    assertThat(limiter.trySpend(1.0)).isFalse();
    // move time 250ms forward, not enough credits to pay for 1.0 item
    clock.advance(Duration.ofMillis(250));
    assertThat(limiter.trySpend(1.0)).isFalse();

    // move time 500ms forward, now enough credits to pay for 1.0 item
    clock.advance(Duration.ofMillis(500));

    assertThat(limiter.trySpend(1.0)).isTrue();
    assertThat(limiter.trySpend(1.0)).isFalse();

    // move time 5s forward, enough to accumulate credits for 10 messages, but it should still be
    // capped at 2
    clock.advance(Duration.ofMillis(5000));

    assertThat(limiter.trySpend(1.0)).isTrue();
    assertThat(limiter.trySpend(1.0)).isTrue();
    assertThat(limiter.trySpend(1.0)).isFalse();
    assertThat(limiter.trySpend(1.0)).isFalse();
    assertThat(limiter.trySpend(1.0)).isFalse();
  }

  @Test
  void testRateLimiterSteadyRate() {
    TestClock clock = TestClock.create();
    RateLimiter limiter = new RateLimiter(5.0 / 60.0, 5.0, clock);
    for (int i = 0; i < 100; i++) {
      assertThat(limiter.trySpend(1.0)).isTrue();
      clock.advance(Duration.ofSeconds(20));
    }
  }

  @Test
  void cantWithdrawMoreThanMax() {
    TestClock clock = TestClock.create();
    RateLimiter limiter = new RateLimiter(1, 1.0, clock);
    assertThat(limiter.trySpend(2)).isFalse();
  }

  @Test
  void testRateLimiterLessThanOne() {
    TestClock clock = TestClock.create();
    RateLimiter limiter = new RateLimiter(0.5, 0.5, clock);

    assertThat(limiter.trySpend(0.25)).isTrue();
    assertThat(limiter.trySpend(0.25)).isTrue();
    assertThat(limiter.trySpend(0.25)).isFalse();
    // move time 250ms forward, not enough credits to pay for 1.0 item
    clock.advance(Duration.ofMillis(250));
    assertThat(limiter.trySpend(0.25)).isFalse();

    // move time 500ms forward, now enough credits to pay for 1.0 item
    clock.advance(Duration.ofMillis(500));

    assertThat(limiter.trySpend(0.25)).isTrue();
    assertThat(limiter.trySpend(0.25)).isFalse();

    // move time 5s forward, enough to accumulate credits for 10 messages, but it should still be
    // capped at 2
    clock.advance(Duration.ofMillis(5000));

    assertThat(limiter.trySpend(0.25)).isTrue();
    assertThat(limiter.trySpend(0.25)).isTrue();
    assertThat(limiter.trySpend(0.25)).isFalse();
    assertThat(limiter.trySpend(0.25)).isFalse();
    assertThat(limiter.trySpend(0.25)).isFalse();
  }

  @Test
  void testRateLimiterMaxBalance() {
    TestClock clock = TestClock.create();
    RateLimiter limiter = new RateLimiter(0.1, 1.0, clock);

    clock.advance(Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(100)));
    assertThat(limiter.trySpend(1.0)).isTrue();
    assertThat(limiter.trySpend(1.0)).isFalse();

    // move time 20s forward, enough to accumulate credits for 2 messages, but it should still be
    // capped at 1
    clock.advance(Duration.ofMillis(20000));

    assertThat(limiter.trySpend(1.0)).isTrue();
    assertThat(limiter.trySpend(1.0)).isFalse();
  }

  /**
   * Validates rate limiter behavior with {@link System#nanoTime()}-like (non-zero) initial nano
   * ticks.
   */
  @Test
  void testRateLimiterInitial() {
    TestClock clock = TestClock.create();
    RateLimiter limiter = new RateLimiter(1000, 100, clock);

    assertThat(limiter.trySpend(100)).isTrue(); // consume initial (max) balance
    assertThat(limiter.trySpend(1)).isFalse();

    clock.advance(Duration.ofMillis(49)); // add 49 credits
    assertThat(limiter.trySpend(50)).isFalse();

    clock.advance(Duration.ofMillis(1)); // add one credit
    assertThat(limiter.trySpend(50)).isTrue(); // consume accrued balance
    assertThat(limiter.trySpend(1)).isFalse();

    clock.advance(Duration.ofMillis(1_000_000)); // add a lot of credits (max out balance)
    assertThat(limiter.trySpend(1)).isTrue(); // take one credit

    clock.advance(Duration.ofMillis(1_000_000)); // add a lot of credits (max out balance)
    assertThat(limiter.trySpend(101)).isFalse(); // can't consume more than max balance
    assertThat(limiter.trySpend(100)).isTrue(); // consume max balance
    assertThat(limiter.trySpend(1)).isFalse();
  }

  /** Validates concurrent credit check correctness. */
  @Test
  void testRateLimiterConcurrency() throws InterruptedException, ExecutionException {
    int numWorkers = 8;
    ExecutorService executorService = Executors.newFixedThreadPool(numWorkers);
    final int creditsPerWorker = 1000;
    TestClock clock = TestClock.create();
    final RateLimiter limiter = new RateLimiter(1, numWorkers * creditsPerWorker, clock);
    final AtomicInteger count = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>(numWorkers);
    for (int w = 0; w < numWorkers; ++w) {
      Future<?> future =
          executorService.submit(
              () -> {
                for (int i = 0; i < creditsPerWorker * 2; ++i) {
                  if (limiter.trySpend(1)) {
                    count.getAndIncrement(); // count allowed operations
                  }
                }
              });
      futures.add(future);
    }
    for (Future<?> future : futures) {
      future.get();
    }
    executorService.shutdown();
    executorService.awaitTermination(1, TimeUnit.SECONDS);
    assertThat(count.get())
        .withFailMessage("Exactly the allocated number of credits must be consumed")
        .isEqualTo(numWorkers * creditsPerWorker);
    assertThat(limiter.trySpend(1)).isFalse();
  }
}
