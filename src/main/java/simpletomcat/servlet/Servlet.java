package simpletomcat.servlet;

import java.io.IOException;

/** 학습용 서블릿 계약. Wrapper가 초기화·해제하고 호출자가 요청별 service를 실행한다. */
public interface Servlet {
    /**
     * 첫 요청 처리 전에 인스턴스의 자원을 초기화한다.
     * <p>Wrapper는 초기화에 성공한 인스턴스만 재사용하며 실패한 인스턴스에는
     * {@link #destroy()}를 호출하지 않는다.
     *
     * @throws ServletException 서블릿 초기화를 완료하지 못한 경우
     */
    void init() throws ServletException;

    /**
     * 전달받은 요청을 처리하고 응답 객체에 결과를 기록한다.
     * <p>호출 계층은 초기화에 성공한 인스턴스를 대여한 뒤 호출하며 사용 후 반환한다.
     * 이 계약 자체는 예외를 HTTP 오류 응답으로 변환하지 않는다.
     *
     * @param request 조회할 요청 데이터
     * @param response 처리 결과를 기록할 응답
     * @throws ServletException 서블릿의 요청 처리에 실패한 경우
     * @throws IOException 요청 처리 중 입출력 작업에 실패한 경우
     */
    void service(ServletRequest request, ServletResponse response) throws ServletException, IOException;

    /**
     * 초기화에 성공한 인스턴스의 모든 사용이 끝난 뒤 보유 자원을 해제한다.
     * <p>Wrapper의 unload가 호출하며 이 계약은 검사 예외를 선언하지 않는다.
     */
    void destroy();
}
