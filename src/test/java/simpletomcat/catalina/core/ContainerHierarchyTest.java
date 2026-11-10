package simpletomcat.catalina.core;

import org.junit.jupiter.api.Test;
import simpletomcat.catalina.Container;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static simpletomcat.catalina.core.CatalinaTestSupport.*;

/** Engine부터 Wrapper까지 자식 등록과 부모 관계의 불변 조건을 검증한다. */
class ContainerHierarchyTest {
    /** 네 계층 등록 후 부모·이름 조회와 자식 배열 복사본의 독립성을 검증한다. */
    @Test
    void registersHierarchy() {
        var engine = new StandardEngine("engine");
        var host = new StandardHost("localhost");
        var context = new StandardContext("");
        var wrapper = new StandardWrapper(CountingServlet::new);
        wrapper.setName("hello");
        engine.addChild(host);
        host.addChild(context);
        context.addChild(wrapper);
        assertNull(engine.getParent());
        assertSame(engine, host.getParent());
        assertSame(host, context.getParent());
        assertSame(context, wrapper.getParent());
        assertSame(host, engine.findChild("localhost"));
        assertSame(context, host.findChild(""));
        assertSame(wrapper, context.findChild("hello"));
        Container[] snapshot = context.findChildren();
        assertEquals(1, snapshot.length);
        snapshot[0] = null;
        assertSame(wrapper, context.findChildren()[0]);
        assertNull(context.findChild(null));
        assertNull(context.findChild("missing"));
        assertEquals(0, wrapper.findChildren().length);
    }

    /** 계층에 맞지 않는 자식·부모와 Wrapper 자식 추가를 거부하고 등록이 없는지 검증한다. */
    @Test
    void rejectsInvalidChildren() {
        var engine = new StandardEngine("engine");
        var host = new StandardHost("host");
        var context = new StandardContext("");
        var wrapper = new StandardWrapper(CountingServlet::new);
        assertThrows(NullPointerException.class, () -> engine.addChild(null));
        assertThrows(IllegalArgumentException.class, () -> engine.addChild(context));
        assertThrows(IllegalArgumentException.class, () -> host.addChild(wrapper));
        assertThrows(IllegalArgumentException.class, () -> context.addChild(host));
        assertThrows(IllegalStateException.class, () -> wrapper.addChild(wrapper));
        assertThrows(IllegalArgumentException.class, () -> engine.setParent(host));
        assertThrows(IllegalArgumentException.class, () -> engine.setParent(null));
        assertThrows(IllegalArgumentException.class, () -> host.setParent(context));
        assertThrows(IllegalArgumentException.class, () -> context.setParent(engine));
        assertThrows(IllegalArgumentException.class, () -> wrapper.setParent(host));
        assertEquals(0, engine.findChildren().length);
        assertEquals(0, host.findChildren().length);
        assertEquals(0, context.findChildren().length);
        assertNull(host.getParent());
        assertNull(context.getParent());
        assertNull(wrapper.getParent());
    }

    /** 중복 이름·동일 객체·다른 부모에 등록된 자식 추가 실패 시 원래 맵과 부모가 보존되는지 검증한다. */
    @Test
    void duplicateDoesNotChangeParent() {
        var engine = new StandardEngine("engine");
        var other = new StandardEngine("other");
        var host = new StandardHost("host");
        var duplicate = new StandardHost("host");
        engine.addChild(host);
        assertThrows(IllegalArgumentException.class, () -> engine.addChild(duplicate));
        assertThrows(IllegalArgumentException.class, () -> engine.addChild(host));
        assertThrows(IllegalArgumentException.class, () -> other.addChild(host));
        assertSame(host, engine.findChild("host"));
        assertSame(engine, host.getParent());
        assertNull(duplicate.getParent());
        assertEquals(1, engine.findChildren().length);
        assertEquals(0, other.findChildren().length);
    }

    /** null 이름·Host 소문자화·빈 Context 이름과 등록 후 이름·부모 변경 제한을 검증한다. */
    @Test
    void validatesNamesAndParentChanges() {
        assertThrows(IllegalArgumentException.class, () -> new StandardEngine(null));
        assertThrows(IllegalArgumentException.class, () -> new StandardHost(null));
        assertThrows(IllegalArgumentException.class, () -> new StandardContext(null));
        var engine = new StandardEngine("engine");
        var other = new StandardEngine("other");
        var host = new StandardHost("LOCALHOST");
        var context = new StandardContext("");
        assertEquals("localhost", host.getName());
        assertEquals("", context.getName());
        host.setParent(engine);
        host.setParent(null);
        assertNull(host.getParent());
        engine.addChild(host);
        host.addChild(context);
        host.setName("LOCALHOST");
        assertThrows(IllegalArgumentException.class, () -> host.setName(null));
        assertThrows(IllegalStateException.class, () -> host.setName("changed"));
        assertThrows(IllegalStateException.class, () -> host.setParent(other));
        assertThrows(IllegalStateException.class, () -> host.setParent(null));
        assertThrows(IllegalArgumentException.class, () -> engine.addChild(new StandardHost("LocalHost")));
        assertSame(host, engine.findChild("localhost"));
        assertSame(engine, host.getParent());
    }

    /** 네 컨테이너가 기본 Valve를 설치하고 Wrapper 생성만으로 서블릿을 생성·초기화하지 않는지 검증한다. */
    @Test
    void installsBasicValveForEachContainer() {
        var creations = new AtomicInteger();
        var servlet = new CountingServlet();
        var wrapper = new StandardWrapper(() -> { creations.incrementAndGet(); return servlet; });
        Container[] containers = {new StandardEngine("engine"), new StandardHost("host"), new StandardContext(""), wrapper};
        Class<?>[] types = {StandardEngineValve.class, StandardHostValve.class, StandardContextValve.class, StandardWrapperValve.class};
        for (int i = 0; i < containers.length; i++) {
            assertInstanceOf(types[i], containers[i].getPipeline().getBasic());
            assertSame(containers[i].getPipeline().getBasic(), containers[i].getPipeline().getFirst());
        }
        assertEquals(0, creations.get());
        assertEquals(0, servlet.inits);
    }
}
