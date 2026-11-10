package simpletomcat.catalina.core;

import java.util.Locale;
import simpletomcat.catalina.*;

/** 대소문자를 정규화한 이름으로 Context들을 소유한다. */
public final class StandardHost extends ContainerBase implements Host {
    /**
     * Host 이름과 다음 계층으로 위임할 기본 Valve를 초기화한다.
     *
     * @param name null이 아닌 컨테이너 이름
     * @throws IllegalArgumentException 이름이 null인 경우
     */
    public StandardHost(String name) {
        super(name);
        installPipeline(new StandardHostValve(this));
    }
    /**
     * 영문 로케일 소문자로 정규화한 이름을 설정한다.
     *
     * @param name 정규화할 호스트 이름
     * @throws IllegalArgumentException 이름이 null인 경우
     * @throws IllegalStateException 등록된 이름을 변경하는 경우
     */
    @Override
    public void setName(String name) {
        super.setName(name == null ? null : name.toLowerCase(Locale.ENGLISH));
    }
    /**
     * Context 형식의 자식만 허용한다.
     *
     * @param child 자식 후보
     * @return 후보가 Context이면 true
     */
    @Override
    protected boolean acceptsChild(Container child) { return child instanceof Context; }
    /**
     * Engine 또는 미연결 상태만 허용한다.
     *
     * @param parent 부모 후보 또는 null
     * @return 허용된 부모이면 true
     */
    @Override
    protected boolean acceptsParent(Container parent) { return parent == null || parent instanceof Engine; }
}
