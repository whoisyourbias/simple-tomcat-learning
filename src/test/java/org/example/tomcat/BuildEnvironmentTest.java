package org.example.tomcat;

import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** JDK 21과 시스템 컴파일러를 사용할 수 있는 빌드 환경인지 검증한다. */
class BuildEnvironmentTest {

    /** 실행 중인 JVM의 주 버전이 프로젝트 기준인 21인지 검증한다. */
    // 빌드 테스트를 실행하면 실행 JVM의 주 버전이 21이어야 한다.
    @Test
    void runsOnJava21() {
        assertEquals(21, Runtime.version().feature()); // 패치 버전 대신 Java 주 버전을 비교한다.
    }

    /** JRE만 있는 환경이 아니라 Java 컴파일러를 포함한 JDK인지 검증한다. */
    // 빌드 테스트를 JDK에서 실행하면 시스템 Java 컴파일러를 얻을 수 있어야 한다.
    @Test
    void providesSystemJavaCompiler() {
        assertNotNull(ToolProvider.getSystemJavaCompiler()); // 시스템 Java 컴파일러를 얻을 수 있어야 빌드 환경이 충족된다.
    }
}
