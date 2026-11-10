package simpletomcat.catalina.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import simpletomcat.catalina.*;

import java.time.Duration;
import java.util.concurrent.*;

class LifecycleControlTest {
    // 배경 실패가 준비 완료보다 먼저 공개되면 성공 전이가 실패를 덮지 않는다.
    @Test
    void preservesBackgroundFailureBeforeStartCompletion() throws Exception {
        class Control extends LifecycleControl {
            public void start() throws LifecycleException {
                started();
            }

            public void stop(Duration t) {}

            void background() {
                failed(new IllegalStateException("accept"));
            }
        }
        var control = new Control();
        control.background();
        assertThrows(LifecycleException.class, control::start);
        assertEquals(LifecycleState.FAILED, control.getState());
    }
}
