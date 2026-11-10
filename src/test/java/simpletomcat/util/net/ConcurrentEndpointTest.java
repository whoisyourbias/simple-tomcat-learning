package simpletomcat.util.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;

class ConcurrentEndpointTest {
    private static Socket connect(int port) throws java.io.IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            return socket;
        } catch (java.io.IOException | RuntimeException failure) {
            try {
                socket.close();
            } catch (java.io.IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    // 첫 연결이 대기해도 다른 작업자가 둘째 연결을 처리하고 종료 후 소유 연결이 남지 않는다.
    @Test
    void servesAnotherConnectionWhileFirstIsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try (var endpoint =
                new ConcurrentEndpoint(
                        0,
                        new EndpointOptions(2, 1, 1000),
                        (in, out) -> {
                            if (count.incrementAndGet() == 1) {
                                entered.countDown();
                                try {
                                    if (!release.await(5, TimeUnit.SECONDS))
                                        throw new java.io.IOException("latch");
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            out.write(65);
                        })) {
            var serving =
                    executor.submit(
                            () -> {
                                endpoint.serve();
                                return null;
                            });
            try (var first = connect(endpoint.getPort())) {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                try (var second = connect(endpoint.getPort())) {
                    second.setSoTimeout(3000);
                    assertEquals(65, second.getInputStream().read());
                }
                release.countDown();
                endpoint.stopAccepting();
                serving.get(5, TimeUnit.SECONDS);
                assertTrue(endpoint.awaitTermination(Duration.ofSeconds(3)));
                assertEquals(0, endpoint.snapshot().ownedConnections());
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // 기본 실행 설정을 제공하고 무한 큐·무한 읽기로 이어질 0과 음수를 거부한다.
    @Test
    void validatesOptions() {
        assertEquals(4, EndpointOptions.defaults().workerCount());
        assertThrows(IllegalArgumentException.class, () -> new EndpointOptions(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new EndpointOptions(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new EndpointOptions(1, 1, 0));
    }

    // 작업자와 큐가 모두 찬 세 번째 연결은 거부하며 앞 작업 완료 후 다시 처리한다.
    @Test
    void rejectsExcessConnectionAndRecovers() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var accept = Executors.newSingleThreadExecutor();
        try (var endpoint =
                new ConcurrentEndpoint(
                        0,
                        new EndpointOptions(1, 1, 500),
                        (in, out) -> {
                            if (count.incrementAndGet() == 1) {
                                entered.countDown();
                                try {
                                    release.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            out.write(65);
                        })) {
            var serving =
                    accept.submit(
                            () -> {
                                endpoint.serve();
                                return null;
                            });
            try (var a = connect(endpoint.getPort())) {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                try (var b = connect(endpoint.getPort())) {
                    assertTrue(
                            endpoint.awaitSnapshot(
                                    q -> q.queuedTasks() == 1, Duration.ofSeconds(3)));
                    try (var c = connect(endpoint.getPort())) {
                        c.setSoTimeout(3000);
                        assertTrue(
                                endpoint.awaitSnapshot(
                                        q -> q.rejectedConnections() == 1, Duration.ofSeconds(3)));
                        try {
                            assertEquals(-1, c.getInputStream().read());
                        } catch (SocketException reset) {
                        }
                    }
                    release.countDown();
                    b.setSoTimeout(3000);
                    assertEquals(65, b.getInputStream().read());
                }
            } finally {
                release.countDown();
                endpoint.stopAccepting();
                serving.get(5, TimeUnit.SECONDS);
                assertTrue(endpoint.awaitTermination(Duration.ofSeconds(3)));
            }
        } finally {
            release.countDown();
            accept.shutdownNow();
            assertTrue(accept.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // 읽기 무진행 제한 뒤 해당 연결만 종료하고 다음 연결을 처리한다.
    @Test
    void timesOutIncompleteHeadersAndRecovers() throws Exception {
        var entered = new CountDownLatch(1);
        var accept = Executors.newSingleThreadExecutor();
        try (var endpoint =
                new ConcurrentEndpoint(
                        0,
                        new EndpointOptions(1, 1, 150),
                        (in, out) -> {
                            entered.countDown();
                            int b = in.read();
                            out.write(b);
                        })) {
            var serving =
                    accept.submit(
                            () -> {
                                endpoint.serve();
                                return null;
                            });
            try (var a = connect(endpoint.getPort())) {
                a.setSoTimeout(3000);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(-1, a.getInputStream().read());
            }
            try (var b = connect(endpoint.getPort())) {
                b.setSoTimeout(3000);
                b.getOutputStream().write(66);
                assertEquals(66, b.getInputStream().read());
            } finally {
                endpoint.stopAccepting();
                serving.get(5, TimeUnit.SECONDS);
                assertTrue(endpoint.awaitTermination(Duration.ofSeconds(3)));
            }
        } finally {
            accept.shutdownNow();
            assertTrue(accept.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // accept 직후 중지한 연결은 등록·Handler 호출 없이 닫히고 accept 스레드도 끝난다.
    @Test
    void closesConnectionAcceptedDuringStop() throws Exception {
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var called = new java.util.concurrent.atomic.AtomicInteger();
        var accept = Executors.newSingleThreadExecutor();
        try (var endpoint =
                new ConcurrentEndpoint(
                        0,
                        new EndpointOptions(1, 1, 500),
                        (in, out) -> called.incrementAndGet(),
                        socket -> {
                            accepted.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("hook");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        })) {
            var serving =
                    accept.submit(
                            () -> {
                                endpoint.serve();
                                return null;
                            });
            try (var socket = connect(endpoint.getPort())) {
                socket.setSoTimeout(3000);
                assertTrue(accepted.await(3, TimeUnit.SECONDS));
                endpoint.stopAccepting();
                release.countDown();
                assertEquals(-1, socket.getInputStream().read());
                serving.get(5, TimeUnit.SECONDS);
                assertTrue(endpoint.awaitTermination(Duration.ofSeconds(3)));
                assertEquals(0, called.get());
                assertEquals(0, endpoint.snapshot().ownedConnections());
            }
        } finally {
            release.countDown();
            accept.shutdownNow();
            assertTrue(accept.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // 큐 취소는 미시작 Handler를 호출하지 않지만 실행 중인 소켓 닫힘을 작업 완료로 오인하지 않는다.
    @Test
    void cancelsQueuedWorkWithoutInvokingHandler() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var called = new java.util.concurrent.atomic.AtomicInteger();
        var accept = Executors.newSingleThreadExecutor();
        try (var endpoint =
                new ConcurrentEndpoint(
                        0,
                        new EndpointOptions(1, 1, 500),
                        (in, out) -> {
                            called.incrementAndGet();
                            entered.countDown();
                            boolean interrupted = false;
                            try {
                                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                                while (release.getCount() > 0)
                                    try {
                                        if (!release.await(
                                                Math.max(1, end - System.nanoTime()),
                                                TimeUnit.NANOSECONDS))
                                            throw new AssertionError("release");
                                    } catch (InterruptedException e) {
                                        interrupted = true;
                                    }
                            } finally {
                                if (interrupted) Thread.currentThread().interrupt();
                            }
                        })) {
            var serving =
                    accept.submit(
                            () -> {
                                endpoint.serve();
                                return null;
                            });
            try (var a = connect(endpoint.getPort())) {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                try (var b = connect(endpoint.getPort())) {
                    assertTrue(
                            endpoint.awaitSnapshot(
                                    q -> q.queuedTasks() == 1, Duration.ofSeconds(3)));
                    endpoint.close();
                    assertEquals(1, called.get());
                    assertEquals(1, endpoint.snapshot().ownedConnections());
                    assertFalse(endpoint.awaitTermination(Duration.ZERO));
                    release.countDown();
                    serving.get(5, TimeUnit.SECONDS);
                    assertTrue(endpoint.awaitTermination(Duration.ofSeconds(3)));
                    assertEquals(1, called.get());
                }
            }
        } finally {
            release.countDown();
            accept.shutdownNow();
            assertTrue(accept.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // 실행 전에 닫힌 Endpoint는 즉시 serve를 끝내며 두 번째 serve는 거부한다.
    @Test
    void closesBeforeServeAndRejectsSecondServe() throws Exception {
        try (var endpoint =
                new ConcurrentEndpoint(
                        0, EndpointOptions.defaults(), (in, out) -> fail("handler"))) {
            endpoint.close();
            endpoint.serve();
            assertThrows(IllegalStateException.class, endpoint::serve);
            assertTrue(endpoint.awaitTermination(Duration.ofSeconds(1)));
        }
    }
}
