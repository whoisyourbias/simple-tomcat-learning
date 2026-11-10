package simpletomcat.catalina.connector;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import simpletomcat.catalina.*;
import simpletomcat.catalina.core.*;
import simpletomcat.coyote.*;
import simpletomcat.util.net.EndpointOptions;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;

class ConnectorTest {
    static class Protocol implements ManagedProtocolHandler {
        volatile boolean closed;
        final CountDownLatch release = new CountDownLatch(1);

        public int getPort() {
            return 0;
        }

        public void serve() throws IOException {
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("wait");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("accept");
        }

        public void close() {
            closed = true;
            release.countDown();
        }

        public void stopAccepting() {
            close();
        }

        public boolean awaitTermination(Duration t) {
            return true;
        }
    }

    // Thread 시작 실패도 바인딩된 Protocol을 취소하고 원인을 보존한다.
    @Test
    void cleansBindingWhenThreadStartFails() throws Exception {
        var p = new Protocol();
        var c =
                new Connector(
                        0,
                        EndpointOptions.defaults(),
                        (port, a, o) -> p,
                        r ->
                                new Thread(r) {
                                    public synchronized void start() {
                                        throw new IllegalStateException("thread");
                                    }
                                });
        var s = new StandardService("s", new StandardEngine("e"), null);
        s.addConnector(c);
        c.bind((q, r) -> {});
        assertThrows(LifecycleException.class, c::start);
        assertTrue(p.closed);
        c.close();
    }

    // accept 배경 실패는 자기 Thread join 없이 상위 Service에 알려지고 진단 원인이 남는다.
    @Test
    void reportsUnexpectedAcceptFailureWithoutSelfJoin() throws Exception {
        var p = new Protocol();
        var c = new Connector(0, EndpointOptions.defaults(), (port, a, o) -> p, r -> new Thread(r));
        var s = new StandardService("s", new StandardEngine("e"), null);
        s.addConnector(c);
        c.bind((q, r) -> {});
        try {
            c.start();
            p.release.countDown();
            assertTrue(c.awaitStopped(Duration.ofSeconds(3)));
            assertEquals(LifecycleState.FAILED, s.getState());
            assertNotNull(s.getFailure());
        } finally {
            c.close();
        }
    }

    // 자식 Connector와 Service에는 최초 전체 시간이 아니라 계속 감소하는 잔여 예산을 전달한다.
    @Test
    void sharesOneDeadlineAcrossConnectorsAndServices() throws Exception {
        var observed = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var server = new StandardServer();
        for (int serviceNumber = 0; serviceNumber < 2; serviceNumber++) {
            var service =
                    new StandardService(
                            "s" + serviceNumber, new StandardEngine("e" + serviceNumber), null);
            for (int connectorNumber = 0; connectorNumber < 2; connectorNumber++) {
                var protocol =
                        new Protocol() {
                            public boolean awaitTermination(Duration timeout) {
                                if (!timeout.isZero()) observed.add(timeout.toNanos());
                                return true;
                            }
                        };
                service.addConnector(
                        new Connector(
                                0, EndpointOptions.defaults(), (p, a, o) -> protocol, Thread::new));
            }
            server.addService(service);
        }
        try {
            server.start();
            server.stop(Duration.ofSeconds(1));
            assertEquals(4, observed.size());
            long previous = TimeUnit.SECONDS.toNanos(1);
            for (long budget : observed) {
                assertTrue(budget <= previous);
                previous = budget;
            }
        } finally {
            server.close();
        }
    }
}
