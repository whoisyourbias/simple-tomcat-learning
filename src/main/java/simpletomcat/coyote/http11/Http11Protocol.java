package simpletomcat.coyote.http11;

import java.io.IOException;
import simpletomcat.coyote.Adapter;
import simpletomcat.coyote.ProtocolHandler;
import simpletomcat.util.net.Endpoint;

/** Endpoint·Processor·Adapter를 조립하며 실행 스레드는 호출자가 관리한다. */
public final class Http11Protocol implements ProtocolHandler {
    private final Endpoint endpoint;

    /** HTTP 동작을 먼저 준비한 뒤 Endpoint를 생성해 생성 실패 시 포트가 남지 않게 한다. */
    public Http11Protocol(int port, Adapter adapter) throws IOException {
        Http11Processor processor = new Http11Processor(adapter);
        endpoint = new Endpoint(port, processor::process);
    }

    /** 1단계와 같은 본문을 제공하는 학습용 앱으로 조립한다. */
    public Http11Protocol(int port) throws IOException {
        this(port, (request, response) -> response.setBody("안녕하세요, simple-tomcat!\n"));
    }

    @Override public int getPort() { return endpoint.getPort(); }
    @Override public void serve() throws IOException { endpoint.serve(); }
    @Override public void close() throws IOException { endpoint.close(); }

    /** 포트 인자를 받아 실행한다. Ctrl+C 종료 시 활성 연결도 즉시 닫는다. */
    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 18081 : Integer.parseInt(args[0]);
        try (Http11Protocol protocol = new Http11Protocol(port)) {
            Thread shutdown = new Thread(() -> {
                try { protocol.close(); }
                catch (IOException failure) { System.err.println("Protocol shutdown failed: " + failure.getMessage()); }
            }, "http11-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                System.out.println("Listening on port " + protocol.getPort());
                protocol.serve();
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException shuttingDown) { /* JVM 종료 중에는 hook이 정리한다. */ }
            }
        }
    }
}
