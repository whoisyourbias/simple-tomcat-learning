package simpletomcat.catalina.core;

import java.io.IOException;
import java.util.Objects;
import simpletomcat.catalina.*;
import simpletomcat.catalina.valves.ValveBase;
import simpletomcat.servlet.*;

/** 요청별 서블릿 대여·필터 체인 호출·반환을 소유한다. HTTP 오류 변환은 Processor 책임이다. */
public final class StandardWrapperValve extends ValveBase {
    /**
     * 이 기본 Valve가 소속된 Wrapper 컨테이너.
     */
    private final Wrapper owner;
    /**
     * 위임 대상의 소속을 검증할 소유 컨테이너를 지정한다.
     *
     * @param owner 소유 컨테이너
     * @throws NullPointerException 소유 컨테이너가 null인 경우
     */
    public StandardWrapperValve(Wrapper owner) { this.owner = Objects.requireNonNull(owner, "owner"); }
    /**
     * 선택 Wrapper를 검증하고 서블릿을 대여하여 호출한 뒤 반드시 반환한다. 반환 실패는 기존 호출 실패에 억제 예외로 첨부한다.
     *
     * @param request 미리 매핑된 요청
     * @param response 처리 결과를 기록할 응답
     * @throws IOException 하위 처리의 입출력이 실패한 경우
     * @throws ServletException 하위 서블릿 생성 또는 처리가 실패한 경우
     * @throws IllegalArgumentException 선택된 대상의 소속이 일치하지 않는 경우
     */
    @Override
    public void invoke(ContainerRequest request, ServletResponse response) throws IOException, ServletException {
        if (request.wrapper() != owner) { throw new IllegalArgumentException("Selected wrapper differs from owner"); }
        if (request.context() != owner.getParent()) {
            throw new IllegalArgumentException("Selected context differs from wrapper parent");
        }
        Servlet servlet = owner.allocate();
        Throwable serviceFailure = null;
        try {
            var filters = request.context() == null ? java.util.List.<Filter>of() : request.context().getFilters();
            new ApplicationFilterChain(filters, servlet).doFilter(request.servletRequest(), response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            serviceFailure = failure;
            throw failure;
        } finally {
            try {
                owner.deallocate(servlet);
            } catch (RuntimeException | Error returnFailure) {
                if (serviceFailure == null) { throw returnFailure; }
                // 동일 예외 객체는 자기 자신에 suppressed로 추가할 수 없다.
                if (returnFailure != serviceFailure) { serviceFailure.addSuppressed(returnFailure); }
            }
        }
    }
}
