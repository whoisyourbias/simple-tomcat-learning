package simpletomcat.catalina.connector;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import simpletomcat.examples.FilterServer;
import simpletomcat.catalina.core.*;
import simpletomcat.catalina.mapper.Mapper;
import simpletomcat.servlet.*;
import simpletomcat.support.HttpTestServer;
import static org.junit.jupiter.api.Assertions.*;

class FilterHttpTest {
    String body(String wire) { return wire.substring(wire.indexOf("\r\n\r\n") + 4); }
    // 두 앱을 호출하면 Engine Valve는 양쪽에 적용되고 shop 필터만 shop 요청을 감싼다.
    @Test void filtersOnlySelectedApp() throws Exception {
        var trace = new CopyOnWriteArrayList<String>(); var engine = FilterServer.createEngine(trace::add);
        FilterServer.startFilters(engine); trace.clear();
        try (var server = new HttpTestServer(new CoyoteAdapter(engine,new Mapper(engine,"localhost")))) {
            assertEquals("shop hello\n",body(server.get("/shop/hello")));
            assertEquals("admin hello\n",body(server.get("/admin/hello")));
            assertEquals(List.of("Engine-before","shop-init","A-before","B-before","shop-service","B-after","A-after","Engine-after",
                    "Engine-before","admin-init","admin-service","Engine-after"),trace);
        } finally { FilterServer.closeApplications(engine); }
        assertTrue(trace.indexOf("shop-destroy") < trace.indexOf("B-destroy"));
        assertEquals(1,Collections.frequency(trace,"A-destroy"));
    }
    // 중단 응답은 200이며 service를 건너뛰고 이후 정상 요청은 새 체인으로 실행한다.
    @Test void shortCircuitSkipsServlet() throws Exception {
        var trace = new CopyOnWriteArrayList<String>(); var engine = FilterServer.createEngine(trace::add); FilterServer.startFilters(engine);
        try (var server = new HttpTestServer(new CoyoteAdapter(engine,new Mapper(engine,"localhost")))) {
            assertEquals("blocked\n",body(server.get("/shop/blocked"))); assertFalse(trace.contains("shop-service"));
            assertEquals("shop hello\n",body(server.get("/shop/hello")));
        } finally { FilterServer.closeApplications(engine); }
    }
    // 필터와 서블릿이 부분 응답 후 실패하면 새 500을 보내고 다음 정상 요청은 복구한다.
    @Test void recoversAfterFilterAndServletFailure() throws Exception {
        var engine = FilterServer.createEngine(s -> {}); FilterServer.startFilters(engine);
        try (var server = new HttpTestServer(new CoyoteAdapter(engine,new Mapper(engine,"localhost")))) {
            for (String path : List.of("/shop/filter-fail","/shop/servlet-fail")) {
                String wire = server.get(path); assertTrue(wire.startsWith("HTTP/1.1 500 ")); assertFalse(wire.contains("partial"));
                assertEquals("shop hello\n",body(server.get("/shop/hello")));
            }
            assertTrue(server.get("/shop/WEB-INF/config").startsWith("HTTP/1.1 404 "));
        } finally { FilterServer.closeApplications(engine); }
    }
    // HEAD는 필터와 서블릿을 실행하지만 본문을 전송하지 않는다.
    @Test void suppressesHeadBody() throws Exception {
        var engine = FilterServer.createEngine(s -> {}); FilterServer.startFilters(engine);
        try (var server = new HttpTestServer(new CoyoteAdapter(engine,new Mapper(engine,"localhost")))) {
            String wire = server.request("HEAD","/shop/hello","localhost"); assertTrue(wire.startsWith("HTTP/1.1 200 ")); assertEquals("",body(wire));
        } finally { FilterServer.closeApplications(engine); }
    }
    // 바인딩 충돌이면 이미 준비한 필터를 해제하고 기존 listener는 닫지 않는다.
    @Test void cleansFiltersWhenProtocolConstructionFails() throws Exception {
        var trace = new CopyOnWriteArrayList<String>(); var engine = FilterServer.createEngine(trace::add);
        try (var occupied = new ServerSocket(0)) {
            assertThrows(java.io.IOException.class, () -> FilterServer.open(engine,occupied.getLocalPort()));
            assertFalse(occupied.isClosed()); assertEquals(1,Collections.frequency(trace,"A-init"));
            assertEquals(1,Collections.frequency(trace,"A-destroy"));
        }
    }
    // 활성 요청을 latch로 고정한 동안 destroy하지 않고 serve 완료 뒤 해제한다.
    @Test void destroysOnlyAfterServingCompletes() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var destroyed = new java.util.concurrent.atomic.AtomicInteger();
        var engine = new StandardEngine("engine"); var host = new StandardHost("localhost"); var context = new StandardContext("");
        engine.addChild(host); host.addChild(context);
        var wrapper = new StandardWrapper(() -> new Servlet() {
            public void init() {} public void destroy() {}
            public void service(ServletRequest q,ServletResponse s) throws ServletException {
                entered.countDown(); try { if (!release.await(5,TimeUnit.SECONDS)) throw new ServletException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ServletException("interrupted",e); } s.setBody("done");
            }
        }); wrapper.setName("s"); context.addChild(wrapper); context.addServletMapping("/",wrapper);
        context.addFilter("f", () -> new Filter() {
            public void init() {} public void destroy() { destroyed.incrementAndGet(); }
            public void doFilter(ServletRequest q,ServletResponse s,FilterChain chain) throws java.io.IOException,ServletException { chain.doFilter(q,s); }
        }); FilterServer.startFilters(engine);
        ExecutorService client = Executors.newSingleThreadExecutor();
        try (var server = new HttpTestServer(new CoyoteAdapter(engine,new Mapper(engine,"localhost")))) {
            Future<String> response = client.submit(() -> server.get("/"));
            try { assertTrue(entered.await(3,TimeUnit.SECONDS)); assertEquals(0,destroyed.get()); }
            finally { release.countDown(); }
            assertEquals("done",body(response.get(5,TimeUnit.SECONDS)));
        } finally {
            release.countDown(); client.shutdownNow(); assertTrue(client.awaitTermination(5,TimeUnit.SECONDS)); FilterServer.closeApplications(engine);
        }
        assertEquals(1,destroyed.get()); FilterServer.closeApplications(engine); assertEquals(1,destroyed.get());
    }
}
