package simpletomcat.catalina.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import simpletomcat.catalina.Container;
import simpletomcat.catalina.Pipeline;
import simpletomcat.catalina.Valve;

/** 이름별 등록과 부모·자식 관계를 관리한다. 동시 구성 변경은 지원하지 않는다. */
public abstract class ContainerBase implements Container {
    /**
     * 부모의 자식 맵에 사용할 등록 이름.
     */
    private String name;
    /**
     * 조립 과정에서 지정된 부모 참조.
     */
    private Container parent;
    /**
     * 이름별 자식 등록 정보. 동시 변경을 지원하지 않는다.
     */
    private final Map<String, Container> children = new HashMap<>();
    /**
     * 구체 컨테이너가 한 번 설치하는 요청 처리 체인.
     */
    private Pipeline pipeline;

    /**
     * 이름을 검증하여 기본 컨테이너를 생성한다.
     *
     * @param name null이 아닌 이름
     * @throws IllegalArgumentException 이름이 null인 경우
     */
    protected ContainerBase(String name) { setName(name); }

    /**
     * 기본 Valve를 가진 파이프라인을 한 번 설치한다.
     *
     * @param basic 기본 처리 Valve
     * @throws IllegalStateException 이미 설치된 경우
     * @throws NullPointerException 기본 Valve가 null인 경우
     * @throws IllegalArgumentException 기본 Valve가 이미 연결된 경우
     */
    protected final void installPipeline(Valve basic) {
        if (pipeline != null) { throw new IllegalStateException("Pipeline is already installed"); }
        pipeline = new StandardPipeline(basic);
    }

    /**
     * 현재 등록 이름을 반환한다.
     *
     * @return 컨테이너 이름
     */
    @Override
    public final String getName() { return name; }

    /**
     * 이름을 설정하며 등록된 컨테이너의 다른 이름 변경을 거부한다.
     *
     * @param name null이 아닌 이름
     * @throws IllegalArgumentException 이름이 null인 경우
     * @throws IllegalStateException 등록된 이름을 변경하는 경우
     */
    @Override
    public void setName(String name) {
        if (name == null) { throw new IllegalArgumentException("Container name is required"); }
        if (isRegistered() && !name.equals(this.name)) {
            throw new IllegalStateException("Cannot rename a registered container");
        }
        this.name = name;
    }

    /**
     * 현재 부모 참조를 반환한다.
     *
     * @return 부모 또는 null
     */
    @Override
    public final Container getParent() { return parent; }

    /**
     * 허용된 부모를 지정하되 등록 이후 부모 변경을 거부한다.
     *
     * @param parent 부모 후보 또는 null
     * @throws IllegalArgumentException 부모 형식이 허용되지 않는 경우
     * @throws IllegalStateException 등록된 부모를 변경하는 경우
     */
    @Override
    public void setParent(Container parent) {
        if (!acceptsParent(parent)) { throw new IllegalArgumentException("Invalid parent type"); }
        if (isRegistered() && parent != this.parent) {
            throw new IllegalStateException("Cannot change a registered parent");
        }
        this.parent = parent;
    }

    /**
     * 자식 형식, 이름 중복, 기존 부모를 검증한 후 부모 연결과 등록을 수행한다.
     *
     * @param child 등록할 자식
     * @throws NullPointerException 자식이 null인 경우
     * @throws IllegalArgumentException 자식 형식, 중복 이름 또는 기존 부모가 유효하지 않은 경우
     */
    @Override
    public void addChild(Container child) {
        Objects.requireNonNull(child, "child");
        if (!acceptsChild(child)) { throw new IllegalArgumentException("Invalid child type"); }
        if (children.containsKey(child.getName())) { throw new IllegalArgumentException("Duplicate child name"); }
        if (child.getParent() != null && child.getParent() != this) {
            throw new IllegalArgumentException("Child already has another parent");
        }
        child.setParent(this);
        children.put(child.getName(), child);
    }

    /**
     * 자식 형식 허용 여부를 판단한다. 기본 구현은 모든 형식을 허용한다.
     *
     * @param child 자식 후보
     * @return 허용하면 true
     */
    protected boolean acceptsChild(Container child) { return true; }
    /**
     * 부모 형식 허용 여부를 판단한다. 기본 구현은 모든 형식을 허용한다.
     *
     * @param parent 부모 후보 또는 null
     * @return 허용하면 true
     */
    protected boolean acceptsParent(Container parent) { return true; }

    /**
     * 부모의 이름별 등록 정보가 이 객체를 가리키는지 확인한다.
     *
     * @return 부모에 등록된 상태이면 true
     */
    private boolean isRegistered() { return parent != null && parent.findChild(name) == this; }

    /**
     * 이름으로 등록된 자식을 조회한다.
     *
     * @param name 등록 이름
     * @return 일치하는 자식 또는 null
     */
    @Override
    public final Container findChild(String name) { return children.get(name); }

    /**
     * 현재 자식들을 순서가 보장되지 않는 새 배열로 반환한다.
     *
     * @return 자식 스냅샷 배열
     */
    @Override
    public final Container[] findChildren() { return children.values().toArray(Container[]::new); }

    /**
     * 구체 컨테이너가 설치한 파이프라인을 반환한다.
     *
     * @return 설치된 파이프라인, 설치 전이면 null
     */
    @Override
    public final Pipeline getPipeline() { return pipeline; }
}
