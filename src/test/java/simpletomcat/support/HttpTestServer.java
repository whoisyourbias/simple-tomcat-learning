package simpletomcat.support;

import simpletomcat.coyote.Adapter;
import simpletomcat.coyote.http11.Http11Protocol;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** 임의 포트·유한 대기·명시적 종료를 공유하는 TCP 테스트 도구. */
public final class HttpTestServer implements AutoCloseable {
    private final Http11Protocol protocol;
    private final ExecutorService worker;
    private final Future<?> serving;

    public HttpTestServer(Adapter adapter) throws Exception {
        this(adapter, port -> {});
    }

    HttpTestServer(Adapter adapter, java.util.function.IntConsumer bound) throws Exception {
        ExecutorService createdWorker = Executors.newSingleThreadExecutor();
        Http11Protocol createdProtocol = null;
        Future<?> createdTask;
        try {
            createdProtocol = new Http11Protocol(0, adapter);
            bound.accept(createdProtocol.getPort());
            Http11Protocol ready = createdProtocol;
            CountDownLatch entered = new CountDownLatch(1);
            createdTask =
                    createdWorker.submit(
                            () -> {
                                entered.countDown();
                                ready.serve();
                                return null;
                            });
            if (!entered.await(3, TimeUnit.SECONDS))
                throw new IllegalStateException("Server did not start");
        } catch (Throwable failure) {
            if (createdProtocol != null)
                try {
                    createdProtocol.close();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            createdWorker.shutdownNow();
            try {
                if (!createdWorker.awaitTermination(5, TimeUnit.SECONDS))
                    failure.addSuppressed(new IllegalStateException("Worker still running"));
            } catch (InterruptedException cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
                Thread.currentThread().interrupt();
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure instanceof Exception exception) throw exception;
            throw (Error) failure;
        }
        protocol = createdProtocol;
        worker = createdWorker;
        serving = createdTask;
    }

    public int port() {
        return protocol.getPort();
    }

    public String get(String path) throws IOException {
        return request("GET", path, "localhost");
    }

    public String request(String method, String path, String host) throws IOException {
        return wire(
                method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nX-Test: value\r\n\r\n");
    }

    public String wire(String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port()), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            protocol.close();
            serving.get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            if (!worker.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Worker still running");
        }
    }
}
