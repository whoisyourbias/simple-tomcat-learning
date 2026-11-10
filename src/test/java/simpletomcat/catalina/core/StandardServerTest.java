package simpletomcat.catalina.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import simpletomcat.catalina.*;
import simpletomcat.catalina.connector.Connector;
import simpletomcat.examples.FilterServer;
import simpletomcat.servlet.*;
import simpletomcat.util.net.EndpointOptions;

import java.net.*;

class StandardServerTest {
    // 같은 Engine·Service를 중복 소유하려는 등록은 기존 구성을 바꾸지 않고 거부한다.
    @Test
    void rejectsDuplicateOwnershipAtomically() throws Exception {
        var e = FilterServer.createEngine(x -> {});
        var s = new StandardService("s", e, "localhost");
        assertThrows(
                IllegalStateException.class, () -> new StandardService("other", e, "localhost"));
        var server = new StandardServer();
        server.addService(s);
        assertThrows(IllegalArgumentException.class, () -> server.addService(s));
        assertEquals(1, server.findServices().length);
        assertThrows(IllegalStateException.class, () -> new StandardServer().addService(s));
        server.close();
    }

    // 뒤 Service 시작 실패는 앞 Service의 포트와 준비된 필터를 회수한다.
    @Test
    void rollsBackEarlierServiceAfterLaterFailure() throws Exception {
        var trace = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var server = new StandardServer();
        var first =
                new StandardService("first", FilterServer.createEngine(trace::add), "localhost");
        var c = new Connector(0, EndpointOptions.defaults());
        first.addConnector(c);
        server.addService(first);
        var engine = new StandardEngine("bad");
        var h = new StandardHost("localhost");
        engine.addChild(h);
        var app = new StandardContext("");
        h.addChild(app);
        app.addFilter(
                "bad",
                () ->
                        new Filter() {
                            public void init() throws ServletException {
                                throw new ServletException("init");
                            }

                            public void destroy() {
                                fail("failed init destroy");
                            }

                            public void doFilter(
                                    ServletRequest q, ServletResponse s, FilterChain n) {}
                        });
        var second = new StandardService("second", engine, "localhost");
        second.addConnector(new Connector(0, EndpointOptions.defaults()));
        server.addService(second);
        try {
            assertThrows(LifecycleException.class, server::start);
            assertEquals(LifecycleState.STOPPED, first.getState());
            assertTrue(trace.contains("A-destroy"));
            try (var rebound = new ServerSocket()) {
                rebound.setReuseAddress(true);
                rebound.bind(new InetSocketAddress(c.getPort()));
            }
        } finally {
            server.close();
        }
    }
}
