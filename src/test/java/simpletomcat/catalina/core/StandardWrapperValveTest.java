package simpletomcat.catalina.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import simpletomcat.catalina.ContainerRequest;
import simpletomcat.catalina.Wrapper;
import simpletomcat.servlet.Servlet;
import simpletomcat.servlet.ServletException;
import simpletomcat.servlet.ServletRequest;
import simpletomcat.servlet.ServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static simpletomcat.catalina.core.CatalinaTestSupport.*;

/** Wrapper Valve의 할당·서비스·반환 순서와 중첩 오류 보존을 검증한다. */
class StandardWrapperValveTest {
    /** 호출 순서와 할당·반환 실패를 제어하여 Valve의 자원 반환 책임을 관찰한다. */
    static final class StubWrapper extends ContainerBase implements Wrapper {
        /** 할당·서비스·반환이 실행된 순서를 기록한다. */
        final List<String> calls = new ArrayList<>();
        /** 서비스 인자와 호출 횟수를 기록하는 단일 서블릿 대역이다. */
        final CountingServlet servlet = new CountingServlet() {
            /**
             * Wrapper 호출 추적에 service를 기록하고 부모 대역으로 요청·응답을 전달한다.
             * @param request 하위 구성 요소에 전달할 요청
             * @param response 전달하거나 검사할 응답
             * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
             * @throws IOException 요청·응답 입출력 또는 테스트가 주입한 I/O 실패가 발생한 경우
             */
            public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
                calls.add("service");
                super.service(request, response);
            }
        };
        /** 설정하면 할당 단계에서 전달할 예외이며 null이면 정상 할당한다. */
        ServletException allocationFailure;
        /** 설정하면 반환 기록 후 던질 실행 예외이며 null이면 정상 반환한다. */
        RuntimeException returnFailure;
        /**
         * 고정 이름의 Wrapper 대역에 검증 대상 기본 Valve를 설치한다.
         */
        StubWrapper() {
            super("stub");
            installPipeline(new StandardWrapperValve(this));
        }
        /**
         * 할당 이벤트를 기록한 뒤 설정된 예외를 던지거나 테스트 서블릿을 반환한다.
         * @return 할당한 단일 테스트 서블릿
         * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
         */
        public Servlet allocate() throws ServletException {
            calls.add("allocate");
            if (allocationFailure != null) throw allocationFailure;
            return servlet;
        }
        /**
         * 반환 객체의 동일성을 확인하고 반환 이벤트를 기록한 뒤 설정된 반환 실패를 재현한다.
         * @param servlet 계층에 등록하거나 반환할 서블릿
         */
        public void deallocate(Servlet servlet) {
            assertSame(this.servlet, servlet);
            calls.add("deallocate");
            if (returnFailure != null) throw returnFailure;
        }
        /**
         * Valve 검증에 필요하지 않은 실제 서블릿 해제를 생략한다.
         */
        public void unload() { }
    }

    /**
     * 서비스 실패 시에도 반환해야 하는 검사 예외·실행 예외·Error 사례를 제공한다.
     * @return 서비스 실패를 대표하는 예외 스트림
     */
    static Stream<Throwable> serviceFailures() {
        return Stream.of(new ServletException("servlet"), new IOException("io"),
                new IllegalStateException("runtime"), new AssertionError("error"));
    }

    /** 정상 서비스에서 할당·서비스·반환이 각 한 번 실행되고 원본 요청과 응답이 전달되는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void servicesAndReturns() throws Exception {
        var wrapper = new StubWrapper();
        var request = new Request("/hello");
        var response = new Response();
        wrapper.getPipeline().getFirst().invoke(new ContainerRequest(request, null, null, wrapper), response);
        assertEquals(List.of("allocate", "service", "deallocate"), wrapper.calls);
        assertEquals(1, wrapper.servlet.services);
        assertSame(request, wrapper.servlet.requests.getFirst());
        assertSame(response, wrapper.servlet.responses.getFirst());
        assertEquals("/hello", response.body);
    }

    /** 서비스의 검사 예외·실행 예외·Error 각각에서 동일 예외가 전파되고 대여를 한 번 반환하는지 검증한다.
     * @param failure 재현할 예외 또는 Error; null이면 정상 진행
     */
    @ParameterizedTest
    @MethodSource("serviceFailures")
    void returnsAfterServiceFailure(Throwable failure) {
        var wrapper = new StubWrapper();
        wrapper.servlet.serviceFailure = failure;
        Throwable actual = assertThrows(Throwable.class, () -> wrapper.getPipeline().getFirst().invoke(
                new ContainerRequest(new Request("/"), null, null, wrapper), new Response()));
        assertSame(failure, actual);
        assertEquals(List.of("allocate", "service", "deallocate"), wrapper.calls);
    }

    /** 할당이 실패하면 동일 예외를 전달하고 서비스·반환을 수행하지 않는지 검증한다. */
    @Test
    void doesNotReturnAfterAllocationFailure() {
        var wrapper = new StubWrapper();
        wrapper.allocationFailure = new ServletException("allocate");
        var actual = assertThrows(ServletException.class, () -> wrapper.getPipeline().getFirst().invoke(
                new ContainerRequest(new Request("/"), null, null, wrapper), new Response()));
        assertSame(wrapper.allocationFailure, actual);
        assertEquals(List.of("allocate"), wrapper.calls);
        assertEquals(0, wrapper.servlet.services);
    }

    /** 서비스와 반환이 모두 실패하면 서비스 예외를 유지하고 반환 예외를 suppressed로 보존하는지 검증한다. */
    @Test
    void preservesServiceFailureWhenReturnAlsoFails() {
        var wrapper = new StubWrapper();
        var failure = new ServletException("service");
        wrapper.servlet.serviceFailure = failure;
        wrapper.returnFailure = new IllegalStateException("return");
        var actual = assertThrows(ServletException.class, () -> wrapper.getPipeline().getFirst().invoke(
                new ContainerRequest(new Request("/"), null, null, wrapper), new Response()));
        assertSame(failure, actual);
        assertArrayEquals(new Throwable[]{wrapper.returnFailure}, actual.getSuppressed());
        assertEquals(List.of("allocate", "service", "deallocate"), wrapper.calls);
    }

    /** 서비스 성공 후 반환만 실패하면 반환 예외 자체가 전파되고 중복 반환이 없는지 검증한다. */
    @Test
    void propagatesReturnFailureAfterSuccessfulService() {
        var wrapper = new StubWrapper();
        wrapper.returnFailure = new IllegalStateException("return");
        var actual = assertThrows(IllegalStateException.class, () -> wrapper.getPipeline().getFirst().invoke(
                new ContainerRequest(new Request("/"), null, null, wrapper), new Response()));
        assertSame(wrapper.returnFailure, actual);
        assertEquals(List.of("allocate", "service", "deallocate"), wrapper.calls);
    }

    /** 다른 Wrapper를 선택한 전달 객체를 거부하고 어느 Wrapper도 할당하지 않는지 검증한다. */
    @Test
    void rejectsDifferentSelectedWrapper() {
        var wrapper = new StubWrapper();
        var other = new StubWrapper();
        assertThrows(IllegalArgumentException.class, () -> wrapper.getPipeline().getFirst().invoke(
                new ContainerRequest(new Request("/"), null, null, other), new Response()));
        assertEquals(List.of(), wrapper.calls);
        assertEquals(List.of(), other.calls);
    }

    /** 원본 서블릿 요청이 null이면 생성 시 거부하고 선택 대상의 null은 허용하는지 검증한다. */
    @Test
    void rejectsNullServletRequest() {
        assertThrows(NullPointerException.class, () -> new ContainerRequest(null, null, null, null));
        var request = new ContainerRequest(new Request("/"), null, null, null);
        assertNull(request.host());
        assertNull(request.context());
        assertNull(request.wrapper());
    }
}
