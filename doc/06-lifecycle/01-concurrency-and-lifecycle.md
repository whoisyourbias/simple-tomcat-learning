# 6단계 — 동시 요청과 서버 생명주기

2026-11-10. 실행 기준은 `bf79de7`(learn/step-05) 이후 `codex/step-06` 작업 트리다. 완료 경계는 코드·테스트·학습 문서를 함께 검증한 `learn/step-06`으로 남긴다. 이전 단계의 실행 기록은 그대로 보존한다.

## 이전 한계와 최소 해결

5단계까지의 Endpoint는 accept한 연결 하나를 호출 스레드에서 끝까지 처리했다. 느린 헤더나 Servlet이 다음 연결을 막았고 close는 listener와 현재 소켓을 즉시 취소했다. FilterServer와 ServerRunner가 앱 준비·해제를 직접 조정했으므로 여러 작업자의 종료를 확인할 주체도 없었다.

가장 작은 동시 처리 방법은 Handler를 작업 풀에 넘기는 것이다. 하지만 무제한 큐는 대기 연결을 계속 쌓고, 소켓을 닫은 것만으로 Servlet이 끝났다고 판단하면 실행 중 destroy가 호출될 수 있다. 그래서 제한 풀과 소켓 소유권, 실제 작업 종료, 앱 수명을 함께 분리했다.

기존 Endpoint·Http11Protocol·3~5단계 예제는 순차 동작으로 유지한다. 새 ConcurrentEndpoint는 worker4·queue16·readTimeout3000ms를 기본으로 사용하고, ConcurrentHttp11Protocol은 연결마다 기존 Http11Processor를 생성한다. HTTP 파서를 복제하지 않았다.

## 구성과 호출 책임

```text
StandardServer
  └─ StandardService ─ Engine → Host → Context → Wrapper
       ├─ Mapper / CoyoteAdapter            └─ 공유 Servlet
       ├─ ApplicationLifecycle       Context └─ 공유 Filter 목록
       └─ Connector
            ├─ accept Thread
            └─ ConcurrentHttp11Protocol → ConcurrentEndpoint
                 └─ 제한 풀의 ConnectionTask
                      → 연결별 Http11Processor
                      → 요청별 Request / Response / MappingData
                      → Engine Pipeline → … → WrapperValve
                      → 요청별 ApplicationFilterChain → 공유 Servlet.service
```

Container/Pipeline/Valve 구성은 시작 전에 freeze한다. 보관한 참조의 setNext·매핑 추가·자식 등록도 거부한다. 자식 목록은 등록 순서로 보존한다. 등록과 freeze를 동시에 하는 구성은 지원하지 않으며, freeze 뒤 Thread.start/Executor.execute로 실행자에 공개한다. Servlet·Filter·Valve의 사용자 업무 필드는 여전히 앱이 동기화해야 한다.

Server는 Service들을 소유하고 Service는 한 Engine과 Connector들을 소유한다. 같은 Engine·Service·Connector의 중복 소유는 거부한다. Connector 생성은 바인딩하지 않으며 getPort는 -1이다. Service.start는 구성 검증·동결→Filter 준비→전체 Connector bind→accept 시작 순서다. Servlet은 기존처럼 요청 시 지연 init한다. 모든 Service의 시작을 네트워크상 원자적으로 공개한다고 보장하지 않는다.

## 작업·소켓·종료를 구별하기

ConcurrentEndpoint는 수락한 연결을 작업 제출 전에 소유 집합에 등록한다. execute 거부는 소켓 close·등록 해제로 끝나며 HTTP503을 생성하지 않는다. 작업자/큐가 모두 차면 클라이언트는 EOF/RST를 볼 수 있다. SO_TIMEOUT은 한 read의 무진행 제한으로, 헤더 전체 절대 기한이나 쓰기 제한이 아니다.

ConnectionTask는 실행/미시작 상태를 보호하고 finally에서 socket close·등록 해제·완료 통지를 수행한다. abort가 큐에서 제거한 작업은 Handler에 진입하지 않는다. 실행 중 작업의 소켓이 닫혀도 콜백이 반환하지 않았다면 소유 집합에 계속 남는다. Snapshot의 queuedTasks는 등록했으나 미시작인 작업 수로 JDK 물리 큐 길이와 항상 같지는 않다.

```text
stop(Duration)
  → Server의 모든 Service quiesce
  → Service의 모든 Connector 수락 중지
  → 동일 Deadline의 잔여 시간으로 accept / 작업 풀 완료 대기
  → 기한 초과: I/O 취소·미시작 큐 취소
      → 실행 중 작업이 남으면 FAILED, 해당 Service 앱 전체 해제 보류
  → 전체 작업 종료: ApplicationLifecycle의 단 하나의 정리 Future
      → 역순 Context마다 역순 Wrapper.unload → Context.stopFilters
      → 정리 Future와 실행기 종료까지 확인 → STOPPED
```

