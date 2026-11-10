package simpletomcat.catalina.connector;

import java.util.Objects;
import java.net.URI;
import simpletomcat.catalina.Engine;
import simpletomcat.catalina.ContainerRequest;
import simpletomcat.catalina.mapper.*;
import simpletomcat.catalina.Wrapper;
import simpletomcat.coyote.Adapter;
import simpletomcat.servlet.Servlet;

/** Coyote 데이터와 Servlet 계약을 연결한다. 소켓·전송·Wrapper 종료는 소유하지 않는다. */
public final class CoyoteAdapter implements Adapter {
    private final Wrapper wrapper;
    private final Engine engine;
    private final Mapper mapper;

    /** 3단계는 모든 요청을 고정 Wrapper에 전달한다. */
    public CoyoteAdapter(Wrapper wrapper) {
        this.wrapper = Objects.requireNonNull(wrapper); this.engine = null; this.mapper = null;
    }

    public CoyoteAdapter(Engine engine, Mapper mapper) {
        this.wrapper = null; this.engine = Objects.requireNonNull(engine); this.mapper = Objects.requireNonNull(mapper);
    }

    @Override public void service(simpletomcat.coyote.Request req, simpletomcat.coyote.Response res) throws Exception {
        if (engine != null) {
            MappingData mapping;
            try {
                String host = req.getHeader("Host");
                String serverName = host == null ? null : URI.create("http://" + host).getHost();
                mapping = mapper.map(serverName, req.getRequestURI());
            } catch (IllegalArgumentException invalidPath) {
                res.setStatus(400); res.setBody("400 Bad Request\n"); return;
            }
            Request request = new Request(req, mapping);
            engine.getPipeline().getFirst().invoke(
                    new ContainerRequest(request, mapping.host(), mapping.context(), mapping.wrapper()), new Response(res));
            return;
        }
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
