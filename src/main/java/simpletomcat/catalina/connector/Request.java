package simpletomcat.catalina.connector;

import java.util.Objects;
import simpletomcat.servlet.ServletRequest;
import simpletomcat.catalina.mapper.MappingData;

/** 요청마다 새로 만드는 읽기 전용 Servlet 요청. 원문 경로와 query를 보존한다. */
public final class Request implements ServletRequest {
    private final simpletomcat.coyote.Request coyote;
    private final MappingData mapping;

    public Request(simpletomcat.coyote.Request coyote) { this(coyote, null); }
    public Request(simpletomcat.coyote.Request coyote, MappingData mapping) {
        this.coyote = Objects.requireNonNull(coyote);
        this.mapping = mapping;
    }
    @Override public String getMethod() { return coyote.getMethod(); }
    @Override public String getRequestURI() { return coyote.getRequestURI(); }
    @Override public String getQueryString() { return coyote.getQueryString(); }
    @Override public String getHeader(String name) { return coyote.getHeader(name); }
    public MappingData getMappingData() { return mapping; }
    @Override public String getContextPath() { return mapping == null ? "" : mapping.contextPath(); }
    @Override public String getServletPath() { return mapping == null ? getRequestURI() : mapping.servletPath(); }
    @Override public String getPathInfo() { return mapping == null ? null : mapping.pathInfo(); }
}
