# simple-tomcat-learning

작은 서버를 개선하며 **Tomcat 내부 구성요소가 어떤 문제를 해결하는지** 배우는 프로젝트다. TCP 연결에서 출발해 Coyote·Catalina를 구현하고, 사용자 DispatcherServlet을 실행하는 내장 컨테이너까지 완주한다.

**0~6단계를 구현했다. 제한 동시 처리·구성 동결·서버 생명주기·종료 기한과 해제 재시도를 검증했다. [6단계 실행 기록](doc/06-lifecycle/01-concurrency-and-lifecycle.md)에 최종 테스트·JAR HTTP 결과와 제약을 남겼다. 7단계는 예정이다.** 기준 소스는 Apache Tomcat 10.1.34로 고정한다.

## 처음 공부한다면

1. Java 클래스·인터페이스·예외·스트림, 기본 HTTP 요청·응답 형식을 익힌다.
2. [전체 구조](doc/00-overview/02-architecture.md)와 [인터랙티브 구조도](doc/00-overview/tomcat-architecture.html)에서 네트워크·Coyote·Catalina의 책임을 읽는다. 구조도는 저장소를 내려받은 뒤 해당 HTML을 브라우저로 열어 요청·응답 애니메이션과 용어 설명을 확인한다. GitHub 파일 화면에서는 소스가 표시된다.
3. [전체 로드맵](doc/plans/2026-11-05-tomcat-learning-roadmap.md)으로 7단계의 문제·완료 조건을 확인한다.
4. [학습·검증·커밋 규칙](doc/reference/03-learning-workflow.md)에 따라 준비 단계의 JDK 21·Maven Wrapper·JUnit 환경을 구성한다.
5. [1단계 기록](doc/01-socket/01-endpoint.md)에서 연결·종료, [2단계 기록](doc/02-coyote/01-http-processing.md)에서 HTTP 책임 분리, [3단계 기록](doc/03-servlet/01-servlet-and-wrapper.md)에서 Servlet 생명주기, [4단계 기록](doc/04-container/01-mapping-and-pipeline.md)에서 컨테이너·매핑·실제 HTTP 연결을 확인한다. [5단계 기록](doc/05-filter/01-filter-chain.md)에서 Context Filter·요청별 체인·오류 복구·해제를 확인한다. [6단계 기록](doc/06-lifecycle/01-concurrency-and-lifecycle.md)에서 동시 요청·소켓 소유권·종료 실패와 재시도를 확인한다. 다음 구현은 7단계 내장 API다.
6. 테스트·HTTP 실험 후 실제 Tomcat 메서드와 호출 순서·상태 변화·오류 처리·자원 소유권을 대조한다.

## 고정된 학습 단계

| 단계 | 이전 문제·핵심 개념 | 완료 조건 | 계획·예정 태그 |
|---|---|---|---|
| 0 준비 | 빌드·검증 출발점 | 환경 테스트 2개 통과·JAR 생성 | [준비 절차](doc/reference/03-learning-workflow.md) · learn/step-00 |
| 1 서버 | 연결과 서버의 수명 | 연속 요청·미완성 요청·종료 | [1단계](doc/plans/2026-11-05-step-01-socket.md) · learn/step-01 |
| 2 Coyote | HTTP와 소켓 책임 분리 | 독립 파싱·오류 복구 | [2단계](doc/plans/2026-11-05-step-02-coyote.md) · learn/step-02 |
| 3 Servlet | 응답과 인스턴스 소유권 | HTTP로 init/service/destroy 1/2/1 | [3단계](doc/plans/2026-11-05-step-03-servlet.md) · learn/step-03 |
| 4 컨테이너 | 웹앱 경계·선택과 호출 | 다중 Host·Context·매핑 순서·404 | [4단계](doc/plans/2026-11-05-step-04-container.md) · learn/step-04 |
| 5 공통 처리 | Valve·Filter 적용 범위 | 전후 순서·중단·오류 복구 | [5단계](doc/plans/2026-11-05-step-05-filter.md) · learn/step-05 |
| 6 실행·종료 | 동시성·Lifecycle·구성 | 요청 격리·시작 실패·안전한 종료 | [6단계](doc/plans/2026-11-05-step-06-lifecycle.md) · learn/step-06 |
| 7 내장 사용 | SimpleTomcat·DispatcherServlet | 두 업무 경로·JAR 실행 | [7단계](doc/plans/2026-11-05-step-07-embedded.md) · learn/step-07 |

