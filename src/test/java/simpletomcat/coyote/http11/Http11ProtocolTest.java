package simpletomcat.coyote.http11;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Http11ProtocolTest {
    // 400·505·501·앱 500 뒤 새 연결은 정상 200이며 응답마다 EOF와 포트 반환을 확인해야 한다.
    @Test
    void recoversAcrossRealConnections() throws Exception {
        Http11Protocol protocol = new Http11Protocol(0, (req, res) -> {
            if (req.getRequestURI().equals("/fail")) throw new IllegalStateException("failure");
            res.setBody(req.getMethod() + " " + req.getRequestURI());
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        Future<?> task = worker.submit(() -> { started.countDown(); protocol.serve(); return null; });
        int port = protocol.getPort();
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            String[][] cases = {
                {"GET / HTTP/1.1\r\n\r\n", "400 Bad Request"},
                {"GET / HTTP/2.0\r\nHost: a\r\n\r\n", "505 HTTP Version Not Supported"},
                {"POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\n\r\n", "501 Not Implemented"},
                {"POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: gzip" + ";a=b".repeat(1000) + "\r\n\r\n", "501 Not Implemented"},
                {"GET /fail HTTP/1.1\r\nHost: a\r\n\r\n", "500 Internal Server Error"}
            };
            for (String[] sample : cases) {
                assertTrue(request(port, sample[0]).contains(sample[1]));
                assertTrue(request(port, "GET /ok HTTP/1.1\r\nHost: a\r\n\r\n").endsWith("GET /ok"));
            }
            assertTrue(request(port, "GET / HTTP/1.0\r\n\r\n").startsWith("HTTP/1.0 200 OK"));
        } finally {
            try { protocol.close(); task.get(5, TimeUnit.SECONDS); }
            finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
        }
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress(port));
        }
    }

    private static String request(int port, String wire) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
