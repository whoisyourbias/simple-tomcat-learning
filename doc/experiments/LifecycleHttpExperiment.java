import simpletomcat.catalina.*;
import simpletomcat.catalina.connector.Connector;
import simpletomcat.catalina.core.*;
import simpletomcat.coyote.http11.ConcurrentHttp11Protocol;
import simpletomcat.examples.LifecycleServer;
import simpletomcat.servlet.*;
import simpletomcat.util.net.EndpointOptions;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 생성 JAR만 클래스패스에 두고 동시 요청·거부·기한초과·해제 재시도를 검증한다. */
public final class LifecycleHttpExperiment {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static String request(int port, String path) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream()
                    .write(
                            ("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String curl(int port, String method, String path) throws Exception {
        Process process =
                new ProcessBuilder(
                                "curl.exe",
                                "--max-time",
                                "3",
                                "-sS",
                                "-i",
                                "-X",
                                method,
                                "http://127.0.0.1:" + port + path)
                        .start();
        try {
            check(process.waitFor(5, TimeUnit.SECONDS), "curl timeout");
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(process.exitValue() == 0, "curl failure");
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                check(process.waitFor(5, TimeUnit.SECONDS), "curl still alive");
            }
        }
    }

    static void rebound(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
        }
    }

    static void demo() throws Exception {
        StandardServer server = new StandardServer();
        StandardService service =
                new StandardService("demo", LifecycleServer.createEngine(x -> {}), "localhost");
        Connector connector = new Connector(0, EndpointOptions.defaults());
        service.addConnector(connector);
        server.addService(service);
        try {
            server.start();
            int port = connector.getPort();
            check(curl(port, "GET", "/shop/hello").endsWith("shop hello\n"), "shop");
            check(curl(port, "GET", "/admin/hello").endsWith("admin hello\n"), "admin");
            check(curl(port, "GET", "/shop/blocked").endsWith("blocked\n"), "blocked");
            String failure = curl(port, "GET", "/shop/filter-fail");
            check(
                    failure.startsWith("HTTP/1.1 500") && !failure.contains("partial"),
                    "filter failure");
            check(
                    curl(port, "GET", "/shop/servlet-fail").startsWith("HTTP/1.1 500"),
                    "servlet failure");
            check(curl(port, "GET", "/shop/hello").startsWith("HTTP/1.1 200"), "recovery");
            check(curl(port, "GET", "/missing").startsWith("HTTP/1.1 404"), "404");
            // HEAD는 -I를 사용해 curl이 본문 길이만큼 기다리지 않도록 한다.
            Process head =
                    new ProcessBuilder(
                                    "curl.exe",
                                    "--max-time",
                                    "3",
                                    "-sS",
                                    "-I",
                                    "http://127.0.0.1:" + port + "/shop/hello")
                            .start();
            try {
                check(head.waitFor(5, TimeUnit.SECONDS), "HEAD timeout");
                check(head.exitValue() == 0, "HEAD failure");
                String wire =
                        new String(head.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                check(wire.endsWith("\r\n\r\n"), "HEAD body");
            } finally {
                if (head.isAlive()) {
                    head.destroyForcibly();
                    head.waitFor(5, TimeUnit.SECONDS);
                }
            }
            server.stop(Duration.ofSeconds(3));
            server.close();
            rebound(port);
            System.out.println(
                    "PASS Lifecycle curl8, errors/recovery/HEAD, repeated stop, port return");
        } finally {
            server.close();
        }
    }

    static void deferredCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger init = new AtomicInteger(),
                destroyed = new AtomicInteger(),
                filterDestroyed = new AtomicInteger();
        StandardEngine engine = new StandardEngine("e");
        StandardHost host = new StandardHost("localhost");
        StandardContext context = new StandardContext("");
        engine.addChild(host);
        host.addChild(context);
        StandardWrapper wrapper =
                new StandardWrapper(
                        () ->
                                new Servlet() {
                                    public void init() {
                                        init.incrementAndGet();
                                    }

                                    public void destroy() {
                                        destroyed.incrementAndGet();
                                    }

                                    public void service(ServletRequest q, ServletResponse s)
                                            throws ServletException {
                                        if (q.getRequestURI().equals("/slow")) {
                                            entered.countDown();
                                            boolean interrupted = false;
                                            try {
                                                long end =
                                                        System.nanoTime()
                                                                + TimeUnit.SECONDS.toNanos(5);
                                                while (release.getCount() > 0)
                                                    try {
                                                        if (!release.await(
                                                                Math.max(
                                                                        1, end - System.nanoTime()),
                                                                TimeUnit.NANOSECONDS))
                                                            throw new ServletException(
                                                                    "release timeout");
                                                    } catch (InterruptedException e) {
                                                        interrupted = true;
                                                    }
                                            } finally {
                                                if (interrupted) Thread.currentThread().interrupt();
                                            }
                                        }
                                        s.setBody(q.getRequestURI() + "?" + q.getQueryString());
                                    }
                                });
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

                            public void doFilter(ServletRequest q, ServletResponse s, FilterChain n)
                                    throws IOException, ServletException {
                                n.doFilter(q, s);
                            }
                        });
        StandardServer server = new StandardServer();
        StandardService service = new StandardService("s", engine, "localhost");
        Connector connector = new Connector(0, new EndpointOptions(2, 1, 500));
        service.addConnector(connector);
        server.addService(service);
        ExecutorService clients = Executors.newSingleThreadExecutor();
        try {
            server.start();
            int port = connector.getPort();
            Future<String> slow = clients.submit(() -> request(port, "/slow?x=1"));
            check(entered.await(3, TimeUnit.SECONDS), "slow entry");
            check(request(port, "/fast?x=2").endsWith("/fast?x=2"), "concurrent fast request");
            check(init.get() == 1, "one shared servlet");
            try {
                server.stop(Duration.ofMillis(150));
                throw new AssertionError("stop should fail");
            } catch (LifecycleException expected) {
                check(server.getState() == LifecycleState.FAILED, "FAILED");
            }
            check(destroyed.get() == 0 && filterDestroyed.get() == 0, "cleanup deferred");
            release.countDown();
            try {
                slow.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                check(expected.getCause() instanceof IOException, "cancelled client I/O");
            }
            server.stop(Duration.ofSeconds(3));
            server.close();
            check(destroyed.get() == 1 && filterDestroyed.get() == 1, "cleanup once");
            rebound(port);
            System.out.println(
                    "PASS concurrent slow/fast isolation, init1, timeout destroy0, retry destroy1,"
                            + " port return");
        } finally {
            release.countDown();
            try {
                server.close();
            } finally {
                clients.shutdownNow();
                check(clients.awaitTermination(5, TimeUnit.SECONDS), "clients remain");
            }
        }
    }

    static void endpointLimits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try (ConcurrentHttp11Protocol protocol =
                new ConcurrentHttp11Protocol(
                        0,
                        (q, s) -> {
                            if (calls.incrementAndGet() == 1) {
                                entered.countDown();
                                check(release.await(5, TimeUnit.SECONDS), "release");
                            }
                            s.setBody(q.getRequestURI());
                        },
                        new EndpointOptions(1, 1, 150))) {
            Future<?> serving =
                    workers.submit(
                            () -> {
                                protocol.serve();
                                return null;
                            });
            int port = protocol.getPort();
            Future<String> first = workers.submit(() -> request(port, "/first"));
            check(entered.await(3, TimeUnit.SECONDS), "first");
            Future<String> queued = workers.submit(() -> request(port, "/queued"));
            check(
                    protocol.awaitSnapshot(s -> s.queuedTasks() == 1, Duration.ofSeconds(3)),
                    "queue");
            try (Socket rejected = new Socket()) {
                rejected.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                rejected.setSoTimeout(3000);
                check(
                        protocol.awaitSnapshot(
                                s -> s.rejectedConnections() == 1, Duration.ofSeconds(3)),
                        "rejection");
                try {
                    check(rejected.getInputStream().read() == -1, "rejected EOF");
                } catch (SocketException reset) {
                }
            }
            release.countDown();
            check(first.get(5, TimeUnit.SECONDS).endsWith("/first"), "first response");
            check(queued.get(5, TimeUnit.SECONDS).endsWith("/queued"), "queued response");
            try (Socket partial = new Socket()) {
                partial.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                partial.setSoTimeout(3000);
                partial.getOutputStream()
                        .write("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
                check(partial.getInputStream().read() == -1, "read timeout close");
            }
            check(request(port, "/recovered").endsWith("/recovered"), "read recovery");
            protocol.stopAccepting();
            serving.get(5, TimeUnit.SECONDS);
            check(protocol.awaitTermination(Duration.ofSeconds(3)), "protocol stop");
            rebound(port);
            System.out.println(
                    "PASS queue saturation/rejection, queued recovery, read timeout/recovery, port"
                            + " return");
        } finally {
            release.countDown();
            workers.shutdownNow();
            check(workers.awaitTermination(5, TimeUnit.SECONDS), "workers remain");
        }
    }

    public static void main(String[] args) throws Exception {
        demo();
        deferredCleanup();
        endpointLimits();
    }
}
