package simpletomcat.catalina.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import simpletomcat.examples.FilterServer;
import simpletomcat.servlet.*;
import static org.junit.jupiter.api.Assertions.*;

class FilterServerLifecycleTest {
    Filter filter(String name,List<String> trace,boolean failInit) {
        return new Filter() {
            public void init() throws ServletException { trace.add(name+".init"); if (failInit) throw new ServletException(name); }
            public void destroy() { trace.add(name+".destroy"); }
            public void doFilter(ServletRequest q,ServletResponse s,FilterChain next) throws java.io.IOException,ServletException { next.doFilter(q,s); }
        };
    }
    // 뒤 Context의 초기화가 실패하면 앞 Context의 성공 필터를 해제하고 실패 객체는 해제하지 않는다.
    @Test void rollsBackEarlierContextsWhenLaterContextFails() throws Exception {
        var trace = new ArrayList<String>(); var engine = new StandardEngine("e"); var host = new StandardHost("localhost"); engine.addChild(host);
        var first = new StandardContext("/first"); var second = new StandardContext("/second"); host.addChild(first); host.addChild(second);
        first.addFilter("A",() -> filter("A",trace,false)); second.addFilter("B",() -> filter("B",trace,true));
        assertThrows(ServletException.class,() -> FilterServer.startFilters(engine));
        assertEquals(List.of("A.init","B.init","A.destroy"),trace);
        FilterServer.stopFilters(engine); assertEquals(3,trace.size());
    }
    // Servlet destroy가 실패해도 모든 Filter를 해제하고 원래 실패를 반환하며 반복 정리는 재호출하지 않는다.
    @Test void stopsFiltersAfterServletDestroyFailure() throws Exception {
        var trace = new ArrayList<String>(); var engine = new StandardEngine("e"); var host = new StandardHost("localhost"); engine.addChild(host);
        var context = new StandardContext(""); host.addChild(context); context.addFilter("A",() -> filter("A",trace,false));
        var failure = new IllegalStateException("destroy servlet");
        var wrapper = new StandardWrapper(() -> new Servlet() {
            public void init() {} public void service(ServletRequest q,ServletResponse s) {}
            public void destroy() { trace.add("servlet.destroy"); throw failure; }
        }); wrapper.setName("s"); context.addChild(wrapper); FilterServer.startFilters(engine);
        Servlet servlet = wrapper.allocate(); wrapper.deallocate(servlet);
        ServletException actual = assertThrows(ServletException.class,() -> FilterServer.closeApplications(engine));
        assertSame(failure,actual.getCause()); assertEquals(List.of("A.init","servlet.destroy","A.destroy"),trace);
        FilterServer.closeApplications(engine); assertEquals(3,trace.size());
    }
}
