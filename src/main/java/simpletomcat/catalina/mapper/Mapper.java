package simpletomcat.catalina.mapper;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import simpletomcat.catalina.*;

/** 서버명·정규 경로에서 대상만 선택한다. 구성은 요청 처리 시작 전에 끝내야 한다. */
public final class Mapper {
    private final Engine engine;
    private final Host defaultHost;

    /** 기본 Host는 null로 생략하거나 이미 등록된 이름으로 명시한다. */
    public Mapper(Engine engine, String defaultHost) {
        this.engine = Objects.requireNonNull(engine);
        this.defaultHost = defaultHost == null ? null : (Host) engine.findChild(defaultHost.toLowerCase(Locale.ROOT));
        if (defaultHost != null && this.defaultHost == null) throw new IllegalArgumentException("Unknown default host");
        for (Container host : engine.findChildren()) {
            for (Container context : host.findChildren()) {
                String path = context.getName();
                if (!path.isEmpty()) {
                    MappingPath.requireCanonical(path);
                    if (path.endsWith("/") || path.contains("*")) throw new IllegalArgumentException("Invalid context path");
                }
            }
        }
    }

    /** serverName은 포트 없는 Host 이름이며 URI는 query 없는 경로다. 앱 콜백을 실행하지 않는다. */
    public MappingData map(String serverName, String uri) {
        MappingPath.requireCanonical(uri);
        Host host = serverName == null ? null : (Host) engine.findChild(serverName.toLowerCase(Locale.ROOT));
        if (host == null) host = defaultHost;
        if (host == null) return new MappingData(null, null, null, null, null, null);

        Context context = null;
        for (Container child : host.findChildren()) {
            String path = child.getName();
            if (matchesPrefix(uri, path) && (context == null || path.length() > context.getName().length())) {
                context = (Context) child;
            }
        }
        if (context == null) return new MappingData(host, null, null, null, null, null);
        String contextPath = context.getName();
        String path = uri.substring(contextPath.length());
        if (path.isEmpty()) path = "/"; // 학습용 모델은 Context 루트 redirect를 생략한다.
        Map<String, Wrapper> mappings = context.getServletMappings();
        Wrapper wrapper = path.equals("/") ? null : mappings.get(path);
        String servletPath = path;
        String pathInfo = null;
        if (wrapper == null) {
            String longest = null;
            for (String pattern : mappings.keySet()) {
                if (!pattern.endsWith("/*")) continue;
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (matchesPrefix(path, prefix) && (longest == null || prefix.length() > longest.length())) longest = prefix;
            }
            if (longest != null) {
                wrapper = mappings.get(longest + "/*");
                servletPath = longest;
                pathInfo = path.length() == longest.length() ? null : path.substring(longest.length());
            }
        }
        if (wrapper == null) {
            int dot = path.lastIndexOf('.');
            if (dot > path.lastIndexOf('/')) wrapper = mappings.get("*" + path.substring(dot));
        }
        if (wrapper == null) wrapper = mappings.get("/");
        return new MappingData(host, context, wrapper, contextPath, wrapper == null ? null : servletPath, pathInfo);
    }

    private static boolean matchesPrefix(String path, String prefix) {
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
