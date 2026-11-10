package simpletomcat.catalina.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import simpletomcat.catalina.*;
import simpletomcat.catalina.connector.Connector;
import simpletomcat.examples.FilterServer;
import simpletomcat.util.net.EndpointOptions;

import java.time.Duration;

class StandardServiceTest {
    // Filter 준비·동결 후 바인딩하고 반복 시작과 종료가 자원을 중복 생성하지 않는다.
    @Test
    void preparesAndCloses() throws Exception {
        var e = FilterServer.createEngine(x -> {});
        var service = new StandardService("s", e, "localhost");
        var c = new Connector(0, EndpointOptions.defaults());
        service.addConnector(c);
        assertEquals(-1, c.getPort());
        service.start();
        service.start();
        assertTrue(e.isFrozen());
        assertTrue(c.getPort() > 0);
        assertEquals(LifecycleState.STARTED, service.getState());
        service.stop(Duration.ofSeconds(3));
        service.close();
        assertEquals(LifecycleState.STOPPED, service.getState());
        assertEquals(LifecycleState.STOPPED, c.getState());
        assertThrows(IllegalStateException.class, service::start);
    }

    // Filter 초기화 전에 구성을 동결하며 콜백의 수명 재진입은 잠금 대기 대신 즉시 거부한다.
    @Test
    void freezesBeforeFilterInit() throws Exception {
        var e = new StandardEngine("e");
        var h = new StandardHost("localhost");
        var app = new StandardContext("");
        e.addChild(h);
        h.addChild(app);
        var owner = new StandardService[1];
        app.addFilter(
                "f",
                () ->
                        new simpletomcat.servlet.Filter() {
                            public void init() {
                                assertTrue(e.isFrozen());
                                assertEquals(LifecycleState.STARTING, owner[0].getState());
                                assertThrows(
                                        IllegalStateException.class,
                                        () -> h.addChild(new StandardContext("/late")));
                                assertThrows(IllegalStateException.class, owner[0]::start);
                            }

                            public void doFilter(
                                    simpletomcat.servlet.ServletRequest q,
                                    simpletomcat.servlet.ServletResponse s,
                                    simpletomcat.servlet.FilterChain chain) {}

                            public void destroy() {}
                        });
        owner[0] = new StandardService("s", e, "localhost");
        owner[0].addConnector(new Connector(0, EndpointOptions.defaults()));
        try {
            owner[0].start();
        } finally {
            owner[0].close();
        }
    }
}
