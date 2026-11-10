package simpletomcat.catalina.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import simpletomcat.catalina.Container;
import simpletomcat.catalina.core.*;
import simpletomcat.catalina.mapper.Mapper;
import simpletomcat.servlet.*;
import simpletomcat.support.HttpTestServer;
import static org.junit.jupiter.api.Assertions.*;

class ContainerHttpTest {
    private final StandardEngine engine = new StandardEngine("engine");
    private final List<StandardWrapper> wrappers = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger(), destroyed = new AtomicInteger();

    private StandardContext context(StandardHost host, String path) {
        StandardContext context = new StandardContext(path); host.addChild(context); return context;
    }

    private void servlet(StandardContext context, String name, String pattern) {
        StandardWrapper wrapper = new StandardWrapper(() -> new Servlet() {
            public void init() { }
            public void service(ServletRequest req, ServletResponse res) throws ServletException {
                calls.incrementAndGet();
                if (req.getRequestURI().endsWith("/fail")) {
                    res.setBody("partial-secret"); res.setContentType("application/partial");
                    throw new ServletException("failure");
                }
                res.setBody(name + "|" + req.getContextPath() + "|" + req.getServletPath() + "|"
                        + req.getPathInfo() + "|" + req.getQueryString());
            }
            public void destroy() { destroyed.incrementAndGet(); }
        });
        wrapper.setName(name); context.addChild(wrapper); context.addServletMapping(pattern, wrapper); wrappers.add(wrapper);
    }

    // 실제 TCP로 Host·최장 Context·네 패턴·query를 구분하고 400/404/500 뒤 다음 요청과 전체 해제를 확인해야 한다.
    @Test
    void routesRealHttpAndRecoversAfterMissingInvalidAndFailedRequests() throws Exception {
        StandardHost first = new StandardHost("a.test"), second = new StandardHost("b.test");
        engine.addChild(first); engine.addChild(second);
        StandardContext shop = context(first, "/shop"), admin = context(first, "/admin");
        StandardContext deep = context(first, "/shop/deep"), other = context(second, "/shop");
        servlet(shop, "exact", "/status"); servlet(admin, "admin", "/status");
        servlet(deep, "deep", "/status"); servlet(other, "other", "/status");
        servlet(shop, "prefix", "/api/*"); servlet(shop, "longest", "/api/deep/*");
        servlet(shop, "ext", "*.do"); servlet(shop, "default", "/");
        try {
            try (HttpTestServer server = new HttpTestServer(new CoyoteAdapter(engine, new Mapper(engine, null)))) {
                String[][] routes = {
                    {"a.TEST:8080", "/shop/status?x=1", "exact|/shop|/status|null|x=1"},
                    {"a.test", "/admin/status", "admin|/admin|/status|null|null"},
                    {"b.test", "/shop/status", "other|/shop|/status|null|null"},
                    {"a.test", "/shop/deep/status", "deep|/shop/deep|/status|null|null"},
                    {"a.test", "/shop/api/deep/x.do", "longest|/shop|/api/deep|/x.do|null"},
                    {"a.test", "/shop/api/x.do", "prefix|/shop|/api|/x.do|null"},
                    {"a.test", "/shop/file.do", "ext|/shop|/file.do|null|null"},
                    {"a.test", "/shop/other?raw=%2F", "default|/shop|/other|null|raw=%2F"}
                };
                for (String[] sample : routes) {
                    String wire = server.request("GET", sample[1], sample[0]);
                    assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
                    assertTrue(wire.endsWith(sample[2]), wire);
                }
                int beforeErrors = calls.get();
                String[][] missing = {{"unknown", "/shop/status"}, {"a.test", "/shopper/status"},
                        {"a.test", "/admin/missing"}, {"a.test", "/shop/WEB-INF/config"},
                        {"a.test", "/shop/meta-inf"}};
                for (String[] sample : missing) assertTrue(server.request("GET", sample[1], sample[0]).startsWith("HTTP/1.1 404"));
                assertEquals(beforeErrors, calls.get());
                for (String path : new String[]{"/shop//status", "/shop/../admin/status", "/shop/%73tatus", "/shop/status;x=1", "*"}) {
                    assertTrue(server.request("OPTIONS", path, "a.test").startsWith("HTTP/1.1 400"), path);
                    assertTrue(server.request("GET", "/shop/status", "a.test").endsWith("exact|/shop|/status|null|null"));
                }
                String failure = server.request("GET", "/shop/fail", "a.test");
                assertTrue(failure.startsWith("HTTP/1.1 500")); assertFalse(failure.contains("partial"));
                assertTrue(server.request("GET", "/shop/status", "a.test").endsWith("exact|/shop|/status|null|null"));
            }
        } finally { for (StandardWrapper wrapper : wrappers) wrapper.unload(); }
        assertEquals(8, destroyed.get());
    }

    // HTTP/1.1 미등록 Host와 HTTP/1.0 Host 생략은 명시한 기본 Host로만 전달되어야 한다.
    @Test
    void usesOnlyExplicitDefaultHost() throws Exception {
        StandardHost host = new StandardHost("localhost"); engine.addChild(host);
        servlet(context(host, ""), "root", "/");
        try {
            try (HttpTestServer server = new HttpTestServer(new CoyoteAdapter(engine, new Mapper(engine, "localhost")))) {
                assertTrue(server.request("GET", "/hello", "unknown").endsWith("root||/hello|null|null"));
                assertTrue(server.wire("GET /hello HTTP/1.0\r\n\r\n").endsWith("root||/hello|null|null"));
            }
        } finally { for (StandardWrapper wrapper : wrappers) wrapper.unload(); }
        assertEquals(2, calls.get()); assertEquals(1, destroyed.get());
    }
}
