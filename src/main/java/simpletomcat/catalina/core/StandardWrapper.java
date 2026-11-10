package simpletomcat.catalina.core;

import simpletomcat.catalina.Wrapper;


import simpletomcat.servlet.Servlet;
import simpletomcat.servlet.ServletException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 서블릿 인스턴스를 지연 초기화하고 재사용하는 학습용 Wrapper.
 * <p>팩터리는 매번 새 비초기화 인스턴스를 제공해야 하며 그 소유권은 이 Wrapper에 있다.
 * 상태 전이는 동기화하지만 service를 직렬화하지는 않는다. 대여 중 unload는 기다리지 않고 거부한다.
 * 같은 객체의 대여를 구별하는 토큰은 없으므로 호출자는 대여마다 정확히 한 번 반환해야 한다.
 */
public final class StandardWrapper implements Wrapper {
    /**
     * 새 비초기화 서블릿을 제공하는 팩터리.
     */
    private final Supplier<? extends Servlet> factory;
    /**
     * 초기화를 완료하여 공유하는 서블릿. 미로딩이면 null이다.
     */
    private Servlet instance;
    /**
     * 반환되지 않은 대여 수. 동일 인스턴스의 대여마다 증가한다.
     */
    private int allocations;
    /**
     * 초기화 콜백 실행 중 재진입을 막는 표시.
     */
    private boolean loading;
    /**
     * 해제 콜백 실행 중 재진입을 제어하는 표시.
     */
    private boolean unloading;

    /**
     * 빈 등록 이름과 서블릿 팩터리 및 기본 Valve를 초기화한다.
     *
     * @param factory 호출마다 새 비초기화 서블릿을 제공할 팩터리
     * @throws NullPointerException 팩터리가 null인 경우
     */
    public StandardWrapper(Supplier<? extends Servlet> factory) {

        this.factory = Objects.requireNonNull(factory, "factory");

    }

    /**
     * init 성공 이후에만 인스턴스를 공개한다. 실패하면 다음 할당에서 생성을 재시도한다.
     *
     * @return 초기화된 공유 서블릿
     * @throws ServletException 팩터리가 null을 반환하거나 생성 또는 초기화가 실패한 경우
     * @throws IllegalStateException 초기화 또는 해제 중 재진입한 경우
     */
    @Override
    public synchronized Servlet allocate() throws ServletException {
        if (loading || unloading) {
            throw new IllegalStateException("Cannot allocate during initialization or unloading");
        }
        if (instance == null) {
            loading = true;
            try {
                Servlet candidate = factory.get();
                if (candidate == null) {
                    throw new ServletException("Servlet factory returned null");
                }
                candidate.init();
                instance = candidate;
            } catch (RuntimeException e) {
                throw new ServletException("Cannot create or initialize servlet", e);
            } finally {
                loading = false;
            }
        }
        allocations++;
        return instance;
    }

    /**
     * 소유 인스턴스와 남은 대여 수를 확인하여 대여 한 건을 반환한다.
     *
     * @param servlet 반환할 공유 서블릿
     * @throws NullPointerException 서블릿이 null인 경우
     * @throws IllegalArgumentException 다른 인스턴스이거나 남은 대여가 없는 경우
     */
    @Override
    public synchronized void deallocate(Servlet servlet) {
        Objects.requireNonNull(servlet, "servlet");
        if (servlet != instance || allocations == 0) {
            throw new IllegalArgumentException("Servlet is not allocated by this wrapper");
        }
        allocations--;
    }

    /**
     * 미사용이면 아무 작업도 하지 않는다. destroy 실패 여부와 무관하게 소유 참조를 정리한다.
     *
     * @throws ServletException destroy의 런타임 예외를 감싼 경우
     * @throws IllegalStateException 초기화 중이거나 남은 대여가 있는 경우
     */
    @Override
    public synchronized void unload() throws ServletException {
        if (loading) {
            throw new IllegalStateException("Cannot unload during initialization");
        }
        if (unloading) {
            return;
        }
        if (allocations > 0) {
            throw new IllegalStateException("Return all allocations before unloading");
        }
        if (instance == null) {
            return;
        }
        unloading = true;
        try {
            instance.destroy();
        } catch (RuntimeException e) {
            throw new ServletException("Cannot destroy servlet", e);
        } finally {
            instance = null;
            unloading = false;
        }
    }
}

