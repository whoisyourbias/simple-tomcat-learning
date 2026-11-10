package simpletomcat.examples;

import simpletomcat.catalina.*;
import simpletomcat.catalina.connector.CoyoteAdapter;
import simpletomcat.catalina.core.*;
import simpletomcat.catalina.mapper.Mapper;
import simpletomcat.coyote.http11.Http11Protocol;
import simpletomcat.servlet.*;

/** 두 Host·두 앱·중첩 앱과 패턴 우선순위를 실제 HTTP로 비교하는 4단계 예제. */
public final class ContainerServer {
    private ContainerServer() { }

    public static void main(String[] args) throws Exception {
        StandardEngine engine = createEngine();
        int port = args.length == 0 ? 18081 : Integer.parseInt(args[0]);
        ServerRunner.run(new Http11Protocol(port, new CoyoteAdapter(engine, new Mapper(engine, "localhost"))),
                () -> unloadServlets(engine));
    }

    /** 실행 예제의 명시적인 시작 전 구성. Host fallback은 main에서 따로 지정한다. */
    public static StandardEngine createEngine() {
        StandardEngine engine = new StandardEngine("demo");
        StandardHost local = new StandardHost("localhost"), other = new StandardHost("other.local");
        engine.addChild(local); engine.addChild(other);
        StandardContext shop = context(local, "/shop"), admin = context(local, "/admin");
        register(shop, "shop-status", "/status"); register(admin, "admin-status", "/status");
        register(context(local, "/shop/deep"), "nested-status", "/status");
        register(context(other, "/shop"), "other-status", "/status");
        register(shop, "api", "/api/*"); register(shop, "deep-api", "/api/deep/*");
        register(shop, "exact", "/api/exact.do"); register(shop, "extension", "*.do");
        register(shop, "default", "/");
        return engine;
    }

    private static StandardContext context(StandardHost host, String path) {
        StandardContext context = new StandardContext(path); host.addChild(context); return context;
    }

    private static void register(StandardContext context, String name, String pattern) {
        StandardWrapper wrapper = new StandardWrapper(() -> new Servlet() {
            public void init() { System.out.println(name + " init"); }
            public void service(ServletRequest request, ServletResponse response) throws ServletException {
                if (request.getRequestURI().endsWith("/fail")) {
                    response.setBody("partial"); throw new ServletException("example failure");
                }
                response.setBody(name + " contextPath=" + request.getContextPath() + " servletPath="
                        + request.getServletPath() + " pathInfo=" + request.getPathInfo() + "\n");
            }
            public void destroy() { System.out.println(name + " destroy"); }
        });
        wrapper.setName(name); context.addChild(wrapper); context.addServletMapping(pattern, wrapper);
    }

    /** serve가 끝난 뒤 모든 Wrapper를 해제한다. 일반 destroy 예외는 모아 전파하며 Error는 즉시 전파한다. */
    public static void unloadServlets(Container container) throws ServletException {
        if (container instanceof Wrapper wrapper) { wrapper.unload(); return; }
        ServletException failure = null;
        for (Container child : container.findChildren()) {
            try { unloadServlets(child); }
            catch (ServletException cause) {
                if (failure == null) failure = cause;
                else if (failure != cause) failure.addSuppressed(cause);
            }
        }
        if (failure != null) throw failure;
    }
}
