package simpletomcat.catalina;

import simpletomcat.servlet.ServletRequest;
import java.util.Objects;

/**
 * 경로 검색 없이 이미 선택된 대상을 호출 계층에 전달한다. 요청마다 새로 생성한다.
 *
 * @param servletRequest 서블릿에 전달할 요청
 * @param host 선택된 Host, 매핑되지 않았으면 null
 * @param context 선택된 Context, 매핑되지 않았으면 null
 * @param wrapper 선택된 Wrapper, 매핑되지 않았으면 null
 */
public record ContainerRequest(ServletRequest servletRequest, Host host, Context context, Wrapper wrapper) {
    /**
     * 매핑 결과를 보관하며 서블릿 요청의 존재를 검증한다.
     *
     * @param servletRequest 서블릿 요청
     * @param host 선택된 Host 또는 null
     * @param context 선택된 Context 또는 null
     * @param wrapper 선택된 Wrapper 또는 null
     * @throws NullPointerException 서블릿 요청이 null인 경우
     */
    public ContainerRequest {
        Objects.requireNonNull(servletRequest, "servletRequest");
    }
}
