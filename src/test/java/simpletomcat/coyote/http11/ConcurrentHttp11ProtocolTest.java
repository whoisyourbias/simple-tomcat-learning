package simpletomcat.coyote.http11;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import simpletomcat.util.net.EndpointOptions;

import java.time.Duration;

class ConcurrentHttp11ProtocolTest {
    // 프로토콜은 바인딩한 포트를 보존하며 실행 전 종료도 자원을 반환한다.
    @Test
    void closesBeforeServing() throws Exception {
        try (var p =
                new ConcurrentHttp11Protocol(
                        0, (q, s) -> s.setBody("hello"), EndpointOptions.defaults())) {
            assertTrue(p.getPort() > 0);
            p.stopAccepting();
            assertTrue(p.awaitTermination(Duration.ofSeconds(1)));
        }
    }
}
