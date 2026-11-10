package simpletomcat.catalina.mapper;

/** 디코딩·정규화를 생략하는 대신 모호한 입력을 거부하는 학습용 경로 정책. */
public final class MappingPath {
    private MappingPath() { }

    /** ASCII 정규 절대 경로만 허용한다. query는 이미 분리되어 있어야 한다. */
    public static void requireCanonical(String path) {
        if (path == null || !path.startsWith("/") || path.contains("//")
                || path.chars().anyMatch(c -> c <= 32 || c >= 127 || "%\\;?#".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Unsupported request path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("Dot path segment");
        }
    }

    /** 정확·접두어·단일 확장자·기본 패턴만 허용한다. 빈 특수 매핑은 지원하지 않는다. */
    public static void requirePattern(String pattern) {
        if (pattern != null && pattern.startsWith("*.")) {
            if (!pattern.substring(2).matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid extension pattern");
            return;
        }
        if (pattern != null && pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (prefix.isEmpty()) return;
            requireCanonical(prefix);
            if (prefix.endsWith("/") || prefix.contains("*")) throw new IllegalArgumentException("Invalid prefix pattern");
            return;
        }
        requireCanonical(pattern);
        if (pattern.contains("*")) throw new IllegalArgumentException("Invalid exact pattern");
    }
}
