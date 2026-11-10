import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import simpletomcat.catalina.connector.CoyoteAdapter;
import simpletomcat.catalina.core.StandardEngine;
import simpletomcat.catalina.mapper.Mapper;
import simpletomcat.coyote.http11.Http11Protocol;
import simpletomcat.examples.ContainerServer;

/** 생성 JAR의 예제 구성에 실제 curl 요청을 보내고 종료 후 포트를 재사용한다. */
public class ContainerHttpExperiment {
    public static void main(String[] args) throws Exception {
        StandardEngine engine = ContainerServer.createEngine();
        Http11Protocol protocol = new Http11Protocol(0, new CoyoteAdapter(engine, new Mapper(engine, "localhost")));
        int port = protocol.getPort();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        Future<?> serving = worker.submit(() -> { entered.countDown(); protocol.serve(); return null; });
        try {
            check(entered.await(3, TimeUnit.SECONDS), "start timeout");
            String[][] cases = {
                {"localhost", "/shop/status", "200", "shop-status contextPath=/shop servletPath=/status pathInfo=null\n"},
                {"localhost", "/admin/status", "200", "admin-status contextPath=/admin servletPath=/status pathInfo=null\n"},
                {"other.local", "/shop/status", "200", "other-status contextPath=/shop servletPath=/status pathInfo=null\n"},
                {"LOCALHOST:8080", "/shop/deep/status", "200", "nested-status contextPath=/shop/deep servletPath=/status pathInfo=null\n"},
                {"localhost", "/shop/api/exact.do", "200", "exact contextPath=/shop servletPath=/api/exact.do pathInfo=null\n"},
                {"localhost", "/shop/api/deep/a.do", "200", "deep-api contextPath=/shop servletPath=/api/deep pathInfo=/a.do\n"},
                {"localhost", "/shop/api/a.do", "200", "api contextPath=/shop servletPath=/api pathInfo=/a.do\n"},
                {"localhost", "/shop/a.do", "200", "extension contextPath=/shop servletPath=/a.do pathInfo=null\n"},
                {"unknown", "/shop/other", "200", "default contextPath=/shop servletPath=/other pathInfo=null\n"},
                {"localhost", "/shopper/status", "404", ""},
                {"localhost", "/admin/missing", "404", ""},
                {"localhost", "/shop/WEB-INF/config", "404", ""},
                {"localhost", "/shop/%73tatus", "400", "400 Bad Request\n"},
                {"localhost", "/shop/../admin/status", "400", "400 Bad Request\n"},
                {"localhost", "/shop/fail", "500", "500 Internal Server Error\n"},
                {"localhost", "/shop/status", "200", "shop-status contextPath=/shop servletPath=/status pathInfo=null\n"}
            };
            for (String[] sample : cases) {
                String curl = System.getenv("SystemRoot") == null ? "curl"
                        : Path.of(System.getenv("SystemRoot"), "System32", "curl.exe").toString();
                Process process = new ProcessBuilder(curl, "--silent", "--show-error", "--include", "--path-as-is",
                        "--noproxy", "*", "--max-time", "3", "-H", "Host: " + sample[0],
                        "http://127.0.0.1:" + port + sample[1]).redirectErrorStream(true).start();
                try {
                    check(process.waitFor(5, TimeUnit.SECONDS), "curl timeout");
                    String wire = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    check(process.exitValue() == 0, "curl failed: " + wire);
                    check(wire.startsWith("HTTP/1.1 " + sample[2] + " "), "status " + wire);
                    check(wire.substring(wire.indexOf("\r\n\r\n") + 4).equals(sample[3]), "body " + wire);
                    System.out.println(sample[0] + " " + sample[1] + " -> " + sample[2]);
                } finally { if (process.isAlive()) process.destroyForcibly(); }
            }
        } finally {
            try { protocol.close(); serving.get(5, TimeUnit.SECONDS); }
            finally {
                worker.shutdownNow(); check(worker.awaitTermination(5, TimeUnit.SECONDS), "worker stop");
                ContainerServer.unloadServlets(engine); ContainerServer.unloadServlets(engine);
            }
        }
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true); rebound.bind(new InetSocketAddress(port));
        }
        System.out.println("PASS: 16 curl cases, repeated unload, port rebind");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
