package simpletomcat.catalina.connector;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import simpletomcat.catalina.core.*;
import simpletomcat.examples.FilterServer;
import simpletomcat.servlet.*;
import simpletomcat.support.LifecycleTestServer;
import simpletomcat.util.net.EndpointOptions;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class LifecycleHttpTest {
    // 공유 Servlet의 두 동시 호출에서 URI·쿼리·본문·필터 위치가 서로 섞이지 않는다.
    @Test
    void concurrentlyInvokesSameServletWithIsolatedChains() throws Exception {
        var entered = new CountDownLatch(2);
        var init = new AtomicInteger();
        var destroyed = new AtomicInteger();
        var trace = new ConcurrentLinkedQueue<String>();
        var engine = new StandardEngine("e");
        var host = new StandardHost("localhost");
        engine.addChild(host);
        var context = new StandardContext("");
        host.addChild(context);
        var servlet =
                new Servlet() {
                    public void init() {
                        init.incrementAndGet();
                    }

                    public void destroy() {
                        destroyed.incrementAndGet();
                    }

                    public void service(ServletRequest q, ServletResponse s)
                            throws ServletException {
                        entered.countDown();
                        try {
                            if (!entered.await(3, TimeUnit.SECONDS))
                                throw new ServletException("Concurrent entry missing");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new ServletException("interrupted", e);
                        }
                        trace.add(q.getRequestURI() + ":service");
                        s.setBody(q.getRequestURI() + "?" + q.getQueryString());
                    }
                };
        var wrapper = new StandardWrapper(() -> servlet);
        wrapper.setName("s");
        context.addChild(wrapper);
        context.addServletMapping("/", wrapper);
        for (String name : List.of("A", "B"))
            context.addFilter(
                    name,
                    () ->
                            new Filter() {
                                public void init() {}

                                public void destroy() {}

                                public void doFilter(
                                        ServletRequest q, ServletResponse s, FilterChain next)
                                        throws java.io.IOException, ServletException {
                                    trace.add(q.getRequestURI() + ":" + name + "-before");
                                    try {
                                        next.doFilter(q, s);
                                    } finally {
                                        trace.add(q.getRequestURI() + ":" + name + "-after");
                                    }
                                }
                            });
        var clients = Executors.newFixedThreadPool(2);
        try (var server = new LifecycleTestServer(engine, new EndpointOptions(2, 2, 1000))) {
            var a = clients.submit(() -> server.request("GET", "/a?x=1", "localhost"));
            var b = clients.submit(() -> server.request("GET", "/b?x=2", "localhost"));
            assertTrue(a.get(5, TimeUnit.SECONDS).endsWith("/a?x=1"));
            assertTrue(b.get(5, TimeUnit.SECONDS).endsWith("/b?x=2"));
            assertEquals(1, init.get());
            for (String path : List.of("/a", "/b"))
                assertEquals(
                        List.of(
                                path + ":A-before",
                                path + ":B-before",
                                path + ":service",
                                path + ":B-after",
                                path + ":A-after"),
                        trace.stream().filter(x -> x.startsWith(path + ":")).toList());
        } finally {
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, destroyed.get());
    }

    // 기존 매핑·필터 중단·500 복구·HEAD 본문 억제를 새 동시 실행 경로에서도 보존한다.
    @Test
    void preservesMappingsShortCircuitAndErrorRecovery() throws Exception {
        try (var server =
                new LifecycleTestServer(
                        FilterServer.createEngine(x -> {}), EndpointOptions.defaults())) {
            assertTrue(server.request("GET", "/shop/hello", "localhost").endsWith("shop hello\n"));
            assertTrue(
                    server.request("GET", "/admin/hello", "localhost").endsWith("admin hello\n"));
            assertTrue(server.request("GET", "/shop/blocked", "localhost").endsWith("blocked\n"));
            String failed = server.request("GET", "/shop/filter-fail", "localhost");
            assertTrue(failed.startsWith("HTTP/1.1 500"));
            assertFalse(failed.contains("partial"));
            assertTrue(
                    server.request("GET", "/shop/servlet-fail", "localhost")
                            .startsWith("HTTP/1.1 500"));
            assertTrue(
                    server.request("GET", "/shop/hello", "localhost").startsWith("HTTP/1.1 200"));
            assertTrue(server.request("HEAD", "/shop/hello", "localhost").endsWith("\r\n\r\n"));
            assertTrue(
                    server.request("GET", "/missing/hello", "localhost")
                            .startsWith("HTTP/1.1 404"));
        }
    }

    // 새 실행 예제도 같은 앱과 Context 경계를 등록한다.
    @Test
    void exampleBuildsExpectedApplications() {
        var engine = simpletomcat.examples.LifecycleServer.createEngine(x -> {});
        assertNotNull(engine.findChild("localhost").findChild("/shop"));
        assertNotNull(engine.findChild("localhost").findChild("/admin"));
    }
}
