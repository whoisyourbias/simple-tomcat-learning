package simpletomcat.support;

import simpletomcat.catalina.connector.Connector;
import simpletomcat.catalina.core.*;
import simpletomcat.util.net.EndpointOptions;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** 시작 실패도 정리하는 포트0 서버와 유한 소켓 클라이언트. */
public final class LifecycleTestServer implements AutoCloseable {
    public final StandardServer server = new StandardServer();
    public final Connector connector;

    public LifecycleTestServer(StandardEngine engine, EndpointOptions options) throws Exception {
        var service = new StandardService("test", engine, "localhost");
        connector = new Connector(0, options);
        service.addConnector(connector);
        server.addService(service);
        try {
            server.start();
        } catch (Exception failure) {
            try {
                server.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public int port() {
        return connector.getPort();
    }

    public String request(String method, String path, String host) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port()), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream()
                    .write(
                            (method
                                            + " "
                                            + path
                                            + " HTTP/1.1\r\nHost: "
                                            + host
                                            + "\r\nX-Test: "
                                            + path
                                            + "\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void close() throws Exception {
        server.close();
    }
}
