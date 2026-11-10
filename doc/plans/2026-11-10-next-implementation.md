# 5단계 Filter 구현 계획과 6·7단계 착수 조건

> **For agentic workers:** 구현을 명시적으로 요청받은 뒤 `superpowers:executing-plans`로 작업별 실행한다. 아래 체크박스는 예정 작업이며 구현 완료 표시가 아니다.

**Goal:** 기존 Valve·매핑·HTTP 동작을 유지하면서 Context 소유 Filter와 요청별 FilterChain을 연결하고 실제 HTTP에서 적용 범위·중단·오류 복구·해제를 검증한다.

**Architecture:** 기존 Engine→Host→Context→Wrapper Pipeline을 재사용한다. Context가 필터 정의와 초기화된 인스턴스를 소유하고, WrapperValve가 대여한 Servlet과 필터 목록으로 요청마다 새 체인을 만든다. 전체 서버 Lifecycle은 6단계에서 도입한다.

**Tech Stack:** Java 21, Maven Wrapper 3.3.2 / Maven 3.9.9, JUnit Jupiter 5.11.4, 블로킹 소켓, 자체 최소 Servlet API. 런타임 의존성 추가 없음.

**Spec:** [전체 로드맵](2026-11-05-tomcat-learning-roadmap.md), [5단계 범위](2026-11-05-step-05-filter.md), [4단계 실제 결과](../04-container/01-mapping-and-pipeline.md), 저장소 `AGENTS.md`.

작성일: 2026-11-10. 이번 요청은 상태 확인과 계획 작성이다. 이 문서의 새 인터페이스·상태·실패 정책은 **작성자의 구현 제안**이며 아직 구현·실행하거나 사용자 승인으로 간주하지 않았다. 6·7단계는 선행 조사 목록이며 즉시 구현 가능한 상세 계획으로 표시하지 않는다.

## Global Constraints

- 프로젝트 문서는 `doc/` 아래, 계획은 `doc/plans/YYYY-MM-DD-topic.md`에 둔다.
- Tomcat 비교 기준은 `10.1.34` 릴리스 태그로 고정한다. 실제 동작과 프로젝트 제안·미조사를 구별한다.
- 연결당 한 요청, 본문 없는 HTTP/1.0·1.1, 현재 URI 수용·거부 정책, 순차 처리를 유지한다.
- Filter 최소 범위는 **Context 전체 REQUEST·등록 순서**다. URL·Servlet 이름 매핑, DispatcherType 확장, async·재디스패치는 추가하지 않는다.
- 공유 Filter·Servlet 객체와 요청별 체인 위치를 구별한다. 사용자 코드의 필드에 대한 동시성 보장은 제공하지 않는다.
- 테스트 메서드 첫 테스트 애너테이션 바로 위에 한국어로 입력·상황과 기대 결과를 적는다.
- 네트워크 테스트는 포트 `0`, 연결·읽기 제한 3초, latch/Future 대기 최대 5초, 명시적 동기화와 finally 정리를 사용한다. `sleep`으로 순서를 추측하지 않는다.
- 단계 완료는 관련 테스트·전체 `clean verify`·실제 HTTP·학습 문서 확인 후 코드·테스트·문서를 함께 커밋하는 경계다. 그 뒤 `learn/step-05`를 생성하며 기존 공개 태그를 이동하지 않는다.
- 과거 검증 기록을 보존한다. 아래 예정 결과와 이번 상태 재검증 결과를 혼동하지 않는다.

## Review Focus

| 실패하기 쉬운 조건 | 기대 결과 | 담당 검증 |
|---|---|---|
| B 필터 init 실패·A 정리도 실패 | B 실패가 주원인, A 정리 실패는 suppressed, A destroy 1·B destroy 0·C init 0 | Task 2 |
| 필터가 chain을 호출하지 않음 | Servlet service 0, 이미 대여한 Servlet은 정확히 반환 | Task 1·3 |
| 필터 또는 Servlet이 부분 응답 뒤 실패 | 기존 Processor가 새 500, 다음 요청은 A부터 시작 | Task 3·4 |
| 두 Context에 다른 필터 등록 | Engine Valve는 양쪽, 각 Filter는 자기 앱만 실행 | Task 3·4 |
| 필터 사용 종료 전 해제·종료 중 실패 | 5단계는 실행자가 serve 완료 후 해제; 일반 destroy 실패에도 형제 정리, 반복 해제 시 재호출 없음 | Task 2·4; 활성 작업 보호는 6단계 |

## 1. 현재 작업 상태와 이번 확인 결과

