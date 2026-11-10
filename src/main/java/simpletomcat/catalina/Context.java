package simpletomcat.catalina;

/** 서블릿 Wrapper들을 소유하는 웹 애플리케이션 경계. */
public interface Context extends Container {
    /** NEW 상태에서 Context 전체 REQUEST 필터를 등록 순서로 추가한다. */
    void addFilter(String name, java.util.function.Supplier<? extends simpletomcat.servlet.Filter> factory);
    /** 수신 전에 필터를 준비한다. 성공분만 공개하며 일반 실패면 역순 정리한다. */
    void startFilters() throws simpletomcat.servlet.ServletException;
    /** 초기화 성공한 공유 필터의 불변 목록을 조회한다. */
    java.util.List<simpletomcat.servlet.Filter> getFilters();
    /** 실행자가 요청 처리 종료를 확인한 뒤 호출한다. 활성 작업 보호는 제공하지 않는다. */
    void stopFilters() throws simpletomcat.servlet.ServletException;
    /** 시작 전에 등록한 패턴과 Wrapper를 조회한다. */
    java.util.Map<String, Wrapper> getServletMappings();
    /** 등록된 자식 Wrapper에 패턴을 추가한다. 중복은 거부한다. */
    void addServletMapping(String pattern, Wrapper wrapper);
}
