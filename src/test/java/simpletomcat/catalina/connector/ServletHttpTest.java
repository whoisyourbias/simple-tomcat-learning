package simpletomcat.catalina.connector;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import simpletomcat.catalina.core.StandardWrapper;
import simpletomcat.servlet.*;
import simpletomcat.support.HttpTestServer;
import static org.junit.jupiter.api.Assertions.*;

class ServletHttpTest {
    // 실제 두 HTTP 요청의 메서드·URI·query·헤더가 전달되고 동일 서블릿 init/service/destroy가 1/2/1이어야 한다.
    @Test
    void reusesServletAcrossHttpRequests() throws Exception {
        AtomicInteger init = new AtomicInteger(), service = new AtomicInteger(), destroy = new AtomicInteger();
        StandardWrapper wrapper = new StandardWrapper(() -> new Servlet() {
            public void init() { init.incrementAndGet(); }
            public void service(ServletRequest req, ServletResponse res) {
                res.setStatus(201);
                res.setContentType("text/plain; charset=UTF-8");
                res.setBody("한글 " + service.incrementAndGet() + " " + req.getMethod() + " "
                        + req.getRequestURI() + " " + req.getQueryString() + " " + req.getHeader("x-test"));
            }
            public void destroy() { destroy.incrementAndGet(); }
        });
        try {
            try (HttpTestServer server = new HttpTestServer(new CoyoteAdapter(wrapper))) {
                String first = server.get("/hello?a=1");
                assertTrue(first.startsWith("HTTP/1.1 201 Created\r\n"));
                assertTrue(first.contains("Content-Type: text/plain; charset=UTF-8\r\n"));
                assertTrue(first.endsWith("한글 1 GET /hello a=1 value"));
                assertTrue(server.get("/other").endsWith("한글 2 GET /other null value"));
                assertEquals(1, init.get());
                assertEquals(0, destroy.get());
            }
        } finally { wrapper.unload(); }
        wrapper.unload();
        assertEquals(2, service.get());
        assertEquals(1, destroy.get());
    }

    // init 첫 실패는 500·service/destroy 0회이며 다음 요청은 새 인스턴스로 성공하고 그 객체만 destroy해야 한다.
    @Test
    void retriesInitAfterHttpFailure() throws Exception {
        AtomicInteger created = new AtomicInteger(), serviced = new AtomicInteger(), destroyed = new AtomicInteger();
        StandardWrapper wrapper = new StandardWrapper(() -> {
            boolean fail = created.incrementAndGet() == 1;
            return new Servlet() {
                public void init() throws ServletException { if (fail) throw new ServletException("init"); }
                public void service(ServletRequest req, ServletResponse res) { serviced.incrementAndGet(); res.setBody("ok"); }
                public void destroy() { destroyed.incrementAndGet(); }
            };
        });
        try {
            try (HttpTestServer server = new HttpTestServer(new CoyoteAdapter(wrapper))) {
                assertTrue(server.get("/hello").startsWith("HTTP/1.1 500"));
                wrapper.unload();
                assertEquals(0, serviced.get());
                assertEquals(0, destroyed.get());
                assertTrue(server.get("/hello").endsWith("ok"));
            }
        } finally { wrapper.unload(); }
        assertEquals(2, created.get());
        assertEquals(1, serviced.get());
        assertEquals(1, destroyed.get());
    }

    // service가 부분 응답 뒤 실패하면 500에서 부분 본문·헤더가 폐기되고 다음 HTTP 요청은 같은 객체로 성공해야 한다.
    @Test
    void returnsAllocationAfterServiceFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger(), init = new AtomicInteger(), destroy = new AtomicInteger();
        StandardWrapper wrapper = new StandardWrapper(() -> new Servlet() {
            public void init() { init.incrementAndGet(); }
            public void service(ServletRequest req, ServletResponse res) throws ServletException {
                if (calls.incrementAndGet() == 1) {
                    res.setContentType("application/partial"); res.setBody("partial-secret");
                    throw new ServletException("service");
                }
                res.setBody("recovered");
            }
            public void destroy() { destroy.incrementAndGet(); }
        });
        try {
            try (HttpTestServer server = new HttpTestServer(new CoyoteAdapter(wrapper))) {
                String failed = server.get("/fail");
                assertTrue(failed.startsWith("HTTP/1.1 500"));
                assertFalse(failed.contains("partial"));
                assertTrue(server.get("/ok").endsWith("recovered"));
                assertEquals(1, init.get());
            }
        } finally { wrapper.unload(); }
        assertEquals(1, destroy.get());
    }

    // latch로 service 실행 중을 고정하면 unload가 destroy 없이 거부되고 반환·서버 종료 후 한 번 해제되어야 한다.
    @Test
    void refusesUnloadDuringActiveHttpService() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger destroyed = new AtomicInteger();
        StandardWrapper wrapper = new StandardWrapper(() -> new Servlet() {
            public void init() { }
            public void service(ServletRequest req, ServletResponse res) throws ServletException {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new ServletException("release timeout");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ServletException("interrupted", e); }
                res.setBody("done");
            }
            public void destroy() { destroyed.incrementAndGet(); }
        });
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            try (HttpTestServer server = new HttpTestServer(new CoyoteAdapter(wrapper))) {
                try {
                    Future<String> result = client.submit(() -> server.get("/hello"));
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    assertThrows(IllegalStateException.class, wrapper::unload);
                    assertEquals(0, destroyed.get());
                    release.countDown();
                    assertTrue(result.get(4, TimeUnit.SECONDS).endsWith("done"));
                } finally { release.countDown(); }
            }
        } finally {
            release.countDown(); client.shutdownNow();
            assertTrue(client.awaitTermination(5, TimeUnit.SECONDS)); wrapper.unload();
        }
        assertEquals(1, destroyed.get());
    }
}