## 빌드·검증 명령

JDK 21의 JAVA_HOME을 설정한 뒤 실행한다. 시스템 Maven 설치는 필요 없다. Windows PATH 설정과 실제 실행 결과는 [0단계 실행 기록](doc/00-overview/03-step-00-setup.md)을 참고한다.

Windows PowerShell

```powershell
.\mvnw.cmd -B clean verify
```

macOS·Linux

```sh
sh ./mvnw -B clean verify
```

현재 산출물은 target/simple-tomcat-0.0.1-SNAPSHOT.jar이며 Endpoint·Coyote·Catalina·Servlet·예제를 포함한 일반 JAR이다. Main-Class를 지정한 `java -jar` 실행은 7단계에서 제공한다.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp target/classes simpletomcat.examples.ContainerServer 18081
# 별도 터미널에서 두 번 호출한다. 서버 종료는 Ctrl+C.
& "$env:SystemRoot\System32\curl.exe" --max-time 3 -i -H 'Host: localhost' http://127.0.0.1:18081/shop/status
& "$env:SystemRoot\System32\curl.exe" --max-time 3 -i -H 'Host: other.local' http://127.0.0.1:18081/shop/status
```

포트 0으로 실행하면 임의 포트를 출력한다. 서버는 HTTP 문법·Host·버전·본문 프레이밍을 검사하고 Mapper가 Host·Context·Wrapper를 선택한 뒤 Pipeline이 Servlet을 실행한다. 예제는 localhost를 기본 Host로 명시한다. 연결당 한 요청, 순차 처리이며 요청 본문·동시 처리는 지원하지 않는다. URI 디코딩 대신 비정규 경로를 400으로 거부한다. 정책·소유권·차이는 [4단계 기록](doc/04-container/01-mapping-and-pipeline.md)을 참고한다.

## 6단계 동시 실행 예제

```powershell
& "$env:JAVA_HOME/bin/java.exe" -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar simpletomcat.examples.LifecycleServer 0
# 별도 터미널에서 출력한 실제 포트로 요청한다.
curl.exe --max-time 3 -i http://127.0.0.1:<실제포트>/shop/hello
```

위의 기존 ContainerServer·FilterServer·Http11Protocol 예제는 순차 경로를 유지한다. LifecycleServer는 worker4·queue16·읽기 무진행 제한3초를 기본으로 사용한다. close는 전체5초 예산으로 수락 중지·작업 대기·앱 해제를 시도하며 진행 작업이 남으면 실패와 해제 보류를 보고한다. 같은 객체 재시작·무한 init의 강제 취소·사용자 업무 필드의 자동 동기화는 제공하지 않는다.

## 완료 태그로 공부하기

완료 경계는 learn/step-00~06이다. learn/step-07은 해당 단계 검증 후 생성할 예정이다.

```sh
git clone https://github.com/whoisyourbias/simple-tomcat-learning.git
cd simple-tomcat-learning
git tag --list 'learn/*'

# 완성 코드를 읽는다.
git switch --detach learn/step-04

# 이전 단계에서 직접 구현한다.
git switch -c study/step-01 learn/step-00

# 책임과 테스트의 변화를 비교한다.
git diff learn/step-01..learn/step-02 -- src doc
```

각 단계의 코드·테스트·학습 문서를 완료 커밋에 포함하고 태그를 남긴다. 세부 규칙은 [커밋·검증 절차](doc/reference/03-learning-workflow.md)를 따른다.

## 범위

JDK 21, 블로킹 소켓, 본문 없는 HTTP/1.0·1.1, 연결당 한 요청, 자체 최소 Servlet 계약, 시작 전 프로그램 등록을 기준으로 한다. 세션·배포·클래스 로더·Jasper·NIO·운영 확장은 [역할과 제외 이유](doc/reference/02-scope-and-differences.md)를 문서로 학습한다.

[문서 목차](doc/README.md) · [현재 상태](doc/00-overview/01-progress.md) · [공식 소스 지도](doc/reference/01-tomcat-source-map.md)
