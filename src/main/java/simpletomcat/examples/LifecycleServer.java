package simpletomcat.examples;

import simpletomcat.catalina.LifecycleException;
import simpletomcat.catalina.connector.Connector;
import simpletomcat.catalina.core.*;
import simpletomcat.util.net.EndpointOptions;

import java.time.Duration;
import java.util.function.Consumer;

/** 동시 처리와 기한 있는 종료를 Server 수명으로 조립하는 예제. */
public final class LifecycleServer {
    private LifecycleServer() {}

    public static StandardEngine createEngine(Consumer<String> trace) {
        return FilterServer.createEngine(trace);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 18081 : Integer.parseInt(args[0]);
        var server = new StandardServer();
        var service =
                new StandardService(
                        "lifecycle-demo", createEngine(System.out::println), "localhost");
        var connector = new Connector(port, EndpointOptions.defaults());
        service.addConnector(connector);
        server.addService(service);
        Object stopping = new Object();
        Thread hook =
                new Thread(
                        () -> {
                            synchronized (stopping) {
                                try {
                                    server.stop(Duration.ofSeconds(5));
                                } catch (LifecycleException e) {
                                    System.err.println("Shutdown incomplete: " + e);
                                }
                            }
                        },
                        "lifecycle-shutdown");
        try {
            server.start();
            Runtime.getRuntime().addShutdownHook(hook);
            System.out.println("Listening on port " + connector.getPort());
            server.await();
            if (server.getFailure() != null)
                throw new LifecycleException("Server failure", server.getFailure());
        } finally {
            synchronized (stopping) {
                try {
                    server.close();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (IllegalStateException shuttingDown) {
                        /* JVM 종료 중 hook이 소유한다. */
                    }
                }
            }
        }
    }
}
