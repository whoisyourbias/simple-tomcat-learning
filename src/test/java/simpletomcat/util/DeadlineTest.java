package simpletomcat.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class DeadlineTest {
    // 0은 즉시 만료하고 음수·나노초 범위 초과는 부작용 전에 거부한다.
    @Test
    void validatesFiniteBudgets() {
        assertEquals(0, Deadline.after(Duration.ZERO).remainingNanos());
        assertThrows(IllegalArgumentException.class, () -> Deadline.after(Duration.ofSeconds(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> Deadline.after(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    // 여러 자식에게 전달하는 잔여 시간은 다시 전체 예산으로 늘어나지 않는다.
    @Test
    void remainingBudgetNeverIncreases() {
        var d = Deadline.after(Duration.ofSeconds(3));
        long a = d.remainingNanos();
        assertTrue(d.remaining().toNanos() <= a);
    }
}
