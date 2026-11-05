# 6단계 · 동시 요청과 서버 생명주기

상태는 예정이다. 기준 Tomcat 태그는 10.1.34다.

## 이전 문제와 최소 해결

순차 서버는 느린 연결 하나가 다음 요청을 막는다. 단순 클래스 분리로 해결되지 않는다. 제한 작업 풀을 붙이고 진행 요청을 latch로 멈춘 상태에서 종료해 보며 수락·작업·서블릿 해제 순서의 문제를 관찰한다.

## 개념·인터페이스·작업

제한된 작업 풀·큐, 읽기 타임아웃, 활성 소켓 집합, 요청별 Processor·Request·Response·FilterChain 상태를 도입한다. 공유 서블릿의 필드가 자동으로 안전해지지 않음을 설명한다.

Lifecycle의 시작·종료·실패 상태를 정의하고 StandardServer·StandardService·Connector를 추가한다. Server는 Service들을, Service는 Engine과 Connector를 소유한다. Connector는 ProtocolHandler와 실행 작업을 연결한다. 시작 전 등록을 끝내고 시작 이후 변경은 거부한다.

## Tomcat 구현 근거와 대응

[StandardService.startInternal/stopInternal](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardService.java), [AbstractEndpoint.processSocket](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/AbstractEndpoint.java), [NioEndpoint.SocketProcessor](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/NioEndpoint.java)를 읽는다.

| 핵심 동작 | Tomcat 동작 | 프로젝트 예정 흐름 | 테스트 |
|---|---|---|---|
| 시작 | Engine 후 Connector 시작 | 컨테이너 준비 → 수신 시작 | 포트 충돌·초기화 실패 rollback |
| 처리 | Executor 인계·SocketState 처리 | 수락 → 제한 풀 → 요청별 처리 | 느린 요청 중 다른 요청·격리 |
| 종료 | 설정별 graceful 대기·pause·Engine stop·Connector stop | 신규 수락 중지 → 요청 정리 → 컨테이너 해제 → 실행 자원 종료 | 종료 순서·실패·재시도 |

## 완료 테스트

- latch로 느린 요청을 고정한 동안 별도 요청이 성공한다.
- URI·본문·필터 위치가 다른 요청에 섞이지 않는다. 한 서블릿에 동시 호출이 가능함을 확인한다.
- 읽기 타임아웃·작업 큐 포화 시 자원을 정리하고 이후 정상 요청을 처리한다.
- 바인딩 충돌·초기화 실패로 시작하지 못하면 이미 준비한 자원을 회수한다.
- 종료가 신규 수락을 멈추고 진행 요청이 끝난 뒤 destroy를 호출한다.
- 제한 시간 이후에도 활성 작업이면 종료 실패를 반환하고 해당 서블릿 해제를 보류한다. 작업 완료 뒤 종료를 재시도한다.
- 반복 종료·읽기 대기 중 종료·포트 반환을 확인한다.

## 구현 순서·실행

테스트 실패 → 요청 상태 분리 → 풀·큐·타임아웃 → Lifecycle·구성 객체 → 실패 정리·종료 순서 → 전체 검증 → 문서·완료 커밋·learn/step-06 순서다. 예정 실행 클래스는 `simpletomcat.examples.LifecycleServer`다. 유한 대기 시간·작업자 수·큐 크기를 설정으로 노출하고 실제 값은 구현 작업 계획에서 근거와 함께 확정한다.

## 차이와 생략

| 항목 | 실제 Tomcat·근거 | 예정 동작 | 이유 | 영향·제약 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| I/O | NIO Poller·SocketProcessor | 블로킹 제한 풀 | NIO 비용 제한 | 느린 연결이 작업자 점유 | 느린 연결·타임아웃 | 유지 |
| 종료 | 설정별 graceful·pause·stop | 유한 대기·실패 보고·활성 해제 보류 | 안전한 결과 명시 | 비협조 앱이면 종료 실패 | latch·재시도 | 유지 |
| 포화 | Executor·Endpoint 설정별 정책 | 유한 큐·명시적 거부와 자원 정리 | 무제한 축적 방지 | 연결 거부 가능 | 큐 포화 뒤 정상 요청 | 구현 전 정책 명시 |
| 상태기계 | LifecycleBase와 다수 이벤트 | 필요한 최소 상태·실패 경로 | 학습 범위 제한 | 재시작·이벤트 전체 미지원 | 중복 시작·종료·실패 | 유지 |

실제 Tomcat이 모든 설정에서 같은 graceful 종료를 보장한다고 설명하지 않는다. 강제 Thread.stop을 사용하거나 실행 중인 서블릿을 정상 해제했다고 기록하지 않는다. 현재 구현·실행 결과는 없다.

다음은 사용자에게 노출된 내부 조립 의존이다. [7단계](2026-11-05-step-07-embedded.md)에서 내장 컨테이너 API로 묶는다.
