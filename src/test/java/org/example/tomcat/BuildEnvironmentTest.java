package org.example.tomcat;

import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuildEnvironmentTest {

    // 빌드 테스트를 실행하면 실행 JVM의 주 버전이 21이어야 한다.
    @Test
    void runsOnJava21() {
        assertEquals(21, Runtime.version().feature());
    }

    // 빌드 테스트를 JDK에서 실행하면 시스템 Java 컴파일러를 얻을 수 있어야 한다.
    @Test
    void providesSystemJavaCompiler() {
        assertNotNull(ToolProvider.getSystemJavaCompiler());
    }
}
