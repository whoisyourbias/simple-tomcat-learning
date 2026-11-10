package simpletomcat.catalina.mapper;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import simpletomcat.catalina.core.*;
import simpletomcat.servlet.*;
import static org.junit.jupiter.api.Assertions.*;

class MapperTest {
    private final StandardEngine engine = new StandardEngine("engine");
    private final StandardHost host = new StandardHost("A.TEST");
    private final StandardContext shop = new StandardContext("/shop");
    private final AtomicInteger calls = new AtomicInteger();

    MapperTest() { engine.addChild(host); host.addChild(shop); }

    private StandardWrapper add(StandardContext context, String name, String pattern) {
        StandardWrapper wrapper = new StandardWrapper(() -> new Servlet() {
            public void init() { calls.incrementAndGet(); }
            public void service(ServletRequest req, ServletResponse res) { calls.incrementAndGet(); }
            public void destroy() { }
        });
        wrapper.setName(name); context.addChild(wrapper); context.addServletMapping(pattern, wrapper);
        return wrapper;
    }

    // Host 대소문자·명시한 기본 Host·미등록 Host를 선택해도 init·service는 실행하지 않아야 한다.
    @Test
    void selectsHostAndExplicitFallbackWithoutInvokingServlet() {
        StandardWrapper expected = add(shop, "status", "/status");
        StandardHost second = new StandardHost("b.test"); engine.addChild(second);
        StandardContext secondShop = new StandardContext("/shop"); second.addChild(secondShop);
        StandardWrapper other = add(secondShop, "status", "/status");
        Mapper strict = new Mapper(engine, null);
        assertSame(expected, strict.map("a.TEST", "/shop/status").wrapper());
        assertSame(other, strict.map("b.test", "/shop/status").wrapper());
        assertNull(strict.map("missing", "/shop/status").host());
        assertSame(host, new Mapper(engine, "a.test").map("missing", "/shop/status").host());
        assertSame(host, new Mapper(engine, "a.test").map(null, "/shop/status").host());
        assertThrows(IllegalArgumentException.class, () -> new Mapper(engine, "missing"));
        assertEquals(0, calls.get());
    }

    // /shop·/shop/deep는 세그먼트 경계의 최장 Context를 선택하며 /shopper는 root 앱을 선택해야 한다.
    @Test
    void selectsLongestContextAtSegmentBoundary() {
        StandardContext root = new StandardContext(""); host.addChild(root);
        StandardContext nested = new StandardContext("/shop/deep"); host.addChild(nested);
        StandardWrapper rootWrapper = add(root, "root", "/");
        StandardWrapper shopWrapper = add(shop, "shop", "/");
        StandardWrapper nestedWrapper = add(nested, "nested", "/");
        Mapper mapper = new Mapper(engine, null);
        assertSame(nestedWrapper, mapper.map("a.test", "/shop/deep/status").wrapper());
        assertSame(shopWrapper, mapper.map("a.test", "/shop/deeper/status").wrapper());
        assertSame(rootWrapper, mapper.map("a.test", "/shopper/status").wrapper());
        assertSame(shopWrapper, mapper.map("a.test", "/shop").wrapper());
        assertEquals("/", mapper.map("a.test", "/shop").servletPath());
    }

    // 충돌 패턴은 정확→최장 접두어→확장자→기본 순서로 선택하고 세 경로 필드가 표와 같아야 한다.
    @ParameterizedTest
    @CsvSource(value = {
        "/shop/a/exact.do|exact|/a/exact.do|<null>",
        "/shop/a/deep/x.do|deep|/a/deep|/x.do",
        "/shop/a/x.do|prefix|/a|/x.do",
        "/shop/a|prefix|/a|<null>",
        "/shop/a/|prefix|/a|/",
        "/shop/ab/x.do|extension|/ab/x.do|<null>",
        "/shop/x.do|extension|/x.do|<null>",
        "/shop/file.do/x|default|/file.do/x|<null>",
        "/shop/other|default|/other|<null>"
    }, delimiter = '|', nullValues = "<null>")
    void mapsPatternsAndPaths(String uri, String name, String servletPath, String pathInfo) {
        add(shop, "default", "/"); add(shop, "extension", "*.do");
        add(shop, "prefix", "/a/*"); add(shop, "deep", "/a/deep/*");
        add(shop, "exact", "/a/exact.do");
        MappingData result = new Mapper(engine, null).map("a.test", uri);
        assertEquals(name, result.wrapper().getName());
        assertSame(host, result.host()); assertSame(shop, result.context());
        assertEquals("/shop", result.contextPath());
        assertEquals(servletPath, result.servletPath()); assertEquals(pathInfo, result.pathInfo());
        assertEquals(0, calls.get());
    }

    // /*는 확장자보다 먼저 선택하고 빈 servletPath와 전체 pathInfo를 반환해야 한다.
    @Test
    void mapsCatchAllPrefixBeforeExtension() {
        StandardWrapper all = add(shop, "all", "/*"); add(shop, "ext", "*.do");
        MappingData result = new Mapper(engine, null).map("a.test", "/shop/a.do");
        assertSame(all, result.wrapper()); assertEquals("", result.servletPath()); assertEquals("/a.do", result.pathInfo());
    }

    // 매핑 실패 단계까지만 결과를 채우고 이전 map 결과가 다음 요청으로 새지 않아야 한다.
    @Test
    void preservesPartialSelectionWithoutLeakingPreviousResult() {
        add(shop, "known", "/known"); Mapper mapper = new Mapper(engine, null);
        assertNotNull(mapper.map("a.test", "/shop/known").wrapper());
        MappingData missing = mapper.map("a.test", "/shop/missing");
        assertSame(shop, missing.context()); assertNull(missing.wrapper());
        assertNull(mapper.map("a.test", "/missing").context());
        assertNull(mapper.map("unknown", "/shop/known").host());
        assertEquals(0, calls.get());
    }

    // 인코딩·파라미터·중복 슬래시·dot 세그먼트·제어문자 등 비정규 경로는 map 전에 거부되어야 한다.
    @ParameterizedTest
    @ValueSource(strings = {"", "*", "hello", "/a//b", "/a/./b", "/a/../b", "/a/.", "/a/..", "/%61", "/%2f", "/%GG", "/a;b", "/a\\b", "/한글", "/a?x=1", "/a#x", "/a b", "/a\u0000"})
    void rejectsNonCanonicalPaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> new Mapper(engine, null).map("a.test", path));
    }

    // 중복·잘못된 패턴·다른 Context Wrapper 등록은 거부하고 기존 정상 매핑은 보존해야 한다.
    @Test
    void rejectsInvalidRegistrationWithoutChangingExistingMapping() {
        StandardWrapper first = add(shop, "first", "/status");
        StandardWrapper foreign = new StandardWrapper(() -> null); foreign.setName("foreign");
        for (String pattern : new String[]{"", "relative", "/a*", "*.a/b", "*.", "/a//b", "/a/../b"}) {
            assertThrows(IllegalArgumentException.class, () -> shop.addServletMapping(pattern, first), pattern);
        }
        assertThrows(IllegalArgumentException.class, () -> shop.addServletMapping("/foreign", foreign));
        assertThrows(IllegalArgumentException.class, () -> shop.addServletMapping("/status", first));
        assertThrows(UnsupportedOperationException.class, () -> shop.getServletMappings().clear());
        assertSame(first, new Mapper(engine, null).map("a.test", "/shop/status").wrapper());
    }
}
