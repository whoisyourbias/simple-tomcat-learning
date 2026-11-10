package simpletomcat.catalina;

/** 정적으로 조립하는 컨테이너 계층. 등록은 addChild로 수행한다. */
public interface Container {
    /**
     * 자식 등록 키로 사용하는 컨테이너 이름을 반환한다.
     *
     * @return 현재 이름
     */
    String getName();
    /**
     * 컨테이너 이름을 설정한다. 등록된 컨테이너의 이름 변경은 허용하지 않는다.
     *
     * @param name null이 아닌 이름
     * @throws IllegalArgumentException 이름이 null인 경우
     * @throws IllegalStateException 등록된 이름을 변경하는 경우
     */
    void setName(String name);
    /**
     * 현재 연결된 부모 컨테이너를 반환한다.
     *
     * @return 부모, 없으면 null
     */
    Container getParent();
    /**
     * 조립용 부모를 설정한다. 등록된 객체의 부모는 변경할 수 없다.
     *
     * @param parent 부모 후보 또는 연결 전 null
     * @throws IllegalArgumentException 허용하지 않는 부모 형식인 경우
     * @throws IllegalStateException 등록된 부모를 변경하는 경우
     */
    void setParent(Container parent);
    /**
     * 허용된 자식을 이름으로 등록하고 자식의 부모를 이 컨테이너로 설정한다.
     *
     * @param child 등록할 자식
     * @throws NullPointerException 자식이 null인 경우
     * @throws IllegalArgumentException 형식, 이름 중복 또는 기존 부모가 유효하지 않은 경우
     * @throws IllegalStateException 자식을 가질 수 없는 컨테이너인 경우
     */
    void addChild(Container child);
    /**
     * 등록 키와 일치하는 자식을 찾는다.
     *
     * @param name 찾을 이름
     * @return 자식, 없으면 null
     */
    Container findChild(String name);
    /**
     * 현재 자식들을 별도 배열로 반환한다. 순서는 보장하지 않는다.
     *
     * @return 등록된 자식의 배열
     */
    Container[] findChildren();
    /**
     * 이 컨테이너의 요청 호출 파이프라인을 반환한다.
     *
     * @return 설치된 파이프라인
     */
    Pipeline getPipeline();
}
