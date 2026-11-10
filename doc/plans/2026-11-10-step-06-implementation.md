# 6단계 동시 요청·서버 생명주기 상세 구현 계획

> **For agentic workers:** 사용자가 6단계 구현을 명시적으로 요청한 뒤 `superpowers:executing-plans`로 작업별 실행한다. 체크박스는 예정 작업이며 구현 완료 표시가 아니다.

**Goal:** 제한된 동시 요청 처리와 Server→Service→Connector 소유권을 연결하고, 시작 실패 정리·신규 수락 중지·진행 작업 대기·해제 보류·종료 재시도를 실제 HTTP로 검증한다.

**Architecture:** 기존 순차 Endpoint·Http11Protocol 예제는 보존한다. 같은 Http11Processor·CoyoteAdapter·Mapper·Pipeline을 사용하는 ConcurrentEndpoint·ConcurrentHttp11Protocol을 추가한다. Service가 구성 동결과 앱 준비를 조정하고, 모든 Connector의 작업 종료를 확인한 뒤 Wrapper→Filter를 해제한다.

**Tech Stack:** Java 21, Maven Wrapper 3.3.2 / Maven 3.9.9, JUnit Jupiter 5.11.4, JDK 블로킹 Socket·ThreadPoolExecutor·ArrayBlockingQueue. 런타임 의존성 추가 없음.

**Spec:** [6단계 기존 범위](2026-11-05-step-06-lifecycle.md), [전체 로드맵](2026-11-05-tomcat-learning-roadmap.md), [5단계 실제 결과](../05-filter/01-filter-chain.md), [이전 계획의 6·7단계 조사 목록](2026-11-10-next-implementation.md), 저장소 `AGENTS.md`.

작성일: 2026-11-10. **이번 산출물은 조사와 계획이다. 아래 신규 타입·설정값·실패 정책은 작성자의 프로젝트 제안이며 구현·실행·사용자 승인 사실이 아니다.** 6단계 코드·테스트·학습 완료 기록·`learn/step-06`은 이번에 만들지 않는다.

## Global Constraints

- 문서는 `doc/`, 계획은 `doc/plans/YYYY-MM-DD-topic.md`에 둔다. 과거 계획·실패·검증 기록을 보존한다.
- 비교 기준은 Apache Tomcat `10.1.34` 고정 소스다. 확인한 분기와 미조사 영역을 구분한다.
- 연결당 한 요청, 본문 없는 HTTP/1.0·1.1, 기존 URI·HEAD·400/404/500/501/505 정책을 유지한다. keep-alive·async·NIO·표준 Jakarta API·7단계 내장 API는 추가하지 않는다.
- 기존 순차 실행 경로·예제와 현재 159사례를 회귀 대상으로 유지한다. 공유 Servlet·Filter·Valve의 업무 필드를 자동으로 동기화하지 않는다.
- 테스트 메서드 첫 테스트 애너테이션 바로 위에 한국어로 입력·상황과 기대 결과를 적는다.
- 네트워크 테스트는 포트 `0`, 클라이언트 연결·읽기 제한 각각 `3초`, latch·Future·테스트 실행기 종료 대기 최대 `5초`를 사용한다. 서버 정책 시간은 테스트마다 명시한다. `sleep`이나 스택 프레임 이름으로 실행 순서를 추측하지 않는다.
- `finally`에서 latch 해제→클라이언트 close→서버 종료 재시도→테스트 실행기 종료를 수행한다. 실패한 생성자가 만든 소켓·실행기도 정리한다.
- 종료는 소켓 close·작업자 interrupt와 작업 완료를 구별한다. `Thread.stop`을 사용하지 않는다. 작업이 남으면 해당 Service의 **전체** 앱 해제를 보류한다.
- 단계 완료는 관련 테스트·전체 `clean verify`·생성 JAR HTTP 실험 후 코드·테스트·학습 문서를 함께 담은 커밋과 새 `learn/step-06` 태그다. 기존 공개 태그는 이동하지 않는다.

## Review Focus

| 놓치기 쉬운 상황 | 고정할 기대 결과 | 담당 |
|---|---|---|
| accept 직후 stop·작업 제출 거부·큐에서 꺼내기 경합 | 수락 소켓 누락 없음, 거부 연결 정리, 종료 후 앱 신규 진입 없음 | Task 2 |
| 소켓이 닫혔지만 Servlet이 interrupt를 무시하며 실행 중 | STOPPED 금지, Wrapper·Filter destroy=0, 완료 후 stop 재시도로 각각 1회 | Task 5 |
| 둘째 Connector 바인딩 또는 뒤 Service 시작 실패 | 앞서 준비한 자원 정리, 원래 실패 보존, 진행 요청이 있으면 앱 해제 보류 | Task 4·5 |
| 보관해 둔 Pipeline·Valve·매핑 뷰로 구성 변경 | 수신 전 동결, 변경 거부, 요청별 URI·응답·체인 위치 격리 | Task 1·3 |
| destroy 지연·실패와 반복 stop | 동일 정리 작업만 대기, 중복 destroy 없음, 시간 초과와 영구 콜백 실패 구별 | Task 5 |

## 1. 현재 기준선과 이번 확인 범위

| 항목 | 2026-11-10 이번 확인 |
|---|---|
| HEAD·완료 경계 | `bf79de7ebc0339262a54884fc65b5d18ea19d8ca`, `learn/step-05`가 같은 커밋 |
| 작업 시작 상태 | `git status --short` 출력 없음, 태그 `learn/step-00~05` 존재, 06·07 없음 |
| 기존 실행 증거 | 5단계 기록에 159사례·clean verify·JAR·Servlet/Container/Filter HTTP 실험 통과가 있음. **이번에는 재실행하지 않음** |
| Endpoint | 생성자 즉시 bind, `current` 한 소켓, 호출 스레드에서 순차 `serve`; close는 listener·current 취소, 실행 스레드 완료를 기다리지 않음 |
| Protocol·Processor | Http11Protocol이 Processor 하나를 보관한 Handler를 연결. Processor의 ParsingContext·Request·Response는 호출 지역 변수지만 동시 실행 계약은 아직 없음 |
| Catalina | Mapper는 등록 구조를 읽음. Wrapper allocate/deallocate는 synchronized, service는 잠금 밖. Filter 인스턴스는 Context 공유, FilterChain은 요청마다 새로 생성 |
| 구성·정리 | ContainerBase 자식은 HashMap, 매핑 뷰는 읽기 전용이나 원본은 변경 가능, Pipeline·Valve next 변경 가능. FilterServer가 수동 준비·해제를 조정 |
| 실행자 한계 | ServerRunner가 즉시 소켓 취소 후 serve 완료를 기다리며 hook 대기는 5초. HttpTestServer는 시작 latch 대기 중 interrupt의 생성 실패 정리가 후속 항목 |

확인 명령: `git status --short`, `git rev-parse HEAD`, `git show-ref --tags`, `rg --files src`와 관련 Java 본문 읽기. 문서 작성으로 과거 159사례를 새 실행 결과로 바꾸지 않는다.

## 2. 접근 선택과 학습 경계

| 접근 | 장점 | 비용·판단 |
|---|---|---|
| 기존 Endpoint에 순차·동시·graceful 모드를 모두 추가 | 기존 Http11Protocol 이름 하나로 사용 | 즉시 close와 대기 종료가 한 클래스에 섞이고 이전 예제 계약까지 변경됨 |
| 별도 동시 전송·프로토콜 조립, HTTP·Catalina 재사용 | 이전 태그·예제 의미 유지, 동시성과 수명 변경 위치가 명확 | 작은 조립 클래스 1개가 늘어남. **이번 계획의 제안** |
| NIO Poller·Processor 재활용까지 구현 | 원본과 구조가 더 가까움 | 6단계 목적보다 I/O 상태기계 비용이 커 현재 제외 범위와 충돌 |

Endpoint 복제에 HTTP 파서를 넣지 않는다. 새 ConcurrentHttp11Protocol의 Handler는 연결마다 `new Http11Processor(adapter)`로 처리한다. 기존 Processor가 이미 지역 상태를 쓰는 점을 기록하되, 새 경로는 Processor 재활용·동시 호출 증명을 요구하지 않는 단순한 소유권을 선택한다.

