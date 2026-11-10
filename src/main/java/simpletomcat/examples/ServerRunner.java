package simpletomcat.examples;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import simpletomcat.coyote.http11.Http11Protocol;

/** 예제의 실행 스레드가 요청 처리를 마친 뒤 앱 자원을 해제하도록 조정한다. */
final class ServerRunner {
    @FunctionalInterface interface Cleanup { void run() throws Exception; }

    private ServerRunner() { }

    static void run(Http11Protocol protocol, Cleanup cleanup) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            try {
                protocol.close();
                if (!finished.await(5, TimeUnit.SECONDS)) System.err.println("Shutdown cleanup timed out");
            } catch (Exception failure) { System.err.println("Shutdown failed: " + failure); }
        }, "example-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try (protocol) {
            System.out.println("Listening on port " + protocol.getPort());
            protocol.serve();
        } finally {
            try { cleanup.run(); }
            finally {
                finished.countDown();
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException shuttingDown) { /* hook이 위 latch를 기다린다. */ }
            }
        }
    }
}
