package simpletomcat.catalina.core;

import java.util.Objects;
import simpletomcat.catalina.Pipeline;
import simpletomcat.catalina.Valve;

/** 일반 Valve를 등록 순서로 연결하고 기본 Valve를 항상 마지막에 둔다. */
public final class StandardPipeline implements Pipeline {
    /**
     * 첫 일반 Valve. 일반 Valve가 없으면 null이다.
     */
    private Valve first;
    /**
     * 일반 Valve 체인 뒤에 배치되는 기본 Valve.
     */
    private Valve basic;

    /**
     * 연결되지 않은 기본 Valve로 파이프라인을 생성한다.
     *
     * @param basic 기본 Valve
     * @throws NullPointerException 기본 Valve가 null인 경우
     * @throws IllegalArgumentException 기본 Valve가 이미 연결된 경우
     */
    public StandardPipeline(Valve basic) {
        validateNewValve(basic);
        this.basic = basic;
    }

    /**
     * 첫 일반 Valve를 반환하며 없으면 기본 Valve를 반환한다.
     *
     * @return 요청 진입 Valve
     */
    @Override
    public Valve getFirst() { return first == null ? basic : first; }

    /**
     * 체인의 마지막 기본 Valve를 반환한다.
     *
     * @return 기본 Valve
     */
    @Override
    public Valve getBasic() { return basic; }

    /**
     * 새 일반 Valve를 기존 일반 체인의 끝과 기본 Valve 사이에 추가한다.
     *
     * @param valve 추가할 Valve
     * @throws NullPointerException Valve가 null인 경우
     * @throws IllegalArgumentException 이미 연결되거나 등록된 Valve인 경우
     */
    @Override
    public void addValve(Valve valve) {
        validateNewValve(valve);
        if (first == null) {
            first = valve;
        } else {
            tail().setNext(valve);
        }
        valve.setNext(basic);
    }

    /**
     * 기본 Valve를 교체한다. 현재 기본 Valve를 다시 지정하면 아무 작업도 하지 않는다.
     *
     * @param valve 새 기본 Valve
     * @throws NullPointerException Valve가 null인 경우
     * @throws IllegalArgumentException 이미 연결되거나 등록된 Valve인 경우
     */
    @Override
    public void setBasic(Valve valve) {
        if (valve == basic) { return; }
        validateNewValve(valve);
        if (first != null) { tail().setNext(valve); }
        basic = valve;
    }

    /**
     * 일반 Valve가 존재한다는 전제에서 기본 Valve 직전 요소를 찾는다.
     *
     * @return 마지막 일반 Valve
     */
    private Valve tail() {
        Valve current = first;
        while (current.getNext() != basic) { current = current.getNext(); }
        return current;
    }

    /**
     * null, 기존 연결, 현재 체인의 중복 등록을 검사한다.
     *
     * @param valve 등록 후보
     * @throws NullPointerException 후보가 null인 경우
     * @throws IllegalArgumentException 이미 연결되거나 등록된 후보인 경우
     */
    private void validateNewValve(Valve valve) {
        Objects.requireNonNull(valve, "valve");
        if (valve.getNext() != null) {
            throw new IllegalArgumentException("Valve is already linked");
        }
        for (Valve current = getFirst(); current != null; current = current.getNext()) {
            if (current == valve) { throw new IllegalArgumentException("Valve is already registered"); }
        }
    }
}