컨테이너 모두에 Tomcat 전체 Lifecycle을 복제하지 않는다. 이번에는 Server·Service·Connector에 최소 Lifecycle을 두고, Service의 ApplicationLifecycle이 기존 Context·Wrapper의 준비·해제를 조정한다. 7단계 SimpleTomcat은 이 구성·수명을 재사용한다.

## 3. Tomcat 10.1.34 원본 조사

공개 GitHub 연결 도구는 이번 세션에서 fetch 실패였다. 웹으로 StandardService 원본을 확인하고, 일반 셸 다운로드의 DNS 실패 뒤 권한 적용 읽기 전용 다운로드로 아래 **14개 파일의 본문과 SHA-256**을 확인했다. 임시 사본은 빌드 산출물 영역 `target/step06-source/`에 두었으며 저장소에 원본 전체를 추가하지 않는다. 아래 행 번호는 원본 Java 파일의 1기준 번호다.

| ID·원본 메서드 | 확인한 실제 책임·순서·상태·오류·소유권 |
|---|---|
| T1 [StandardServer.startInternal/stopInternal/initInternal/destroyInternal](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardServer.java#L855) | Server가 Service 목록에 수명 호출을 전달하며 naming·utility executor도 관리한다. start/stop은 findServices 순회 순서다. 이 메서드들이 프로젝트 제안처럼 모든 형제 실패를 모아 역순 rollback하는 것으로 설명하지 않는다 |
| T2 [StandardService.startInternal/stopInternal](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardService.java#L406) | Engine→Executors→MapperListener→FAILED가 아닌 Connector 순서로 start. stop은 각 Protocol의 closeServerSocketGraceful→설정된 총 잔여 시간만큼 연결 대기→모든 Connector pause→Engine stop→STARTED Connector stop→MapperListener·Executors stop. gracefulStopAwaitMillis 기본값은 0 |
| T3 [LifecycleBase.start/stop/destroy·실패 처리](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/util/LifecycleBase.java#L139), [LifecycleState](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/LifecycleState.java#L24) | NEW의 start는 init부터 진행하고 STOPPED 재시작 경로도 있다. startInternal이 FAILED를 설정한 제어된 실패는 stop으로 정리; 던져진 실패는 handleSubClassException에서 FAILED와 throwOnFailure 정책으로 처리한다. init·destroy·이벤트·availability를 포함한 상태는 프로젝트보다 많음 |
| T4 [Connector.initInternal/startInternal/stopInternal/destroyInternal](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/connector/Connector.java#L990) | init에서 CoyoteAdapter를 만들어 ProtocolHandler에 연결하고 init 위임. start/stop/destroy도 ProtocolHandler에 위임하며 예외를 LifecycleException으로 감싼다. pause/resume는 별도 경로. 앱 객체를 직접 destroy하지 않음 |
| T5 [AbstractEndpoint.createExecutor/shutdownExecutor](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/AbstractEndpoint.java#L1084) | 내부 Executor는 가상 스레드 설정 또는 Tomcat TaskQueue·ThreadPoolExecutor로 생성. 내부 풀만 shutdownNow와 설정 대기 후 종료 경고 처리. 외부 Executor의 소유권과 다름 |
| T6 [AbstractEndpoint.processSocket](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/AbstractEndpoint.java#L1247), [TaskQueue.offer/force](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/threads/TaskQueue.java#L63), [ThreadPoolExecutor.execute](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/threads/ThreadPoolExecutor.java#L1374) | SocketProcessor를 생성/재사용하고 dispatch이면 Executor에 전달한다. 거부 시 false. TaskQueue는 작업자 수 등에 따라 풀 확장/큐 적재를 선택하고 execute는 거부 시 force도 시도한다. JDK 고정 풀과 큐 정책이 같지 않으며, 거부 시 HTTP 503을 생성하는 메서드도 아님 |
| T7 [Acceptor.run](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/Acceptor.java#L120), [NioEndpoint.setSocketOptions·Poller](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/NioEndpoint.java#L445) | Acceptor가 연결 수 제한·accept·설정 실패 시 close를 처리한다. NIO는 비블로킹 설정과 connections의 Wrapper 보관, 읽기/쓰기 제한 설정 뒤 Poller 등록을 수행한다. Poller의 processSocket 실패 분기는 close로 진행한다(823행 부근) |
| T8 [NioEndpoint.SocketProcessor.doRun](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/NioEndpoint.java#L1688), [AbstractProtocol.ConnectionHandler.process/release](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/AbstractProtocol.java#L812) | SocketProcessor는 Handler를 호출하고 CLOSED이면 socketWrapper.close를 수행한다. Handler가 처리 중 Processor 소유권을 확보하고 재사용/생성→process→SocketState별 연결/해제를 수행한다. release는 recycle과 캐시 반환이며 요청마다 반드시 새로 생성하는 설계가 아님 |
| T9 [AbstractEndpoint.init/start·closeServerSocketGraceful/awaitConnectionsClose](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/AbstractEndpoint.java#L1511) | bindOnInit은 기본 true. graceful에서 listener를 닫는 조건은 BOUND_ON_START다. 연결 대기는 connections를 50ms 간격으로 확인하고 잔여 시간을 반환한다. 모든 설정에서 listener close→모든 service 완료를 보장한다고 해석하지 않음 |
| T10 [StandardContext.filterStart/filterStop](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardContext.java#L3842), [startInternal/stopInternal](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardContext.java#L4460) | 시작 중 filterStart 성공 후 loadOnStartup을 진행한다. 정지는 자식 컨테이너 stop 뒤 filterStop. 필터 구성은 Context 소유다. filterStart의 일반 실패는 기록하고 뒤 정의도 시도하므로 프로젝트의 최초 실패 중단·역순 정리와 다름 |
| T11 [StandardWrapper.unload](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapper.java#L920) | countAllocated를 unloadDelay에서 계산한 시간으로 최대 21회 기다린 뒤 destroy 경로로 진행한다. 대여 수가 반드시 0일 때까지 무한 대기하거나, 기한 뒤 반드시 destroy를 보류하는 코드가 아니다. 프로젝트는 Service에서 전체 작업 완료를 먼저 확인하는 정책을 선택 |
| T12 [Http11Processor.service](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/http11/Http11Processor.java#L251) | I/O·파싱·Adapter 호출 예외 분기와 read timeout 변경이 있다. 예를 들어 Adapter 측 InterruptedIOException은 CLOSE_CONNECTION_NOW로 진행한다. 모든 timeout의 응답 코드를 조사한 것으로 표시하지 않음 |

위 표는 메서드 본문에서 확인한 요약이다. TLS·HTTP/2·async·외부 Executor의 모든 설정 조합, OS별 쓰기 중단, 정지 후 재배포의 전체 상태는 미조사이며 6단계의 동일성을 주장하지 않는다.

## 4. 프로젝트의 구체적인 계약

### 4.1 설정과 소유권

| 설정 | 예정 값·입력 제약 | 이유·의미 |
|---|---|---|
| workerCount | 기본4, 1 이상 | JDK 플랫폼 스레드 고정 수. core=max, CallerRunsPolicy 사용 금지 |
| queueCapacity | 기본16, 1 이상 | ArrayBlockingQueue. OS accept backlog·전체 클라이언트 연결 수와 구분 |
| readTimeoutMillis | 기본3000, 1 이상 | accept 후 등록 전 Socket.setSoTimeout. 각 blocking read의 무진행 제한이며 헤더 전체의 절대 기한은 아님 |
| stop(Duration) | 0 이상·toNanos 표현 가능, close는5초 | Server/Service가 단조 시간 Deadline을 만들고 모든 자식과 정리에 잔여 시간만 전달. 자식마다5초를 더하지 않음 |
| 포트 | 0~65535, 0은 임의 포트 | Connector 생성은 bind 없음, Service 시작 때 bind. getPort는 bind 전 -1, 성공 후 종료해도 실포트 보존 |
| 포화·읽기 제한 | 503/408 신설 없이 연결 close | Endpoint는 HTTP를 모름. EOF 또는 RST와 다음 정상 연결로 복구 확인 |

고정 풀은 Endpoint가 생성·소유한다. 큐에서 기다리는 연결도 소유한다. 상한은 실행 중 workerCount＋물리 queueCapacity이며 accept 직후 인계 중에는 일시적으로 연결 1개가 더 존재할 수 있다. OS backlog는 이 수에 포함하지 않는다.

| 소유자 | 소유 자원 | 차용·종료 책임 |
|---|---|---|
| StandardServer | 등록 순서의 Service들 | 각 Service stop 조정. 서로 다른 Service에도 같은 Engine·Connector 공유 금지 |
| StandardService | Engine 구성, Mapper/Adapter, Connector들, ApplicationLifecycle | Context의 Filter·Wrapper의 Servlet 소유권 유지. 작업 종료 확인 뒤 해제 지시 |
| Connector | ProtocolHandler 하나, accept 실행 Thread, 종료 결과 | 앱 해제는 하지 않음. accept 장애와 작업 종료를 상위에 보고 |
| ConcurrentEndpoint | listener, 고정 풀, 수락한 ConnectionTask/Socket 집합 | Handler는 스트림을 차용. 정상·예외·거부·취소에서 close와 등록 해제 |
| ConnectionTask | 자기 socket의 종료 처리권 | QUEUED→RUNNING→FINISHED 또는 QUEUED→CANCELLED. 등록 해제·완료 통지는 한 번 |
| 요청 한 건 | Processor, Coyote/connector Request·Response, MappingData, FilterChain | 마지막 I/O·finally까지 포함해 완료. 공유 인스턴스와 구별 |

### 4.2 구성 동결과 공개

Service.start는 구조·defaultHost 검증 뒤 Engine 아래 자식·매핑·Pipeline·Valve next·Filter 등록을 재귀적으로 freeze하고 Filter init을 수행한다. Pipeline은 검증 단계에서 identity 집합으로 순환·중복 링크·basic 뒤의 next를 거부해 freeze 순회가 무한 반복하지 않게 한다. ContainerBase 자식 Map을 LinkedHashMap으로 바꿔 등록 순서 준비·역순 정리를 정의한다. findChildren의 순서 계약도 갱신한다.

Container/Pipeline/Valve에 `freeze()`·`isFrozen()`을 추가하고 ContainerBase·StandardPipeline·ValveBase가 구현한다. 동결 뒤 setName/setParent/addChild, addServletMapping/addFilter, addValve/setBasic, setNext는 IllegalStateException으로 거부한다. 보관한 참조로 변경하는 경우도 포함한다. 외부 매핑 뷰의 변경 불가를 유지한다. freeze는 반복 가능하고 해제는 불가하다.

등록·freeze 중 동시 구성 변경은 지원하지 않는다. 구성은 한 제어 스레드에서 끝내고 freeze→Filter 준비→Thread.start/Executor.execute의 공개 경계로 작업자에 전달한다. 요청마다 전체 읽기 잠금을 잡아 직렬화하지 않는다. 사용자 정의 Container/Valve 구현도 이 계약을 지켜야 한다.

### 4.3 수락·실행·정지의 경합 제어

1. `serve()`는 호출 스레드에서 accept만 수행하며 한 번만 호출할 수 있다. Handler 실행을 accept 스레드에 넘기는 정책은 사용하지 않는다.
2. 수락 후 Socket 옵션을 설정한다. 공통 잠금에서 accepting 재확인→Task 소유 집합 등록→고정 풀 execute를 수행한다. 정지가 먼저면 즉시 close한다. AbortPolicy 거부도 등록 해제·close 후 다음 accept로 진행한다.
3. Task는 같은 잠금에서 시작 가능 여부를 판단한다. graceful 기간에는 이미 수락한 큐 작업도 실행한다. abort 이후 미시작 Task는 Handler에 진입하지 못한다. 사용자 Handler·I/O는 잠금 밖이다.
4. Task finally는 socket close→등록 해제→완료 통지 순서다. IOException·일반 RuntimeException은 연결별 실패로 기록하고 다른 연결을 유지한다. Error에서도 finally를 수행하되 HTTP·VM 복구를 보장하지 않는다.
5. `stopAccepting()`은 수락 금지 공개→listener close→pool.shutdown 순서다. 수락한 Task는 즉시 끊지 않으며 accept 종료 대기는 별도로 수행한다.
6. `awaitTermination(timeout)` 성공 조건은 accept 루프 종료(미시작 포함)·풀 종료·전체 Task 완료/소유 집합 비움이다. socket.isClosed만으로 성공 처리하지 않는다.
7. 기한 뒤 `close()`는 listener·모든 소유 socket을 닫고 shutdownNow가 반환한 미시작 Task를 cancel·등록 해제한다. 실행 중 Task에는 interrupt가 전달될 수 있지만 등록 해제는 해당 Task finally까지 보류한다. close 자체는 완료 대기를 하지 않는다. 직후 비차단 확인에서 작업이 남으면 상위에 실패를 반환한다.

Snapshot은 `accepting, ownedConnections, runningTasks, queuedTasks, rejectedConnections`를 갖는다. queuedTasks는 등록했으나 시작하지 않은 Task 수이며 JDK 물리 큐 길이와 항상 같지는 않다. 상태 변경 통지 기반 `awaitSnapshot(predicate, timeout)`으로 테스트·학습 실험에서 큐 적재·거부·시작을 명시적으로 기다린다. predicate는 부작용 없이 즉시 반환해야 한다.

### 4.4 Lifecycle 상태·시작·시작 실패

최소 상태는 `NEW, STARTING, STARTED, STOPPING, STOPPED, FAILED`다. 공개 조작은 `start()`·`stop(Duration)`·`close()`·`getState()`이며 getState는 volatile 상태의 비차단 조회다.

| 호출·현재 상태 | 프로젝트 결과 |
|---|---|
| start / NEW | STARTING→준비→STARTED. 구조·Filter init·bind·Thread 시작 실패는 FAILED와 원인 보고 |
| start / STARTED | no-op. 스레드·Filter init 추가 없음 |
| start / STOPPED 또는 FAILED | IllegalStateException. 같은 객체 재시작은 제외하고 새 구성을 사용 |
| stop / NEW | 자원 생성 없이 STOPPED, 초기화하지 않은 Servlet/Filter destroy=0 |
| stop / STARTED | STOPPING→수락 중지→작업 대기→앱 정리→STOPPED 또는 FAILED |
| stop / FAILED | 남은 작업·정리를 같은 소유권 기록에서 재시도. 성공 시 STOPPED, 영구 정리 실패는 FAILED와 재보고 |
| stop / STOPPED | no-op |
| 조작 중 start/stop 재진입·다른 제어 스레드의 중복 조작 | IllegalStateException. 긴 monitor 획득 대기 대신 거부. getState는 호출 가능 |
| 잘못된 인수 | 부작용 전 IllegalArgumentException. timeout null은 NullPointerException |

Connector는 Service용 준비 메서드 `bind(Adapter)`를 둔다. NEW→STARTING에서 Protocol을 만들되 accept Thread는 시작하지 않는다. start는 bind된 경우에만 accept Thread를 시작해 STARTED로 진행한다. Service 미소속·미bind 상태의 단독 start는 거부한다.

Service.start 순서는 **구조 검증→전체 트리 freeze→등록 순서 Context.startFilters→Mapper/Adapter 준비→전체 Connector bind→등록 순서 Connector.start**다. Servlet 지연 init은 유지한다. Filter init 실패와 요청 중 Servlet init 실패를 구별하고 후자는 기존 500·다음 요청 재시도 정책을 유지한다.

실패하면 성공·부분 성공한 자원을 기록하고, 5초 rollback 예산에서 모든 Connector 수락 중지·종료 확인→앱 정리를 진행한다. 부분 bind한 Connector도 대상이다. 최초 시작 실패를 LifecycleException의 cause로 보존하고 일반 정리 실패·rollback 기한 초과는 suppressed로 붙인다. 시작 조작은 FAILED로 끝나며 명시적 stop으로 남은 자원을 재확인할 수 있다.

Server는 Service를 등록 순서로 시작한다. 뒤 Service 실패 시 실패 Service를 포함해 시도한 Service들을 역순 stop한다. 앞 Service가 요청을 받았을 수 있으므로 **서버 전체 시작을 네트워크상 원자적 공개라고 설명하지 않는다.** rollback에도 진행 작업 안전 규칙을 적용한다.

시작 후 accept 루프가 예상 밖에 실패하면 Connector가 원인을 저장해 Service→Server로 통지한다. Service는 FAILED로 바꾸고 같은 Service의 모든 Connector 수락을 비차단으로 중지한다. 통지한 스레드 자신을 join하지 않으며 앱 정리는 제어 스레드 stop에 맡긴다. `getFailure()`로 과거 원인을 보존하고, 이후 stop이 자원 정리에 성공하면 STOPPED로 진행할 수 있다. 과거 accept 실패 자체를 destroy 실패와 같은 영구 정리 실패로 취급하지 않는다.

### 4.5 종료·정리·실패 재시도

Server.stop도 기다리기 전에 모든 Service의 quiesce를 먼저 시도한다. Service.stop은 **모든 Connector 수락을 먼저 중지한 뒤** 같은 Deadline의 잔여 시간으로 각 accept Thread·Endpoint를 기다린다. 기한 뒤 전체 Connector I/O를 취소한다. 작업·스레드가 하나라도 남으면 FAILED와 LifecycleException을 반환하고 해당 Service의 모든 Wrapper·Filter 정리를 시작하지 않는다. 다른 Service의 독립된 정리는 시도할 수 있다.

모든 Connector 종료 뒤 ApplicationLifecycle이 등록 역순 Context마다 Wrapper를 등록 역순 unload하고 그 Context.stopFilters를 호출한다. 일반 예외에도 형제·Filter 정리를 계속하고 최초 원인과 suppressed를 보존한다. 독립 Service 사이에도 같은 예외 집계 규칙을 적용한다.

`destroy()`의 사용자 코드가 호출자를 무기한 막지 않도록 Service마다 **단 한 번** 정리 Future를 생성하고 전용 단일 스레드 실행기에서 위 정리를 수행한다. submit 후 실행기는 shutdown해 추가 작업을 막고 호출자는 잔여 시간만 기다린다. 사용자 콜백 중 Lifecycle 제어 monitor를 잡지 않는다.

- 잔여 시간0이면 미착수 정리를 새로 시작하지 않고 FAILED로 남겨 다음 stop에서 진행한다. 완료 상태 확인은0에서도 가능하다.
- 정리 Future 대기 시간 초과는 FAILED다. Future를 cancel하지 않고 보관하며 다음 stop은 **동일 Future와 실행기 종료**를 기다린다. 중복 destroy는 금지한다.
- 지연 정리가 성공하면 다음 stop에서 STOPPED로 진행한다. 일반 destroy 예외로 정리가 끝났다면 FAILED와 저장한 예외를 반복 보고하고 콜백을 다시 호출하지 않는다. 자원 회수와 콜백 성공을 구별한다.
- 대기 interrupt는 인터럽트 상태를 복원하고 LifecycleException으로 보고한다. 수락 중지·I/O 취소는 시도하지만 작업 완료 전 정리는 금지한다. 재시도에 필요한 자원 기록을 남긴다.
- 정리 스레드도 소유한 비daemon 스레드다. 기한 경과만으로 종료했다고 표시하지 않는다. 콜백이 영원히 반환하지 않으면 자원이 남는다. hook은5초 종료 시도·실패 보고까지만 하며 JVM·OS 최종 종료에서 destroy 완료를 보장하지 않는다.

초기화 임의 코드의 강제 시간 제한은 도입하지 않는다. start 중 무한 Filter init과 다른 스레드에서의 start 취소는 이번에도 보장하지 않는다. 유한한 것은 위 stop의 대기이며 모든 시작 콜백의 실행 시간이 아니다. 학습 문서에도 이 한계를 남긴다.

관리 중인 구성에 외부에서 FilterServer.stopFilters·Wrapper.unload를 병렬 호출하지 않는다. Server/Service를 종료 창구로 사용한다. 기존 단독 Wrapper·순차 예제의 계약은 유지한다.

## 5. 파일 구조와 작업 간 인터페이스

아래 파일은 **구현 때** 생성·수정한다. `M=src/main/java/simpletomcat`, `T=src/test/java/simpletomcat`로 경로 접두사를 줄여 적었다. 모든 신규 타입의 패키지는 파일 경로와 같다.

| 작업 | 생성 | 수정 |
|---|---|---|
| 1 구성 | `M/catalina/Freezable.java`, `T/catalina/core/ContainerFreezeTest.java` | `M/catalina/{Container,Pipeline,Valve}.java`, `M/catalina/core/{ContainerBase,StandardContext,StandardPipeline}.java`, `M/catalina/valves/ValveBase.java` |
| 2 전송 | `M/util/Deadline.java`, `M/util/net/{EndpointOptions,ConcurrentEndpoint}.java`, `T/util/DeadlineTest.java`, `T/util/net/{EndpointOptionsTest,ConcurrentEndpointTest}.java` | 기존 Endpoint는 변경하지 않음 |
| 3 프로토콜 | `M/coyote/ManagedProtocolHandler.java`, `M/coyote/http11/ConcurrentHttp11Protocol.java`, `T/coyote/http11/ConcurrentHttp11ProtocolTest.java` | 기존 HTTP 파서·순차 Http11Protocol 계약은 유지 |
| 4 시작 | `M/catalina/{Lifecycle,LifecycleState,LifecycleException}.java`, `M/catalina/core/{StandardServer,StandardService,ApplicationLifecycle}.java`, `M/catalina/connector/Connector.java`, `T/catalina/core/{StandardServerTest,StandardServiceTest,ApplicationLifecycleTest}.java`, `T/catalina/connector/ConnectorTest.java` | `M/catalina/core/StandardEngine.java`에 Service 소유자 연결·중복 소유 방지 |
| 5 종료 | `T/catalina/core/LifecycleShutdownTest.java` | Task 2~4 타입에 종료 구현, 시작 실패 테스트의 정리 assertion 보강 |
| 6 HTTP·기록 | `M/examples/LifecycleServer.java`, `T/catalina/connector/LifecycleHttpTest.java`, `T/support/{LifecycleTestServer,HttpTestServerTest}.java`, `doc/experiments/LifecycleHttpExperiment.java`, `doc/06-lifecycle/01-concurrency-and-lifecycle.md` | `T/support/HttpTestServer.java`의 생성 실패 정리, README·목차·진행 상황·소스 지도·범위·구조도 |

### 공개·작업 간 계약

```java
// Task 1: Container, Pipeline, Valve가 이 계약을 확장한다.
interface Freezable { void freeze(); boolean isFrozen(); }

// Task 2: 값은 불변, defaults()는 (4, 16, 3000).
record EndpointOptions(int workerCount, int queueCapacity, int readTimeoutMillis) {
    static EndpointOptions defaults();
}
// public final class Deadline: after는 양수뿐 아니라 ZERO도 허용한다.
static Deadline after(Duration timeout);
long remainingNanos();
Duration remaining();

// public final class ConcurrentEndpoint implements AutoCloseable
ConcurrentEndpoint(int port, EndpointOptions options, Endpoint.Handler handler) throws IOException;
int getPort();
void serve() throws IOException;
void stopAccepting() throws IOException;
boolean awaitTermination(Duration timeout) throws InterruptedException;
void close() throws IOException; // 즉시 I/O 취소, 완료 대기는 별도
record Snapshot(boolean accepting, int ownedConnections, int runningTasks,
                int queuedTasks, long rejectedConnections) {}
Snapshot snapshot();
boolean awaitSnapshot(Predicate<Snapshot> expected, Duration timeout) throws InterruptedException;

// Task 3: 기존 ProtocolHandler는 수정 없이 확장한다.
interface ManagedProtocolHandler extends ProtocolHandler {
    void stopAccepting() throws IOException;
    boolean awaitTermination(Duration timeout) throws InterruptedException;
}
// public final class ConcurrentHttp11Protocol implements ManagedProtocolHandler
ConcurrentHttp11Protocol(int port, Adapter adapter, EndpointOptions options) throws IOException;
// 위 메서드들은 Endpoint에 위임. snapshot/awaitSnapshot도 같은 타입으로 위임한다.

// Task 4~5: public 계약. 구현체는 일반 예외를 LifecycleException으로 보존한다.
interface Lifecycle extends AutoCloseable {
    void start() throws LifecycleException;
    void stop(Duration timeout) throws LifecycleException;
    LifecycleState getState();
    Throwable getFailure(); // 마지막 실패 진단, 실패가 없으면 null
    default void close() throws LifecycleException; // stop(Duration.ofSeconds(5))
}
// LifecycleException extends Exception: (String message, Throwable cause) 생성자.
// 시간 초과 cause는 TimeoutException, 대기 중단 cause는 InterruptedException.

// core.StandardService implements Lifecycle
StandardService(String name, StandardEngine engine, String defaultHost);
void addConnector(Connector connector);
Connector[] findConnectors();
StandardEngine getEngine();
void quiesce() throws LifecycleException; // package-private: Server가 전체 수락부터 중지
// core.StandardServer implements Lifecycle
StandardServer();
void addService(StandardService service);
StandardService[] findServices();
void await() throws InterruptedException; // 조작이 끝난 STOPPED/FAILED까지 대기
boolean await(Duration timeout) throws InterruptedException; // 같은 조건, 유한 대기

// connector.Connector implements Lifecycle
Connector(int port, EndpointOptions options); // 포트·설정 검증만, bind 없음
void attach(StandardService owner); // 한 번만, 중복/다른 소유자 거부
void bind(Adapter adapter) throws LifecycleException; // Service 준비 단계용
void quiesce() throws IOException; // 수락 중지, 멱등
void abort() throws IOException; // I/O 취소, 멱등
boolean awaitStopped(Duration timeout) throws InterruptedException; // 자원 종료 사실
int getPort();

// core.ApplicationLifecycle: package-private, Service가 하나 소유
ApplicationLifecycle(StandardEngine engine);
void prepare() throws ServletException; // 시작한 Context/부분 실패 자원 기록
void cleanup(Duration timeout) throws LifecycleException; // 동일 Future 추적
```

코드 블록은 계약 표기이며 그대로 복사할 구현 코드가 아니다. 별도로 package-private로 표시한 메서드와 ApplicationLifecycle을 제외하고, 메서드·생성자·중첩 record는 표기한 public 클래스에서 public으로 노출한다. EndpointOptions 생성자도 public이다. Deadline.remaining은 이미 소비한 시간을 다시 더하지 않는다. 서비스 이름은 공백 불가·중복 불가, defaultHost는 기존 Mapper의 null/등록 이름 계약을 따른다. Server·Service에 최소 하나의 자식이 있어야 start할 수 있다.

Service/Server 등록은 NEW에서만 가능하고 동일 객체·이름 중복·다른 소유자 등록을 원자적으로 거부한다. StandardEngine은 한 Service만 소유하며 공유를 금지한다. attach/bind/quiesce/abort는 구성 조정용 API이며, 사용자 예제의 정상 창구는 Server.start/stop이다.

Connector의 start는 accept Thread 시작만 소유한다. stop은 quiesce→잔여 시간 awaitStopped→시간 초과 abort·실패 보고를 수행한다. Service는 모든 Connector를 먼저 quiesce한 뒤 같은 절차를 조정한다. 배경 실패는 getFailure에 남겨도 자원 회수에 성공한 후속 stop은 STOPPED가 될 수 있다. ApplicationLifecycle의 영구 정리 실패는 반복 stop에도 계속 실패한다.

## 6. 구현 작업과 예정 검증

각 Task는 테스트 작성→해당 실패 확인→최소 구현→같은 테스트 통과 순서다. **아래 테스트명·숫자·응답은 예정 assertion이다. 실제 통과 결과가 아니다.** 작업 커밋을 나누더라도 최종 단계 커밋에는 코드·테스트·학습 문서를 함께 포함한다. 새 태그는 Task 6 뒤에만 생성한다.

### Task 1: 구성 동결과 요청 전 공개

**Files/Interfaces:** 5절 Task 1 파일. Freezable 제공, 기존 Container/Pipeline/Valve가 소비. StandardContext 필터 상태와 구성 동결은 별개로 유지해 freeze 뒤 startFilters는 가능하게 한다.

- [ ] `ContainerFreezeTest`에 `freezesWholeTreeAndSavedReferences`를 작성한다. Engine/Host/Context/Wrapper와 보관한 Pipeline/Valve 참조의 모든 변경 메서드가 freeze 후 IllegalStateException, 기존 조회·매핑·호출은 유지됨을 assert한다. 시작 시 필터 내부의 addChild/addFilter 시도도 거부한다.
- [ ] `preservesRegistrationOrderAndImmutableMappings`, `freezesRepeatedlyWithoutChangingLinks`, `rejectsCyclicValveChainBeforeCallbacks`로 자식 등록 순서, 외부 매핑 변경 거부, 두 번째 freeze의 링크·정의 불변, 직접 setNext로 만든 순환의 콜백 전 거부를 확인한다. 등록 전/후 이름·부모 제약 회귀도 유지한다.
- [ ] `.\mvnw.cmd -B '-Dtest=ContainerFreezeTest' test`의 신규 계약 부재 실패를 확인한 뒤 Freezable·가드·재귀 freeze·LinkedHashMap을 구현한다. Valve를 freeze할 때 next로 재귀 순회하지 않고 Pipeline이 자기 체인만 순회해 각 Valve를 동결한다.
- [ ] `.\mvnw.cmd -B '-Dtest=ContainerFreezeTest,ContainerHierarchyTest,MapperTest,StandardPipelineTest,StandardContextFilterTest,FilterInvocationTest' test`에서 실패·오류·건너뜀0을 확인한다. 이 단계는 동시 서버 완성을 뜻하지 않는다.

### Task 2: 제한 풀·연결 소유권·취소

**Files/Interfaces:** 5절 Task 2. EndpointOptions·Deadline·ConcurrentEndpoint를 제공하고 기존 Endpoint.Handler를 소비한다. ConnectionTask는 ConcurrentEndpoint 내부 구현으로 둔다.

- [ ] `EndpointOptionsTest`에 기본 `(4,16,3000)`과 각0/음수 거부를 추가한다. `DeadlineTest`는 ZERO 즉시 만료·음수/범위 초과 거부·단조 잔여 시간 비증가를 검증한다. 시각 경과를 기다리는 sleep 테스트는 만들지 않는다.
- [ ] `ConcurrentEndpointTest.servesAnotherConnectionWhileFirstIsBlocked`: worker2/queue1, 첫 Handler를 latch에 멈추고 둘째 응답이 첫 요청 해제 전에 도착해야 한다. 두 Handler가 서로 다른 작업 스레드이며 accept 스레드에서 실행되지 않음을 확인한다.
- [ ] `rejectsExcessConnectionAndRecovers`: worker1/queue1, 첫 Handler 진입 latch→둘째 queued snapshot→셋째 rejected snapshot 순서로 고정한다. 셋째는 EOF/RST·Handler 미호출, 첫째 해제 후 둘째와 새 요청 성공·최종 owned0을 확인한다.
- [ ] `timesOutIncompleteHeadersAndRecovers`: Handler가 입력 읽기 직전에 latch 통지, readTimeout200ms로 일부 입력만 보낸 연결이 닫히고 다음 완성 입력이 성공해야 한다. 경과 시간의 정확한200ms 일치는 assert하지 않는다.
- [ ] `preservesAcceptedWorkDuringQuiesce`, `cancelsQueuedWorkWithoutInvokingHandler`, `keepsRunningTaskOwnedAfterSocketClose`, `closesConnectionAcceptedDuringStop`, `closesBeforeServeAndRejectsSecondServe`를 추가한다. graceful 큐 진행·abort 뒤 미시작 호출0·소켓 close 뒤 실행중 owned1·경합 연결 누락0·반복 close·포트 반환을 고정한다.
- [ ] 경합 테스트는 패키지 내부 생성자에서 accept 직후 실행할 **테스트용 인계 훅**을 주입해 그 지점에 latch를 둔다. 기본 생성자는 무동작 훅을 사용한다. 훅은 소유권 공통 잠금 밖에서 실행하고, 훅 실패 때도 수락 소켓을 닫는다. Executor 거부·Handler I/O/Runtime 실패에도 같은 정리를 검증한다.
- [ ] `.\mvnw.cmd -B '-Dtest=DeadlineTest,EndpointOptionsTest,ConcurrentEndpointTest' test`의 실패를 확인하고 4.3절 소유권 전이를 구현한 뒤 같은 명령을 통과시킨다. 정리 실패도 최초 원인을 덮지 않게 집계한다. 기존 `EndpointTest`·`Http11ProtocolTest` 회귀를 함께 확인한다.

### Task 3: 동시 HTTP 조립과 요청 격리

**Files/Interfaces:** 5절 Task 3. ManagedProtocolHandler를 제공하며 Task 2 Endpoint를 소비한다. 파서에 새 HTTP 정책을 넣지 않는다.

- [ ] `ConcurrentHttp11ProtocolTest.isolatesConcurrentRequestsAndResponses`에서 worker2를 사용한다. `/a?x=1`·`/b?x=2`, 서로 다른 헤더·UTF-8 본문·200/500을 두 Adapter 진입 latch로 겹치고 URI/query/헤더/응답에 상대 값이 섞이지 않음을 assert한다.
- [ ] `recoversAfterTimeoutAndAdapterFailure`, `keepsHeadBodyEmptyDuringOtherRequest`로 부분 헤더 close 뒤 정상200, Adapter 부분 응답 뒤 새500·다음200, 병행 HEAD 본문0바이트를 확인한다.
- [ ] `.\mvnw.cmd -B '-Dtest=ConcurrentHttp11ProtocolTest' test`의 타입 부재 실패 뒤 새 프로토콜을 구현한다. 생성자에서 adapter/options/port를 검증한 뒤 Endpoint를 만들고 실패 시 생성된 listener·풀을 회수한다.
- [ ] `.\mvnw.cmd -B '-Dtest=ConcurrentHttp11ProtocolTest,Http11ProcessorTest,Http11ProtocolTest' test`를 통과시킨다. Processor 생성은 연결별이며 CoyoteAdapter의 공유는 다음 Task에서 준비된 읽기 전용 구성으로 제한한다.

### Task 4: Lifecycle·구성 소유권·시작 실패 정리

**Files/Interfaces:** 5절 Task 4. Task 1·3을 소비하고 Lifecycle·Server/Service/Connector·ApplicationLifecycle을 제공한다. stop의 정상 정리 기반은 이 Task에서 연결하고 Task 5에서 기한·재시도를 보강한다.

- [ ] `ApplicationLifecycleTest`에 `preparesFiltersBeforeAnyBinding`, `recordsEarlierContextsAfterLaterInitFailure`, `cleansWrappersBeforeFiltersInReverseOrder`를 작성한다. 두 Context A/B에서 B.init 실패 시 성공한 A만destroy1·B.destroy0, 원인 보존·형제 정리 계속·반복 호출 추가0을 assert한다. FilterState가 FAILED여도 해당 Context의 남은 성공 자원을 cleanup 대상으로 보존한다.
- [ ] `StandardServiceTest`에 `rejectsInvalidConfigurationBeforeCallbacks`, `freezesBeforeFilterInit`, `bindsAllConnectorsBeforeServing`, `rollsBackFirstBindingWhenSecondPortIsOccupied`를 추가한다. 임의 포트 점유로 둘째bind를 실패시키고 앞 실포트 재bind·필터해제1·외부 점유listener 유지·원래 BindException 보존을 확인한다.
- [ ] `StandardServerTest`에 `rejectsDuplicateOwnershipAtomically`, `rollsBackEarlierServiceAfterLaterFailure`를 추가한다. 같은 Engine/Service/Connector 재소유·같은 이름 중복은 기존 목록·소유자 변경 없이 거부한다. 뒤 Service 실패 후 앞 자원 회수와 최초 cause/suppressed 구조를 assert한다.
- [ ] `ConnectorTest`에 `doesNotBindInConstructor`, `requiresOwnerAndBindingBeforeStart`, `cleansBindingWhenThreadStartFails`, `reportsUnexpectedAcceptFailureWithoutSelfJoin`를 추가한다. 패키지 내부 `ProtocolFactory`·`ThreadFactory` 주입 생성자로 시작 실패/accept 예외를 결정적으로 발생시킨다. ProtocolFactory 계약은 `(int port, Adapter adapter, EndpointOptions options) -> ManagedProtocolHandler`, IOException 허용이다.
- [ ] 같은 테스트에 중복start no-op·NEW stop·STOPPED/FAILED start 거부·STARTING 중 변경/재진입 거부·getPort 전후 값을 고정한다. 시작 중 사용자 Filter가 getState를 호출할 수 있어야 한다. accept Thread가 시작 직후 실패하면 뒤늦은 start 완료가 FAILED를 STARTED로 덮지 않는 경합 테스트도 포함한다.
- [ ] `.\mvnw.cmd -B '-Dtest=ApplicationLifecycleTest,StandardServiceTest,StandardServerTest,ConnectorTest' test`의 실패를 확인하고 4.4절을 구현한 뒤 같은 명령을 통과시킨다. begin/finish 상태 잠금과 사용자 콜백 실행을 분리한다. rollback에서 이미 시도한 정리 Future를 잃지 않는다.

### Task 5: 기한 있는 종료·해제 보류·동일 정리 추적

**Files/Interfaces:** Task 2~4의 stop 경로와 `LifecycleShutdownTest`. stop(Duration)의 기한은 accept 종료·진행 작업·정리 Future·정리 실행기 종료 전체에 공유한다.

- [ ] `waitsForRequestBeforeDestroy`: Servlet 진입 latch 뒤 stop을 다른 제어 작업에서 실행한다. accepting=false 확인→Servlet/Filter destroy0→release→응답 완료→Wrapper destroy→Filter destroy→STOPPED를 assert한다. 신규 연결은 거부/close되며 신규service0이다.
- [ ] `defersAllApplicationCleanupWhenOneConnectorStillRuns`: 두 Connector 중 하나에서 interrupt를 무시하는 **유한 latch 대역**을 실행한다. stop200ms가 실패하고 두 Context 모두destroy0·해당 작업은 여전히소유중이어야 한다. finally에서 release하고 stop3초 재시도 후destroy각1·포트반환을 확인한다.
- [ ] `cancelsQueuedRequestsAtDeadline`, `stopsConnectionsWaitingForInput`, `sharesOneDeadlineAcrossConnectorsAndServices`: 미완성 입력·가득 찬 큐·두 Service에서 먼저 모든 수락을 중지하고 잔여 예산만 소비하는지 확인한다. latch와 호출받은 timeout을 기록하는 대역으로 시간 전달을 검증하며 벽시계의 정밀 일치를 요구하지 않는다.
- [ ] `reusesCleanupFutureWhenDestroyIsBlocked`: destroy 진입 latch 뒤 stop200ms 실패→두 번째stop도 같은destroy1→release→세 번째stop 성공·실행기종료. 콜백 중 getState 가능·stop 재진입 거부도 확인한다.
- [ ] `preservesCleanupFailureWithoutCallingDestroyAgain`: 한 Wrapper.destroy와 한 Filter.destroy가 각각 실패해도 모든 형제를 정리하고 최초 cause/suppressed를 보존한다. 반복stop은 저장실패·FAILED·호출수불변이며 새 정리를 시작하지 않는다. 정리 실행기 생성/submit 실패도 대역으로 확인하고, 미실행 자원 기록을 유지한 재시도에서 한 번만 정리한다.
- [ ] `restoresInterruptAndAllowsCleanupRetry`, `retainsActiveWorkDuringStartupRollback`, `cleansIndependentServiceAfterSiblingTimeout`: 제어 작업 interrupt, 첫 Service가 요청 처리 중 뒤 Service 시작 실패, 한 Service 지연 중 다른 Service 정리를 latch로 고정한다. 진행 앱destroy0과 독립 앱destroy1·후속 회수를 assert한다.
- [ ] `.\mvnw.cmd -B '-Dtest=LifecycleShutdownTest,StandardServiceTest,StandardServerTest,ConnectorTest,ApplicationLifecycleTest,ConcurrentEndpointTest' test`의 실패를 확인한 뒤 4.5절을 구현하고 통과시킨다. 모든 대역은5초 내 외부에서 풀 수 있고 테스트 finally가 반드시 해제한다.

### Task 6: 실제 HTTP·예제·문서·단계 완료 경계

**Files/Interfaces:** 5절 Task 6. 새 Lifecycle 공개 계약으로 조립하며 기존 FilterServer의 정리 코드를 새 Server의 수명과 중복 호출하지 않는다.

- [ ] `LifecycleTestServer`는 Server·클라이언트·테스트 작업자를 소유하고 생성 실패도 정리한다. 기존 `HttpTestServer`의 시작 latch interrupt 누락은 같은 자원 규칙으로 보완한다. `HttpTestServerTest`에서 시작 대기를 강제로 interrupt하는 주입 대역으로 원래 실패 보존·포트 반환·실행기 종료를 확인한다. 테스트 경합을 sleep으로 만들지 않는다.
- [ ] `LifecycleHttpTest`는 두 Context와 Context별 Filter A/B, 공유 Servlet 하나를 조립한다. `concurrentlyInvokesSameServletWithIsolatedChains`에서 init1·동일 인스턴스의service2중첩·각 요청의 A-before/B-before/service/B-after/A-after를 요청 ID별로 검증한다. trace는 ConcurrentLinkedQueue, 카운터는 AtomicInteger를 사용한다.
- [ ] `preservesMappingsShortCircuitAndErrorRecovery`는 기존 Host/Context 매핑·404·필터중단service0·부분응답뒤500·다음200·HEAD0바이트를 동시 실행 경로로 확인한다. `rollsBackBeforeAcceptingOnFilterFailure`, `stopsThroughServerAfterRequestsFinish`는 준비 전 요청진입0과 HTTP 완료 뒤 Servlet/Filter destroy각1·반복close·포트반환을 확인한다.
- [ ] `LifecycleServer.createEngine(Consumer<String> trace)`·`main(String[] args)`를 추가한다. FilterServer의 앱 구성을 재사용할 수 있지만 수명은 새 Server가 소유한다. main은 포트 인자(기본18081)→Service/Connector 등록→start→실포트 출력→Server.await로 실행하고 hook은stop5초를 호출한다. FAILED로 await가 풀리면 저장 원인을 보고하고 정리를 시도한다. hook/main의 중복 제어 조작은 예제 내부 단일 종료 조정으로 직렬화한다.
- [ ] 관련 테스트 뒤 `.\mvnw.cmd -B clean verify`를 실행한다. Surefire XML에서 실제 전체 사례·실패·오류·건너뜀을 집계하고 기존159사례 회귀·신규 사례 수·BUILD SUCCESS·JAR 생성을 기록한다. 구현 중 메서드 수가 바뀔 수 있으므로 최종 총수를 미리 통과 수로 적지 않는다.
- [ ] 생성 JAR에 대해 기존 Servlet/Container/Filter 실험과 새 `LifecycleHttpExperiment`를 실행한다. 새 실험은 프로그램 등록+포트0+latch/Future로 느린 요청 중 다른 요청·같은 인스턴스 동시 진입·격리·포화·읽기 제한·오류 뒤 복구·기한 초과destroy0·해제뒤재시도destroy1·포트반환을 확인한다. curl은 `--max-time 3`, 실험 클라이언트 소켓도 연결/읽기3초다.
- [ ] `doc/06-lifecycle/01-concurrency-and-lifecycle.md`에 이전 한계→최소 풀 적용→책임 분리→호출/종료 순서→실제 실패/성공·명령·날짜·커밋·환경→원본 차이→7단계 문제를 기록한다. README·진행 상황·범위·소스 지도·구조도에 순차 예제와 새 동시 예제의 적용 범위를 구분한다.
- [ ] 변경 코드·테스트·학습 문서의 요구 대응·소유권·종료 실패를 검토하고 관련 검사만 재실행한다. 전부 통과한 뒤 `feat(step-06): 동시 요청과 안전한 서버 생명주기 구현` 완료 커밋에 코드·테스트·문서를 함께 담는다. `git tag --list 'learn/step-06'`이 비어 있을 때만 새 태그를 생성하고 이미 있으면 이동하지 않는다.

## 7. 핵심 대응과 차이·생략

각 원본 근거 T1~T12는 3절의 고정 링크를 따른다. 아래 프로젝트 열은 모두 예정 동작이다.

| 항목 | 실제 Tomcat 동작·근거 | 프로젝트 동작 | 이유 | 영향·제약 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| I/O 인계 | Acceptor→NIO Poller→SocketProcessor→Handler, T6~T8 | accept→고정 블로킹 풀→새 Processor→공유 Adapter | 상태기계 학습 범위 제한 | 느린 연결이 작업자 점유 | Task2 느림·Task3 격리 | NIO 구현 없이 유지 |
| Processor 수명 | 확보·SocketState별 연결/재활용, T8 | 연결마다 새 생성·요청 상태 지역 보관 | recycle 누락 위험 축소 | 객체 생성 비용·keep-alive 없음 | Task3 URI/본문/HEAD 격리 | 의도적 유지 |
| 실행기·포화 | 내부/외부 Executor·TaskQueue 확장/force 분기, T5·T6 | 내부 소유 JDK고정4·유한큐16·AbortPolicy·연결close | 상한·거부 결과를 관찰 가능 | 총TCP접속수 제한 아님, 503 없음 | Task2 큐 포화·회복 | 외부Executor 제외 |
| 읽기/쓰기 제한 | NIO Wrapper에 read/write timeout, Processor 추가 분기, T7·T12 | read SO_TIMEOUT3000ms, stop 기한 뒤socket close | 작은 blocking 모델 | 헤더 절대기한·쓰기 자체의 시간제한 없음 | Task2 부분헤더·Task5 I/O취소 | slow-drip·OS별 쓰기 정밀조사는 별도 |
| 바인딩 | 기본bindOnInit=true, graceful close는BOUND_ON_START, T9 | 앱준비뒤bind, 모든 Connector bind뒤수락 시작 | 준비/바인딩실패 소유권 명확화 | Connector구성만으로 포트 예약 안 됨 | Task4 포트충돌·준비선행 | 유지 |
| 시작 실패 | LifecycleBase 제어실패/던진실패·throwOnFailure, T1~T4 | 항상 원인보고·성공분기록·역순rollback | 학습 예제에서 실패를 숨기지 않음 | 무한init의 강제취소 미지원 | Task4·5 부분시작 | 유지·별도 한계 명시 |
| 공유 구성 | 동적 addConnector 등 수명 연계 경로 존재, T2 | 첫시작전freeze, 이후등록거부 | 읽기전용구성의 안전한공개 | 실행중추가·재배포불가 | Task1 보관참조/순환 | 동적구성 제외 |
| 시작·종료 상태 | init/destroy·이벤트·재시작 포함, T3 | 6상태, close가종료/해제조정, 같은객체재시작거부 | 필요한 실패경로에 집중 | Tomcat Lifecycle API호환 아님 | Task4 상태표 | 7단계도이계약재사용 |
| Servlet/Filter 준비 | Filter준비후loadOnStartup, T10 | Filter선행·Servlet지연init유지 | 3~5단계동작유지 | Servletinit실패는시작실패아닌요청500 | Task4·6 init1/500/복구 | loadOnStartup 제외 |
| 진행작업 종료 | Service graceful설정·pause후Engine stop, Wrapper는유한대기뒤destroy경로, T2·T9·T11 | 전체작업완료전Service전체해제보류, 기한후실패·재시도 | 사용중인스턴스의명시적보호 | 비협조작업이면FAILED·일부자원남음 | Task5 destroy0→해제뒤1 | 의도적으로원본보다보수적 |
| 콜백 정리 | Wrapper/Context의수명호출, 일반실패처리는각메서드별, T10·T11 | Service별1정리Future·한번만destroy·실패집계 | 호출자유한대기·재시도중복방지 | 임의코드강제종료없음·원본전체실패정책과다름 | Task5 지연/영구실패 | 유지 |
| 앱공유객체 | 이번소스조사로업무필드안전성까지보장하지않음 | Servlet/Filter/Valve공유, 업무상태는앱책임 | 컨테이너와앱동시성구별 | ++카운터등경합가능 | Task6 AtomicInteger·동일인스턴스중첩 | 학습설명·자동잠금없음 |
| 운영기능 | naming·JMX·utility executor·TLS등추가, T1·T4 | 등록된서버수명·plain HTTP만 | 현재학습경계 | 일반Tomcat대체아님 | 범위문서점검 | 자동후속구현없음 |

## 8. 예정 실행 명령과 7단계 인계

다음 명령의 새 타입·파일은 **아직 없으므로 지금 실행 가능한 완료 예제가 아니다.** 구현 후 실제 환경·결과와 함께 학습 문서에 옮긴다.

```powershell
.\mvnw.cmd -B clean verify
New-Item -ItemType Directory -Force target/experiments | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar -d target/experiments doc/experiments/ServletHttpExperiment.java doc/experiments/ContainerHttpExperiment.java doc/experiments/FilterHttpExperiment.java doc/experiments/LifecycleHttpExperiment.java
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' ServletHttpExperiment
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' ContainerHttpExperiment
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' FilterHttpExperiment
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' LifecycleHttpExperiment
& "$env:JAVA_HOME/bin/java.exe" -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar simpletomcat.examples.LifecycleServer 0
# 별도 터미널: 출력한 실제 포트를 사용한다.
curl.exe --max-time 3 -i http://127.0.0.1:<실제포트>/shop/hello
curl.exe --max-time 3 -I http://127.0.0.1:<실제포트>/shop/hello
```

포트 권한·DNS·샌드박스 제한은 앱 실패와 별도로 기록한다. 실험 프로세스도5초 단위의 유한 완료 대기와 finally 정리를 사용한다. 실제 콘솔 Ctrl+C 검증은 graceful 성공/기한초과 보고를 구분하고, 종료했다고만 적지 말고 포트 재bind·콜백 횟수·잔여 작업 여부를 기록한다. 강제 JVM 종료는 일반 반복 stop 검증과 구분한다.

7단계 착수 전 확정할 것은 `startup.Tomcat.addContext/addServlet/start`의 상세 분기, SimpleTomcat의 등록 반환 타입·기본 Host·실패 계약, DispatcherServlet의 업무경로·Manifest다. 6단계에서는 해당 API·업무 예제·java -jar 진입점을 구현하지 않는다. 7단계는 이번 Server/Service/Connector의 완료 결과를 읽고 새 상세 계획에서 조립 API를 정한다.

추가 조사로 남길 영역은 다음과 같다. 이를 구현 준비가 된 Tomcat 동일 동작으로 표시하지 않는다.

- 무한 init의 안전한 취소, 영구 실행 콜백의 강제 종료: 이번 범위에서 보장하지 않음. 동작·한계 설명을 완료 조건으로 둔다.
- TLS/HTTP2/async·재배포·외부 Executor·virtual thread·Tomcat의 모든 timeout 상태별 응답: 제외, 필요 시 별도 목표로 조사한다.
- 쓰기 정체의 OS별 취소 시간·지속 입력 헤더의 절대 기한: SO_TIMEOUT으로 해결됐다고 표시하지 않는다. 프로젝트 요구 확장 시 별도 재현·정책이 먼저다.

## 9. 원본 파일 식별값

2026-11-10 고정 태그 raw 다운로드의 SHA-256이다. 전체 원본을 재배포하지 않고 재조회 대조에 쓴다. `target/step06-source`는 clean 때 지워질 수 있으므로 조사 근거는 위 링크·메서드·식별값으로 보존한다.

```text
AbstractEndpoint.java   5D7C83653D4919269E28C942E75FD4AE13A4BB818CD705DC7E8F09D21F3DE0C8
AbstractProtocol.java   E93D3D36A191586D4C9EE7BF0AA20FED00821D8AEEA118D54ACF62D960C32BF7
Acceptor.java           315BD394367AE78E8450AAC0ED6BD5EA8D0D1F884E7FF9EC98C91D017B0EAE64
Connector.java          913640ECE5158F0DC0A537698F0DBDE5255518991167B6EFEAFF9B078795FE99
Http11Processor.java    45F116FCFB75D5568D0E6E6388A7B74D7D98B7863FACF81696686F2EC1B4AFB6
LifecycleBase.java      2AF6DBC2DD1B246B84D246A6334CFFD77AC0D319F14F5D3AC860C29768313C92
LifecycleState.java     078BCBF18D2954797528CA35A8035B7B3EA898D59E57A0B6A64B208945E1A391
NioEndpoint.java        185CF430AB72311C09E956C9C882D86695A14D83163FF941C2C10BFCC822A039
StandardContext.java    9F935288D86C51A3ED02CEC4CFA8D8B189D35935D3A2D9A926CA80A2CBE552D2
StandardServer.java     73FE385F13B1354ABCA838E536FFD9FD11CA64A390765AB632CFDA2639139145
StandardService.java    603815E156179E26E923BD4241B1DBC0C31766BD7A5A4412FCCF8E147AEB8D58
StandardWrapper.java    49BF9927C5A15133A56DDA58153573ABE8F24A79636284969D57FA9784BA7EB3
TaskQueue.java          D6522CF5A2C24D314ADDCDF22F28045EE3C73BF63FF68FDC25441CD80749B656
ThreadPoolExecutor.java ECFCE67AEC66295B4ACC04E96548C33DB3AEAD14046212ADDB76B3C273C74971
```

## 10. 계획 점검과 이번 문서의 완료 경계

| 기존 6단계 요구 | 대응 작업 |
|---|---|
| 느린 요청 중 다른 요청·동일 Servlet 동시 호출 | Task2·3·6 |
| 요청 데이터·응답·Filter 위치 격리 | Task3·6 |
| 읽기 제한·큐 포화·오류 뒤 회복 | Task2·3·6 |
| 수신 전 준비·바인딩/초기화/부분 시작 실패 | Task1·4·5 |
| 신규 수락 중지·작업 대기·안전한 해제 | Task2·4·5 |
| 기한 초과 해제 보류·완료 후 재시도·반복 종료·포트 반환 | Task5·6 |
| 학습 문서·원본 차이·실제 검증·완료 커밋/태그 | Task6·7절 |

자체 검토에서는 타입/서명·Task 의존성·상태표·실패 원인 보존·Review Focus별 테스트를 대조했다. 중간 정리 실패, accept 직후 소유권 경합, 전체 수락 선행 중지, 늦은 시작 완료의 FAILED 덮어쓰기, 영구 콜백 실패와 재시도 가능한 지연을 구분하도록 보강했다.

이번 실제 문서 검사에서 변경한 Markdown 5개 파일의 로컬 링크46개·원본14개 SHA-256 일치·줄 끝 공백 없음·예정 체크박스 미완료 상태를 확인했다. `git diff --check`도 종료0이었다. 신규 계획은 미추적 파일이므로 별도 공백 검사에 포함했다. 변경은 `doc/`의 계획·목차·진행 상황·소스 지도·기존 범위 문서뿐이다.

제품 테스트·clean verify·HTTP 실험은 이번에 실행하지 않았으며 결과를 추가하지 않는다. **0~5단계 완료·6~7단계 예정이라는 구현 상태와 기존 태그는 유지한다.**


## 2026-11-10 — 사용자 구현 요청 후 실제 실행

사용자의 명시적 구현 요청으로 Task1~6을 실행했다. 원문 체크박스·예정값은 계획 작성 시점 그대로 보존하고, 실제 구현·검증은 [6단계 기록](../06-lifecycle/01-concurrency-and-lifecycle.md)을 따른다. 테스트를 suite별로 통합했고 LifecycleControl 공통 상태 가드를 추가했다. 독립 리뷰에서 발견한 시작 성공/배경 실패 경합과 Connector 종료 상태 누락을 수정했다.

Ruling: 전체 공유 기한을 앞 Service가 소진하면 뒤 독립 Service의 신규 정리는 Duration.ZERO에서 시작하지 않고 다음 stop으로 넘긴다. 모든 수락은 먼저 중지한다. 이는 계획의 전체 예산·0에서 신규 정리 금지와 일치하며, Task5의 독립 앱 destroy1은 잔여 예산이 있거나 후속 stop까지 포함한 결과로 구체화한다. 관련 재시도 테스트를 추가했다.

Ruling: 기존 HttpTestServer의 생성 실패 정리를 공통 유틸에 보완하고, 계획의 가상 테스트명을 실제 동작 suite에 통합했다. EndpointOptions는 ConcurrentEndpointTest, 요청/체인 격리는 LifecycleHttpTest, 지연/영구 실패/부분 시작은 LifecycleShutdownTest가 담당한다. 개별 단계 중간 커밋 대신 검증된 코드·테스트·문서를 단계 완료 커밋 하나에 담는다.