| 항목 | 2026-11-10 확인 결과 |
|---|---|
| 브랜치·HEAD | `main`, `76f20ecc4298cebc6412a98f9d60406e2ba3ae86` |
| 작업 시작 시 변경 | `git status --short` 출력 없음 |
| 완료 태그 | `learn/step-00~04`; `learn/step-04`는 HEAD와 동일, 05~07 없음 |
| 0~2단계 | 빌드 환경, Endpoint, HTTP Processor·Protocol·Adapter 구현 |
| 3~4단계 | Servlet·Wrapper·대여 반환, 컨테이너 계층·Mapper·Pipeline·실제 HTTP 구현 |
| 이미 있는 5단계 기반 | `StandardPipeline.addValve/setBasic/getFirst`, `ValveBase`의 next 연결, 등록 순서·역순 복귀·중단 테스트 5사례 |
| 아직 없는 기능 | Filter·FilterChain·필터 수명, 제한 실행 풀·전체 Lifecycle·Connector, SimpleTomcat·실행 JAR 진입점 |
| 현재 위험·제약 | 순차 처리·읽기 제한 없음, 사용 중 Wrapper unload 거부, shutdown hook 최대 5초, 테스트 도구 생성 중 인터럽트 정리 후속 |

이번에 **기존 코드만** 재검증했다. 최초 샌드박스 실행 `mvnw.cmd -B clean verify`는 138사례 중 실패 1·오류 14로 종료 1이었다. TCP `Permission denied: getsockopt`와 HTTP 진입 latch 실패를 확인했다. 코드 수정 없이 샌드박스 밖 재실행은 **138사례, 실패/오류/건너뜀 0, BUILD SUCCESS, 종료 0**, `target/simple-tomcat-0.0.1-SNAPSHOT.jar` 생성이었다. 환경 제한 기록을 코드 결함 수정 기록으로 바꾸지 않는다.

생성 JAR에 대해 기존 `doc/experiments/ServletHttpExperiment.java`, `ContainerHttpExperiment.java`를 컴파일·실행했다. 두 프로세스 모두 종료 0: Servlet 두 GET의 init/service/destroy=1/2/0, 500·HEAD·다음 정상 요청·최종 1/4/1·반복 unload·포트 재사용; Container는 **curl 16사례**, 매핑·400/404/500·복구·반복 unload·포트 재사용 PASS였다. 이 결과는 4단계 회귀 확인이며 Filter 검증 결과가 아니다.

