package simpletomcat.catalina.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import simpletomcat.servlet.*;
import static org.junit.jupiter.api.Assertions.*;

class StandardContextFilterTest {
    final List<String> calls = new ArrayList<>();
    class RecordingFilter implements Filter {
        final String name; ServletException initFailure; RuntimeException destroyFailure;
        RecordingFilter(String name) { this.name = name; }
        public void init() throws ServletException { calls.add(name + ".init"); if (initFailure != null) throw initFailure; }
        public void destroy() { calls.add(name + ".destroy"); if (destroyFailure != null) throw destroyFailure; }
        public void doFilter(ServletRequest q, ServletResponse s, FilterChain c) throws java.io.IOException, ServletException { c.doFilter(q,s); }
    }
    // 등록 순서대로 한 번 초기화하고 불변 목록을 공개하며 역순으로 한 번 해제한다.
    @Test void initializesAndDestroysOnceInOrder() throws Exception {
        var context = new StandardContext(""); var a = new RecordingFilter("A"); var b = new RecordingFilter("B");
        context.addFilter("A", () -> a); context.addFilter("B", () -> b);
        assertThrows(IllegalStateException.class, context::getFilters);
        context.startFilters(); context.startFilters();
        assertEquals(List.of(a,b), context.getFilters());
        assertThrows(UnsupportedOperationException.class, () -> context.getFilters().clear());
        context.stopFilters(); context.stopFilters();
        assertEquals(List.of("A.init","B.init","B.destroy","A.destroy"), calls);
        assertThrows(IllegalStateException.class, context::startFilters);
        assertThrows(IllegalStateException.class, context::getFilters);
    }
    // 잘못된 등록을 거부해도 기존 순서가 보존되며 준비 이후 등록은 거부한다.
    @Test void rejectsInvalidRegistrationAtomically() throws Exception {
        var c = new StandardContext(""); var a = new RecordingFilter("A"); c.addFilter("A", () -> a);
        assertThrows(NullPointerException.class, () -> c.addFilter(null, () -> a));
        assertThrows(NullPointerException.class, () -> c.addFilter("B", null));
        assertThrows(IllegalArgumentException.class, () -> c.addFilter(" ", () -> a));
        assertThrows(IllegalArgumentException.class, () -> c.addFilter("A", () -> a));
        c.startFilters(); assertEquals(List.of(a), c.getFilters());
        assertThrows(IllegalStateException.class, () -> c.addFilter("B", () -> a)); c.stopFilters();
    }
    // B 초기화 실패와 A 정리 실패가 겹치면 B를 보존하고 C는 생성하지 않는다.
    @Test void rollsBackAndPreservesFailures() throws Exception {
        var c = new StandardContext(""); var a = new RecordingFilter("A"); var b = new RecordingFilter("B");
        b.initFailure = new ServletException("B"); a.destroyFailure = new IllegalStateException("A");
        c.addFilter("A", () -> a); c.addFilter("B", () -> b);
        c.addFilter("C", () -> { fail("C must not be created"); return null; });
        var failure = assertThrows(ServletException.class, c::startFilters);
        assertSame(b.initFailure, failure); assertArrayEquals(new Throwable[]{a.destroyFailure}, failure.getSuppressed());
        assertEquals(List.of("A.init","B.init","A.destroy"), calls);
        assertThrows(IllegalStateException.class, c::getFilters); assertThrows(IllegalStateException.class, c::startFilters);
        c.stopFilters(); assertEquals(3, calls.size());
    }
    // 팩터리 null 반환과 런타임 예외는 검사 예외가 되고 성공한 필터를 정리한다.
    @Test void handlesFactoryFailures() {
        for (boolean nullResult : List.of(true,false)) {
            calls.clear(); var c = new StandardContext(""); c.addFilter("A", () -> new RecordingFilter("A"));
            c.addFilter("B", () -> { if (nullResult) return null; throw new IllegalArgumentException("factory"); });
            assertThrows(ServletException.class, c::startFilters);
            assertEquals(List.of("A.init","A.destroy"), calls);
        }
    }
    // 여러 destroy가 실패해도 모든 형제를 정리하고 반복 정리에서 다시 호출하지 않는다.
    @Test void continuesAfterDestroyFailure() throws Exception {
        var c = new StandardContext(""); var a = new RecordingFilter("A"); var b = new RecordingFilter("B");
        a.destroyFailure = new IllegalStateException("A"); b.destroyFailure = new IllegalStateException("B");
        c.addFilter("A", () -> a); c.addFilter("B", () -> b); c.startFilters();
        var failure = assertThrows(ServletException.class, c::stopFilters);
        assertSame(b.destroyFailure, failure.getCause()); assertArrayEquals(new Throwable[]{a.destroyFailure}, failure.getSuppressed());
        c.stopFilters(); assertEquals(List.of("A.init","B.init","B.destroy","A.destroy"), calls);
    }
    // 무필터 NEW는 빈 목록이고 준비 중 재진입은 거부하며 정지 뒤에는 조회를 거부한다.
    @Test void enforcesStatesAndReentry() throws Exception {
        var c = new StandardContext(""); assertEquals(List.of(), c.getFilters());
        c.addFilter("A", () -> new RecordingFilter("A") {
            public void init() throws ServletException {
                assertThrows(IllegalStateException.class, c::startFilters);
                assertThrows(IllegalStateException.class, c::stopFilters);
                assertThrows(IllegalStateException.class, c::getFilters); super.init();
            }
        }); c.startFilters(); c.stopFilters();
        var empty = new StandardContext(""); empty.stopFilters(); empty.stopFilters();
        assertThrows(IllegalStateException.class, empty::getFilters);
    }
}
