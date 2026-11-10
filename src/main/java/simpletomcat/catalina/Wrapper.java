package simpletomcat.catalina;

import simpletomcat.servlet.Servlet;
import simpletomcat.servlet.ServletException;

/** 서블릿 한 정의의 인스턴스와 생명주기를 소유한다. service 호출은 호출자의 책임이다. */
public interface Wrapper {
    /**
     * 초기화된 인스턴스를 대여한다. 사용 후 반드시 finally에서 deallocate해야 한다.
     *
     * @return 초기화된 공유 서블릿
     * @throws ServletException 생성 또는 초기화가 실패한 경우
     * @throws IllegalStateException 초기화 또는 해제 중 재진입한 경우
     */
    Servlet allocate() throws ServletException;
    /**
     * 대여를 한 번 반환한다. 인스턴스 자체를 destroy하지 않는다.
     *
     * @param servlet 반환할 인스턴스
     * @throws NullPointerException 인스턴스가 null인 경우
     * @throws IllegalArgumentException 대여한 인스턴스가 아니거나 남은 대여가 없는 경우
     */
    void deallocate(Servlet servlet);
    /**
     * 모든 대여 반환 후 해제한다. 반복 호출 가능하며 이후 allocate는 새로 로드한다.
     *
     * @throws ServletException 해제 콜백이 실패한 경우
     * @throws IllegalStateException 초기화 중이거나 반환되지 않은 대여가 있는 경우
     */
    void unload() throws ServletException;
}

