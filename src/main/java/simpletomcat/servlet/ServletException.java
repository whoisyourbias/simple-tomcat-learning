package simpletomcat.servlet;

/** 학습용 서블릿의 초기화·처리·해제 오류. 원인 예외를 보존한다. */
public class ServletException extends Exception {
    /**
     * 서블릿 실패의 설명을 보존하는 예외를 생성한다.
     * @param message 실패 설명, 없으면 null
     */
    public ServletException(String message) { super(message); }
    /**
     * 서블릿 실패의 설명과 원인 예외를 보존한다.
     * @param message 실패 설명, 없으면 null
     * @param cause 원인 예외, 없으면 null
     */
    public ServletException(String message, Throwable cause) { super(message, cause); }
}
