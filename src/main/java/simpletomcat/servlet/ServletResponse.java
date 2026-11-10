package simpletomcat.servlet;

/** 최소 응답 작성 계약. 여기서 HTTP 전송·commit·스트리밍은 제공하지 않는다. */
public interface ServletResponse {
    /**
     * 응답 상태 코드를 지정한다. 실제 전송 전 검증은 연결 구현의 책임이다.
     * @param status 기록할 HTTP 상태 코드
     */
    void setStatus(int status);
    /**
     * 응답 본문의 미디어 유형을 지정한다.
     * @param contentType Content-Type에 사용할 미디어 유형과 선택적 문자 인코딩
     */
    void setContentType(String contentType);
    /**
     * 기존 문자열 본문을 교체한다. 실제 바이트 변환은 연결 구현의 책임이다.
     * @param body 새 응답 본문
     */
    void setBody(String body);
}
