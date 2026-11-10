import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import simpletomcat.catalina.connector.CoyoteAdapter;
import simpletomcat.catalina.core.StandardWrapper;
import simpletomcat.coyote.http11.Http11Protocol;
import simpletomcat.servlet.*;

/** 테스트 러너 밖에서 생성된 JAR의 공개 API와 실제 TCP를 확인하는 재실행 가능한 실험. */
public class ServletHttpExperiment {
    public static void main(String[] args) throws Exception {
        AtomicInteger init = new AtomicInteger(), calls = new AtomicInteger(), destroy = new AtomicInteger();
        StandardWrapper wrapper = new StandardWrapper(() -> new Servlet() {
            public void init() { init.incrementAndGet(); }
            public void service(ServletRequest req, ServletResponse res) throws ServletException {
                if (req.getRequestURI().equals("/fail")) { res.setBody("partial"); throw new ServletException("failure"); }
                calls.incrementAndGet(); res.setBody("안녕하세요, Servlet!\n");
            }
            public void destroy() { destroy.incrementAndGet(); }
        });
        Http11Protocol protocol = new Http11Protocol(0, new CoyoteAdapter(wrapper));
        int port = protocol.getPort();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        Future<?> serving = worker.submit(() -> { entered.countDown(); protocol.serve(); return null; });
        try {
            check(entered.await(3, TimeUnit.SECONDS), "start timeout");
            exchange(port, "GET", "/hello", 200, "안녕하세요, Servlet!\n");
            exchange(port, "GET", "/hello", 200, "안녕하세요, Servlet!\n");
            check(init.get() == 1 && calls.get() == 2 && destroy.get() == 0, "lifecycle before shutdown");
            System.out.println("Two GET requests: init/service/destroy=1/2/0");
            exchange(port, "GET", "/fail", 500, "500 Internal Server Error\n");
            exchange(port, "HEAD", "/hello", 200, "");
            exchange(port, "GET", "/hello", 200, "안녕하세요, Servlet!\n");
        } finally {
            try { protocol.close(); serving.get(5, TimeUnit.SECONDS); }
            finally {
                worker.shutdownNow(); check(worker.awaitTermination(5, TimeUnit.SECONDS), "worker stop");
                wrapper.unload(); wrapper.unload();
            }
        }
        check(init.get() == 1 && calls.get() == 4 && destroy.get() == 1, "final lifecycle");
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true); rebound.bind(new InetSocketAddress(port));
        }
        System.out.println("PASS: error recovery, HEAD, lifecycle=1/4/1, repeated unload, port rebind");
    }

    private static void exchange(int port, String method, String path, int status, String body) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000); socket.setSoTimeout(3000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(wire.startsWith("HTTP/1.1 " + status + " "), "status " + wire);
            check(wire.substring(wire.indexOf("\r\n\r\n") + 4).equals(body), "body " + wire);
            check(wire.contains("Connection: close\r\n"), "close header");
            System.out.println(method + " " + path + " -> " + status + ", body bytes=" + body.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
