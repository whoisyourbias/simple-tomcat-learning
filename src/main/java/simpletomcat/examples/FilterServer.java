package simpletomcat.examples;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import simpletomcat.catalina.*;
import simpletomcat.catalina.core.*;
import simpletomcat.catalina.connector.CoyoteAdapter;
import simpletomcat.catalina.mapper.Mapper;
import simpletomcat.catalina.valves.ValveBase;
import simpletomcat.coyote.http11.Http11Protocol;
import simpletomcat.servlet.*;

/** 컨테이너 Valve와 앱 Filter의 적용 범위·중단·실패·수명을 비교하는 순차 서버. */
public final class FilterServer {
    private FilterServer() { }
    public static StandardEngine createEngine() { return createEngine(System.out::println); }

    /** 기록 대상만 주입하며 요청별 체인은 WrapperValve에서 생성한다. */
    public static StandardEngine createEngine(Consumer<String> trace) {
        Objects.requireNonNull(trace, "trace");
        var engine = new StandardEngine("filter-demo"); var host = new StandardHost("localhost"); engine.addChild(host);
        engine.getPipeline().addValve(new ValveBase() {
            public void invoke(ContainerRequest q, ServletResponse s) throws IOException, ServletException {
                trace.accept("Engine-before"); try { getNext().invoke(q,s); } finally { trace.accept("Engine-after"); }
            }
        });
        for (String app : List.of("shop","admin")) {
            var context = new StandardContext("/" + app); host.addChild(context);
            var wrapper = new StandardWrapper(() -> new Servlet() {
                public void init() { trace.accept(app + "-init"); }
                public void destroy() { trace.accept(app + "-destroy"); }
                public void service(ServletRequest q,ServletResponse s) throws ServletException {
                    trace.accept(app + "-service");
                    if (q.getRequestURI().endsWith("/servlet-fail")) { s.setBody("partial"); throw new ServletException("Servlet failure"); }
                    s.setBody(app + " hello\n");
                }
            }); wrapper.setName(app); context.addChild(wrapper); context.addServletMapping("/",wrapper);
            if (app.equals("shop")) for (String name : List.of("A","B")) context.addFilter(name, () -> new Filter() {
                public void init() { trace.accept(name + "-init"); }
                public void destroy() { trace.accept(name + "-destroy"); }
                public void doFilter(ServletRequest q,ServletResponse s,FilterChain next) throws IOException, ServletException {
                    trace.accept(name + "-before");
                    try {
                        if (name.equals("A") && q.getRequestURI().endsWith("/blocked")) { s.setBody("blocked\n"); return; }
                        if (name.equals("B") && q.getRequestURI().endsWith("/filter-fail")) { s.setBody("partial"); throw new ServletException("Filter failure"); }
                        next.doFilter(q,s);
                    } finally { trace.accept(name + "-after"); }
                }
            });
        }
        return engine;
    }

    /** 뒤 Context의 준비 실패면 앞서 준비한 Context를 역순 정리한다. */
    public static void startFilters(StandardEngine engine) throws ServletException {
        var ready = new ArrayList<Context>();
        try { for (Context c : contexts(engine)) { c.startFilters(); ready.add(c); } }
        catch (ServletException | RuntimeException failure) {
            for (int i = ready.size()-1; i>=0; i--) {
                try { ready.get(i).stopFilters(); }
                catch (ServletException | RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    /** 실행자가 serve 완료를 확인한 뒤 호출하며 일반 실패에도 형제 정리를 계속한다. */
    public static void stopFilters(StandardEngine engine) throws ServletException {
        ServletException failure = null; List<Context> all = contexts(engine);
        for (int i=all.size()-1; i>=0; i--) {
            try { all.get(i).stopFilters(); }
            catch (ServletException | RuntimeException cause) {
                if (failure == null) failure = cause instanceof ServletException se ? se : new ServletException("Cannot stop filters",cause);
                else if (failure != cause) failure.addSuppressed(cause);
            }
        }
        if (failure != null) throw failure;
    }

    /** Servlet 해제 실패에도 Filter 해제를 수행하고 첫 실패를 보존한다. */
    public static void closeApplications(StandardEngine engine) throws ServletException {
        ServletException failure = null;
        try { ContainerServer.unloadServlets(engine); } catch (ServletException e) { failure = e; }
        try { stopFilters(engine); } catch (ServletException e) { if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }

    /** 필터 준비 뒤 바인딩하며 바인딩 실패 시 성공한 필터의 소유권도 회수한다. */
    public static Http11Protocol open(StandardEngine engine, int port) throws IOException, ServletException {
        startFilters(engine);
        try { return new Http11Protocol(port,new CoyoteAdapter(engine,new Mapper(engine,"localhost"))); }
        catch (IOException | RuntimeException failure) {
            try { stopFilters(engine); } catch (ServletException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static List<Context> contexts(StandardEngine engine) {
        var result = new ArrayList<Context>();
        for (Container host : engine.findChildren()) for (Container child : host.findChildren()) result.add((Context)child);
        return result;
    }

    public static void main(String[] args) throws Exception {
        var engine = createEngine(); int port = args.length == 0 ? 18081 : Integer.parseInt(args[0]);
        ServerRunner.run(open(engine,port), () -> closeApplications(engine));
    }
}
