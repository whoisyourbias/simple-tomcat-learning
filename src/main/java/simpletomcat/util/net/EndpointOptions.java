package simpletomcat.util.net;

/** 블로킹 작업자·큐·읽기 무진행 제한. */
public record EndpointOptions(int workerCount, int queueCapacity, int readTimeoutMillis) {
    public EndpointOptions {
        if (workerCount < 1 || queueCapacity < 1 || readTimeoutMillis < 1)
            throw new IllegalArgumentException("Positive endpoint limits required");
    }

    public static EndpointOptions defaults() {
        return new EndpointOptions(4, 16, 3000);
    }
}
