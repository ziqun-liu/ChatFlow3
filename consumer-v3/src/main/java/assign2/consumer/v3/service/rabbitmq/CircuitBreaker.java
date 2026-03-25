package assign2.consumer.v3.service.rabbitmq;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Generic circuit breaker — copied verbatim from server-v2, package renamed.
 * Used to protect both RabbitMQ publish paths and MySQL batch writes.
 */
public class CircuitBreaker {

    public enum State {CLOSED, OPEN, HALF_OPEN}

    private static final Logger logger = Logger.getLogger(CircuitBreaker.class.getName());

    private final int failureThreshold;
    private final long cooldownMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);
    private final AtomicReference<Thread> probeThread = new AtomicReference<>(null);

    private final AtomicLong totalSuccesses = new AtomicLong();
    private final AtomicLong totalFailures = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();
    private final AtomicInteger timesOpened = new AtomicInteger();

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) return true;
        if (current == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= cooldownMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    probeThread.set(null);
                    logger.info("CircuitBreaker: OPEN -> HALF_OPEN, probing...");
                }
                return tryClaimProbe();
            }
            totalRejected.incrementAndGet();
            return false;
        }
        return tryClaimProbe();
    }

    public void recordSuccess() {
        totalSuccesses.incrementAndGet();
        State current = state.get();
        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                failureCount.set(0);
                logger.info("CircuitBreaker: HALF_OPEN -> CLOSED, recovered. " + metrics());
            }
        } else if (current == State.CLOSED) {
            failureCount.set(0);
        }
    }

    public void recordFailure() {
        totalFailures.incrementAndGet();
        State current = state.get();
        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                timesOpened.incrementAndGet();
                logger.warning("CircuitBreaker: HALF_OPEN -> OPEN, still unavailable. " + metrics());
            }
        } else if (current == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openedAt.set(System.currentTimeMillis());
                    timesOpened.incrementAndGet();
                    logger.warning("CircuitBreaker: CLOSED -> OPEN after " + failures + " failures. " + metrics());
                }
            }
        }
    }

    public State getState() { return state.get(); }

    public void summary() {
        logger.info("=== CircuitBreaker Metrics ===\n"
            + "  Current state   : " + state.get() + "\n"
            + "  Total successes : " + totalSuccesses.get() + "\n"
            + "  Total failures  : " + totalFailures.get() + "\n"
            + "  Total rejected  : " + totalRejected.get() + "\n"
            + "  Times opened    : " + timesOpened.get());
    }

    private boolean tryClaimProbe() {
        Thread me = Thread.currentThread();
        if (probeThread.compareAndSet(null, me)) return true;
        totalRejected.incrementAndGet();
        return false;
    }

    private String metrics() {
        return "[successes=" + totalSuccesses.get() + ", failures=" + totalFailures.get()
            + ", rejected=" + totalRejected.get() + ", timesOpened=" + timesOpened.get() + "]";
    }
}
