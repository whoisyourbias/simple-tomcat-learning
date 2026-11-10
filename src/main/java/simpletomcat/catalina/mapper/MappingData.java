package simpletomcat.catalina.mapper;

import simpletomcat.catalina.*;

/** 요청 한 건의 선택 결과. 찾지 못한 계층과 경로는 null이다. 서블릿 호출은 수행하지 않는다. */
public record MappingData(Host host, Context context, Wrapper wrapper,
                          String contextPath, String servletPath, String pathInfo) { }
