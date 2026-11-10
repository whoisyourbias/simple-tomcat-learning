package simpletomcat.catalina.core;

import simpletomcat.catalina.ContainerRequest;
import simpletomcat.catalina.valves.ValveBase;
import simpletomcat.servlet.Servlet;
import simpletomcat.servlet.ServletException;
import simpletomcat.servlet.ServletRequest;
import simpletomcat.servlet.ServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 컨테이너 테스트에서 호출 횟수·전달 인자·Valve 순서를 관찰하는 테스트 대역을 제공한다. */
final class CatalinaTestSupport {
    /** 경로만 보관하고 GET 메서드와 빈 헤더·쿼리를 제공하는 요청 대역이다.
     * @param getRequestURI 원본 요청 경로 */
    record Request(String getRequestURI) implements ServletRequest {
        /**
         * 모든 테스트 요청에 고정 GET 메서드를 제공한다.
         * @return 요청 메서드
         */
        public String getMethod() { return "GET"; }
        /**
         * 대역이 보관한 요청 경로를 가공 없이 반환한다.
         * @return 보관한 요청 경로
         */
        public String getRequestURI() { return getRequestURI; }
        /**
         * 쿼리가 없는 요청을 나타내도록 항상 null을 반환한다.
         * @return 쿼리 문자열 또는 null
         */
        public String getQueryString() { return null; }
        /**
         * 헤더가 없는 요청을 나타내도록 이름과 관계없이 null을 반환한다.
         * @param name 조회할 헤더 이름
         * @return 지원하는 헤더 값 또는 null
         */
        public String getHeader(String name) { return null; }
    }

    /** 서블릿 또는 Valve가 설정한 상태·본문·콘텐츠 유형을 보관하는 응답 대역이다. */
    static final class Response implements ServletResponse {
        /** 응답에 설정된 상태 값이며 초기값은 200이다. */
        int status = 200;
        /** 응답에 마지막으로 설정된 본문 문자열이다. */
        String body = "";
        /** 응답에 마지막으로 설정된 콘텐츠 유형이다. */
        String contentType;
        /**
         * 서비스 또는 Valve가 지정한 상태 값을 관찰 가능한 필드에 저장한다.
         * @param status 설정할 응답 상태 코드
         */
        public void setStatus(int status) { this.status = status; }
        /**
         * 서비스 또는 Valve가 지정한 본문을 관찰 가능한 필드에 저장한다.
         * @param body 응답에 보관할 본문
         */
        public void setBody(String body) { this.body = body; }
        /**
         * 서비스가 지정한 콘텐츠 유형을 관찰 가능한 필드에 저장한다.
         * @param contentType 응답에 보관할 콘텐츠 유형
         */
        public void setContentType(String contentType) { this.contentType = contentType; }
    }

    /** init·service·destroy 횟수와 전달 인자를 기록하고 설정된 실패를 재현한다. */
    static class CountingServlet implements Servlet {
        /** 초기화 시도 횟수로, 실패하는 호출도 포함한다. */
        int inits;
        /** 서비스 시도 횟수로, 실패하는 호출도 포함한다. */
        int services;
        /** 서블릿 해제 콜백 호출 횟수이다. */
        int destroys;
        /** 서비스에서 재현할 예외 또는 Error이며 null이면 정상 응답한다. */
        Throwable serviceFailure;
        /** 초기화에서 재현할 검사 예외이며 null이면 정상 초기화한다. */
        ServletException initFailure;
        /** 서비스에 전달된 요청 참조를 호출 순서대로 보관한다. */
        final List<ServletRequest> requests = new ArrayList<>();
        /** 서비스에 전달된 응답 참조를 호출 순서대로 보관한다. */
        final List<ServletResponse> responses = new ArrayList<>();
        /**
         * 초기화 시도 횟수를 늘리고 설정된 초기화 예외가 있으면 같은 인스턴스를 던진다.
         * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
         */
        public void init() throws ServletException { inits++; if (initFailure != null) throw initFailure; }
        /**
         * 서비스 횟수와 요청·응답 참조를 기록한 뒤 설정된 실패를 재현하며, 정상일 때 요청 경로를 본문에 쓴다.
         * @param request 하위 구성 요소에 전달할 요청
         * @param response 전달하거나 검사할 응답
         * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
         * @throws IOException 요청·응답 입출력 또는 테스트가 주입한 I/O 실패가 발생한 경우
         */
        public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
            services++;
            requests.add(request);
            responses.add(response);
            fail(serviceFailure);
            response.setBody(request.getRequestURI());
        }
        /**
         * 해제 콜백 호출 횟수를 한 번 늘린다.
         */
        public void destroy() { destroys++; }
    }

    /**
     * 설정된 검사 예외·실행 예외·Error를 같은 인스턴스로 다시 던져 실패 경로를 재현한다.
     * @param failure 재현할 예외 또는 Error; null이면 정상 진행
     * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
     * @throws IOException 요청·응답 입출력 또는 테스트가 주입한 I/O 실패가 발생한 경우
     */
    static void fail(Throwable failure) throws ServletException, IOException {
        if (failure instanceof ServletException e) throw e;
        if (failure instanceof IOException e) throw e;
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
    }

    /** 진입·복귀 순서와 전달 객체를 기록하며 다음 Valve 호출 여부를 제어한다. */
    static class TraceValve extends ValveBase {
        /** 진입·복귀 기록에서 이 Valve를 식별하는 이름이다. */
        final String name;
        /** Valve 진입·복귀 또는 서비스 이벤트를 발생 순서대로 누적하는 기록이다. */
        final List<String> trace;
        /** true일 때만 다음 Valve가 있으면 호출하여 체인 중단을 제어한다. */
        boolean proceed = true;
        /** 가장 최근 Valve 호출에 전달된 컨테이너 요청의 참조이다. */
        ContainerRequest seenRequest;
        /** 가장 최근 Valve 호출에 전달된 응답의 참조이다. */
        ServletResponse seenResponse;
        /**
         * 기록에 사용할 이름과 공유 이벤트 목록을 연결한다.
         * @param name 진입·복귀 기록에서 사용할 Valve 이름
         * @param trace 호출 순서를 누적할 공유 목록
         */
        TraceValve(String name, List<String> trace) { this.name = name; this.trace = trace; }
        /**
         * 전달 인자를 보관하고 진입을 기록한 뒤 허용된 경우 다음 Valve를 호출하며, 정상 복귀 시 복귀를 기록한다.
         * @param request 하위 구성 요소에 전달할 요청
         * @param response 전달하거나 검사할 응답
         * @throws ServletException 테스트가 주입한 서블릿 생명주기·서비스 실패가 발생한 경우
         * @throws IOException 요청·응답 입출력 또는 테스트가 주입한 I/O 실패가 발생한 경우
         */
        public void invoke(ContainerRequest request, ServletResponse response) throws ServletException, IOException {
            seenRequest = request;
            seenResponse = response;
            trace.add(name + " 전");
            if (proceed && getNext() != null) getNext().invoke(request, response);
            trace.add(name + " 후");
        }
    }
}
