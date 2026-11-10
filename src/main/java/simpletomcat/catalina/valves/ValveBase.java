package simpletomcat.catalina.valves;

import simpletomcat.catalina.Valve;

/**
 * next 참조만 보관한다. 요청별 상태는 보관하지 않는다.
 * <p>기본 생성자는 다음 Valve가 연결되지 않은 상태로 하위 Valve를 생성한다.
 */
public abstract class ValveBase implements Valve {
    /**
     * 다음 호출 요소. 기본 Valve나 체인의 끝에서는 null이다.
     */
    private Valve next;

    /**
     * 저장된 다음 Valve 참조를 반환한다.
     *
     * @return 다음 Valve 또는 null
     */
    @Override
    public final Valve getNext() { return next; }

    /**
     * 다음 Valve 참조를 교체하며 연결 유효성은 호출자가 관리한다.
     *
     * @param valve 다음 Valve 또는 null
     */
    @Override
    public final void setNext(Valve valve) { next = valve; }
}
