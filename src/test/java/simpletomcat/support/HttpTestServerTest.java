package simpletomcat.support;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

class HttpTestServerTest {
    // 생성 중 시작 대기가 인터럽트되면 바인딩 포트와 실행기를 회수하고 인터럽트를 보존한다.
    @Test
    void cleansResourcesWhenStartupWaitIsInterrupted() throws Exception {
        var port = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedException.class, () -> new HttpTestServer((q, s) -> {}, port::set));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        try (var rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress(port.get()));
        }
    }
}
