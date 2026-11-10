package simpletomcat.servlet;

/** 프로토콜 데이터와 분리된 최소 요청 조회 계약. connector.Request가 HTTP 데이터에 연결한다. */
public interface ServletRequest {
    /**
     * 요청의 HTTP 메서드를 조회한다.
     * @return GET, POST 등 요청 메서드
     */
    String getMethod();
    /**
     * 쿼리를 제외한 디코딩 전 URI 경로를 조회한다.
     * @return 퍼센트 디코딩 전 요청 경로
     */
    String getRequestURI();
    /**
     * 디코딩 전 쿼리 문자열을 조회한다.
     * @return 물음표 뒤의 쿼리, 쿼리가 없으면 null
     */
    String getQueryString();
    /**
     * 이름의 대소문자를 구분하지 않고 첫 번째 헤더 값을 조회한다.
     * @param name 조회할 헤더 이름
     * @return 첫 번째 헤더 값, 해당 헤더가 없으면 null
     */
    String getHeader(String name);

    /** 선택된 앱 경로. root 앱과 고정 Wrapper 요청은 빈 문자열이다. */
    default String getContextPath() { return ""; }
    /** 정확·확장자·기본 매핑은 앱 내부 전체 경로, 접두어 매핑은 접두어다. */
    default String getServletPath() { return getRequestURI(); }
    /** 접두어 뒤 남은 경로. 없으면 null이다. */
    default String getPathInfo() { return null; }
}