재현 명령은 [4단계 기록](../04-container/01-mapping-and-pipeline.md#2026-11-10-실제-검증)에 있다. 기존 학습 기록은 덮어쓰지 않는다.

## 2. 접근 방식과 제안

| 접근 | 장점 | 비용·판단 |
|---|---|---|
| 기존 Pipeline 유지 후 Filter만 추가 | 현재 태그 경계·138사례 회귀 유지, Valve와 앱 필터의 범위 차이를 관찰 가능 | **제안**. 이미 있는 Valve 연결 기능을 다시 구현하지 않음 |
| 전체 Lifecycle·동시성을 먼저 구현 | 수신 전 준비·종료를 한 번에 통합 | 기존 6단계 범위가 먼저 섞여 Filter와 공유 상태 학습 경계가 흐려짐 |
| Jakarta Servlet·실제 Tomcat을 의존성으로 도입 | 표준 API를 바로 사용 가능 | 자체 최소 계약·내부 책임 직접 구현이라는 현재 목표와 범위가 달라짐 |

실행 순서는 기존 로드맵대로 **5 Filter → 6 동시성·Lifecycle → 7 내장 API**를 제안한다. 이번에는 그 선택을 검토할 수 있는 문서까지만 작성한다.

## 3. Tomcat 10.1.34 고정 소스 조사

2026-11-10 아래 여섯 원본 파일을 고정 태그 raw URL로 내려받아 메서드 본문을 확인했다. 일반 셸의 DNS 접근 실패 후 권한 적용 다운로드로 확인했다. 아래 줄 번호는 내려받은 원본 Java 파일 기준이다.

| 소스·메서드 | 실제 책임·호출 순서·상태·오류·소유권 |
|---|---|
| [StandardPipeline.addValve/getFirst](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardPipeline.java#L268) | 일반 Valve를 basic 앞에 next로 연결한다. Contained에는 소유 컨테이너를 연결하고 사용 가능한 Pipeline이면 Lifecycle Valve 시작도 시도한다. 프로젝트는 next 연결만 이미 구현했으며 Lifecycle·이벤트는 없음 |
| [StandardContext.filterStart/filterStop](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardContext.java#L3842) | Context가 FilterConfig들을 소유한다. filterStart는 정의마다 Config 생성·성공한 것 보관, 일반 실패를 기록하고 나머지 정의도 시도한 후 boolean 반환. filterStop은 보관 Config의 release 후 맵 정리. 이 루프의 순서를 필터 매핑 실행 순서·역순 해제로 단정하지 않음 |
| [StandardContext.startInternal/stopInternal](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardContext.java#L4460) | 시작 중 filterStart 실패면 ok=false, 이어지는 loadOnStartup을 건너뛰고 Context 시작 실패 경로로 간다. 정지에서는 자식 컨테이너 stop 뒤 filterStop 호출(4654행). 실패 객체의 전체 재시작·롤백 동작은 LifecycleBase까지 더 조사해야 함 |
| [ApplicationFilterConfig 생성자/getFilter/initFilter/release](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/ApplicationFilterConfig.java#L215) | Config 생성 중 필터 생성 또는 제공 객체 확보 후 init(FilterConfig). InstanceManager·JMX 경로 존재. release는 destroy·주입 객체 정리·참조 제거, 일반 destroy 실패는 로그 기록. 생성자가 init에서 실패한 객체가 성공 Config 맵에 등록된다고 단정하지 않음 |
| [ApplicationFilterFactory.createFilterChain](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/ApplicationFilterFactory.java#L57) | Servlet 없으면 null. 보안 설정과 요청 종류에 따라 새 체인 또는 Request에 보관한 체인을 사용. DispatcherType·경로·Servlet 이름으로 선택하며 URL 매핑 필터 다음 Servlet 이름 매핑 필터를 추가. 실행 인스턴스는 Context의 Config에서 찾음 |
| [ApplicationFilterChain.doFilter/internalDoFilter/addFilter/release](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/ApplicationFilterChain.java) | 체인의 pos를 증가시키고 필터 호출, 끝이면 service. Config 중복 추가 방지. IOException·ServletException·RuntimeException은 재전파하고 다른 Throwable은 치명 오류 확인 후 래핑하는 경로 존재. release는 인스턴스 destroy가 아니라 필터·Servlet 참조와 pos/n 정리 |
| [StandardWrapperValve.invoke](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapperValve.java#L141) | Wrapper allocate 후 체인 생성·실행. 오류는 요청·응답 오류 상태에 반영. finally에서 체인 release 뒤 deallocate. 소켓을 소유하거나 필터를 요청마다 destroy하지 않음 |

원본 파일 SHA-256(재조회 식별용):

```text
ApplicationFilterChain.java   F37A8E9ED9FA7265A74AAB165C7221605FC42DB11B3914BD3E2B460C412471D9
ApplicationFilterConfig.java  B670CC5BC50B33EC1AB086B35CF825D00C03982C2BF23B06D823DB4017614318
ApplicationFilterFactory.java 9FC79D80AECBB1A79CDD8708904C98D348435E87B17C0E36894F21862528FEB5
StandardContext.java          9F935288D86C51A3ED02CEC4CFA8D8B189D35935D3A2D9A926CA80A2CBE552D2
StandardPipeline.java         89042DD44F137256A6CDC4CC655EE567788FDACCC6B5EF2A455660E4EC0AD763
StandardWrapperValve.java     3E46CBBA5A08D1502C0F4C894B9B1293D591D9E19536F9BF6B2DC103B78E648A
```

### 핵심 동작 대응

| 동작 | Tomcat 책임 | 프로젝트 예정 호출·소유권 | 검증 |
|---|---|---|---|
| 컨테이너 공통 처리 | Pipeline·next·basic | 기존 Engine Valve→하위 Pipeline; 소켓은 Endpoint 소유 유지 | 두 앱 Valve 전후·중단 |
| 앱 공통 처리 | Factory 선택·Chain 실행 | 선택 Context의 목록→새 Chain→A→B→Servlet→B→A | 호출 순서·앱 격리 |
| Servlet 반환 | WrapperValve allocate/deallocate | allocate 성공 후 체인 생성·실행 전체를 try/finally로 감싸 반환 | 생성·필터·service 실패와 반환 실패 |
| Filter 수명 | Context·ApplicationFilterConfig | Context startFilters→init 성공 목록 공개→요청 간 재사용→stopFilters | init 1, 복수 요청, destroy 1 |
| 체인 상태 | ApplicationFilterChain.pos | 필터 목록 snapshot과 위치는 요청별 객체에만 보관 | 두 체인 교차 호출·오류 뒤 위치 0 |
| 서버 종료 | 전체 Lifecycle과 Context stop | 5단계 실행자가 protocol.close→serve 완료→Wrapper unload→Filter stop | 해제 순서·포트 반환; 강제 graceful 보장 없음 |

## 4. 차이·생략과 영향

| 항목 | 실제 Tomcat 동작 | 프로젝트 예정 동작 | 이유 | 영향 | 검증 | 후속 여부 |
|---|---|---|---|---|---|---|
| API·생성 | Jakarta FilterConfig·InstanceManager·주입·JMX | 자체 Filter, Supplier, 인자 없는 init | 현재 Servlet 계약과 일관성 | Jakarta 호환·설정 주입 없음 | 계약·인자 동일성·횟수 | 의도적 유지 |
| 적용 순서 | Dispatcher·URL 매핑 후 Servlet 이름 매핑, Config 중복 방지 | Context 전체 REQUEST, 고유 이름 등록 순서 | 체인의 의미에 집중 | 세부 매핑·forward/error/async 없음 | 두 앱·순서·중복 이름 거부 | 의도적 유지 |
| 시작 실패 | filterStart가 일반 실패 후 다른 정의도 시도, Context 시작 실패로 연결 | 최초 일반 실패에서 중단, init 성공분 역순 정리, FAILED | 부분 준비 상태를 외부에 공개하지 않음 | Tomcat의 동일 실패·재시작 정책이 아님 | A 성공/B 실패/C 미호출·정리 실패 보존 | 6단계 전체 rollback과 연결 |
| 실패 init 객체 | Config 생성 성공 전 예외 경로 존재, 전체 정리 보장 미조사 | 실패 필터 destroy 생략; init 내부 부분 자원은 필터가 회수 | init 성공분만 컨테이너 소유로 공개 | init 구현의 실패 정리 책임 필요 | 실패 객체 destroy 0, 성공분 destroy 1 | 실제 전체 실패 정책 추가 조사 |
| 체인 수명 | Request 보관·재사용 가능, release로 참조·위치 정리 | 매 요청 새 체인, 호출 종료 후 미보관 | 위치 공유 제거 | 할당 증가; 체인을 비동기로 보관·재사용 금지 | 새 체인·교차 호출·복구 | 의도적 유지 |
| 요청 예외 | Valve가 오류 상태·unavailable 등 처리, Chain에 Throwable 변환 경로 | IOException/ServletException/RuntimeException/Error 원형 전파·반환 보장, 일반 Exception은 기존 Processor 500 | 기존 경계 보존 | 오류 페이지·unavailable 없음; Error의 HTTP 복구 보장 없음 | 동일 예외·suppressed·500/다음 200 | 의도적 유지 |
| 종료 순서·실패 | 자식 stop 후 filterStop, Config.release 일반 실패 로그 | Wrapper unload 뒤 Filter 역순 destroy; RuntimeException 모아 ServletException으로 보고, 반복 시 재호출 없음 | 획득 역순·실패 관찰성 | 실제 필터 destroy 순서와 동일하다는 보장 없음 | 순서·형제 정리·실패 반환 | 6단계 종료 조정 |
| 활성 작업 보호 | Lifecycle·가용성·요청 종료 조정 등 연결 | start/stop은 실행자가 요청 밖에서 직렬 호출 | 순차 단계 유지 | stopFilters 자체로 활성 요청 안전 종료를 보장하지 않음. Error·무한 콜백의 전체 정리도 보장하지 않음 | serve 완료 뒤 stop만 사용 | 6단계 필수 |
| Valve 관리 | Contained·Lifecycle·컨테이너 이벤트 | 기존 next 체인 재사용, 인스턴스는 Pipeline 하나 전용 | 중복 구현 방지 | 동적 변경·Valve Lifecycle 없음 | 기존 5사례·HTTP 범위 | 전체 시작 후 변경 거부는 6단계 |

## 5. 파일 책임과 제안 인터페이스

| 파일 | 변경·책임 |
|---|---|
| `src/main/java/simpletomcat/servlet/Filter.java` | 신규 최소 필터 계약 |
| `src/main/java/simpletomcat/servlet/FilterChain.java` | 신규 다음 호출 계약 |
| `src/main/java/simpletomcat/catalina/core/ApplicationFilterChain.java` | 신규 불변 목록·요청별 위치·마지막 Servlet 호출 |
| `src/main/java/simpletomcat/catalina/Context.java` | Filter 등록·준비·조회·해제 계약 추가 |
| `src/main/java/simpletomcat/catalina/core/StandardContext.java` | 정의 순서·성공 인스턴스·필터 전용 상태 소유 |
| `src/main/java/simpletomcat/catalina/core/StandardWrapperValve.java` | 직접 service 대신 Context 목록으로 체인 실행, 기존 반환·원인 보존 |
| `src/main/java/simpletomcat/examples/FilterServer.java` | 신규 두 앱·전후·중단·예외 예제, 시작 실패·종료 정리 소유 |
| `src/test/java/simpletomcat/catalina/core/ApplicationFilterChainTest.java` | 신규 순서·전달·중단·위치 테스트 |
| `src/test/java/simpletomcat/catalina/core/StandardContextFilterTest.java` | 신규 등록·초기화·실패·해제 테스트 |
| `src/test/java/simpletomcat/catalina/core/StandardWrapperValveTest.java` | 필터 실패와 대여 반환 회귀 보강 |
| `src/test/java/simpletomcat/catalina/core/ContainerInvocationTest.java` | 두 앱 범위와 기존 Valve 연결 검증 보강 |
| `src/test/java/simpletomcat/catalina/connector/FilterHttpTest.java` | 신규 실제 HTTP 회귀 |
| `doc/experiments/FilterHttpExperiment.java` | 신규 생성 JAR·curl·해제·포트 실험 |
| `doc/05-filter/01-filter-chain.md` | 구현 때 신규 학습·실제 실행 기록 |

공개 계약 제안:

```java
// simpletomcat.servlet.Filter
void init() throws ServletException;
void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException;
void destroy();
// simpletomcat.servlet.FilterChain
void doFilter(ServletRequest request, ServletResponse response)
        throws IOException, ServletException;
// simpletomcat.catalina.Context
void addFilter(String name, java.util.function.Supplier<? extends Filter> factory);
void startFilters() throws ServletException;
java.util.List<Filter> getFilters();
void stopFilters() throws ServletException;
// simpletomcat.catalina.core.ApplicationFilterChain
public ApplicationFilterChain(java.util.List<Filter> filters, Servlet servlet);
```

목록은 생성 시 `List.copyOf`로 고정한다. FilterChain은 다음 요소 또는 Servlet에 전달 인자를 그대로 넘긴다. 체인 자체는 필터를 초기화·해제하거나 예외를 HTTP로 변환하지 않는다. 동기 요청 안에서만 사용하며 각 필터는 chain을 0회 또는 1회 호출한다. 중복 호출 탐지 기능은 이번 범위에 추가하지 않는다.

### 필터 전용 상태·실패 계약

- `NEW → STARTING → STARTED`, 일반 준비 실패는 `FAILED`. `stopFilters`는 `NEW/STARTED/FAILED → STOPPING → STOPPED`; 전체 서버 Lifecycle 상태와 다른 내부 상태다.
- addFilter는 NEW에서만 허용. null 이름·factory는 NullPointerException, blank·중복 이름은 IllegalArgumentException, NEW 외 등록은 IllegalStateException. 실패 등록은 기존 순서를 바꾸지 않는다.
- startFilters는 STARTED 재호출 시 no-op, STARTING/STOPPING/STOPPED/FAILED에서는 IllegalStateException. factory는 Context마다 별개 새 비초기화 객체를 제공하는 호출자 계약이다.
- factory null 반환·RuntimeException은 ServletException으로 감싸고, init의 ServletException은 주원인으로 유지. 최초 실패에서 뒤 정의는 시도하지 않는다. 성공분만 역순 destroy하고 RuntimeException 정리 실패는 주원인에 suppressed로 보존한다. 실패 객체의 부분 자원은 init 책임이며 destroy 호출하지 않는다.
- 성공 목록은 모두 init된 뒤 한 번에 공개한다. getFilters는 STARTED면 불변 목록, **필터 정의가 없는 NEW에 한해 빈 목록**을 반환해 기존 무필터 예제·테스트를 보존한다. 그 외 미준비·정지·실패 상태는 IllegalStateException이다.
- stopFilters는 STOPPED 재호출 no-op. STARTING/STOPPING 재진입은 IllegalStateException. 성공분을 역순으로 한 번씩 해제하고 RuntimeException은 첫 원인을 감싼 ServletException에 모아 보고한다. 일반 실패에도 참조를 제거하고 STOPPED로 만든다. Error는 전파하며 모든 형제 정리를 보장한다고 서술하지 않는다.
- 이 단계는 재시작·사용 중 stop 경쟁을 지원하지 않는다. 예제 실행자는 모든 요청 처리가 끝난 뒤 stopFilters를 호출한다. 무한 init/doFilter/destroy에 대한 유한 종료는 제공하지 않는다.

## 6. 작업별 구현·검증 순서 — 모두 미실행

### Task 1: 최소 Filter 계약과 요청별 체인

**Files:** 신규 Filter·FilterChain·ApplicationFilterChain·ApplicationFilterChainTest(위 표의 정확한 경로).

**Interfaces:** 기존 ServletRequest/ServletResponse/Servlet 사용. 위 생성자와 `FilterChain.doFilter`를 제공하며 Task 3이 사용한다.

- [ ] `ApplicationFilterChainTest`에 `invokesInRegistrationOrder`, `shortCircuits`, `passesArgumentsUnchanged`, `keepsPositionsPerChain`, `propagatesFailure`를 먼저 작성한다. assertions: `List.of("A-before", "B-before", "service", "B-after", "A-after")`, 중단 service=0, 동일 요청·응답 객체, 두 체인의 A/B/service 각 1, 주입 예외와 assertSame. 빈 목록은 service=1, 입력 목록 변경 후에도 생성 당시 순서 보존도 확인한다.
- [ ] `.\mvnw.cmd -B '-Dtest=ApplicationFilterChainTest' test`로 타입 미구현 또는 assertion 실패를 확인하고 원인을 기록한다.
- [ ] 계약·생성자를 구현한다. 체인 위치는 인스턴스 필드이며 필터 호출 전에 증가시킨다. 정적 위치·Context 필드 위치·Servlet 인스턴스의 위치 저장은 사용하지 않는다.
- [ ] 같은 명령으로 전 사례 통과를 확인한다. 작업 커밋 `feat(step-05): 요청별 FilterChain 추가`를 만들되 완료 태그는 붙이지 않는다.

### Task 2: Context 필터 등록·준비·실패 정리

**Files:** Context·StandardContext 수정, StandardContextFilterTest 신규. 기존 매핑·자식 등록은 보존한다.

**Interfaces:** Task 1의 Filter를 소비하고 `addFilter/startFilters/getFilters/stopFilters`를 제공한다. Task 3은 getFilters, Task 4는 나머지 수명 메서드를 사용한다.

- [ ] `StandardContextFilterTest`에 `preservesRegistrationOrder`, `rejectsInvalidRegistrationAtomically`, `initializesOnceAndPublishesImmutableList`, `rollsBackOnInitFailure`, `preservesInitAndCleanupFailures`, `destroysInReverseOrderOnce`, `continuesAfterDestroyFailure`, `enforcesFilterStates`를 작성한다. 위 상태 전이·예외 타입·getFilters의 무필터 NEW 예외를 assertion으로 고정한다.
- [ ] A 성공/B init 실패/C 대역에서 `A.init, B.init, A.destroy`만 기록, B/C destroy=0·C factory=0·getFilters 거부를 확인한다. A destroy도 실패하면 B 원인 보존·suppressed A를 확인한다. factory null/실패도 같은 rollback 경로를 검증한다.
- [ ] `.\mvnw.cmd -B '-Dtest=StandardContextFilterTest' test` 실패 확인 후 필터 정의 LinkedHashMap·성공 목록·상태 전이를 구현한다. 기존 Container 수명으로 확대하지 않는다.
- [ ] 같은 명령과 `'-Dtest=ContainerHierarchyTest,MapperTest' test`로 필터 수명·기존 계층·매핑 통과를 확인한다. 작업 커밋 `feat(step-05): Context 필터 수명 관리`를 만든다.

### Task 3: WrapperValve 연결·Valve와 Filter 범위

**Files:** StandardWrapperValve·StandardWrapperValveTest·ContainerInvocationTest 수정. StandardPipeline은 기존 기능을 사용한다.

**Interfaces:** Task 2의 Context.getFilters와 Task 1 생성자 사용. 기존 `invoke(ContainerRequest, ServletResponse)`·Wrapper 대여 반환 계약을 유지한다.

- [ ] `StandardWrapperValveTest`에 `returnsAfterFilterShortCircuit`, `returnsAfterFilterFailure`, `returnsAfterChainPreparationFailure`, `preservesFilterFailureWhenReturnFails`를 추가한다. 체인 준비·중단·검사 예외·RuntimeException·Error에도 allocate 성공이면 deallocate=1, 실패 주원인과 suppressed 반환 실패가 보존됨을 검증한다.
- [ ] `ContainerInvocationTest`에 두 Context `/shop`, `/admin`을 만들고 `appliesEngineValveToBothAppsAndFilterToOwnerOnly`를 추가한다. shop 호출은 Engine-before/A-before/service/A-after/Engine-after, admin은 Engine-before/service/Engine-after를 assert한다. 필터 없는 기존 계층 호출 순서도 보존한다.
- [ ] 관련 두 테스트 실행의 실패를 확인한 뒤 WrapperValve를 변경한다. 선택 Context가 있으면 owner의 부모와 동일한지 검사하고 그 목록을 사용한다. Context와 owner의 부모가 모두 없는 기존 단독 Wrapper 호출만 빈 목록으로 처리한다. 부모 Context가 있는데 선택 Context가 없거나 다르면 IllegalArgumentException으로 거부하는 테스트도 추가해 필터 우회를 막는다. allocate 이후 목록 조회·체인 생성·실행을 모두 기존 try/finally 안에 두어 준비 실패에도 반환한다.
- [ ] `.\mvnw.cmd -B '-Dtest=ApplicationFilterChainTest,StandardContextFilterTest,StandardWrapperValveTest,ContainerInvocationTest,StandardPipelineTest' test`로 통과 확인. 작업 커밋 `feat(step-05): WrapperValve에 필터 체인 연결`을 만든다.

### Task 4: 실제 HTTP·예제 시작과 종료·학습 기록

**Files:** FilterServer·FilterHttpTest·FilterHttpExperiment 신규. 구현 후 `doc/05-filter/01-filter-chain.md`, README·진행 상황·범위·구조도·소스 지도에 실제 상태를 반영한다. 필요하면 `HttpTestServer` 생성 실패 정리만 이 작업의 테스트 자원 책임으로 보완한다.

**Interfaces:** FilterServer는 `public static StandardEngine createEngine()`, `public static void startFilters(StandardEngine engine) throws ServletException`, `public static void stopFilters(StandardEngine engine) throws ServletException`, `public static void main(String[] args) throws Exception`를 제공한다. 기존 Mapper·CoyoteAdapter(Engine, Mapper)·Http11Protocol·ContainerServer.unloadServlets를 재사용한다.

- [ ] `FilterHttpTest`에 `filtersOnlySelectedApp`, `shortCircuitSkipsServlet`, `recoversAfterFilterAndServletFailure`, `suppressesHeadBody`, `destroysOnlyAfterServingCompletes`, `cleansFiltersWhenProtocolConstructionFails`를 먼저 추가한다. 정상 200·중단 service=0·부분 문자열 없는 500·다음 200·HEAD 본문 0바이트·init/destroy=1을 확인한다. 바인딩 실패는 점유한 임의 포트를 사용하고 외부 listener는 유지·이미 시작한 Filter만 정리되는지 확인한다.
- [ ] 예제는 localhost의 `/shop`, `/admin`에 `/hello` Servlet을 두고 정상 본문을 각각 `shop hello\n`, `admin hello\n`으로 고정해 HTTP 테스트에서도 비교한다. Engine 기록 Valve는 두 앱에 적용한다. shop의 A/B는 등록 순서로 전후 기록, `/shop/blocked`는 A가 **200과 `blocked\n`**을 작성하고 chain 생략, `/shop/filter-fail`은 B가 부분 본문 뒤 일반 예외, `/shop/servlet-fail`은 Servlet이 예외를 던지게 기본 `/` 매핑한다. 기존 Processor 응답 계약을 확장하지 않는다.
- [ ] 두 Context 준비 후에만 프로토콜을 생성·serve한다. 뒤 Context 초기화 실패 또는 프로토콜 생성 실패면 앞서 준비한 Context도 해제한다. 생성 후 serve 실패·정상 종료는 protocol close→serve 반환→모든 Wrapper unload→Filter stop 순서다. 일반 Wrapper unload 실패에도 Filter 정리와 다른 Context 정리를 시도하고 최초 실패·suppressed를 보존한다. 기존 ServerRunner의 5초 hook 한계는 학습 문서에 유지한다.
- [ ] `.\mvnw.cmd -B '-Dtest=FilterHttpTest,ServletHttpTest,ContainerHttpTest' test` 통과 후 `.\mvnw.cmd -B clean verify`를 실행한다. 기존 138사례와 신규 사례 수를 실제 보고서에서 집계하고 0 실패·오류·건너뜀 및 JAR 생성을 기록한다. 환경 권한 실패와 앱 실패를 구별한다.
- [ ] `doc/experiments/FilterHttpExperiment.java`로 **생성 JAR**을 조립·호출한다. 포트 0·curl `--max-time 3`·Future 최대 5초·finally 종료를 사용한다. 위 두 앱·중단·필터 500·Servlet 500·다음 정상·HEAD·초기화/해제 횟수·반복 해제·같은 포트 재바인딩을 확인한다. 기존 Servlet/Container 실험도 재실행한다.
- [ ] 학습 문서에 이전 한계→최소 감싸기→Valve와 Filter 책임 분리→호출 순서→실제 명령·날짜·기준 커밋·결과→Tomcat 차이→6단계 문제를 쓴다. 이번 계획의 예정 assertion을 실행 결과처럼 복사하지 않는다.
- [ ] 코드·테스트·학습 문서를 포함해 `feat(step-05): Context Filter와 실제 HTTP 처리 구현` 완료 커밋을 만든다. 검증 성공 후 `git tag learn/step-05`를 생성한다. 이미 존재하면 중지하고 이동하지 않는다.

예정 JAR 실험 명령(파일은 구현 때 생성하며 **아직 실행할 수 없음**):

```powershell
New-Item -ItemType Directory -Force target/experiments
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar -d target/experiments doc/experiments/FilterHttpExperiment.java
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' FilterHttpExperiment
```

## 7. 6·7단계로 넘길 항목 — 상세 조사 전

| 단계 | 현재 코드에서 출발할 문제·변경 위치 | 구현 전에 확정할 조사·정책 | 완료 검증 |
|---|---|---|---|
| 6 실행 | Endpoint.current 단일 소켓·순차 serve, Http11Protocol에서 Processor 사용 방식 | AbstractEndpoint.processSocket·NioEndpoint.SocketProcessor·Executor의 거부/소켓 소유권 재조사; 작업자/큐/읽기/종료 제한 값, 포화 시 응답·close 정책 확정 | latch 느린 연결 동안 다른 요청, 큐 포화·읽기 제한 뒤 복구·요청 데이터/체인 격리 |
| 6 수명 | ServerRunner의 실행자 소유 종료, Wrapper 사용 중 unload 거부, 필터 수동 준비·해제 | StandardServer/StandardService/Connector/LifecycleBase의 시작 실패·pause·stop·destroy 추적. 최소 상태·등록 동결·다중 Connector 부분 실패·재시도 정책 결정 | 수신 전 앱 준비, 바인딩/초기화 실패 rollback, 신규 수락 중지→요청 완료→Wrapper→Filter 해제 |
| 6 종료 실패 | 무한 callback·5초 hook·사용 중 자원 | 제한 후 실패 보고·활성 인스턴스 해제 보류·완료 후 재시도; 소켓 취소와 앱 종료를 구별. Thread.stop 사용 금지 | 진행 요청에는 destroy=0, 요청 완료 뒤 재시도 destroy=1·포트 반환·반복 close |
| 7 내장 API | 예제가 Engine/Host/Context/Mapper/Protocol 조립을 직접 수행 | startup.Tomcat.addContext/addServlet/start 및 6단계 최종 API를 읽고 SimpleTomcat의 등록 반환 타입·실패·port/close 계약 확정 | 내부 코드 수정 없이 Servlet/Filter 등록·포트 0 시작·반복 종료 |
| 7 완주 | 일반 JAR에는 Main-Class 없음 | 자체 DispatcherServlet의 `/hello`, `/orders/count` 경로·404/500 정책, Manifest 진입점·실제 실행 명령 확정 | 같은 Dispatcher로 두 업무 경로, 오류 뒤 복구, java -jar·curl·종료, 0~7 태그 별도 체크아웃 검증 |

6단계 상세 계획은 [기존 6단계 범위](2026-11-05-step-06-lifecycle.md), 7단계는 [기존 7단계 범위](2026-11-05-step-07-embedded.md)를 보존하고 실제 선행 단계 결과를 반영해 새 날짜 문서로 구체화한다. 위 미확정 정책을 Tomcat의 확정 동작이나 구현 준비 완료로 표시하지 않는다.

## 8. 계획 점검과 이번 문서의 완료 경계

5단계 기존 요구인 전후 순서·중단·앱 범위·오류 뒤 위치 복구·필터 초기화/해제는 Task 1~4에 대응시켰다. 현재 Valve 기반과 standalone Wrapper 호출을 보존하고 새 목록 조회가 실패하는 경로도 반환 책임 안에 포함했다. 정의한 인터페이스는 작업별 소비·제공 계약과 맞춰 자체 점검했다.

이번 문서 작성으로 **구현 완료 상태·태그는 바뀌지 않는다**. 이번 실제 검증은 4단계 기준선 재확인이며 5단계 작업 체크박스는 모두 미완료다. 구현은 사용자가 단계를 명시적으로 요청한 뒤 진행한다.


## 2026-11-10 — 사용자 5단계 구현 요청 이후 결과

위는 계획 작성 당시 기록으로 보존한다. 사용자 명시 요청으로 Task 1~4의 기능을 구현했다. 실제 테스트 파일은 FilterInvocationTest와 FilterServerLifecycleTest로 반환·정리 검증을 별도 분리했다. 예제 바인딩 실패를 직접 검증하기 위해 FilterServer.open과 closeApplications, trace 주입 createEngine 오버로드를 추가했다. 작업별 중간 커밋 대신 검증된 코드·테스트·문서 전체를 하나의 단계 완료 커밋으로 묶는다. 159사례·clean verify·JAR HTTP 실험 결과와 실패 이력은 [5단계 학습 기록](../05-filter/01-filter-chain.md)에 남겼다. 이전 체크박스는 계획 원문이며 실행 결과는 이 추가 기록을 따른다.
