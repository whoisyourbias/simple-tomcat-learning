package simpletomcat.servlet;

import java.io.IOException;

/** Context가 초기화·해제하며 동기 요청의 다음 호출을 감싸는 최소 계약. */
public interface Filter {
    /** 요청 수신 전 초기화한다. 실패 중 획득한 부분 자원은 구현자가 정리한다. */
    void init() throws ServletException;
    /** 다음 호출은 같은 동기 요청 안에서 0회 또는 1회 수행한다. */
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException;
    /** 모든 사용이 끝난 뒤 초기화 성공 객체의 자원을 해제한다. */
    void destroy();
}
