package simpletomcat.coyote;

import java.util.*;

/** 파싱을 마친 요청이다. URI와 쿼리는 디코딩하지 않은 원문이다. */
public final class Request {
    private final String method;
    private final String requestURI;
    private final String queryString;
    private final String protocol;
    private final Map<String, List<String>> headers;

    public Request(String method, String requestURI, String queryString, String protocol,
                   Map<String, List<String>> headers) {
        this.method = method;
        this.requestURI = requestURI;
        this.queryString = queryString;
        this.protocol = protocol;
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(copy);
    }

    public String getMethod() { return method; }
    public String getRequestURI() { return requestURI; }
    public String getQueryString() { return queryString; }
    public String getProtocol() { return protocol; }
    public List<String> getHeaders(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()); }
    public String getHeader(String name) { return getHeaders(name).isEmpty() ? null : getHeaders(name).getFirst(); }
    public Map<String, List<String>> getHeaders() { return headers; }
}
