package simpletomcat.catalina.core;

import org.junit.jupiter.api.Test;
import simpletomcat.catalina.ContainerRequest;
import simpletomcat.servlet.*;
import java.io.IOException;
import java.util.*;
import static simpletomcat.catalina.core.CatalinaTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class FilterInvocationTest {
    static Filter filter(FilterChain action) {
        return new Filter() {
            public void init() { } public void destroy() { }
            public void doFilter(ServletRequest q, ServletResponse s, FilterChain next) throws IOException, ServletException { action.doFilter(q,s); }
        };
    }
    // 필터가 중단해도 대여는 반환되어 서블릿 해제가 가능하고 service는 실행하지 않는다.
    @Test void returnsAfterShortCircuit() throws Exception {
        var c = new StandardContext(""); var servlet = new CountingServlet();
        var w = new StandardWrapper(() -> servlet); w.setName("s"); c.addChild(w);
        c.addFilter("stop", () -> filter((q,s) -> s.setBody("blocked"))); c.startFilters();
        try {
            var response = new Response(); w.getPipeline().getFirst().invoke(new ContainerRequest(new Request("/"), null,c,w),response);
            assertEquals("blocked",response.body); assertEquals(0,servlet.services);
            w.unload(); assertEquals(1,servlet.destroys);
        } finally { w.unload(); c.stopFilters(); }
    }
    // 각 필터 실패 타입과 반환 실패가 겹쳐도 필터 주원인과 suppressed를 보존한다.
    @Test void returnsAfterFilterFailures() throws Exception {
        for (Throwable failure : List.of(new IOException("io"),new ServletException("s"),new IllegalStateException("r"),new AssertionError("e"))) {
            var c = new StandardContext(""); var w = new StandardWrapperValveTest.StubWrapper(); c.addChild(w);
            c.addFilter("fail", () -> filter((q,s) -> {
                if (failure instanceof IOException e) throw e;
                if (failure instanceof ServletException e) throw e;
                if (failure instanceof RuntimeException e) throw e;
                throw (Error) failure;
            })); c.startFilters(); w.returnFailure = new IllegalStateException("return");
            try {
                assertSame(failure,assertThrows(Throwable.class, () -> w.getPipeline().getFirst().invoke(new ContainerRequest(new Request("/"),null,c,w),new Response())));
                assertEquals(List.of("allocate","deallocate"),w.calls);
                assertArrayEquals(new Throwable[]{w.returnFailure},failure.getSuppressed());
            } finally { c.stopFilters(); }
        }
    }
    // 미준비 목록 조회 실패에서도 대여를 반환하고 잘못된 Context 선택은 거부한다.
    @Test void returnsAfterPreparationFailureAndRejectsContextBypass() {
        var c = new StandardContext(""); var w = new StandardWrapperValveTest.StubWrapper(); c.addChild(w);
        c.addFilter("pending", () -> filter((q,s) -> {}));
        assertThrows(IllegalStateException.class, () -> w.getPipeline().getFirst().invoke(new ContainerRequest(new Request("/"),null,c,w),new Response()));
        assertEquals(List.of("allocate","deallocate"),w.calls);
        assertThrows(IllegalArgumentException.class, () -> w.getPipeline().getFirst().invoke(new ContainerRequest(new Request("/"),null,null,w),new Response()));
    }
}
