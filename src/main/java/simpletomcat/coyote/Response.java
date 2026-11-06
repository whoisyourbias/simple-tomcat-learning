package simpletomcat.coyote;

import java.util.*;

/** 전송 전 상태·헤더·UTF-8 본문을 메모리에 보관한다. */
public final class Response {
    private int status = 200;
    private String body = "";
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public int getStatus() { return status; }
    public void setStatus(int status) {
        if (status < 200 || status > 599) throw new IllegalArgumentException("Only final status 200..599 is supported");
        this.status = status;
    }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = Objects.requireNonNull(body); }
    public Map<String, String> getHeaders() { return Collections.unmodifiableMap(headers); }
    public void setHeader(String name, String value) {
        if (!isToken(name) || value == null || value.chars().anyMatch(c -> c < 32 && c != 9 || c > 126)) {
            throw new IllegalArgumentException("Invalid response header");
        }
        headers.put(name, value);
    }

    /** HTTP token 문법을 요청과 응답 양쪽에서 공유한다. */
    public static boolean isToken(String value) {
        return value != null && !value.isEmpty() && value.chars().allMatch(c ->
                c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                        || "!#$%&'*+-.^_`|~".indexOf(c) >= 0);
    }
}
