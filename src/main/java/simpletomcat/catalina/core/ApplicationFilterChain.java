package simpletomcat.catalina.core;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import simpletomcat.servlet.*;

/** 공유 필터 목록과 구별되는 요청별 진행 위치를 소유한다. */
public final class ApplicationFilterChain implements FilterChain {
    private final List<Filter> filters;
    private final Servlet servlet;
    private int position;

    /** 목록을 복사해 요청 중 등록 목록의 변화가 실행 순서에 영향을 주지 않게 한다. */
    public ApplicationFilterChain(List<Filter> filters, Servlet servlet) {
        this.filters = List.copyOf(filters);
        this.servlet = Objects.requireNonNull(servlet, "servlet");
    }

    @Override public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
        if (position < filters.size()) {
            // 재귀적인 다음 호출 전에 위치를 옮긴다. 필터 객체에는 위치를 보관하지 않는다.
            filters.get(position++).doFilter(request, response, this);
        } else {
            servlet.service(request, response);
        }
    }
}
