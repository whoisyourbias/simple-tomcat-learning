package simpletomcat.catalina.core;

import simpletomcat.catalina.*;
import simpletomcat.servlet.ServletException;
import simpletomcat.util.Deadline;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 성공·부분 성공 Context와 단 하나의 청소 작업을 보관한다. */
final class ApplicationLifecycle {
    private final StandardEngine engine;
    private final java.util.function.Supplier<ExecutorService> executors;
    private final List<Context> attempted = new ArrayList<>();
    private ExecutorService executor;
    private Future<?> cleanup;
    private Throwable permanentFailure;

    ApplicationLifecycle(StandardEngine engine) {
        this(
                engine,
                () ->
                        Executors.newSingleThreadExecutor(
                                r -> new Thread(r, "tomcat-application-cleanup")));
    }

    ApplicationLifecycle(
            StandardEngine engine, java.util.function.Supplier<ExecutorService> executors) {
        this.engine = Objects.requireNonNull(engine);
        this.executors = Objects.requireNonNull(executors);
    }

    void prepare() throws ServletException {
        for (Container host : engine.findChildren())
            for (Container child : host.findChildren()) {
                Context context = (Context) child;
                attempted.add(context);
                context.startFilters();
            }
    }

    void cleanup(Duration timeout) throws LifecycleException {
        var d = Deadline.after(timeout);
        if (permanentFailure != null)
            throw LifecycleControl.exception("Application cleanup failed", permanentFailure);
        if (attempted.isEmpty()) return;
        if (cleanup == null) {
            if (d.remainingNanos() == 0)
                throw LifecycleControl.exception(
                        "Application cleanup not started", new TimeoutException());
            try {
                executor = Objects.requireNonNull(executors.get());
                cleanup =
                        executor.submit(
                                () -> {
                                    Throwable first = null;
                                    for (int i = attempted.size() - 1; i >= 0; i--) {
                                        Context context = attempted.get(i);
                                        Container[] children = context.findChildren();
                                        for (int j = children.length - 1; j >= 0; j--) {
                                            try {
                                                ((Wrapper) children[j]).unload();
                                            } catch (ServletException | RuntimeException e) {
                                                first = LifecycleControl.append(first, e);
                                            }
                                        }
                                        try {
                                            context.stopFilters();
                                        } catch (ServletException | RuntimeException e) {
                                            first = LifecycleControl.append(first, e);
                                        }
                                    }
                                    if (first != null) throw new CompletionException(first);
                                });
            } catch (RuntimeException failure) {
                throw LifecycleControl.exception("Cannot start application cleanup", failure);
            } finally {
                if (executor != null) executor.shutdown();
            }
        }
        try {
            cleanup.get(d.remainingNanos(), TimeUnit.NANOSECONDS);
            if (!executor.awaitTermination(d.remainingNanos(), TimeUnit.NANOSECONDS))
                throw new TimeoutException("Cleanup executor remains");
        } catch (ExecutionException e) {
            permanentFailure =
                    e.getCause() instanceof CompletionException c ? c.getCause() : e.getCause();
            throw LifecycleControl.exception("Application cleanup failed", permanentFailure);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LifecycleControl.exception("Application cleanup interrupted", e);
        } catch (TimeoutException e) {
            throw LifecycleControl.exception("Application cleanup timed out", e);
        }
    }
}
