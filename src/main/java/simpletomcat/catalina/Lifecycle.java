package simpletomcat.catalina;

import java.time.Duration;

public interface Lifecycle extends AutoCloseable {
    void start() throws LifecycleException;

    void stop(Duration timeout) throws LifecycleException;

    LifecycleState getState();

    Throwable getFailure();

    default void close() throws LifecycleException {
        stop(Duration.ofSeconds(5));
    }
}
