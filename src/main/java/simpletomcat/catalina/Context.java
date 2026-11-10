package simpletomcat.catalina;

/** 서블릿 Wrapper들을 소유하는 웹 애플리케이션 경계. */
public interface Context extends Container {
    /** 시작 전에 등록한 패턴과 Wrapper를 조회한다. */
    java.util.Map<String, Wrapper> getServletMappings();
    /** 등록된 자식 Wrapper에 패턴을 추가한다. 중복은 거부한다. */
    void addServletMapping(String pattern, Wrapper wrapper);
}
