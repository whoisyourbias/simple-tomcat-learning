package simpletomcat.catalina.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import simpletomcat.catalina.*;
import simpletomcat.catalina.connector.Connector;
import simpletomcat.servlet.*;
import simpletomcat.util.net.EndpointOptions;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class LifecycleShutdownTest {
    static final class Fixture implements AutoCloseable {
        final StandardServer server = new StandardServer();
        final StandardService service;
        final Connector connector;
        final StandardContext context = new StandardContext("");
        final AtomicInteger destroyed = new AtomicInteger();
        final AtomicInteger filterDestroyed = new AtomicInteger();

        Fixture(java.util.function.Supplier<Servlet> servlet) {
            this(servlet, true, "s");
        }

        Fixture(java.util.function.Supplier<Servlet> servlet, boolean attach, String name) {
            var engine = new StandardEngine("e");
            var host = new StandardHost("localhost");
            engine.addChild(host);
            host.addChild(context);
            var wrapper = new StandardWrapper(servlet);
            wrapper.setName("s");
            context.addChild(wrapper);
            context.addServletMapping("/", wrapper);
            context.addFilter(
                    "f",
                    () ->
                            new Filter() {
                                public void init() {}

                                public void destroy() {
                                    filterDestroyed.incrementAndGet();
                                }

                                public void doFilter(
                                        ServletRequest q, ServletResponse s, FilterChain n)
                                        throws IOException, ServletException {
                                    n.doFilter(q, s);
                                }
                            });
            service = new StandardService(name, engine, "localhost");
            connector = new Connector(0, new EndpointOptions(2, 1, 500));
            service.addConnector(connector);
            if (attach) server.addService(service);
        }

        String get() throws IOException {
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", connector.getPort()), 3000);
                socket.setSoTimeout(3000);
                socket.getOutputStream()
                        .write(
                                "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                return new String(
                        socket.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        public void close() throws Exception {
            server.close();
        }
    }

    static void waitFor(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (latch.getCount() > 0) {
                try {
                    if (!latch.await(Math.max(1, until - System.nanoTime()), TimeUnit.NANOSECONDS))
                        throw new AssertionError("latch timeout");
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    // 실행 중 Servlet이 인터럽트를 무시하면 소켓 취소 뒤에도 앱을 해제하지 않고 완료 뒤 재시도한다.
    @Test
    void defersAllApplicationCleanupWhenOneConnectorStillRuns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var destroyed = new AtomicInteger();
        var clients = Executors.newSingleThreadExecutor();
        var f =
                new Fixture(
                        () ->
                                new Servlet() {
                                    public void init() {}

                                    public void destroy() {
                                        destroyed.incrementAndGet();
                                    }

                                    public void service(ServletRequest q, ServletResponse s) {
                                        entered.countDown();
                                        waitFor(release);
                                        s.setBody("ok");
                                    }
                                });
        try {
            f.server.start();
            var response = clients.submit(f::get);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(LifecycleException.class, () -> f.server.stop(Duration.ofMillis(150)));
            assertEquals(LifecycleState.FAILED, f.server.getState());
            assertEquals(0, destroyed.get());
            assertEquals(0, f.filterDestroyed.get());
            release.countDown();
            response.get(5, TimeUnit.SECONDS);
            f.server.stop(Duration.ofSeconds(3));
            assertEquals(1, destroyed.get());
            assertEquals(1, f.filterDestroyed.get());
            assertEquals(LifecycleState.STOPPED, f.server.getState());
        } finally {
            release.countDown();
            f.close();
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // destroy가 지연되어도 같은 청소 Future만 기다리며 반복 stop에서 destroy를 중복 호출하지 않는다.
    @Test
    void reusesCleanupFutureWhenDestroyIsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var destroyed = new AtomicInteger();
        var f =
                new Fixture(
                        () ->
                                new Servlet() {
                                    public void init() {}

                                    public void destroy() {
                                        destroyed.incrementAndGet();
                                        entered.countDown();
                                        waitFor(release);
                                    }

                                    public void service(ServletRequest q, ServletResponse s) {
                                        s.setBody("ok");
                                    }
                                });
        try {
            f.server.start();
            assertTrue(f.get().contains("200 OK"));
            assertThrows(LifecycleException.class, () -> f.server.stop(Duration.ofMillis(150)));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(LifecycleException.class, () -> f.server.stop(Duration.ofMillis(100)));
            assertEquals(1, destroyed.get());
            release.countDown();
            f.server.stop(Duration.ofSeconds(3));
            assertEquals(1, destroyed.get());
            assertEquals(1, f.filterDestroyed.get());
        } finally {
            release.countDown();
            f.close();
        }
    }

    // 첫 Connector 바인딩 성공 뒤 둘째 충돌은 앞 포트와 앱 성공 초기화분을 회수한다.
    @Test
    void rollsBackFirstBindingWhenSecondPortIsOccupied() throws Exception {
        var f =
                new Fixture(
                        () ->
                                new Servlet() {
                                    public void init() {}

                                    public void destroy() {}

                                    public void service(ServletRequest q, ServletResponse s) {}
                                });
        try (var occupied = new ServerSocket(0)) {
            f.service.addConnector(
                    new Connector(occupied.getLocalPort(), EndpointOptions.defaults()));
            assertThrows(LifecycleException.class, f.server::start);
            assertEquals(1, f.filterDestroyed.get());
            try (var rebound = new ServerSocket()) {
                rebound.setReuseAddress(true);
                rebound.bind(new InetSocketAddress(f.connector.getPort()));
            }
            assertFalse(occupied.isClosed());
        } finally {
            f.close();
        }
    }

    // destroy의 일반 실패에도 Filter 정리를 마치고 반복 stop은 같은 실패와 호출 수를 보존한다.
    @Test
    void preservesCleanupFailureWithoutCallingDestroyAgain() throws Exception {
        var destroyed = new AtomicInteger();
        var f =
                new Fixture(
                        () ->
                                new Servlet() {
                                    public void init() {}

                                    public void destroy() {
                                        destroyed.incrementAndGet();
                                        throw new IllegalStateException("destroy");
                                    }

                                    public void service(ServletRequest q, ServletResponse s) {
                                        s.setBody("ok");
                                    }
                                });
        f.server.start();
        f.get();
        assertThrows(LifecycleException.class, () -> f.server.stop(Duration.ofSeconds(3)));
        assertEquals(1, f.filterDestroyed.get());
        assertThrows(LifecycleException.class, () -> f.server.stop(Duration.ofSeconds(3)));
        assertEquals(1, destroyed.get());
        assertEquals(LifecycleState.FAILED, f.server.getState());
    }

    // 시작 전 stop은 미초기화 앱 콜백을 호출하지 않고 반복 종료가 가능하다.
    @Test
    void stopsNewServerWithoutCreatingApplication() throws Exception {
        var created = new AtomicInteger();
        var f =
                new Fixture(
                        () -> {
                            created.incrementAndGet();
                            return null;
                        });
        f.server.close();
        f.server.close();
        assertEquals(0, created.get());
        assertEquals(-1, f.connector.getPort());
        assertEquals(LifecycleState.STOPPED, f.server.getState());
    }

    // 종료 대기가 인터럽트되면 상태를 복원하고 실행 중 앱 해제는 보류한 뒤 재시도한다.
    @Test
    void restoresInterruptAndAllowsCleanupRetry() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var destroyed = new AtomicInteger();
        var clients = Executors.newSingleThreadExecutor();
        var f =
                new Fixture(
                        () ->
                                new Servlet() {
                                    public void init() {}

                                    public void destroy() {
                                        destroyed.incrementAndGet();
                                    }

                                    public void service(ServletRequest q, ServletResponse s) {
                                        entered.countDown();
                                        waitFor(release);
                                        s.setBody("ok");
                                    }
                                });
        try {
            f.server.start();
            var response = clients.submit(f::get);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            try {
                assertThrows(LifecycleException.class, () -> f.server.stop(Duration.ofSeconds(1)));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(0, destroyed.get());
            } finally {
                Thread.interrupted();
            }
            release.countDown();
            response.get(5, TimeUnit.SECONDS);
            f.server.stop(Duration.ofSeconds(3));
            assertEquals(1, destroyed.get());
        } finally {
            Thread.interrupted();
            release.countDown();
            f.close();
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // 앞 정리 콜백이 전체 기한을 소진하면 독립 Service 신규 청소는 보류하고 다음 stop에서 회수한다.
    @Test
    void retriesIndependentServiceWhenSharedDeadlineWasExhausted() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slowDestroyed = new AtomicInteger();
        var fastDestroyed = new AtomicInteger();
        var slow =
                new Fixture(
                        () ->
                                new Servlet() {
                                    public void init() {}

                                    public void service(ServletRequest q, ServletResponse s) {
                                        s.setBody("slow");
                                    }

                                    public void destroy() {
                                        slowDestroyed.incrementAndGet();
                                        entered.countDown();
                                        waitFor(release);
                                    }
                                },
                        false,
                        "slow");
        var fast =
                new Fixture(
                        () ->
                                new Servlet() {
                                    public void init() {}

                                    public void service(ServletRequest q, ServletResponse s) {
                                        s.setBody("fast");
                                    }

                                    public void destroy() {
                                        fastDestroyed.incrementAndGet();
                                    }
                                },
                        false,
                        "fast");
        var server = new StandardServer();
        server.addService(fast.service);
        server.addService(slow.service);
        try {
            server.start();
            fast.get();
            slow.get();
            assertThrows(LifecycleException.class, () -> server.stop(Duration.ofMillis(150)));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(1, slowDestroyed.get());
            assertEquals(0, fastDestroyed.get());
            release.countDown();
            server.stop(Duration.ofSeconds(3));
            assertEquals(1, fastDestroyed.get());
            assertEquals(1, slowDestroyed.get());
            assertEquals(1, fast.filterDestroyed.get());
            assertEquals(1, slow.filterDestroyed.get());
        } finally {
            release.countDown();
            server.close();
        }
    }

    // 뒤 Service 초기화 실패와 제어 스레드 인터럽트가 겹쳐도 앞 Service의 진행 요청은 해제하지 않는다.
    @Test
    void retainsActiveWorkDuringStartupRollback() throws Exception {
        var requestEntered = new CountDownLatch(1);
        var releaseRequest = new CountDownLatch(1);
        var initEntered = new CountDownLatch(1);
        var failInit = new CountDownLatch(1);
        var destroyed = new AtomicInteger();
        var first =
                new Fixture(
                        () ->
                                new Servlet() {
                                    public void init() {}

                                    public void destroy() {
                                        destroyed.incrementAndGet();
                                    }

                                    public void service(ServletRequest q, ServletResponse s) {
                                        requestEntered.countDown();
                                        waitFor(releaseRequest);
                                        s.setBody("ok");
                                    }
                                },
                        false,
                        "first");
        var engine = new StandardEngine("later");
        var host = new StandardHost("localhost");
        var context = new StandardContext("");
        engine.addChild(host);
        host.addChild(context);
        context.addFilter(
                "bad",
                () ->
                        new Filter() {
                            public void init() throws ServletException {
                                initEntered.countDown();
                                try {
                                    if (!failInit.await(3, TimeUnit.SECONDS))
                                        throw new ServletException("init wait");
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new ServletException("unexpected interrupt", e);
                                }
                                Thread.currentThread().interrupt();
                                throw new ServletException("later-init");
                            }

                            public void destroy() {
                                fail("Failed init must not destroy");
                            }

                            public void doFilter(
                                    ServletRequest q, ServletResponse s, FilterChain n) {}
                        });
        var later = new StandardService("later", engine, "localhost");
        later.addConnector(new Connector(0, EndpointOptions.defaults()));
        var server = new StandardServer();
        server.addService(first.service);
        server.addService(later);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var starting =
                    workers.submit(
                            () -> {
                                server.start();
                                return null;
                            });
            assertTrue(initEntered.await(3, TimeUnit.SECONDS));
            var response = workers.submit(first::get);
            assertTrue(requestEntered.await(3, TimeUnit.SECONDS));
            failInit.countDown();
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> starting.get(5, TimeUnit.SECONDS));
            Throwable cause = failure;
            boolean found = false;
            while (cause != null) {
                if ("later-init".equals(cause.getMessage())) found = true;
                cause = cause.getCause();
            }
            assertTrue(found);
            assertEquals(0, destroyed.get());
            assertEquals(0, first.filterDestroyed.get());
            releaseRequest.countDown();
            try {
                response.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                assertInstanceOf(IOException.class, expected.getCause());
            }
            server.stop(Duration.ofSeconds(3));
            assertEquals(1, destroyed.get());
            assertEquals(1, first.filterDestroyed.get());
        } finally {
            failInit.countDown();
            releaseRequest.countDown();
            try {
                server.close();
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
