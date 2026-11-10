package simpletomcat.catalina.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import simpletomcat.catalina.ContainerRequest;
import simpletomcat.servlet.ServletException;
import simpletomcat.servlet.ServletRequest;
import simpletomcat.servlet.ServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static simpletomcat.catalina.core.CatalinaTestSupport.*;

/** 수동 선택한 컨테이너 계층의 Pipeline 호출 순서와 서블릿 생명주기를 함께 검증한다. */
class ContainerInvocationTest {
    /** 한 서블릿으로 연결된 네 컨테이너를 구성하여 수동 선택과 Pipeline 호출을 지원한다. */
    static final class Hierarchy {
        /** 요청 호출을 시작하는 최상위 컨테이너이다. */
        final StandardEngine engine = new StandardEngine("engine");
        /** Engine에 등록하여 선택 대상의 부모 관계를 검사할 Host이다. */
        final StandardHost host = new StandardHost("host");
        /** Host에 등록하여 선택 대상의 부모 관계를 검사할 Context이다. */
        final StandardContext context = new StandardContext("");
        /** 테스트 서블릿의 할당과 해제를 담당하는 최하위 컨테이너이다. */
        final StandardWrapper wrapper;
        /**
         * 주어진 서블릿의 Wrapper를 생성하고 Engine·Host·Context를 부모·자식으로 연결한다.
         * @param servlet 계층에 등록하거나 반환할 서블릿
         */
        Hierarchy(CountingServlet servlet) {
            wrapper = new StandardWrapper(() -> servlet);
            wrapper.setName("hello");
            engine.addChild(host);
            host.addChild(context);
            context.addChild(wrapper);
        }
        /**
         * 원본 요청에 이 계층의 Host·Context·Wrapper를 수동 선택한 전달 객체를 만든다.
         * @param servletRequest 수동 선택 정보에 포함할 원본 서블릿 요청
         * @return 선택한 계층을 포함하는 요청
         */
        ContainerRequest request(ServletRequest servletRequest) {
            return new ContainerRequest(servletRequest, host, context, wrapper);
        }
        /**
         * Engine의 첫 Valve에서 시작하여 수동 선택된 계층으로 요청과 응답을 전달한다.
         * @param request 하위 구성 요소에 전달할 요청
         * @param response 전달하거나 검사할 응답
         * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
         * @throws IOException 요청·응답 입출력 또는 테스트가 주입한 I/O 실패가 발생한 경우
         */
        void invoke(ContainerRequest request, Response response) throws ServletException, IOException {
            engine.getPipeline().getFirst().invoke(request, response);
        }
    }

