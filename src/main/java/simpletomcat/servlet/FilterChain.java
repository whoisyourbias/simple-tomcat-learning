package simpletomcat.servlet;

import java.io.IOException;

/** 동기 요청에서 다음 필터 또는 마지막 Servlet을 호출한다. 보관·비동기 재사용하지 않는다. */
@FunctionalInterface
public interface FilterChain {
    void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException;
}
