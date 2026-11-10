package simpletomcat.catalina.core;

import org.junit.jupiter.api.Test;
import simpletomcat.catalina.Wrapper;
import simpletomcat.servlet.Servlet;
import simpletomcat.servlet.ServletException;
import simpletomcat.servlet.ServletRequest;
import simpletomcat.servlet.ServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 서블릿 지연 초기화, 대여 수 추적, 실패 후 재시도와 해제의 생명주기 계약을 검증한다. */
class StandardWrapperTest {
    /** 서블릿 생명주기와 실패 이벤트를 발생 순서대로 기록한다. */
    private final List<String> events = new ArrayList<>();
    /** 테스트 호출에서 동일성 또는 요청 내용 전달을 확인할 기준 요청이다. */
    private final TestRequest request = new TestRequest("GET", "/hello", "name=한글");

    /** 두 요청을 같은 서블릿에 전달하고 두 번 해제해도 init/service/destroy 순서와 횟수가 1/2/1인지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void servicesTwiceAndDestroysOnce() throws Exception {
        RecordingServlet servlet = new RecordingServlet();
        Wrapper wrapper = new StandardWrapper(() -> servlet);
        TestResponse first = new TestResponse();
        TestResponse second = new TestResponse();

        call(wrapper, first);
        call(wrapper, second);
        wrapper.unload();
        wrapper.unload();

        assertEquals(List.of("init", "service", "service", "destroy"), events);
        assertSame(request, servlet.lastRequest);
        assertSame(second, servlet.lastResponse);
        assertEquals(201, first.status);
        assertEquals("text/plain; charset=UTF-8", first.contentType);
        assertEquals("GET /hello?name=한글 header=value", first.body);
        assertEquals(first.body, second.body);
    }

    /** 생성 직후에는 팩터리·init이 실행되지 않고, 연속 할당은 동일 객체를 반환하며 초기화는 한 번인지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void initializesOnceAndReusesServlet() throws Exception {
        AtomicInteger created = new AtomicInteger();
        StandardWrapper wrapper = new StandardWrapper(() -> {
            created.incrementAndGet();
            return new RecordingServlet();
        });
        assertEquals(0, created.get());
        assertTrue(events.isEmpty());

        Servlet first = wrapper.allocate();
        wrapper.deallocate(first);
        Servlet second = wrapper.allocate();
        assertSame(first, second);
        assertEquals(1, created.get());
        assertEquals(List.of("init"), events);
        wrapper.deallocate(second);
        wrapper.unload();
    }

    /** 할당 전 unload를 반복해도 팩터리와 생명주기 콜백은 실행되지 않는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void unloadBeforeAllocationDoesNothing() throws Exception {
        StandardWrapper wrapper = new StandardWrapper(() -> {
            fail("미사용 서블릿을 생성하면 안 된다");
            return null;
        });
        wrapper.unload();
        wrapper.unload();
        assertTrue(events.isEmpty());
    }

    /** init 실패 시 service·destroy를 호출하지 않고 다음 할당에서 새 객체를 초기화하는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void retriesWithFreshInstanceAfterInitFailure() throws Exception {
        ServletException failure = new ServletException("init failed");
        AtomicInteger created = new AtomicInteger();
        StandardWrapper wrapper = new StandardWrapper(() -> {
            if (created.incrementAndGet() == 1) {
                return new RecordingServlet() {
                    /**
                     * 초기화 실패 이벤트를 기록하고 준비한 검사 예외를 그대로 던진다.
                     * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
                     */
                    @Override public void init() throws ServletException {
                        events.add("failed init");
                        throw failure;
                    }
                };
            }
            return new RecordingServlet();
        });

        assertSame(failure, assertThrows(ServletException.class, wrapper::allocate));
        wrapper.unload();
        assertEquals(List.of("failed init"), events);
        call(wrapper, new TestResponse());
        wrapper.unload();
        assertEquals(2, created.get());
        assertEquals(List.of("failed init", "init", "service", "destroy"), events);
    }

    /** 생성 팩터리의 실행 예외를 원인 보존한 ServletException으로 전달하고 다음 생성은 성공하는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void retriesAfterFactoryFailure() throws Exception {
        RuntimeException failure = new IllegalStateException("creation failed");
        AtomicInteger attempts = new AtomicInteger();
        StandardWrapper wrapper = new StandardWrapper(() -> {
            if (attempts.incrementAndGet() == 1) throw failure;
            return new RecordingServlet();
        });
        assertSame(failure, assertThrows(ServletException.class, wrapper::allocate).getCause());
        call(wrapper, new TestResponse());
        wrapper.unload();
        assertEquals(2, attempts.get());
        assertEquals(List.of("init", "service", "destroy"), events);
    }

    /** null 팩터리는 즉시 거부하고 null 생성 결과는 초기화 없이 ServletException으로 전달하는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void rejectsNullFactoryAndNullServlet() throws Exception {
        assertThrows(NullPointerException.class, () -> new StandardWrapper(null));
        StandardWrapper wrapper = new StandardWrapper(() -> null);
        assertThrows(ServletException.class, wrapper::allocate);
        wrapper.unload();
        assertTrue(events.isEmpty());
    }

    /** init의 실행 예외는 원인을 보존하며 실패한 객체에 destroy를 호출하지 않는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void wrapsRuntimeInitFailureWithoutDestroy() throws Exception {
        RuntimeException failure = new IllegalArgumentException("init");
        StandardWrapper wrapper = new StandardWrapper(() -> new RecordingServlet() {
            /**
             * 준비한 실행 예외를 던져 초기화 실패 시 예외 변환과 해제 생략을 검증한다.
             */
            @Override public void init() { throw failure; }
        });
        assertSame(failure, assertThrows(ServletException.class, wrapper::allocate).getCause());
        wrapper.unload();
        assertTrue(events.isEmpty());
    }

    /** 첫 service의 ServletException 후 finally 반환하면 다음 요청은 재초기화 없이 성공하고 정상 해제되는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void serviceFailureDoesNotPoisonNextRequest() throws Exception {
        ServletException failure = new ServletException("service failed");
        StandardWrapper wrapper = new StandardWrapper(() -> new RecordingServlet() {
            /** 첫 서비스 호출에만 실패를 주입하기 위한 일회성 상태이다. */
            private boolean first = true;
            /**
             * 첫 호출에만 실패 이벤트와 예외를 만들고 이후 호출은 부모의 정상 서비스로 위임한다.
             * @param req 서비스에 전달된 원본 요청
             * @param res 서비스 결과를 기록할 응답
             * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
             * @throws IOException 요청·응답 입출력 또는 테스트가 주입한 I/O 실패가 발생한 경우
             */
            @Override public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
                if (first) {
                    first = false;
                    events.add("failed service");
                    throw failure;
                }
                super.service(req, res);
            }
        });
        assertSame(failure, assertThrows(ServletException.class, () -> call(wrapper, new TestResponse())));
        TestResponse response = new TestResponse();
        call(wrapper, response);
        wrapper.unload();
        assertEquals(201, response.status);
        assertEquals(List.of("init", "failed service", "service", "destroy"), events);
    }

    /** service의 IOException도 원래 예외로 전달하며 finally 반환 후 destroy가 실행되는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void ioFailureStillReturnsAllocation() throws Exception {
        IOException failure = new IOException("output failed");
        StandardWrapper wrapper = new StandardWrapper(() -> new RecordingServlet() {
            /**
             * 출력 실패를 나타내는 IOException을 던져 서비스 실패 후 대여 반환을 관찰한다.
             * @param req 서비스에 전달된 원본 요청
             * @param res 서비스 결과를 기록할 응답
             * @throws IOException 요청·응답 입출력 또는 테스트가 주입한 I/O 실패가 발생한 경우
             */
            @Override public void service(ServletRequest req, ServletResponse res) throws IOException {
                throw failure;
            }
        });
        assertSame(failure, assertThrows(IOException.class, () -> call(wrapper, new TestResponse())));
        wrapper.unload();
        assertEquals(List.of("init", "destroy"), events);
    }

    /** 대여 중 unload는 거부하고 destroy하지 않으며 반환 후에는 한 번 해제하는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void refusesUnloadWhileAllocated() throws Exception {
        StandardWrapper wrapper = new StandardWrapper(RecordingServlet::new);
        Servlet servlet = wrapper.allocate();
        assertThrows(IllegalStateException.class, wrapper::unload);
        assertEquals(List.of("init"), events);
        wrapper.deallocate(servlet);
        wrapper.unload();
        assertEquals(List.of("init", "destroy"), events);
    }

    /** 같은 객체를 두 번 대여하면 한 번 반환만으로 종료할 수 없고 두 번 반환 뒤 해제되는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void tracksMultipleAllocations() throws Exception {
        StandardWrapper wrapper = new StandardWrapper(RecordingServlet::new);
        Servlet first = wrapper.allocate();
        Servlet second = wrapper.allocate();
        assertSame(first, second);
        wrapper.deallocate(first);
        assertThrows(IllegalStateException.class, wrapper::unload);
        wrapper.deallocate(second);
        wrapper.unload();
        assertEquals(List.of("init", "destroy"), events);
    }

    /** null·미대여·다른 객체 반환을 거부하고 정상 대여 수가 손상되지 않는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void rejectsInvalidDeallocation() throws Exception {
        StandardWrapper wrapper = new StandardWrapper(RecordingServlet::new);
        assertThrows(NullPointerException.class, () -> wrapper.deallocate(null));
        assertThrows(IllegalArgumentException.class, () -> wrapper.deallocate(new RecordingServlet()));
        Servlet servlet = wrapper.allocate();
        assertThrows(IllegalArgumentException.class, () -> wrapper.deallocate(new RecordingServlet()));
        assertThrows(IllegalStateException.class, wrapper::unload);
        wrapper.deallocate(servlet);
        assertThrows(IllegalArgumentException.class, () -> wrapper.deallocate(servlet));
        wrapper.unload();
        assertEquals(List.of("init", "destroy"), events);
    }

    /** 정상 unload 후 다시 할당하면 새 객체를 생성·초기화하고 각 객체를 한 번씩 해제하는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void reloadsAfterUnload() throws Exception {
        StandardWrapper wrapper = new StandardWrapper(RecordingServlet::new);
        Servlet first = wrapper.allocate();
        wrapper.deallocate(first);
        wrapper.unload();
        Servlet second = wrapper.allocate();
        assertNotSame(first, second);
        wrapper.deallocate(second);
        wrapper.unload();
        assertEquals(List.of("init", "destroy", "init", "destroy"), events);
    }

    /** destroy 실패도 원인을 보존하고 참조를 해제해 반복 unload는 재호출하지 않으며 새 할당이 가능한지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void destroyFailureStillReleasesInstance() throws Exception {
        RuntimeException failure = new IllegalStateException("destroy failed");
        AtomicInteger created = new AtomicInteger();
        StandardWrapper wrapper = new StandardWrapper(() -> created.incrementAndGet() == 1
                ? new RecordingServlet() {
                    /**
                     * 해제 실패 이벤트를 기록한 뒤 준비한 실행 예외를 던진다.
                     */
                    @Override public void destroy() { events.add("failed destroy"); throw failure; }
                } : new RecordingServlet());
        call(wrapper, new TestResponse());
        assertSame(failure, assertThrows(ServletException.class, wrapper::unload).getCause());
        wrapper.unload();
        call(wrapper, new TestResponse());
        wrapper.unload();
        assertEquals(2, created.get());
        assertEquals(List.of("init", "service", "failed destroy", "init", "service", "destroy"), events);
    }

    /** init 콜백에서 재할당·해제를 시도하면 거부하되 정상 초기화와 이후 해제는 가능한지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void rejectsLifecycleReentryDuringInit() throws Exception {
        StandardWrapper[] holder = new StandardWrapper[1];
        holder[0] = new StandardWrapper(() -> new RecordingServlet() {
            /**
             * 초기화 중 재할당과 해제가 거부됨을 확인한 뒤 정상 초기화 이벤트를 기록한다.
             * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
             */
            @Override public void init() throws ServletException {
                assertThrows(IllegalStateException.class, holder[0]::allocate);
                assertThrows(IllegalStateException.class, holder[0]::unload);
                super.init();
            }
        });
        call(holder[0], new TestResponse());
        holder[0].unload();
        assertEquals(List.of("init", "service", "destroy"), events);
    }

    /** destroy 중 재할당은 거부하고 중첩 unload는 destroy를 반복하지 않는지 검증한다.
     * @throws Exception 테스트 준비·요청 처리·자원 정리 중 예상하지 않은 실패가 발생한 경우
     */
    @Test
    void preventsAllocationDuringDestroy() throws Exception {
        StandardWrapper[] holder = new StandardWrapper[1];
        holder[0] = new StandardWrapper(() -> new RecordingServlet() {
            /**
             * 해제 중 할당 거부와 중첩 해제의 멱등성을 확인한 뒤 정상 해제 이벤트를 기록한다.
             */
            @Override public void destroy() {
                assertThrows(IllegalStateException.class, holder[0]::allocate);
                assertDoesNotThrow(holder[0]::unload);
                super.destroy();
            }
        });
        call(holder[0], new TestResponse());
        holder[0].unload();
        assertEquals(List.of("init", "service", "destroy"), events);
    }

    /**
     * 서블릿을 대여해 기준 요청으로 서비스하며 성공·실패와 관계없이 finally에서 반환한다.
     * @param wrapper 서블릿을 대여하고 반환할 소유자
     * @param response 전달하거나 검사할 응답
     * @throws Exception 서버 종료 대기 또는 테스트 호출이 실패한 경우
     */
    private void call(Wrapper wrapper, ServletResponse response) throws Exception {
        Servlet servlet = wrapper.allocate();
        try {
            servlet.service(request, response);
        } finally {
            wrapper.deallocate(servlet);
        }
    }

    /** 생명주기 이벤트와 마지막 서비스 인자를 기록하고 요청 정보로 응답을 채운다. */
    private class RecordingServlet implements Servlet {
        /** 마지막으로 서비스에 전달된 원본 요청의 참조이다. */
        ServletRequest lastRequest;
        /** 마지막으로 서비스에 전달된 응답의 참조이다. */
        ServletResponse lastResponse;
        /**
         * 정상 초기화 이벤트를 공유 목록에 추가한다.
         * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
         */
        @Override public void init() throws ServletException { events.add("init"); }
        /**
         * 서비스 이벤트와 마지막 인자를 보관하고 상태 201·텍스트 유형 및 요청 정보 본문을 설정한다.
         * @param req 서비스에 전달된 원본 요청
         * @param res 서비스 결과를 기록할 응답
         * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
         * @throws IOException 요청·응답 입출력 또는 테스트가 주입한 I/O 실패가 발생한 경우
         */
        @Override public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
            events.add("service");
            lastRequest = req;
            lastResponse = res;
            res.setStatus(201);
            res.setContentType("text/plain; charset=UTF-8");
            res.setBody(req.getMethod() + " " + req.getRequestURI() + "?" + req.getQueryString()
                    + " header=" + req.getHeader("X-Test"));
        }
        /**
         * 정상 해제 이벤트를 공유 목록에 추가한다.
         */
        @Override public void destroy() { events.add("destroy"); }
    }

    /** 메서드·경로·쿼리를 보관하고 X-Test 헤더만 제공하는 불변 요청 대역이다.
     * @param method 요청 메서드
     * @param requestURI 요청 경로
     * @param queryString 요청 쿼리; 없으면 null */
    private record TestRequest(String method, String requestURI, String queryString) implements ServletRequest {
        /**
         * 생성 시 보관한 요청 메서드를 반환한다.
         * @return 요청 메서드
         */
        @Override public String getMethod() { return method; }
        /**
         * 대역이 보관한 요청 경로를 가공 없이 반환한다.
         * @return 보관한 요청 경로
         */
        @Override public String getRequestURI() { return requestURI; }
        /**
         * 생성 시 보관한 쿼리 문자열을 반환한다.
         * @return 쿼리 문자열 또는 null
         */
        @Override public String getQueryString() { return queryString; }
        /**
         * X-Test 이름을 대소문자 구분 없이 조회하면 value를 반환하고 다른 이름에는 null을 반환한다.
         * @param name 조회할 헤더 이름
         * @return 지원하는 헤더 값 또는 null
         */
        @Override public String getHeader(String name) { return "X-Test".equalsIgnoreCase(name) ? "value" : null; }
    }

    /** 서비스가 설정한 상태·콘텐츠 유형·본문을 관찰하기 위한 응답 대역이다. */
    private static class TestResponse implements ServletResponse {
        /** 응답에 설정된 상태 값이며 초기값은 200이다. */
        int status = 200;
        /** 응답에 마지막으로 설정된 콘텐츠 유형이다. */
        String contentType;
        /** 응답에 마지막으로 설정된 본문 문자열이다. */
        String body;
        /**
         * 서비스 또는 Valve가 지정한 상태 값을 관찰 가능한 필드에 저장한다.
         * @param status 설정할 응답 상태 코드
         */
        @Override public void setStatus(int status) { this.status = status; }
        /**
         * 서비스가 지정한 콘텐츠 유형을 관찰 가능한 필드에 저장한다.
         * @param contentType 응답에 보관할 콘텐츠 유형
         */
        @Override public void setContentType(String contentType) { this.contentType = contentType; }
        /**
         * 서비스 또는 Valve가 지정한 본문을 관찰 가능한 필드에 저장한다.
         * @param body 응답에 보관할 본문
         */
        @Override public void setBody(String body) { this.body = body; }
    }
}
