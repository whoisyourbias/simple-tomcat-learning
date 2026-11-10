package simpletomcat.catalina.connector;

import java.util.Objects;
import simpletomcat.catalina.Wrapper;
import simpletomcat.coyote.Adapter;
import simpletomcat.servlet.Servlet;

/** Coyote 데이터와 Servlet 계약을 연결한다. 소켓·전송·Wrapper 종료는 소유하지 않는다. */
public final class CoyoteAdapter implements Adapter {
    private final Wrapper wrapper;

    /** 3단계는 모든 요청을 고정 Wrapper에 전달한다. */
    public CoyoteAdapter(Wrapper wrapper) { this.wrapper = Objects.requireNonNull(wrapper); }

    @Override public void service(simpletomcat.coyote.Request req, simpletomcat.coyote.Response res) throws Exception {
        Request request = new Request(req);
        Response response = new Response(res);
        Servlet servlet = wrapper.allocate();
        Throwable failure = null;
        try {
            servlet.service(request, response);
        } catch (Exception | Error cause) {
            failure = cause;
            throw cause;
        } finally {
            try { wrapper.deallocate(servlet); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) throw cleanup;
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
    }
}
