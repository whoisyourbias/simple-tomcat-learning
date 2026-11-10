package simpletomcat.catalina.core;

import simpletomcat.catalina.*;

/** 콜백 동안 잠금을 유지하지 않는 수명 조작 가드. */
public abstract class LifecycleControl implements Lifecycle {
    protected volatile LifecycleState state = LifecycleState.NEW;
    protected volatile Throwable failure;
    private boolean operating;

    public final LifecycleState getState() {
        return state;
    }

    public final Throwable getFailure() {
        return failure;
    }

    protected final synchronized void begin() {
        if (operating) throw new IllegalStateException("Lifecycle operation already running");
        operating = true;
    }

    protected final synchronized void end() {
        operating = false;
        notifyAll();
    }

    protected final synchronized boolean operating() {
        return operating;
    }

    protected final synchronized void failed(Throwable cause) {
        failure = cause;
        state = LifecycleState.FAILED;
        synchronized (this) {
            notifyAll();
        }
    }

    protected final synchronized void started() throws LifecycleException {
        if (state == LifecycleState.FAILED)
            throw exception("Background failure during startup", failure);
        state = LifecycleState.STARTED;
        notifyAll();
    }

    public static LifecycleException exception(String message, Throwable cause) {
        return cause instanceof LifecycleException e ? e : new LifecycleException(message, cause);
    }

    public static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
}
