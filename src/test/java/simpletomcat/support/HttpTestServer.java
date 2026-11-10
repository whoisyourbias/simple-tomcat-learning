package simpletomcat.support;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import simpletomcat.coyote.Adapter;
import simpletomcat.coyote.http11.Http11Protocol;

/** 임의 포트·유한 대기·명시적 종료를 공유하는 TCP 테스트 도구. */
public final class HttpTestServer implements AutoCloseable {
    private final Http11Protocol protocol;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Future<?> serving;

    public HttpTestServer(Adapter adapter) throws Exception {
        protocol = new Http11Protocol(0, adapter);
        CountDownLatch entered = new CountDownLatch(1);
        serving = worker.submit(() -> { entered.countDown(); protocol.serve(); return null; });
        if (!entered.await(3, TimeUnit.SECONDS)) {
            close();
            throw new IllegalStateException("Server did not start");
        }
    }

    public int port() { return protocol.getPort(); }

    public String get(String path) throws IOException { return request("GET", path, "localhost"); }

    public String request(String method, String path, String host) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port()), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: " + host
                    + "\r\nX-Test: value\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override public void close() throws Exception {
        try {
            protocol.close();
            serving.get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Worker still running");
        }
    }
}
