package simpletomcat.catalina.core;

import simpletomcat.catalina.*;
import simpletomcat.catalina.connector.*;
import simpletomcat.catalina.mapper.Mapper;
import simpletomcat.util.Deadline;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

/** 동결된 Engine과 모든 Connector의 작업 종료 및 앱 해제를 조정한다. */
public final class StandardService extends LifecycleControl {
    private final String name;
    private final StandardEngine engine;
    private final String defaultHost;
    private final ApplicationLifecycle applications;
    private final List<Connector> connectors = new ArrayList<>();
    private StandardServer server;

    public StandardService(String name, StandardEngine engine, String defaultHost) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Service name required");
        this.name = name;
        this.engine = Objects.requireNonNull(engine);
        this.defaultHost = defaultHost;
        engine.attachService(this);
        applications = new ApplicationLifecycle(engine);
    }

    public String getName() {
        return name;
    }

    public StandardEngine getEngine() {
        return engine;
    }

    synchronized void attach(StandardServer server) {
        if (this.server != null || state != LifecycleState.NEW)
            throw new IllegalStateException("Service owned");
        this.server = server;
    }

    public synchronized void addConnector(Connector connector) {
        if (state != LifecycleState.NEW) throw new IllegalStateException("Registration closed");
        Objects.requireNonNull(connector);
        if (connectors.contains(connector))
            throw new IllegalArgumentException("Duplicate connector");
        connector.attach(this);
        connectors.add(connector);
    }

    public synchronized Connector[] findConnectors() {
        return connectors.toArray(Connector[]::new);
    }

    public void connectorFailed(Connector connector, Throwable cause) {
        failed(cause);
        try {
            quiesce();
        } catch (LifecycleException e) {
            cause.addSuppressed(e);
        }
        if (server != null) server.serviceFailed(cause);
    }

    public void start() throws LifecycleException {
        begin();
        try {
            if (state == LifecycleState.STARTED) return;
            if (state != LifecycleState.NEW)
                throw new IllegalStateException("Cannot restart service");
            state = LifecycleState.STARTING;
            try {
                if (connectors.isEmpty()) throw new IllegalStateException("Connector required");
                var mapper = new Mapper(engine, defaultHost);
                engine.freeze();
                applications.prepare();
                var adapter = new CoyoteAdapter(engine, mapper);
                for (var c : findConnectors()) c.bind(adapter);
                for (var c : findConnectors()) c.start();
                started();
            } catch (Exception cause) {
                failed(cause);
                try {
                    stopOwned(Deadline.after(Duration.ofSeconds(5)));
                } catch (Exception cleanup) {
                    if (cleanup != cause) cause.addSuppressed(cleanup);
                }
                state = LifecycleState.FAILED;
                throw exception("Service start failed", cause);
            }
        } finally {
            end();
        }
    }

    void quiesce() throws LifecycleException {
        Throwable first = null;
        for (var c : findConnectors())
            try {
                c.quiesce();
            } catch (IOException e) {
                first = append(first, e);
            }
        if (first != null) throw exception("Cannot quiesce connectors", first);
    }

    private void stopOwned(Deadline deadline) throws LifecycleException {
        Throwable first = null;
        try {
            quiesce();
        } catch (LifecycleException e) {
            first = e;
        }
        for (var c : findConnectors())
            try {
                c.stop(deadline.remaining());
            } catch (LifecycleException e) {
                first = append(first, e);
            }
        boolean complete = true;
        for (var c : findConnectors())
            try {
                if (!c.awaitStopped(Duration.ZERO)) complete = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                first = append(first, e);
                complete = false;
            }
        if (!complete)
            throw exception(
                    "Service work remains; application cleanup deferred",
                    append(first, new TimeoutException()));
        try {
            applications.cleanup(deadline.remaining());
        } catch (LifecycleException e) {
            first = append(first, e);
        }
        if (first != null) throw exception("Service cleanup failed", first);
    }

    public void stop(Duration timeout) throws LifecycleException {
        var d = Deadline.after(timeout);
        begin();
        try {
            if (state == LifecycleState.STOPPED) return;
            state = LifecycleState.STOPPING;
            try {
                stopOwned(d);
                state = LifecycleState.STOPPED;
            } catch (LifecycleException e) {
                failed(e);
                throw e;
            }
        } finally {
            end();
        }
    }
}
