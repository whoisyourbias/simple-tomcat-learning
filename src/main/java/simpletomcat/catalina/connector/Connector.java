package simpletomcat.catalina.connector;

import simpletomcat.catalina.*;
import simpletomcat.catalina.core.*;
import simpletomcat.coyote.*;
import simpletomcat.coyote.http11.ConcurrentHttp11Protocol;
import simpletomcat.util.Deadline;
import simpletomcat.util.net.EndpointOptions;

import java.io.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

/** Protocol과 accept 실행 스레드를 소유하며 앱 객체는 해제하지 않는다. */
public final class Connector extends LifecycleControl {
    @FunctionalInterface
    interface ProtocolFactory {
        ManagedProtocolHandler open(int port, Adapter adapter, EndpointOptions options)
                throws IOException;
    }

    private final int requestedPort;
    private final EndpointOptions options;
    private final ProtocolFactory factory;
    private final ThreadFactory threads;
    private StandardService owner;
    private ManagedProtocolHandler protocol;
    private volatile Thread accepting;
    private volatile int port = -1;
    private volatile boolean quiescing;

    public Connector(int port, EndpointOptions options) {
        this(port, options, ConcurrentHttp11Protocol::new, r -> new Thread(r, "tomcat-accept"));
    }

    Connector(int port, EndpointOptions options, ProtocolFactory factory, ThreadFactory threads) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        requestedPort = port;
        this.options = Objects.requireNonNull(options);
        this.factory = Objects.requireNonNull(factory);
        this.threads = Objects.requireNonNull(threads);
    }

    public synchronized void attach(StandardService service) {
        if (owner != null || state != LifecycleState.NEW)
            throw new IllegalStateException("Connector already owned");
        owner = Objects.requireNonNull(service);
    }

    public int getPort() {
        return port;
    }

    public void bind(Adapter adapter) throws LifecycleException {
        begin();
        try {
            if (owner == null || state != LifecycleState.NEW)
                throw new IllegalStateException("Cannot bind");
            state = LifecycleState.STARTING;
            try {
                protocol = factory.open(requestedPort, Objects.requireNonNull(adapter), options);
                port = protocol.getPort();
            } catch (IOException | RuntimeException e) {
                failed(e);
                throw exception("Connector binding failed", e);
            }
        } finally {
            end();
        }
    }

    public void start() throws LifecycleException {
        begin();
        try {
            if (state == LifecycleState.STARTED) return;
            if (state != LifecycleState.STARTING || protocol == null || owner == null)
                throw new IllegalStateException("Bind before start");
            try {
                accepting =
                        Objects.requireNonNull(
                                threads.newThread(
                                        () -> {
                                            try {
                                                protocol.serve();
                                                if (!quiescing)
                                                    throw new IOException(
                                                            "Accept loop ended unexpectedly");
                                            } catch (IOException | RuntimeException cause) {
                                                if (!quiescing) {
                                                    failed(cause);
                                                    owner.connectorFailed(this, cause);
                                                }
                                            }
                                        }),
                                "accept thread");
                accepting.start();
                started();
            } catch (RuntimeException e) {
                failed(e);
                try {
                    abort();
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw exception("Connector thread start failed", e);
            }
        } finally {
            end();
        }
    }

    public void quiesce() throws IOException {
        quiescing = true;
        if (protocol != null) protocol.stopAccepting();
    }

    public void abort() throws IOException {
        quiescing = true;
        if (protocol != null) protocol.close();
    }

    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        var d = Deadline.after(timeout);
        Thread thread = accepting;
        if (thread != null && thread.isAlive()) {
            long n = d.remainingNanos();
            if (n > 0) TimeUnit.NANOSECONDS.timedJoin(thread, n);
            if (thread.isAlive()) return false;
        }
        return protocol == null || protocol.awaitTermination(d.remaining());
    }

    public void stop(Duration timeout) throws LifecycleException {
        var d = Deadline.after(timeout);
        begin();
        try {
            if (state == LifecycleState.STOPPED) return;
            state = LifecycleState.STOPPING;
            try {
                quiesce();
                if (!awaitStopped(d.remaining())) {
                    abort();
                    if (!awaitStopped(Duration.ZERO))
                        throw new TimeoutException("Connector workers remain");
                }
                state = LifecycleState.STOPPED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try {
                    abort();
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                failed(e);
                throw exception("Connector stop interrupted", e);
            } catch (IOException | TimeoutException e) {
                failed(e);
                throw exception("Connector stop failed", e);
            }
        } finally {
            end();
        }
    }
}
