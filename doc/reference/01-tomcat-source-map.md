# Tomcat 10.1.34 공식 소스 지도

2026-11-10 6단계 구현 결과: [동시성·수명 학습 기록](../06-lifecycle/01-concurrency-and-lifecycle.md)에 실제 호출·소유권·검증·원본 차이를 남겼다. 아래 조사 시점 기록은 보존한다.

2026-11-10 6단계 계획 조사: StandardServer·StandardService·Connector·LifecycleBase/State, Endpoint·Acceptor·NIO·ProtocolHandler 처리 경로, TaskQueue/Executor, Context·Wrapper·HTTP Processor의 고정 소스 14개를 확인했다. [6단계 상세 계획](../plans/2026-11-10-step-06-implementation.md)에 실제 메서드·시작/종료·오류·소유권, SHA-256, 프로젝트 차이와 미조사 영역을 기록했다. 6단계 구현·실행 검증은 아직 예정이며, 아래 과거 조사 기록을 보존한다.

2026-11-10 5단계 구현에서는 S8 외 ApplicationFilterFactory.createFilterChain, ApplicationFilterConfig의 생성자/getFilter/initFilter/release, StandardContext.filterStart/filterStop/startInternal/stopInternal을 고정 소스로 조사했다. 책임·상태·실패·종료와 원본 SHA-256은 [상세 계획](../plans/2026-11-10-next-implementation.md#3-tomcat-10134-고정-소스-조사), 실제 차이·검증은 [5단계 기록](../05-filter/01-filter-chain.md)을 따른다.

릴리스 태그 `10.1.34`에 고정한다. 최신 배포 권장 버전을 뜻하지 않는다. 클래스명과 함께 메서드의 책임·호출 순서·상태·오류·자원 소유권을 읽는다.

| ID | 단계 | 공식 소스·읽을 메서드 | 확인할 동작 |
|---|---|---|---|
| S1 | 1 | [Acceptor.run](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/Acceptor.java) | 연결 수락·인계·실패 연결 정리. HTTP 해석은 하지 않음 |
| S2 | 2 | [AbstractProtocol.ConnectionHandler.process](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/AbstractProtocol.java), [NioEndpoint.SocketProcessor.doRun](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/NioEndpoint.java) | Handler의 Processor 확보·호출, 소켓 상태에 따른 Endpoint 정리 |
| S3 | 2 | [Http11Processor.service/prepareRequest/prepareResponse](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/http11/Http11Processor.java), [Http11InputBuffer.parseRequestLine/parseHeaders](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/http11/Http11InputBuffer.java) | 파싱·요청 준비·Adapter 호출·응답 준비·본문 필터 |
| S4 | 3 | [StandardWrapper.allocate/deallocate/initServlet/unload](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapper.java) | 초기화·사용 중 할당 수·해제 |
| S5 | 4 | [CoyoteAdapter.service/postParseRequest](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/connector/CoyoteAdapter.java) | URI 준비·Mapper 호출 뒤 Engine Pipeline 진입 |
| S6 | 4 | [Mapper.map/internalMap/internalMapWrapper](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/mapper/Mapper.java) | Host·Context·Wrapper 선택. 정확·접두어·확장자·기본 순서와 welcome 등 추가 규칙 |
| S7 | 4 | [StandardContextValve.invoke](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardContextValve.java) | 보호 경로와 Wrapper 확인, 다음 Pipeline 호출 |
| S8 | 5 | [StandardPipeline.addValve/getFirst](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardPipeline.java), [StandardWrapperValve.invoke](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapperValve.java), [ApplicationFilterChain.doFilter/internalDoFilter](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/ApplicationFilterChain.java) | 기본 Valve와 사용자 Valve, 할당 후 필터 체인과 마지막 service 호출 |
| S9 | 6 | [StandardService.startInternal/stopInternal](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardService.java), [AbstractEndpoint.processSocket](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/AbstractEndpoint.java) | Engine 시작 후 Connector 시작, graceful 설정·pause·stop, Executor 인계 |
| S10 | 7 | [startup.Tomcat.addContext/addServlet/start](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/startup/Tomcat.java) | 프로그램 등록과 내부 구성 조립 |

S1~S9의 핵심 흐름을 기준으로 단계별 계획을 작성했다. 구현을 시작할 때 실제 변경과 관련된 예외 분기까지 다시 확인한다. 공개 API의 모든 메서드나 오류 상태를 이미 확인했다고 간주하지 않는다. S10은 7단계 API를 확정할 때 상세 조사할 대상이다.

설명 전용 소스는 [범위와 차이](02-scope-and-differences.md)에 따로 연결한다. 아직 조사하지 않은 상세는 사실로 확정하지 않고 해당 단계의 조사 항목에 남긴다.
