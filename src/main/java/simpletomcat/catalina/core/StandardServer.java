package simpletomcat.catalina.core;

import simpletomcat.catalina.*;
import simpletomcat.util.Deadline;

import java.time.Duration;
import java.util.*;

/** Service들의 전체 수락 중지와 역순 정리를 조정한다. */
public final class StandardServer extends LifecycleControl {
    private final List<StandardService> services = new ArrayList<>();

    public synchronized void addService(StandardService service) {
        if (state != LifecycleState.NEW) throw new IllegalStateException("Registration closed");
        Objects.requireNonNull(service);
        if (services.stream().anyMatch(s -> s == service || s.getName().equals(service.getName())))
            throw new IllegalArgumentException("Duplicate service");
        service.attach(this);
        services.add(service);
    }

    public synchronized StandardService[] findServices() {
        return services.toArray(StandardService[]::new);
    }

    void serviceFailed(Throwable cause) {
        failed(cause);
    }

    public void start() throws LifecycleException {
        begin();
        try {
            if (state == LifecycleState.STARTED) return;
            if (state != LifecycleState.NEW)
                throw new IllegalStateException("Cannot restart server");
            state = LifecycleState.STARTING;
            var attempted = new ArrayList<StandardService>();
            try {
                if (services.isEmpty()) throw new IllegalStateException("Service required");
                for (var s : findServices()) {
                    attempted.add(s);
                    s.start();
                }
                started();
            } catch (Exception cause) {
                failed(cause);
                var d = Deadline.after(Duration.ofSeconds(5));
                for (var s : attempted)
                    try {
                        s.quiesce();
                    } catch (Exception cleanup) {
                        cause.addSuppressed(cleanup);
                    }
                Collections.reverse(attempted);
                for (var s : attempted)
                    try {
                        s.stop(d.remaining());
                    } catch (Exception cleanup) {
                        if (cleanup != cause) cause.addSuppressed(cleanup);
                    }
                state = LifecycleState.FAILED;
                throw exception("Server start failed", cause);
            }
        } finally {
            end();
        }
    }

    public void stop(Duration timeout) throws LifecycleException {
        var d = Deadline.after(timeout);
        begin();
        try {
            if (state == LifecycleState.STOPPED) return;
            state = LifecycleState.STOPPING;
            Throwable first = null;
            for (var s : findServices())
                try {
                    s.quiesce();
                } catch (LifecycleException e) {
                    first = append(first, e);
                }
            var all = findServices();
            for (int i = all.length - 1; i >= 0; i--)
                try {
                    all[i].stop(d.remaining());
                } catch (LifecycleException e) {
                    first = append(first, e);
                }
            if (first != null) {
                failed(first);
                throw exception("Server cleanup failed", first);
            }
            state = LifecycleState.STOPPED;
        } finally {
            end();
        }
    }

    public synchronized void await() throws InterruptedException {
        while (operating() || state != LifecycleState.STOPPED && state != LifecycleState.FAILED)
            wait();
    }

    public synchronized boolean await(Duration timeout) throws InterruptedException {
        var d = Deadline.after(timeout);
        while (operating() || state != LifecycleState.STOPPED && state != LifecycleState.FAILED) {
            long n = d.remainingNanos();
            if (n == 0) return false;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, n);
        }
        return true;
    }
}
