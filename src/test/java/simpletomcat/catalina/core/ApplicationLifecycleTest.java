package simpletomcat.catalina.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import simpletomcat.servlet.*;

import java.time.Duration;
import java.util.*;

class ApplicationLifecycleTest {
    // 뒤 Context init 실패 시 성공한 앞 Context만 역순 정리하고 반복 청소는 재호출하지 않는다.
    @Test
    void recordsEarlierContextsAfterLaterInitFailure() throws Exception {
        var trace = new ArrayList<String>();
        var e = new StandardEngine("e");
        var h = new StandardHost("localhost");
        e.addChild(h);
        for (String name : List.of("A", "B")) {
            var c = new StandardContext("/" + name);
            h.addChild(c);
            c.addFilter(
                    name,
                    () ->
                            new Filter() {
                                public void init() throws ServletException {
                                    trace.add(name + ".init");
                                    if (name.equals("B")) throw new ServletException("B");
                                }

                                public void destroy() {
                                    trace.add(name + ".destroy");
                                }

                                public void doFilter(
                                        ServletRequest q, ServletResponse s, FilterChain n) {}
                            });
        }
        e.freeze();
        var life = new ApplicationLifecycle(e);
        assertThrows(ServletException.class, life::prepare);
        life.cleanup(Duration.ofSeconds(3));
        life.cleanup(Duration.ofSeconds(3));
        assertEquals(List.of("A.init", "B.init", "A.destroy"), trace);
    }

    // 청소 실행기 생성·제출 실패는 성공 초기화 소유권을 보존하고 재시도에서 한 번만 해제한다.
    @Test
    void retriesCleanupWhenExecutorCreationOrSubmissionFails() throws Exception {
        var e = new StandardEngine("e");
        var h = new StandardHost("localhost");
        var c = new StandardContext("");
        e.addChild(h);
        h.addChild(c);
        var destroyed = new java.util.concurrent.atomic.AtomicInteger();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        c.addFilter(
                "f",
                () ->
                        new Filter() {
                            public void init() {}

                            public void destroy() {
                                destroyed.incrementAndGet();
                            }

                            public void doFilter(
                                    ServletRequest q, ServletResponse s, FilterChain chain) {}
                        });
        var life =
                new ApplicationLifecycle(
                        e,
                        () -> {
                            int attempt = attempts.incrementAndGet();
                            if (attempt == 1) throw new IllegalStateException("executor creation");
                            if (attempt == 2)
                                return new java.util.concurrent.ThreadPoolExecutor(
                                        1,
                                        1,
                                        0,
                                        java.util.concurrent.TimeUnit.MILLISECONDS,
                                        new java.util.concurrent.ArrayBlockingQueue<Runnable>(1)) {
                                    public void execute(Runnable command) {
                                        throw new java.util.concurrent.RejectedExecutionException(
                                                "submit");
                                    }
                                };
                            return java.util.concurrent.Executors.newSingleThreadExecutor();
                        });
        life.prepare();
        assertThrows(
                simpletomcat.catalina.LifecycleException.class,
                () -> life.cleanup(Duration.ofSeconds(1)));
        assertEquals(0, destroyed.get());
        assertThrows(
                simpletomcat.catalina.LifecycleException.class,
                () -> life.cleanup(Duration.ofSeconds(1)));
        assertEquals(0, destroyed.get());
        life.cleanup(Duration.ofSeconds(3));
        life.cleanup(Duration.ofSeconds(3));
        assertEquals(1, destroyed.get());
        assertEquals(3, attempts.get());
    }
}
