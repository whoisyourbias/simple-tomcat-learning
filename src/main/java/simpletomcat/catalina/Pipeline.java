package simpletomcat.catalina;

/** 구성 변경은 요청 처리 전에 끝낸다. Valve는 한 Pipeline에만 등록한다. */
public interface Pipeline extends Freezable {
    /**
     * 일반 Valve가 있으면 첫 요소를, 없으면 기본 Valve를 반환한다.
     *
     * @return 요청 진입 Valve
     */
    Valve getFirst();

    /**
     * 호출 체인의 마지막 기본 Valve를 반환한다.
     *
     * @return 기본 Valve
     */
    Valve getBasic();

    /**
     * 기본 Valve를 교체하고 마지막 일반 Valve의 연결을 갱신한다.
     *
     * @param valve 연결되지 않은 새 기본 Valve
     * @throws NullPointerException Valve가 null인 경우
     * @throws IllegalArgumentException 이미 연결되거나 등록된 Valve인 경우
     */
    void setBasic(Valve valve);

    /**
     * 일반 Valve를 등록 순서대로 기본 Valve 앞에 추가한다.
     *
     * @param valve 연결되지 않은 일반 Valve
     * @throws NullPointerException Valve가 null인 경우
     * @throws IllegalArgumentException 이미 연결되거나 등록된 Valve인 경우
     */
    void addValve(Valve valve);
}