    /** 네 Pipeline의 진입·복귀 순서와 같은 전달 객체·응답, 서비스의 원본 요청 전달을 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void invokesSelectedHierarchy() throws Exception {
        var trace = new ArrayList<String>();
        var servlet = new CountingServlet() {
            /**
             * 공유 호출 추적에 service를 기록하고 부모 대역으로 요청·응답을 전달한다.
             * @param request 하위 구성 요소에 전달할 요청
             * @param response 전달하거나 검사할 응답
             * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
             * @throws IOException 요청·응답 입출력 또는 테스트가 주입한 I/O 실패가 발생한 경우
             */
            public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
                trace.add("service");
                super.service(request, response);
            }
        };
        var hierarchy = new Hierarchy(servlet);
        var valves = List.of(new TraceValve("engine", trace), new TraceValve("host", trace),
                new TraceValve("context", trace), new TraceValve("wrapper", trace));
        hierarchy.engine.getPipeline().addValve(valves.get(0));
        hierarchy.host.getPipeline().addValve(valves.get(1));
        hierarchy.context.getPipeline().addValve(valves.get(2));
        hierarchy.wrapper.getPipeline().addValve(valves.get(3));
        var original = new Request("/hello");
        var request = hierarchy.request(original);
        var response = new Response();
        try {
            hierarchy.invoke(request, response);
            assertEquals(List.of("engine 전", "host 전", "context 전", "wrapper 전", "service",
                    "wrapper 후", "context 후", "host 후", "engine 후"), trace);
            for (var valve : valves) {
                assertSame(request, valve.seenRequest);
                assertSame(response, valve.seenResponse);
            }
            assertSame(original, servlet.requests.getFirst());
            assertSame(response, servlet.responses.getFirst());
        } finally {
            hierarchy.wrapper.unload();
        }
    }

    /** URI와 다른 형제 이름이 같아도 수동 선택된 Wrapper만 실행하고 형제 Context·Wrapper는 실행하지 않는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void ignoresUnselectedSiblings() throws Exception {
        var selected = new CountingServlet();
        var other = new CountingServlet();
        var siblingServlet = new CountingServlet();
        var hierarchy = new Hierarchy(selected);
        var siblingContext = new StandardContext("/other");
        hierarchy.host.addChild(siblingContext);
        var sibling = new StandardWrapper(() -> siblingServlet);
        sibling.setName("sibling");
        siblingContext.addChild(sibling);
        var otherWrapper = new StandardWrapper(() -> other);
        otherWrapper.setName("other");
        hierarchy.context.addChild(otherWrapper);
        try {
            hierarchy.invoke(hierarchy.request(new Request("/other")), new Response());
            assertEquals(1, selected.services);
            assertEquals(0, other.services);
            assertEquals(0, siblingServlet.services);
            assertEquals(0, other.inits);
            assertEquals(0, siblingServlet.inits);
        } finally {
            hierarchy.wrapper.unload();
            otherWrapper.unload();
            sibling.unload();
        }
    }

    /** Host·Context·Wrapper를 하나씩 누락하면 404와 빈 본문을 설정하고 하위 Pipeline·서비스 호출을 중단하는지 검증한다.
     * @param missing 누락할 Host·Context·Wrapper의 위치
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void missingTargetStopsInvocation(int missing) throws Exception {
        var servlet = new CountingServlet();
        var hierarchy = new Hierarchy(servlet);
        var trace = new ArrayList<String>();
        hierarchy.host.getPipeline().addValve(new TraceValve("host", trace));
        hierarchy.context.getPipeline().addValve(new TraceValve("context", trace));
        hierarchy.wrapper.getPipeline().addValve(new TraceValve("wrapper", trace));
        var request = new ContainerRequest(new Request("/"), missing == 0 ? null : hierarchy.host,
                missing == 1 ? null : hierarchy.context, missing == 2 ? null : hierarchy.wrapper);
        var response = new Response();
        response.body = "old body";
        hierarchy.invoke(request, response);
        assertEquals(404, response.status);
        assertEquals("", response.body);
        assertEquals(0, servlet.services);
        assertEquals(0, servlet.inits);
        var expected = switch (missing) {
            case 0 -> List.<String>of();
            case 1 -> List.of("host 전", "host 후");
            default -> List.of("host 전", "context 전", "context 후", "host 후");
        };
        assertEquals(expected, trace);
    }

    /** 다른 계층의 Host·Context·Wrapper를 지정하면 서비스 전에 예외로 거부하는지 검증한다.
     * @param different 다른 계층에서 선택할 대상 위치
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsTargetsFromAnotherHierarchy(int different) {
        var servlet = new CountingServlet();
        var otherServlet = new CountingServlet();
        var hierarchy = new Hierarchy(servlet);
        var other = new Hierarchy(otherServlet);
        var request = new ContainerRequest(new Request("/"), different == 0 ? other.host : hierarchy.host,
                different == 1 ? other.context : hierarchy.context, different == 2 ? other.wrapper : hierarchy.wrapper);
        assertThrows(IllegalArgumentException.class, () -> hierarchy.invoke(request, new Response()));
        assertEquals(0, servlet.services);
        assertEquals(0, otherServlet.services);
        assertEquals(0, servlet.inits);
        assertEquals(0, otherServlet.inits);
    }

    /** 서로 다른 두 요청을 순차 처리한 뒤 해제하면 인자가 섞이지 않고 init/service/destroy가 1/2/1인지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void servicesTwiceAndDestroysOnce() throws Exception {
        var servlet = new CountingServlet();
        var hierarchy = new Hierarchy(servlet);
        var first = new Request("/first");
        var second = new Request("/second");
        var firstResponse = new Response();
        var secondResponse = new Response();
        try {
            hierarchy.invoke(hierarchy.request(first), firstResponse);
            hierarchy.invoke(hierarchy.request(second), secondResponse);
            assertEquals(List.of(first, second), servlet.requests);
            assertEquals(List.of(firstResponse, secondResponse), servlet.responses);
            assertEquals("/first", firstResponse.body);
            assertEquals("/second", secondResponse.body);
            assertEquals(0, servlet.destroys);
        } finally {
            hierarchy.wrapper.unload();
        }
        assertEquals(1, servlet.inits);
        assertEquals(2, servlet.services);
        assertEquals(1, servlet.destroys);
    }

    /** 첫 서비스 실패 후 같은 인스턴스로 다음 요청이 성공하고 모든 대여 반환 후 해제되는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void failureThenNextRequestAndUnload() throws Exception {
        var servlet = new CountingServlet();
        var hierarchy = new Hierarchy(servlet);
        var failure = new ServletException("first");
        servlet.serviceFailure = failure;
        try {
            assertSame(failure, assertThrows(ServletException.class,
                    () -> hierarchy.invoke(hierarchy.request(new Request("/fail")), new Response())));
            servlet.serviceFailure = null;
            var response = new Response();
            hierarchy.invoke(hierarchy.request(new Request("/next")), response);
            assertEquals("/next", response.body);
            assertEquals(1, servlet.inits);
            assertEquals(2, servlet.services);
        } finally {
            hierarchy.wrapper.unload();
        }
        assertEquals(1, servlet.destroys);
    }

    /** 첫 초기화 실패 후 새 인스턴스로 재시도하고 실패 객체는 서비스·해제하지 않는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void retriesAfterInitializationFailure() throws Exception {
        var failed = new CountingServlet();
        failed.initFailure = new ServletException("init");
        var successful = new CountingServlet();
        var creations = new AtomicInteger();
        var hierarchy = new Hierarchy(new CountingServlet());
        var wrapper = new StandardWrapper(() -> creations.getAndIncrement() == 0 ? failed : successful);
        wrapper.setName("retry");
        hierarchy.context.addChild(wrapper);
        try {
            var request = new ContainerRequest(new Request("/retry"), hierarchy.host, hierarchy.context, wrapper);
            assertSame(failed.initFailure, assertThrows(ServletException.class,
                    () -> hierarchy.invoke(request, new Response())));
            var response = new Response();
            hierarchy.invoke(request, response);
            assertEquals("/retry", response.body);
            assertEquals(2, creations.get());
            assertEquals(0, failed.services);
            assertEquals(0, failed.destroys);
            assertEquals(1, successful.inits);
            assertEquals(1, successful.services);
        } finally {
            wrapper.unload();
        }
        assertEquals(1, successful.destroys);
    }
}
