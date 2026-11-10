package simpletomcat.coyote.http11;

import simpletomcat.coyote.*;
import simpletomcat.util.net.*;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/** 연결별 Processor와 공유 Adapter를 조립한다. */
public final class ConcurrentHttp11Protocol implements ManagedProtocolHandler {
    private final ConcurrentEndpoint endpoint;

    public ConcurrentHttp11Protocol(int port, Adapter adapter, EndpointOptions options)
            throws IOException {
        Objects.requireNonNull(adapter);
        endpoint =
                new ConcurrentEndpoint(
                        port, options, (in, out) -> new Http11Processor(adapter).process(in, out));
    }

    public int getPort() {
        return endpoint.getPort();
    }

    public void serve() throws IOException {
        endpoint.serve();
    }

    public void stopAccepting() throws IOException {
        endpoint.stopAccepting();
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return endpoint.awaitTermination(timeout);
    }

    public void close() throws IOException {
        endpoint.close();
    }

    public ConcurrentEndpoint.Snapshot snapshot() {
        return endpoint.snapshot();
    }

    public boolean awaitSnapshot(Predicate<ConcurrentEndpoint.Snapshot> expected, Duration timeout)
            throws InterruptedException {
        return endpoint.awaitSnapshot(expected, timeout);
    }
}
