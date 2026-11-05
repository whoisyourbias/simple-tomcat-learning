package simpletomcat.util.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EndpointTest {
    private static final String BODY = "안녕하세요, simple-tomcat!\n";

    // 임의 포트 서버에 두 연결로 요청하면 각각 200·UTF-8 바이트 길이·고정 본문·EOF를 받아야 한다.
    @Test
    void respondsToTwoConnections() throws Exception {
        try (RunningServer server = new RunningServer()) {
            for (int i = 0; i < 2; i++) {
                assertResponse(request(server.port(), "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"));
            }
        }
    }

    // 헤더 끝을 분할 전송하면 마지막 LF 전에는 응답하지 않고 완성 뒤 정확한 응답을 받아야 한다.
    @Test
    void waitsForSplitHeaderTerminator() throws Exception {
        try (RunningServer server = new RunningServer(); Socket client = connect(server.port())) {
            send(client, "GET / HTTP/1.1\r\nHost: localhost\r\n\r");
            server.awaitBlockedIn("readHeaders");
            client.setSoTimeout(200);
            assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
            client.setSoTimeout(3000);
            send(client, "\n");
            assertResponse(client.getInputStream().readAllBytes());
        }
    }

    // 미완성 헤더 뒤 송신 EOF를 보내면 무응답으로 닫고 다음 정상 연결은 처리해야 한다.
    @Test
    void recoversAfterIncompleteEof() throws Exception {
        try (RunningServer server = new RunningServer()) {
            try (Socket client = connect(server.port())) {
                send(client, "GET / HTTP/1.1\r\nHost: localhost\r\n");
                client.shutdownOutput();
                assertEquals(-1, client.getInputStream().read());
            }
            assertResponse(request(server.port(), "GET / HTTP/1.1\r\n\r\n"));
        }
    }

    // HTTP 문법이나 Host가 잘못돼도 헤더 끝이 있으면 1단계에서는 고정 200 응답을 받아야 한다.
    @Test
    void respondsWithoutValidatingHttp() throws Exception {
        try (RunningServer server = new RunningServer()) {
            assertResponse(request(server.port(), "not-http\r\n\r\n"));
        }
    }

    // 헤더 읽기 중 클라이언트가 RST로 끊어도 다음 정상 요청을 처리해야 한다.
    @Test
    void recoversAfterConnectionReset() throws Exception {
        try (RunningServer server = new RunningServer()) {
            try (Socket client = connect(server.port())) {
                send(client, "GET / HTTP/1.1\r\n");
                server.awaitBlockedIn("readHeaders");
                client.setSoLinger(true, 0);
            }
            assertResponse(request(server.port(), "GET / HTTP/1.1\r\n\r\n"));
        }
    }

    // accept 대기 중 close를 두 번 호출하면 serve가 끝나고 같은 포트를 다시 바인딩할 수 있어야 한다.
    @Test
    void closesWhileAcceptingAndReleasesPort() throws Exception {
        try (RunningServer server = new RunningServer()) {
            server.awaitBlockedIn("serve");
            server.stop();
            server.endpoint.close();
            assertPortReleased(server.port());
        }
    }

    // 미완성 헤더를 읽는 중 close하면 연결 EOF와 serve 종료, 포트 반환을 확인해야 한다.
    @Test
    void closesWhileReading() throws Exception {
        try (RunningServer server = new RunningServer(); Socket client = connect(server.port())) {
            send(client, "GET / HTTP/1.1\r\n");
            server.awaitBlockedIn("readHeaders");
            server.stop();
            assertEquals(-1, client.getInputStream().read());
            assertPortReleased(server.port());
        }
    }

    // serve 전에 close하면 serve는 반환하고 반복 close도 가능하며 포트가 반환돼야 한다.
    @Test
    void closesBeforeServing() throws Exception {
        try (Endpoint endpoint = new Endpoint(0)) {
            int port = endpoint.getPort();
            endpoint.close();
            endpoint.serve();
            endpoint.close();
            assertPortReleased(port);
        }
    }

    // 이미 실행 중인 서버에서 serve를 다시 호출하면 기존 루프를 방해하지 않고 거부해야 한다.
    @Test
    void rejectsSecondServe() throws Exception {
        try (RunningServer server = new RunningServer()) {
            assertThrows(IllegalStateException.class, server.endpoint::serve);
            assertResponse(request(server.port(), "GET / HTTP/1.1\r\n\r\n"));
        }
    }

    // 연결 시도와 close를 같은 latch로 시작해 경합시켜도 serve와 클라이언트가 유한 시간 안에 끝나야 한다.
    @Test
    void closesDuringConnectionRace() throws Exception {
        ExecutorService clients = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 30; i++) {
                try (RunningServer server = new RunningServer()) {
                    CountDownLatch go = new CountDownLatch(1);
                    Future<?> client = clients.submit(() -> {
                        assertTrue(go.await(3, TimeUnit.SECONDS));
                        try (Socket socket = connect(server.port())) {
                            try {
                                assertEquals(-1, socket.getInputStream().read());
                            } catch (SocketTimeoutException timeout) {
                                throw new AssertionError("종료 후 연결이 남음", timeout);
                            } catch (java.net.SocketException reset) {
                                // 종료와 경합한 연결은 RST로 끝날 수도 있다.
                            }
                        } catch (java.net.ConnectException refused) {
                            // 수신 소켓 종료가 먼저면 연결이 거부된다.
                        }
                        return null;
                    });
                    go.countDown();
                    server.stop();
                    client.get(5, TimeUnit.SECONDS);
                    assertPortReleased(server.port());
                }
            }
        } finally {
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            return socket;
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
    }

    private static void send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static byte[] request(int port, String request) throws IOException {
        try (Socket socket = connect(port)) {
            send(socket, request);
            return socket.getInputStream().readAllBytes();
        }
    }

    private static void assertResponse(byte[] response) {
        String message = new String(response, StandardCharsets.UTF_8);
        int boundary = message.indexOf("\r\n\r\n");
        assertTrue(boundary >= 0, message);
        String headers = message.substring(0, boundary);
        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"), headers);
        assertTrue(headers.contains("Content-Type: text/plain; charset=UTF-8"), headers);
        assertTrue(Arrays.asList(headers.split("\r\n")).contains(
                "Content-Length: " + BODY.getBytes(StandardCharsets.UTF_8).length), headers);
        assertTrue(headers.contains("Connection: close"), headers);
        assertEquals(BODY, message.substring(boundary + 4));
    }

    private static void assertPortReleased(int port) throws IOException {
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress(port));
            assertEquals(port, rebound.getLocalPort());
        }
    }

    private static final class RunningServer implements AutoCloseable {
        final Endpoint endpoint = new Endpoint(0);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final Future<?> task;

        RunningServer() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            task = worker.submit(() -> {
                thread.set(Thread.currentThread());
                started.countDown();
                endpoint.serve();
                return null;
            });
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                awaitBlockedIn("serve");
            } catch (Throwable failure) {
                close();
                throw failure;
            }
        }

        int port() {
            return endpoint.getPort();
        }

        void awaitBlockedIn(String method) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                if (task.isDone()) {
                    task.get(1, TimeUnit.SECONDS);
                    fail("서버가 대기 전에 종료됨");
                }
                StackTraceElement[] stack = thread.get().getStackTrace();
                boolean inEndpoint = Arrays.stream(stack).anyMatch(frame ->
                        frame.getClassName().equals(Endpoint.class.getName()) && frame.getMethodName().equals(method));
                boolean inSocket = Arrays.stream(stack).anyMatch(frame ->
                        frame.getClassName().startsWith("sun.nio.ch.")
                                && (frame.getMethodName().contains("accept")
                                || frame.getMethodName().contains("read")
                                || frame.getMethodName().contains("Read")));
                if (inEndpoint && inSocket) {
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            fail("소켓 대기 상태에 도달하지 못함: " + method);
        }

        void stop() throws Exception {
            endpoint.close();
            task.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                stop();
            } finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
