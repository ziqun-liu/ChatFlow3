package assign2.server.v2.service.rabbitmq;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Circuit breaker for RabbitMQ publish failures.
 *
 * State machine:
 *
 *   CLOSED ──(failures >= threshold)──► OPEN
 *     ▲                                   │
 *     │                            (cooldown elapsed)
 *     │                                   ▼
 *   (success)                         HALF_OPEN
 *     └───────────────────────────────────┘
 *                       │
 *                  (failure)
 *                       │
 *                       ▼
 *                      OPEN
 *
 * CLOSED   : Normal operation. All publish requests pass through.
 * OPEN     : RabbitMQ is unavailable. Requests are fast-failed immediately
 *            without attempting a connection, avoiding CONFIRM_TIMEOUT_MS waits.
 * HALF_OPEN: Cooldown elapsed. One probe request is allowed through to test
 *            recovery. Success → CLOSED. Failure → OPEN (restart cooldown).
 *
 * Also tracks metrics (successes, failures, rejections, times opened)
 * and logs a summary on each state transition.
 */
public class CircuitBreaker {

  public enum State { CLOSED, OPEN, HALF_OPEN }

  private static final Logger logger = Logger.getLogger(CircuitBreaker.class.getName());

  // ── Configuration ────────────────────────────────────────────────────────
  private final int failureThreshold;  // consecutive failures before OPEN
  private final long cooldownMs;       // ms to stay OPEN before HALF_OPEN

  // ── State machine ────────────────────────────────────────────────────────
  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private final AtomicLong openedAt = new AtomicLong(0);

  // Ensures only one probe request passes through in HALF_OPEN.
  // Reset each time circuit transitions into HALF_OPEN.
  private final AtomicReference<Thread> probeThread = new AtomicReference<>(null);

  // ── Metrics ──────────────────────────────────────────────────────────────
  private final AtomicLong totalSuccesses = new AtomicLong();
  private final AtomicLong totalFailures  = new AtomicLong();
  private final AtomicLong totalRejected  = new AtomicLong();  // fast-failed in OPEN
  private final AtomicInteger timesOpened = new AtomicInteger();

  public CircuitBreaker(int failureThreshold, long cooldownMs) {
    this.failureThreshold = failureThreshold;
    this.cooldownMs = cooldownMs;
  }

  // ── Core API ─────────────────────────────────────────────────────────────

  /**
   * Returns true if the caller is allowed to attempt a publish to RabbitMQ.
   *
   * CLOSED    → always true
   * OPEN      → false (fast-fail), unless cooldown has elapsed → transition to HALF_OPEN
   * HALF_OPEN → true for exactly one probe thread; others are fast-failed
   */
  public boolean allowRequest() {
    State current = state.get();

    if (current == State.CLOSED) {
      return true;
    }

    if (current == State.OPEN) {
      if (System.currentTimeMillis() - openedAt.get() >= cooldownMs) {
        // Cooldown elapsed — transition to HALF_OPEN for one probe
        if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
          probeThread.set(null);  // reset probe slot
          logger.info("CircuitBreaker: OPEN -> HALF_OPEN, probing RabbitMQ...");
        }
        return tryClaimProbe();
      }
      totalRejected.incrementAndGet();
      return false;
    }

    // HALF_OPEN: only one probe thread may proceed
    return tryClaimProbe();
  }

  /**
   * Record a successful publish. Resets failure count.
   * HALF_OPEN → CLOSED if probe succeeded.
   */
  public void recordSuccess() {
    totalSuccesses.incrementAndGet();
    State current = state.get();
    if (current == State.HALF_OPEN) {
      if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
        failureCount.set(0);
        logger.info("CircuitBreaker: HALF_OPEN -> CLOSED, RabbitMQ recovered. " + metrics());
      }
    } else if (current == State.CLOSED) {
      failureCount.set(0);
    }
  }

  /**
   * Record a failed publish. Increments failure count.
   * CLOSED    → OPEN if failures >= threshold.
   * HALF_OPEN → OPEN (probe failed, restart cooldown).
   */
  public void recordFailure() {
    totalFailures.incrementAndGet();
    State current = state.get();
    if (current == State.HALF_OPEN) {
      if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
        openedAt.set(System.currentTimeMillis());
        timesOpened.incrementAndGet();
        logger.warning("CircuitBreaker: HALF_OPEN -> OPEN, RabbitMQ still unavailable. "
            + metrics());
      }
    } else if (current == State.CLOSED) {
      int failures = failureCount.incrementAndGet();
      if (failures >= failureThreshold) {
        if (state.compareAndSet(State.CLOSED, State.OPEN)) {
          openedAt.set(System.currentTimeMillis());
          timesOpened.incrementAndGet();
          logger.warning("CircuitBreaker: CLOSED -> OPEN after " + failures
              + " consecutive failures. " + metrics());
        }
      }
    }
  }

  // ── Metrics ──────────────────────────────────────────────────────────────

  public void summary() {
    logger.info("=== CircuitBreaker Metrics ===\n"
        + "  Current state   : " + state.get() + "\n"
        + "  Total successes : " + totalSuccesses.get() + "\n"
        + "  Total failures  : " + totalFailures.get() + "\n"
        + "  Total rejected  : " + totalRejected.get() + "\n"
        + "  Times opened    : " + timesOpened.get());
  }

  public State getState() {
    return state.get();
  }

  // ── Internal ─────────────────────────────────────────────────────────────

  /**
   * Allows exactly one thread to act as the HALF_OPEN probe.
   * All other concurrent threads are fast-failed.
   */
  private boolean tryClaimProbe() {
    Thread me = Thread.currentThread();
    if (probeThread.compareAndSet(null, me)) {
      return true;  // this thread is the probe
    }
    // Another thread already claimed the probe slot
    totalRejected.incrementAndGet();
    return false;
  }

  private String metrics() {
    return "[successes=" + totalSuccesses.get()
        + ", failures=" + totalFailures.get()
        + ", rejected=" + totalRejected.get()
        + ", timesOpened=" + timesOpened.get() + "]";
  }
}