최소 상태는 NEW/STARTING/STARTED/STOPPING/STOPPED/FAILED다. 같은 객체의 재시작은 거부한다. 시작 성공 전이와 배경 실패는 같은 monitor에서 처리해 FAILED가 늦은 STARTED로 덮이지 않게 했다. 사용자 콜백 동안 제어 monitor를 잡지 않아 getState가 대기하지 않으며 수명 조작 재진입은 즉시 거부한다.

destroy 지연도 호출자가 잔여 시간만 기다린다. 시간이 지나도 정리 Future를 cancel하지 않으며 반복 stop은 같은 작업을 기다린다. 지연 작업이 완료되면 재시도에서 STOPPED로 갈 수 있다. 반면 일반 destroy 예외는 최초 원인과 suppressed를 보존한 영구 정리 실패로 남기고 같은 콜백을 재호출하지 않는다.

**공유 기한 정책의 구체화:** 역순 Service 정리에서 앞 작업이 전체 예산을 소진하면 뒤 Service의 미착수 앱 정리는 새로 시작하지 않는다. 모든 listener는 이미 중지한다. 다음 stop에 새 예산을 주면 보류한 독립 Service도 회수한다. 남은 시간이0인데도 모든 독립 destroy가 즉시 실행된다고 보장하지 않는다. 이 선택은 무제한 추가 정리 스레드 대신 소유권과 전체 대기 상한을 유지한다.

## 실제 검증과 실패 기록

| 실행 | 실제 결과 |
|---|---|
| 구성 동결 테스트 최초 실행 | freeze API 부재 컴파일 실패 후 구현, 관련54사례 통과 |
| Endpoint/Protocol 최초 실행 | 신규 타입 부재 컴파일 실패. 첫 TCP 실행은 sandbox connect Permission denied였고 코드 수정 없이 권한 적용 회귀54사례 통과 |
| Lifecycle 최초 실행 | 신규 타입 부재 컴파일 실패 후 기본 시작/반복종료 통과 |
| 독립 예비 리뷰 | 배경 FAILED→STARTED 덮어쓰기, 자식 Connector 종료 상태 누락 발견. 원자적 상태 전이와 Connector.stop 연결 보강 후 관련7사례 통과 |
| 테스트 helper 생성 실패 | 시작 대기 interrupt 경로의 포트·실행기 누수 재현 계약 추가 후 회수·interrupt 보존 통과 |
| 중간 전체 clean verify | 189사례, 실패/오류/건너뜀0, BUILD SUCCESS, JAR 생성. 이후 경계 테스트를 추가했으므로 최종 집계는 아래 기록을 따른다 |
| 새 생성 JAR 실험 | curl8·두 앱·필터중단·필터/Servlet500·복구·HEAD·반복stop·포트반환 PASS |
| 새 동시성/종료 JAR 실험 | 느린 요청 중 빠른 응답·공유init1·기한초과destroy0·완료뒤재시도destroy1·포트반환 PASS |
| 새 제한/복구 JAR 실험 | 큐포화 연결거부·대기요청복구·읽기제한close·다음정상응답·포트반환 PASS |
| 기존 생성 JAR 실험 | Servlet init/service/destroy1/4/1·HEAD·500뒤복구, Container curl16, Filter curl9, 반복해제·포트반환 모두 PASS |

네트워크 검증은 sandbox 밖 권한 적용 실행이었다. 환경 권한 실패를 제품 결함으로 기록하지 않는다. 성공 전이/종료 상태 리뷰 수정은 실제 코드 결함 수정이다. 테스트 trace는 ConcurrentLinkedQueue, 공유 카운터는 AtomicInteger, 실행 순서는 latch와 Future·Snapshot 대기로 고정했다. 네트워크 포트0·클라이언트 연결/읽기3초·실행기 대기5초·finally 정리를 사용한다.

## 계획 대비 실행 정리

테스트는 계획의 사례를 동작별 suite로 합쳤다. EndpointOptions 경계는 ConcurrentEndpointTest, Request/Response/FilterChain 격리는 LifecycleHttpTest, 여러 종료 경계는 LifecycleShutdownTest에 모았다. LifecycleControl은 중복된 상태 조작을 공통화한 추가 타입이다. 계획의 Task별 가상의 중간 커밋 대신 최종 검증된 코드·테스트·문서를 한 단계 완료 커밋으로 묶는다.

| 위험/계획 항목 | 실제 검증 위치 |
|---|---|
| 보관 참조 구성 변경·순환 Valve | ContainerFreezeTest·StandardServiceTest |
| 풀·큐 포화·읽기 제한·accept 직후 stop·미시작 취소·serve1회 | ConcurrentEndpointTest·LifecycleHttpExperiment |
| 같은 Servlet 중첩·요청 경로/쿼리/응답·각 체인 순서 | LifecycleHttpTest·LifecycleHttpExperiment |
| Context 초기화 실패·소유권·부분 Server 시작·Thread 시작/accept 실패 | ApplicationLifecycleTest·StandardServerTest·ConnectorTest |
| 배경 실패 상태 보존·Connector 종료 상태 | LifecycleControlTest·StandardServiceTest |
| 진행중 해제 보류·초기화 실패 rollback 중 진행 요청·interrupt | LifecycleShutdownTest |
| destroy 지연/일반 실패·동일 Future·반복stop·공유기한·독립 앱 재시도 | LifecycleShutdownTest·ConnectorTest |
| 실제 HTTP 매핑·중단·404/500·HEAD·복구·포트 반환 | LifecycleHttpTest·기존/신규 JAR 실험 |

