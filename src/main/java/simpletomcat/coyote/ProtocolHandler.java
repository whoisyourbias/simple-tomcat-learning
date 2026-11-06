package simpletomcat.coyote;

import java.io.IOException;

/** 호출 스레드에서 실행하고 소켓 대기를 취소하는 최소 프로토콜 계약이다. */
public interface ProtocolHandler extends AutoCloseable {
    int getPort();
    void serve() throws IOException;
    @Override void close() throws IOException;
}
