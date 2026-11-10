package simpletomcat.catalina.connector;

import java.util.Objects;
import simpletomcat.servlet.ServletRequest;

/** 요청마다 새로 만드는 읽기 전용 Servlet 요청. 원문 경로와 query를 보존한다. */
public final class Request implements ServletRequest {
    private final simpletomcat.coyote.Request coyote;

    public Request(simpletomcat.coyote.Request coyote) { this.coyote = Objects.requireNonNull(coyote); }
    @Override public String getMethod() { return coyote.getMethod(); }
    @Override public String getRequestURI() { return coyote.getRequestURI(); }
    @Override public String getQueryString() { return coyote.getQueryString(); }
    @Override public String getHeader(String name) { return coyote.getHeader(name); }
}
