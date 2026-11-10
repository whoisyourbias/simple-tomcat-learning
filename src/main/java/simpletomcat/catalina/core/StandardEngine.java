package simpletomcat.catalina.core;

import simpletomcat.catalina.*;

/** Host만 등록하는 루트 컨테이너. */
public final class StandardEngine extends ContainerBase implements Engine {
    /**
     * Engine 이름과 다음 계층으로 위임할 기본 Valve를 초기화한다.
     *
     * @param name null이 아닌 컨테이너 이름
     * @throws IllegalArgumentException 이름이 null인 경우
     */
    public StandardEngine(String name) {
        super(name);
        installPipeline(new StandardEngineValve(this));
    }
    /**
     * Host 형식의 자식만 허용한다.
     *
     * @param child 자식 후보
     * @return 후보가 Host이면 true
     */
    @Override
    protected boolean acceptsChild(Container child) { return child instanceof Host; }
    /**
     * 루트 컨테이너이므로 null을 포함한 모든 부모 설정을 거부한다.
     *
     * @param parent 부모 후보 또는 null
     * @return 항상 false
     */
    @Override
    protected boolean acceptsParent(Container parent) { return false; }
}
