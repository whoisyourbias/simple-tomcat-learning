package simpletomcat.util.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A sequential, one-request-per-connection learning server. */
public final class Endpoint implements AutoCloseable {
    private final ServerSocket listener;
    private final Object lifecycle = new Object();
    private final int port;
    private volatile boolean closed;
    // Both fields below are guarded by lifecycle. Never hold it during accept/read/write.
    private boolean serveCalled;
    private Socket current;

    public Endpoint(int port) throws IOException {
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
            this.port = listener.getLocalPort();
        } catch (IOException | RuntimeException failure) {
            try {
                listener.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public int getPort() {
        return port;
    }

    /** Runs on the caller's thread until close; may be called only once. */
    public void serve() throws IOException {
        synchronized (lifecycle) {
            if (serveCalled) {
                throw new IllegalStateException("serve may be called only once");
            }
            serveCalled = true;
        }
        try {
            while (!closed) {
                Socket accepted;
                try {
                    accepted = listener.accept();
                } catch (IOException failure) {
                    if (closed) {
                        return;
                    }
                    throw failure;
                }
                try (Socket socket = accepted) {
                    synchronized (lifecycle) {
                        // close may have run after accept and before registration.
                        if (closed) {
                            continue;
                        }
                        current = socket;
                    }
                    if (readHeaders(socket.getInputStream())) {
                        respond(socket);
                    }
                } catch (IOException connectionFailure) {
                    // EOF/reset/write failure belongs to this connection, not the listener.
                    System.getLogger(Endpoint.class.getName()).log(
                            System.Logger.Level.DEBUG, "Connection closed during I/O", connectionFailure);
                } finally {
                    synchronized (lifecycle) {
                        current = null;
                    }
                }
            }
        } finally {
            close();
        }
    }

    private boolean readHeaders(InputStream input) throws IOException {
        // Shift across read boundaries without retaining an unbounded header buffer.
        int tail = 0;
        int value;
        while ((value = input.read()) != -1) {
            tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) {
                return true;
            }
        }
        return false;
    }

    private void respond(Socket socket) throws IOException {
        byte[] body = "안녕하세요, simple-tomcat!\n".getBytes(StandardCharsets.UTF_8);
        byte[] headers = ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        socket.getOutputStream().write(headers);
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    /** Cancels accept and current connection I/O; the serve caller owns thread joining. */
    @Override
    public void close() throws IOException {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                listener.close();
            } finally {
                if (current != null) {
                    current.close();
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 18081 : Integer.parseInt(args[0]);
        try (Endpoint endpoint = new Endpoint(port)) {
            Thread shutdown = new Thread(() -> {
                try {
                    endpoint.close();
                } catch (IOException failure) {
                    System.err.println("Endpoint shutdown failed: " + failure.getMessage());
                }
            }, "endpoint-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                System.out.println("Listening on port " + endpoint.getPort());
                endpoint.serve();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException shuttingDown) {
                    // The hook is already running during JVM shutdown.
                }
            }
        }
    }
}
