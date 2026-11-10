package simpletomcat.catalina.connector;

import java.util.Objects;
import simpletomcat.servlet.ServletResponse;

/** Servlet의 응답 작성을 기존 Coyote 버퍼에 위임한다. 실제 전송은 Processor 책임이다. */
public final class Response implements ServletResponse {
    private final simpletomcat.coyote.Response coyote;

    public Response(simpletomcat.coyote.Response coyote) { this.coyote = Objects.requireNonNull(coyote); }
    @Override public void setStatus(int status) { coyote.setStatus(status); }
    @Override public void setContentType(String type) { coyote.setHeader("Content-Type", type); }
    @Override public void setBody(String body) { coyote.setBody(body); }
}
