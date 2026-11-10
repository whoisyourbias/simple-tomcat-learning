package simpletomcat.catalina.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import simpletomcat.catalina.ContainerRequest;
import simpletomcat.catalina.valves.ValveBase;
import simpletomcat.servlet.ServletResponse;

class ContainerFreezeTest {
    // 전체 트리를 동결하면 보관한 컨테이너·파이프라인·Valve 참조의 변경도 거부한다.
    @Test
    void freezesWholeTreeAndSavedReferences() {
        var engine = new StandardEngine("e");
        var host = new StandardHost("localhost");
        var context = new StandardContext("");
        engine.addChild(host);
        host.addChild(context);
        var pipeline = context.getPipeline();
        var basic = pipeline.getBasic();
        engine.freeze();
        engine.freeze();
        assertTrue(context.isFrozen());
        assertThrows(IllegalStateException.class, () -> context.setName(""));
        assertThrows(IllegalStateException.class, () -> context.setParent(host));
        assertThrows(IllegalStateException.class, () -> context.addServletMapping("/late", null));
        assertThrows(
                IllegalStateException.class,
                () ->
                        pipeline.addValve(
                                new ValveBase() {
                                    public void invoke(ContainerRequest q, ServletResponse s) {}
                                }));
        assertThrows(
                IllegalStateException.class, () -> host.addChild(new StandardContext("/late")));
        assertThrows(IllegalStateException.class, () -> context.addFilter("late", () -> null));
        assertThrows(IllegalStateException.class, () -> basic.setNext(null));
        assertThrows(IllegalStateException.class, () -> pipeline.setBasic(basic));
        assertThrows(IllegalStateException.class, () -> engine.setName("other"));
    }

    // 잘못된 순환 Valve 링크는 동결 순회 전에 유한하게 거부한다.
    @Test
    void rejectsCyclicValveChainBeforeCallbacks() {
        var engine = new StandardEngine("e");
        engine.getPipeline().getBasic().setNext(engine.getPipeline().getBasic());
        assertThrows(IllegalStateException.class, engine::freeze);
    }

    // 등록 순서를 유지하고 외부 매핑 뷰는 동결 전후 모두 변경할 수 없다.
    @Test
    void preservesRegistrationOrderAndImmutableMappings() {
        var host = new StandardHost("localhost");
        var a = new StandardContext("/a");
        var b = new StandardContext("/b");
        host.addChild(a);
        host.addChild(b);
        host.freeze();
        assertArrayEquals(new Object[] {a, b}, host.findChildren());
        assertThrows(
                UnsupportedOperationException.class, () -> a.getServletMappings().put("/", null));
    }
}
