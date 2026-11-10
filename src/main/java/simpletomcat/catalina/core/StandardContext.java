package simpletomcat.catalina.core;

import simpletomcat.catalina.*;
import simpletomcat.servlet.*;

import java.util.*;
import java.util.function.Supplier;

/** 서블릿 Wrapper들을 소유하는 컨테이너. */
public final class StandardContext extends ContainerBase implements Context {
    private enum FilterState {
        NEW,
        STARTING,
        STARTED,
        FAILED,
        STOPPING,
        STOPPED
    }

    private FilterState filterState = FilterState.NEW;
    private final Map<String, Supplier<? extends Filter>> filterDefinitions = new LinkedHashMap<>();
    private List<Filter> filters = List.of();

    @Override
    public synchronized void addFilter(String name, Supplier<? extends Filter> factory) {
        requireMutable();
        if (filterState != FilterState.NEW)
            throw new IllegalStateException("Filter registration is closed");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        if (name.isBlank()) throw new IllegalArgumentException("Blank filter name");
        if (filterDefinitions.putIfAbsent(name, factory) != null)
            throw new IllegalArgumentException("Duplicate filter name");
    }

    @Override
    public synchronized void startFilters() throws ServletException {
        if (filterState == FilterState.STARTED) return;
        if (filterState != FilterState.NEW)
            throw new IllegalStateException("Cannot start filters in " + filterState);
        filterState = FilterState.STARTING;
        var initialized = new ArrayList<Filter>();
        try {
            for (Supplier<? extends Filter> factory : filterDefinitions.values()) {
                Filter candidate = factory.get();
                if (candidate == null) throw new ServletException("Filter factory returned null");
                candidate.init();
                initialized.add(candidate);
            }
            filters = List.copyOf(initialized);
            filterState = FilterState.STARTED;
        } catch (ServletException | RuntimeException cause) {
            ServletException failure =
                    cause instanceof ServletException se
                            ? se
                            : new ServletException("Cannot initialize filters", cause);
            filterState = FilterState.FAILED;
            destroyFilters(initialized, failure);
            throw failure;
        } catch (Error fatal) {
            // 치명 오류의 전체 복구는 보장하지 않지만 성공분의 소유권은 후속 stop에 남긴다.
            filters = List.copyOf(initialized);
            filterState = FilterState.FAILED;
            throw fatal;
        }
    }

    @Override
    public synchronized List<Filter> getFilters() {
        if (filterState == FilterState.STARTED) return filters;
        if (filterState == FilterState.NEW && filterDefinitions.isEmpty()) return List.of();
        throw new IllegalStateException("Filters are not ready: " + filterState);
    }

    @Override
    public synchronized void stopFilters() throws ServletException {
        if (filterState == FilterState.STOPPED) return;
        if (filterState == FilterState.STARTING || filterState == FilterState.STOPPING)
            throw new IllegalStateException("Cannot stop filters in " + filterState);
        filterState = FilterState.STOPPING;
        List<Filter> owned = filters;
        filters = List.of();
        try {
            ServletException failure = destroyFilters(owned, null);
            if (failure != null) throw failure;
        } finally {
            filterState = FilterState.STOPPED;
        }
    }

    /** 일반 해제 실패에도 형제를 계속 정리한다. Error는 즉시 전파한다. */
    private ServletException destroyFilters(List<Filter> owned, ServletException failure) {
        for (int i = owned.size() - 1; i >= 0; i--) {
            try {
                owned.get(i).destroy();
            } catch (RuntimeException cleanup) {
                if (failure == null)
                    failure = new ServletException("Cannot destroy filter", cleanup);
                else if (cleanup != failure.getCause()) failure.addSuppressed(cleanup);
            }
        }
        return failure;
    }

    private final java.util.Map<String, Wrapper> mappings = new java.util.LinkedHashMap<>();

    @Override
    public java.util.Map<String, Wrapper> getServletMappings() {
        return java.util.Collections.unmodifiableMap(mappings);
    }

    /** 이름으로 등록한 자식에만 패턴을 연결하고 중복·모호한 패턴을 거부한다. */
    @Override
    public void addServletMapping(String pattern, Wrapper wrapper) {
        requireMutable();
        simpletomcat.catalina.mapper.MappingPath.requirePattern(pattern);
        if (wrapper == null
                || wrapper.getParent() != this
                || findChild(wrapper.getName()) != wrapper) {
            throw new IllegalArgumentException("Wrapper must be registered in this context");
        }
        if (mappings.putIfAbsent(pattern, wrapper) != null)
            throw new IllegalArgumentException("Duplicate servlet mapping");
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
    protected boolean acceptsChild(Container child) {
        return child instanceof Wrapper;
    }

    /**
     * Host 또는 미연결 상태만 허용한다.
     *
     * @param parent 부모 후보 또는 null
     * @return 허용된 부모이면 true
     */
    @Override
    protected boolean acceptsParent(Container parent) {
        return parent == null || parent instanceof Host;
    }
}