## Tomcat 대조와 남은 범위

[고정 소스 조사·14개 SHA-256·차이 표](../plans/2026-11-10-step-06-implementation.md)를 기준으로 한다. 실제 Tomcat은 NIO Poller·SocketProcessor와 Processor 재활용, TaskQueue의 풀 확장/force 분기, bindOnInit 설정별 graceful, 다수 Lifecycle 이벤트·재시작을 갖는다. 프로젝트는 JDK 고정 블로킹 풀·연결별 Processor·동결 구성·6상태를 선택했다.

특히 Tomcat StandardWrapper.unload의 유한 대기 뒤 destroy 경로와 달리, 프로젝트는 Service 전체 작업 완료 전 앱 해제를 보류하는 보수적 정책이다. 두 방식이 같다고 설명하지 않는다. 모든 차이의 이유·영향·검증·후속 여부는 상세 계획7절에 있다.

무한 init 취소, 영구 실행 사용자 콜백의 강제 종료, TLS/HTTP2/async·배포·재시작·외부 Executor는 제외한다. close 기본5초는 종료 시도와 보고의 예산이며 무한 콜백이나 JVM/OS 종료에서 destroy 완료를 보장하지 않는다. 쓰기 정체·지속 입력 헤더의 절대 기한도 SO_TIMEOUT으로 해결되지 않는다.

다음은 사용자가 내부 Engine/Service/Connector를 직접 조립해야 하는 문제다. 7단계에서 원본 startup.Tomcat을 조사하고 SimpleTomcat과 사용자 DispatcherServlet·실행 JAR 진입점을 추가한다.

## 재현 명령

```powershell
.\mvnw.cmd -B clean verify
New-Item -ItemType Directory -Force target/experiments | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar -d target/experiments doc/experiments/ServletHttpExperiment.java doc/experiments/ContainerHttpExperiment.java doc/experiments/FilterHttpExperiment.java doc/experiments/LifecycleHttpExperiment.java
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' LifecycleHttpExperiment
& "$env:JAVA_HOME/bin/java.exe" -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar simpletomcat.examples.LifecycleServer 0
# 별도 터미널에서 출력한 실제 포트를 사용한다.
curl.exe --max-time 3 -i http://127.0.0.1:<실제포트>/shop/hello
```


## 최종 검증 — 2026-11-10

최종 `mvnw.cmd -B clean verify`는 **194사례, 실패/오류/건너뜀0, BUILD SUCCESS, JAR 생성**으로 종료0이었다(2026-11-10 17:54:40 KST). 기존159＋신규35사례다. 최종 JAR로 Servlet/Container/Filter/Lifecycle 네 실험을 다시 컴파일·실행했고 모두 종료0이었다. Servlet1/4/1·Container curl16·Filter curl9 및 새 Lifecycle의 curl8·동시 요청·큐/읽기 제한·destroy0→재시도1·포트 반환을 확인했다.

최종 점검 중 추가 freeze assertion에서 누락 import로 testCompile이 실패해 해당 import를 복원했다. 또 계획의 정리 실행기 생성/submit 실패 대역을 보강하며 일반 RuntimeException이 LifecycleException으로 변환되지 않는 결함을 재현했다. 생성/제출 실패를 래핑하고 성공 초기화 자원과 미착수 정리를 보관하도록 수정해 관련5사례와 최종 전체 검증을 통과했다. 이 변경도 독립 재리뷰에서 추가 차단 사항이 없었다.

원시 실행 기록은 [최종 빌드](../plans/step-06-work/final-clean-verify.log), [Lifecycle JAR 실험](../plans/step-06-work/LifecycleHttpExperiment.log), [실행 ledger](../plans/step-06-work/progress.md)에 보존했다. 구조도 JavaScript 문법 검사는 통과했다. 이 환경에 Playwright가 없어 브라우저 자동 회귀는 재실행하지 않았다. 구조도는 6단계 배지·호출 클래스·종료 순서·실행 기록 링크만 갱신했다.


최종 문서 검사에서 변경 Markdown의 로컬 링크104개·신규 테스트 한국어 주석35개·줄 끝 공백 검사를 통과했고 Surefire XML 집계도194/0/0/0이었다. `git diff --check`와 구조도 `node --check`는 종료0이었다. Git의 CRLF→LF 안내는 저장소 정규화 안내이며 공백 검사 실패가 아니다. 신규 Java 형식 정리는 기능 변경 파일과 신규 파일에만 적용했다.
