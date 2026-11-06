package simpletomcat.coyote;

/** HTTP 처리와 애플리케이션 처리를 잇는 동기 호출 경계다. */
@FunctionalInterface
public interface Adapter {
    /** 응답을 메모리에 작성한다. 일반 예외는 Processor가 500으로 변환한다. */
    void service(Request request, Response response) throws Exception;
}
