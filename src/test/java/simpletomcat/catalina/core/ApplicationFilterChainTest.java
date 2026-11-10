package simpletomcat.catalina.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import simpletomcat.servlet.*;
import static org.junit.jupiter.api.Assertions.*;
import static simpletomcat.catalina.core.CatalinaTestSupport.*;

class ApplicationFilterChainTest {
    final List<String> trace = new ArrayList<>();
    Filter filter(String name) {
        return new Filter() {
            public void init() { }
            public void destroy() { }
            public void doFilter(ServletRequest q, ServletResponse s, FilterChain c) throws java.io.IOException, ServletException {
                trace.add(name + "-before"); c.doFilter(q, s); trace.add(name + "-after");
            }
        };
    }
    // A와 B를 등록하면 진입은 등록 순서, 복귀는 역순이며 원본 요청과 응답을 전달한다.
    @Test void invokesInRegistrationOrder() throws Exception {
        var servlet = new CountingServlet() {
            public void service(ServletRequest q, ServletResponse s) throws java.io.IOException, ServletException {
                trace.add("service"); super.service(q, s);
            }
        };
        var q = new Request("/hello"); var s = new Response();
        new ApplicationFilterChain(List.of(filter("A"), filter("B")), servlet).doFilter(q, s);
        assertEquals(List.of("A-before", "B-before", "service", "B-after", "A-after"), trace);
        assertSame(q, servlet.requests.getFirst()); assertSame(s, servlet.responses.getFirst());
    }
    // 필터가 다음 호출을 생략하면 서블릿은 실행되지 않는다.
    @Test void shortCircuits() throws Exception {
        var servlet = new CountingServlet();
        Filter stop = new Filter() {
            public void init() { } public void destroy() { }
            public void doFilter(ServletRequest q, ServletResponse s, FilterChain c) { s.setBody("blocked"); }
        };
        var response = new Response();
        new ApplicationFilterChain(List.of(stop), servlet).doFilter(new Request("/"), response);
        assertEquals(0, servlet.services); assertEquals("blocked", response.body);
    }
    // 생성 뒤 목록을 변경해도 스냅샷과 요청별 위치를 유지하고 빈 목록은 서블릿을 호출한다.
    @Test void keepsPositionsPerChain() throws Exception {
        var filters = new ArrayList<>(List.of(filter("A")));
        var servlet = new CountingServlet();
        var first = new ApplicationFilterChain(filters, servlet);
        var second = new ApplicationFilterChain(filters, servlet); filters.clear();
        first.doFilter(new Request("/one"), new Response());
        second.doFilter(new Request("/two"), new Response());
        new ApplicationFilterChain(List.of(), servlet).doFilter(new Request("/three"), new Response());
        assertEquals(List.of("A-before", "A-after", "A-before", "A-after"), trace);
        assertEquals(3, servlet.services);
    }
    // 서블릿에서 던진 검사 예외는 같은 객체로 전달되고 새 체인은 처음부터 실행한다.
    @Test void propagatesFailure() throws Exception {
        var servlet = new CountingServlet(); var failure = new ServletException("failure");
        servlet.serviceFailure = failure;
        assertSame(failure, assertThrows(ServletException.class, () ->
                new ApplicationFilterChain(List.of(filter("A")), servlet).doFilter(new Request("/"), new Response())));
        servlet.serviceFailure = null;
        new ApplicationFilterChain(List.of(filter("A")), servlet).doFilter(new Request("/next"), new Response());
        assertEquals(List.of("A-before", "A-before", "A-after"), trace);
    }
}
