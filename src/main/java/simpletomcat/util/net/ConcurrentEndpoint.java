package simpletomcat.util.net;

import simpletomcat.util.Deadline;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** 수락·작업·소켓 소유권을 분리한 제한 동시 실행 Endpoint. */
public final class ConcurrentEndpoint implements AutoCloseable {
    private final Object lock = new Object();
    private final ServerSocket listener;
    private final Endpoint.Handler handler;
    private final EndpointOptions options;
    private final ThreadPoolExecutor pool;
    private final Set<ConnectionTask> owned = new HashSet<>();
    private final int port;
    private final java.util.function.Consumer<Socket> afterAccept;
    private boolean accepting = true, aborted, serveCalled, serveFinished;
    private long rejected;

    public record Snapshot(
            boolean accepting,
            int ownedConnections,
            int runningTasks,
            int queuedTasks,
            long rejectedConnections) {}

    public ConcurrentEndpoint(int port, EndpointOptions options, Endpoint.Handler handler)
            throws IOException {
        this(port, options, handler, socket -> {});
    }

    ConcurrentEndpoint(
            int port,
            EndpointOptions options,
            Endpoint.Handler handler,
            java.util.function.Consumer<Socket> afterAccept)
            throws IOException {
        this.afterAccept = Objects.requireNonNull(afterAccept);
        this.options = Objects.requireNonNull(options);
        this.handler = Objects.requireNonNull(handler);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
            this.port = listener.getLocalPort();
        } catch (IOException | RuntimeException e) {
            try {
                listener.close();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        pool =
                new ThreadPoolExecutor(
                        options.workerCount(),
                        options.workerCount(),
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(options.queueCapacity()),
                        new ThreadPoolExecutor.AbortPolicy());
    }

    public int getPort() {
        return port;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            int running = 0;
            for (var t : owned) if (t.running) running++;
            return new Snapshot(accepting, owned.size(), running, owned.size() - running, rejected);
        }
    }

    public boolean awaitSnapshot(Predicate<Snapshot> expected, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(expected);
        var d = Deadline.after(timeout);
        synchronized (lock) {
            while (!expected.test(snapshot())) {
                long n = d.remainingNanos();
                if (n == 0) return false;
                TimeUnit.NANOSECONDS.timedWait(lock, n);
            }
            return true;
        }
    }

    public void serve() throws IOException {
        synchronized (lock) {
            if (serveCalled) throw new IllegalStateException("serve once");
            serveCalled = true;
        }
        try {
            while (true) {
                synchronized (lock) {
                    if (!accepting) return;
                }
                Socket socket;
                try {
                    socket = listener.accept();
                } catch (IOException e) {
                    synchronized (lock) {
                        if (!accepting) return;
                    }
                    throw e;
                }
                try {
                    afterAccept.accept(socket);
                    socket.setSoTimeout(options.readTimeoutMillis());
                    synchronized (lock) {
                        if (!accepting) {
                            socket.close();
                            continue;
                        }
                        var task = new ConnectionTask(socket);
                        owned.add(task);
                        try {
                            pool.execute(task);
                        } catch (RejectedExecutionException e) {
                            rejected++;
                            task.finish();
                        } catch (RuntimeException | Error e) {
                            task.finish();
                            throw e;
                        }
                        lock.notifyAll();
                    }
                } catch (IOException | RuntimeException e) {
                    try {
                        socket.close();
                    } catch (IOException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                }
            }
        } finally {
            synchronized (lock) {
                serveFinished = true;
                lock.notifyAll();
            }
            stopAccepting();
        }
    }

    private final class ConnectionTask implements Runnable {
        private final Socket socket;
        private boolean running, finished;

        ConnectionTask(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            synchronized (lock) {
                if (finished) return;
                if (aborted) {
                    finish();
                    return;
                }
                running = true;
                lock.notifyAll();
            }
            try {
                handler.process(socket.getInputStream(), socket.getOutputStream());
            } catch (IOException | RuntimeException failure) {
                System.getLogger(getClass().getName())
                        .log(System.Logger.Level.DEBUG, "Connection failure", failure);
            } finally {
                finish();
            }
        }

        void finish() {
            synchronized (lock) {
                if (finished) return;
                finished = true;
                try {
                    socket.close();
                } catch (IOException e) {
                    System.getLogger(getClass().getName())
                            .log(System.Logger.Level.DEBUG, "Socket close failure", e);
                }
                owned.remove(this);
                lock.notifyAll();
            }
        }
    }

    public void stopAccepting() throws IOException {
        synchronized (lock) {
            accepting = false;
            try {
                listener.close();
            } finally {
                pool.shutdown();
                lock.notifyAll();
            }
        }
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        var d = Deadline.after(timeout);
        synchronized (lock) {
            while ((serveCalled && !serveFinished) || !owned.isEmpty()) {
                long n = d.remainingNanos();
                if (n == 0) return false;
                TimeUnit.NANOSECONDS.timedWait(lock, n);
            }
        }
        return pool.awaitTermination(d.remainingNanos(), TimeUnit.NANOSECONDS);
    }

    public void close() throws IOException {
        IOException failure = null;
        synchronized (lock) {
            aborted = true;
            accepting = false;
            try {
                listener.close();
            } catch (IOException e) {
                failure = e;
            }
            for (var task : new ArrayList<>(owned)) {
                try {
                    task.socket.close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            for (Runnable task : pool.shutdownNow()) ((ConnectionTask) task).finish();
            lock.notifyAll();
        }
        if (failure != null) throw failure;
    }
}
