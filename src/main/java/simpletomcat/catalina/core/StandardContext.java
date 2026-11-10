package simpletomcat.catalina.core;

import simpletomcat.catalina.*;

/** 서블릿 Wrapper들을 소유하는 컨테이너. */
public final class StandardContext extends ContainerBase implements Context {
    private final java.util.Map<String, Wrapper> mappings = new java.util.LinkedHashMap<>();

    @Override public java.util.Map<String, Wrapper> getServletMappings() { return java.util.Collections.unmodifiableMap(mappings); }

    /** 이름으로 등록한 자식에만 패턴을 연결하고 중복·모호한 패턴을 거부한다. */
    @Override public void addServletMapping(String pattern, Wrapper wrapper) {
        simpletomcat.catalina.mapper.MappingPath.requirePattern(pattern);
        if (wrapper == null || wrapper.getParent() != this || findChild(wrapper.getName()) != wrapper) {
            throw new IllegalArgumentException("Wrapper must be registered in this context");
        }
        if (mappings.putIfAbsent(pattern, wrapper) != null) throw new IllegalArgumentException("Duplicate servlet mapping");
    }
    /**
     * Context 이름과 다음 계층으로 위임할 기본 Valve를 초기화한다.
     *
     * @param name null이 아닌 컨테이너 이름
     * @throws IllegalArgumentException 이름이 null인 경우
     */
    public StandardContext(String name) {
        super(name);
        installPipeline(new StandardContextValve(this));
    }
    /**
     * Wrapper 형식의 자식만 허용한다.
     *
     * @param child 자식 후보
     * @return 후보가 Wrapper이면 true
     */
    @Override
    protected boolean acceptsChild(Container child) { return child instanceof Wrapper; }
    /**
     * Host 또는 미연결 상태만 허용한다.
     *
     * @param parent 부모 후보 또는 null
     * @return 허용된 부모이면 true
     */
    @Override
    protected boolean acceptsParent(Container parent) { return parent == null || parent instanceof Host; }
}
