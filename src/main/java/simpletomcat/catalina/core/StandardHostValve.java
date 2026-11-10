package simpletomcat.catalina.core;

import java.io.IOException;
import java.util.Objects;
import simpletomcat.catalina.*;
import simpletomcat.catalina.valves.ValveBase;
import simpletomcat.servlet.*;

/** 미리 선택된 Context의 Pipeline에 위임한다. */
public final class StandardHostValve extends ValveBase {
    /**
     * 이 기본 Valve가 소속된 Host 컨테이너.
     */
    private final Host owner;
    /**
     * 위임 대상의 소속을 검증할 소유 컨테이너를 지정한다.
     *
     * @param owner 소유 컨테이너
     * @throws NullPointerException 소유 컨테이너가 null인 경우
     */
    public StandardHostValve(Host owner) { this.owner = Objects.requireNonNull(owner, "owner"); }
    /**
     * 선택된 Context가 없으면 빈 404 응답을 기록하고, 있으면 부모를 검증하여 위임한다.
     *
     * @param request 미리 매핑된 요청
     * @param response 처리 결과를 기록할 응답
     * @throws IOException 하위 처리의 입출력이 실패한 경우
     * @throws ServletException 하위 서블릿 생성 또는 처리가 실패한 경우
     * @throws IllegalArgumentException 선택된 대상의 소속이 일치하지 않는 경우
     */
    @Override
    public void invoke(ContainerRequest request, ServletResponse response) throws IOException, ServletException {
        Context target = request.context();
        if (target == null) {
            response.setStatus(404);
            response.setBody("");
            return;
        }
        if (target.getParent() != owner) { throw new IllegalArgumentException("Context belongs to another host"); }
        target.getPipeline().getFirst().invoke(request, response);
    }
}
