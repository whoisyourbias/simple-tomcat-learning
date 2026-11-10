package simpletomcat.examples;

import simpletomcat.catalina.connector.CoyoteAdapter;
import simpletomcat.catalina.core.StandardWrapper;
import simpletomcat.coyote.http11.Http11Protocol;
import simpletomcat.servlet.*;

/** 두 HTTP 요청에서 같은 서블릿의 init/service/destroy 수명을 관찰하는 3단계 예제. */
public final class ServletServer {
    private ServletServer() { }

    public static void main(String[] args) throws Exception {
        StandardWrapper wrapper = new StandardWrapper(() -> new Servlet() {
            private int calls;
            public void init() { System.out.println("Servlet init"); }
            public void service(ServletRequest req, ServletResponse res) throws ServletException {
                if (req.getRequestURI().equals("/fail")) throw new ServletException("example failure");
                res.setContentType("text/plain; charset=UTF-8");
                res.setBody("안녕하세요, Servlet! service=" + ++calls + "\n");
            }
            public void destroy() { System.out.println("Servlet destroy; successful services=" + calls); }
        });
        int port = args.length == 0 ? 18081 : Integer.parseInt(args[0]);
        ServerRunner.run(new Http11Protocol(port, new CoyoteAdapter(wrapper)), wrapper::unload);
    }
}
