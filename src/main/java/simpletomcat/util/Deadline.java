package simpletomcat.util;

import java.time.Duration;
import java.util.Objects;

/** 여러 자원의 대기에 하나의 단조 시간 예산을 공유한다. */
public final class Deadline {
    private final long started = System.nanoTime();
    private final long budget;

    private Deadline(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative timeout");
        try {
            budget = timeout.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Timeout too large", e);
        }
    }

    public static Deadline after(Duration timeout) {
        return new Deadline(timeout);
    }

    public long remainingNanos() {
        return Math.max(0, budget - (System.nanoTime() - started));
    }

    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }
}
