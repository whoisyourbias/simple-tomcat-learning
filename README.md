# simple-tomcat-learning

작은 서버를 개선하며 **Tomcat 내부 구성요소가 어떤 문제를 해결하는지** 배우는 프로젝트다. TCP 연결에서 출발해 Coyote·Catalina를 구현하고, 사용자 DispatcherServlet을 실행하는 내장 컨테이너까지 완주한다.

**현재 저장소는 설계 문서만 제공한다. 실행 환경과 0~7단계 구현·테스트·학습 태그는 모두 예정이다.** 기준 소스는 Apache Tomcat 10.1.34로 고정한다.

## 처음 공부한다면

1. Java 클래스·인터페이스·예외·스트림, 기본 HTTP 요청·응답 형식을 익힌다.
2. [전체 구조](doc/00-overview/02-architecture.md)와 [인터랙티브 구조도](doc/00-overview/tomcat-architecture.html)에서 네트워크·Coyote·Catalina의 책임을 읽는다. 구조도는 저장소를 내려받은 뒤 해당 HTML을 브라우저로 열어 요청·응답 애니메이션과 용어 설명을 확인한다. GitHub 파일 화면에서는 소스가 표시된다.
3. [전체 로드맵](doc/plans/2026-11-05-tomcat-learning-roadmap.md)으로 7단계의 문제·완료 조건을 확인한다.
4. [학습·검증·커밋 규칙](doc/reference/03-learning-workflow.md)에 따라 준비 단계의 JDK 21·Maven Wrapper·JUnit 환경을 구성한다.
5. [1단계 계획](doc/plans/2026-11-05-step-01-socket.md)의 실험부터 시작한다. 이전 코드의 최소 수정으로 문제를 해결한 뒤 책임 분리의 이유를 기록한다.
6. 테스트·HTTP 실험 후 실제 Tomcat 메서드와 호출 순서·상태 변화·오류 처리·자원 소유권을 대조한다.

## 고정된 학습 단계

| 단계 | 이전 문제·핵심 개념 | 완료 조건 | 계획·예정 태그 |
|---|---|---|---|
| 0 준비 | 빌드·검증 출발점 | 환경 테스트·JAR | [준비 절차](doc/reference/03-learning-workflow.md) · learn/step-00 |
| 1 서버 | 연결과 서버의 수명 | 연속 요청·미완성 요청·종료 | [1단계](doc/plans/2026-11-05-step-01-socket.md) · learn/step-01 |
| 2 Coyote | HTTP와 소켓 책임 분리 | 독립 파싱·오류 복구 | [2단계](doc/plans/2026-11-05-step-02-coyote.md) · learn/step-02 |
| 3 Servlet | 응답과 인스턴스 소유권 | HTTP로 init/service/destroy 1/2/1 | [3단계](doc/plans/2026-11-05-step-03-servlet.md) · learn/step-03 |
| 4 컨테이너 | 웹앱 경계·선택과 호출 | 다중 Host·Context·매핑 순서·404 | [4단계](doc/plans/2026-11-05-step-04-container.md) · learn/step-04 |
| 5 공통 처리 | Valve·Filter 적용 범위 | 전후 순서·중단·오류 복구 | [5단계](doc/plans/2026-11-05-step-05-filter.md) · learn/step-05 |
| 6 실행·종료 | 동시성·Lifecycle·구성 | 요청 격리·시작 실패·안전한 종료 | [6단계](doc/plans/2026-11-05-step-06-lifecycle.md) · learn/step-06 |
| 7 내장 사용 | SimpleTomcat·DispatcherServlet | 두 업무 경로·JAR 실행 | [7단계](doc/plans/2026-11-05-step-07-embedded.md) · learn/step-07 |

## 구현 후 사용할 명령

현재 Java 코드·Maven Wrapper는 없다. 다음은 0단계 구현 후 제공할 공통 검증 방법이다.

Windows PowerShell

```powershell
.\mvnw.cmd -B clean verify
```

macOS·Linux

```sh
sh ./mvnw -B clean verify
```

단계별 실행 클래스·curl·포트는 각 계획의 실행 항목을 따른다. 7단계에서 실행 가능한 JAR을 제공한다. 실제로 확인한 명령은 해당 단계 학습 문서와 README에 반영한다.

## 완료 태그로 공부하기

아래 명령은 해당 태그가 생성된 후 사용한다. 현재 학습 태그는 없다.

```sh
git clone https://github.com/whoisyourbias/simple-tomcat-learning.git
cd simple-tomcat-learning
git tag --list 'learn/*'

# 완성 코드를 읽는다.
git switch --detach learn/step-01

# 이전 단계에서 직접 구현한다.
git switch -c study/step-01 learn/step-00

# 책임과 테스트의 변화를 비교한다.
git diff learn/step-01..learn/step-02 -- src doc
```

각 단계의 코드·테스트·학습 문서를 완료 커밋에 포함하고 태그를 남긴다. 세부 규칙은 [커밋·검증 절차](doc/reference/03-learning-workflow.md)를 따른다.

## 범위

JDK 21, 블로킹 소켓, 본문 없는 HTTP/1.0·1.1, 연결당 한 요청, 자체 최소 Servlet 계약, 시작 전 프로그램 등록을 기준으로 한다. 세션·배포·클래스 로더·Jasper·NIO·운영 확장은 [역할과 제외 이유](doc/reference/02-scope-and-differences.md)를 문서로 학습한다.

[문서 목차](doc/README.md) · [현재 상태](doc/00-overview/01-progress.md) · [공식 소스 지도](doc/reference/01-tomcat-source-map.md)
