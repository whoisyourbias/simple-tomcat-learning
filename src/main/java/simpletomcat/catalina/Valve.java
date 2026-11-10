package simpletomcat.catalina;

import simpletomcat.servlet.ServletException;
import simpletomcat.servlet.ServletResponse;

import java.io.IOException;

/** next 호출 여부와 전후 처리를 담당하는 컨테이너 호출 요소. */
public interface Valve extends Freezable {
    /**
     * 체인에서 다음 호출 대상으로 설정된 Valve를 반환한다.
     *
     * @return 다음 Valve, 마지막이면 null
     */
    Valve getNext();

    /**
     * 다음 호출 대상을 설정한다. 실제 호출 여부는 각 Valve가 결정한다.
     *
     * @param valve 다음 Valve 또는 null
     */
    void setNext(Valve valve);

    /**
     * 요청을 처리하거나 선택된 다음 처리 요소로 위임한다.
     *
     * @param request 요청과 미리 선택한 컨테이너
     * @param response 기록할 응답
     * @throws IOException 입출력 처리가 실패한 경우
     * @throws ServletException 서블릿 초기화 또는 처리가 실패한 경우
     */
    void invoke(ContainerRequest request, ServletResponse response)
            throws IOException, ServletException;
}
