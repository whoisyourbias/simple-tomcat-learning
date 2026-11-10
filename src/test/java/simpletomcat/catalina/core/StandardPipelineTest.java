package simpletomcat.catalina.core;

import org.junit.jupiter.api.Test;
import simpletomcat.catalina.ContainerRequest;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static simpletomcat.catalina.core.CatalinaTestSupport.*;

/** 일반 Valve와 기본 Valve의 연결·교체·중단 및 잘못된 등록의 원자성을 검증한다. */
class StandardPipelineTest {
    /** Valve 진입·복귀 또는 서비스 이벤트를 발생 순서대로 누적하는 기록이다. */
    final List<String> trace = new ArrayList<>();
    /** 체인의 마지막 실행 지점을 관찰하는 기본 Valve 대역이다. */
    final TraceValve basic = new TraceValve("basic", trace);
    /** 테스트 호출에서 동일성 또는 요청 내용 전달을 확인할 기준 요청이다. */
    final ContainerRequest request = new ContainerRequest(new Request("/"), null, null, null);
    /** Pipeline 호출 결과를 관찰하기 위해 전달하는 응답 대역이다. */
    final Response response = new Response();

    /** 일반 Valve가 없으면 기본 Valve가 첫 진입점이며 한 번 호출되는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void returnsBasicWithoutCustomValves() throws Exception {
        var pipeline = new StandardPipeline(basic);
        assertSame(basic, pipeline.getBasic());
        assertSame(basic, pipeline.getFirst());
        pipeline.getFirst().invoke(request, response);
        assertEquals(List.of("basic 전", "basic 후"), trace);
    }

    /** A와 B를 등록하면 등록 순서로 진입하고 기본 Valve 후 역순 복귀하는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void invokesInRegistrationOrder() throws Exception {
        var pipeline = new StandardPipeline(basic);
        pipeline.addValve(new TraceValve("A", trace));
        pipeline.addValve(new TraceValve("B", trace));
        pipeline.getFirst().invoke(request, response);
        assertEquals(List.of("A 전", "B 전", "basic 전", "basic 후", "B 후", "A 후"), trace);
    }

    /** 일반 Valve 뒤 기본 Valve를 교체하고 재설정해도 새 기본 Valve만 마지막에 한 번 실행되는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void replacesBasicAtTail() throws Exception {
        var pipeline = new StandardPipeline(basic);
        var a = new TraceValve("A", trace);
        var b = new TraceValve("B", trace);
        var replacement = new TraceValve("new", trace);
        pipeline.addValve(a);
        pipeline.addValve(b);
        pipeline.setBasic(replacement);
        pipeline.setBasic(replacement);
        pipeline.getFirst().invoke(request, response);
        assertSame(a, pipeline.getFirst());
        assertSame(replacement, pipeline.getBasic());
        assertNull(replacement.getNext());
        assertEquals(List.of("A 전", "B 전", "new 전", "new 후", "B 후", "A 후"), trace);
    }

    /** 중간 Valve가 next 호출을 생략하면 이후 호출이 없고 다음 요청에서 정상 재개되는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void shortCircuitsBeforeBasic() throws Exception {
        var pipeline = new StandardPipeline(basic);
        var a = new TraceValve("A", trace);
        var b = new TraceValve("B", trace);
        a.proceed = false;
        pipeline.addValve(a);
        pipeline.addValve(b);
        pipeline.getFirst().invoke(request, response);
        assertEquals(List.of("A 전", "A 후"), trace);
        trace.clear();
        a.proceed = true;
        pipeline.getFirst().invoke(new ContainerRequest(new Request("/next"), null, null, null), new Response());
        assertEquals(List.of("A 전", "B 전", "basic 전", "basic 후", "B 후", "A 후"), trace);
    }

    /** null·중복·기본 Valve·이미 연결된 Valve 등록을 거부한 뒤 기존 체인이 보존되는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void rejectsInvalidValveWithoutChangingChain() throws Exception {
        assertThrows(NullPointerException.class, () -> new StandardPipeline(null));
        var pipeline = new StandardPipeline(basic);
        var a = new TraceValve("A", trace);
        var linked = new TraceValve("linked", trace);
        linked.setNext(new TraceValve("tail", trace));
        pipeline.addValve(a);
        assertThrows(NullPointerException.class, () -> pipeline.addValve(null));
        assertThrows(NullPointerException.class, () -> pipeline.setBasic(null));
        assertThrows(IllegalArgumentException.class, () -> pipeline.addValve(a));
        assertThrows(IllegalArgumentException.class, () -> pipeline.addValve(basic));
        assertThrows(IllegalArgumentException.class, () -> pipeline.addValve(linked));
        assertThrows(IllegalArgumentException.class, () -> pipeline.setBasic(a));
        assertThrows(IllegalArgumentException.class, () -> pipeline.setBasic(linked));
        assertThrows(IllegalArgumentException.class, () -> new StandardPipeline(linked));
        pipeline.getFirst().invoke(request, response);
        assertSame(basic, pipeline.getBasic());
        assertEquals(List.of("A 전", "basic 전", "basic 후", "A 후"), trace);
    }
}
