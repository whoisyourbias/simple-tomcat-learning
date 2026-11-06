# Tomcat 구성요소 설명과 실제 호출 관계 보강

## 승인한 계획 — 2026-11-05

대상은 `doc/00-overview/tomcat-architecture.html`이다. 기존 그림을 유지하며 모든 기존 용어의 hover에는 역할·대표 클래스를, 클릭·Enter·Space·터치에는 그림 바로 아래 별도 상세 패널을 제공한다. 요청 재생 단계 설명과 구성요소 상세 설명은 각각 유지한다.

설명은 소속 패키지·계약과 대표 구현 → 생성·보유·설정/주입·조회 결과 → 초기화/조립 → 요청 시 호출 → 반환/상태/오류/정리 → 고정 소스 링크 순서다. 관계가 없는 개념에는 해당하지 않음을 명시한다. 조립 관계를 요청 호출로 표시하지 않는다.

선택지는 hover만 확장, hover와 클릭 고정, hover 요약과 별도 상세 패널이었다. 사용자는 **기존 요소와 핵심 내부 흐름** 및 **hover 요약과 별도 상세 패널**을 선택했다. 그림 노드·재생 경로를 확장하지 않고 Endpoint·Processor·Valve·FilterChain의 숨은 호출을 패널에서 풀어 설명한다.

### 작업 순서와 완료 기준

1. Tomcat 10.1.34 소스의 실제 생성자·메서드·분기를 확인하고 아래 대응표를 작성한다. 확인하지 않은 정책은 조사 대상으로 남긴다.
2. 문자열 용어집을 구조화된 데이터로 바꾸고 요약 hover와 별도 상세 패널을 연결한다. 외부 라이브러리 없이 HTML 단독 열람을 유지한다.
3. 선택 강조, 패널 교체, 닫기/Escape, 원래 용어로 포커스 복귀, 작은 화면의 긴 패키지명 줄바꿈을 구현한다.
4. 브라우저에서 전체 노출 용어, 키보드·터치, 일반/JSP 재생·일시정지·속도·단계 이동·초기화를 검증한다. 예상 동작과 실제 결과를 구분해 기록한다.

이번 작업은 학습 자료의 UI·설명 변경이다. Java 서버의 학습 단계 완료를 뜻하지 않으며 기존 `learn/step-NN` 태그와 과거 검증 기록은 변경하지 않는다.

## 고정 소스와 핵심 동작 대응

모든 구현 근거는 Apache Tomcat **10.1.34** 태그다. 각 패널에 해당 소스 파일과 읽을 메서드를 연결했다. 아래 순서는 일반 HTTP/1.1 NIO 동기 요청 기준이며, 항상 모든 분기를 통과한다는 의미는 아니다.

| 구성·근거 | 생성·설정·소유권 | 실제 호출·상태·오류·정리 |
|---|---|---|
| [StandardServer](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardServer.java), [startup.Tomcat](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/startup/Tomcat.java) | 내장 구성의 `getServer()`가 Server·Service 생성, `addService()`가 역참조 설정 | Server가 Service 시작·중지·파괴를 관리하며 매 요청의 HTTP 실행 단계는 아님 |
| [StandardService](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardService.java) | 필드에서 Mapper·MapperListener 생성, `addConnector()`/`setContainer()`로 Service 역참조 설정 | `startInternal()`: Engine → Executor → MapperListener → Connector. `stopInternal()`: graceful 시도·설정된 대기 → pause → Engine.stop → Connector.stop → MapperListener.stop → Executor.stop. 소켓 바인딩·대기 효과는 설정 의존 |
| [Connector](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/connector/Connector.java), [AbstractProtocol](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/AbstractProtocol.java) | Connector 생성 또는 전달된 ProtocolHandler 사용. `initInternal()`이 Adapter 생성·`setAdapter()`; AbstractProtocol 생성자가 ConnectionHandler 생성·Endpoint에 설정 | ProtocolHandler 생명주기는 Endpoint로 위임. `ConnectionHandler.process()`는 연결된/재사용/새 Processor 확보 후 `processor.process()`. OPEN은 반환·읽기 재감시, CLOSED 등은 별도 처리 |
| [Acceptor](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/Acceptor.java), [AbstractEndpoint](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/AbstractEndpoint.java), [NioEndpoint](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/NioEndpoint.java) | Http11NioProtocol이 Endpoint 생성. Endpoint가 소켓·Poller·Acceptor 관리; 외부 Executor가 없으면 내부 Executor 생성 | `Acceptor.run → serverSocketAccept → setSocketOptions → Poller.register`; `Poller.processKey → processSocket(..., true) → Executor.execute → SocketProcessor.doRun → Handler.process`. CLOSED/실패는 소켓 정리. 정지 시 내부 Executor 정리와 외부 Executor 소유권을 구분 |
| [Http11Processor](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/http11/Http11Processor.java), [CoyoteAdapter](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/connector/CoyoteAdapter.java) | Protocol의 `createProcessor()`가 Processor 생성; Adapter·프로토콜 전달. Adapter는 필요 시 Catalina Request/Response 생성·연결 | `AbstractProcessorLight.process → Http11Processor.service → parseRequestLine/parseHeaders → prepareRequest → Adapter.service`. Adapter는 `postParseRequest` 성공 시 Engine 첫 Valve 호출. 동기 반환 시 finish/recycle, Processor는 endRequest 및 SocketState 반환. 파싱 오류·미완성 입력·커밋 이후 오류는 별도 분기 |
| [MapperListener](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/mapper/MapperListener.java), [Mapper](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/mapper/Mapper.java), [MappingData](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/mapper/MappingData.java) | Service가 Mapper·Listener 소유; Listener가 배포 이벤트와 등록 테이블 동기화 | `CoyoteAdapter.postParseRequest → Mapper.map/internalMap/internalMapWrapper → MappingData` 기록. Mapper는 Servlet을 호출하지 않음. 요청 재사용 시 MappingData.recycle |
| [StandardPipeline](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardPipeline.java) 및 StandardEngine/Host/Context/WrapperValve | ContainerBase가 Pipeline 생성; 각 Standard Container 생성자가 기본 Valve 생성·setBasic. Pipeline이 Container/next 참조 설정 | `getFirst().invoke()` 후 각 Valve가 다음 호출을 결정. EngineValve → 선택 Host Pipeline → HostValve → 선택 Context Pipeline → ContextValve → 선택 Wrapper Pipeline. HostValve는 클래스 로더 bind/unbind·요청 이벤트·오류 처리. ContextValve는 보호 경로/없거나 사용 불가 Wrapper에 404, acknowledgement I/O 실패에 500 |
| [StandardWrapper](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapper.java), [StandardWrapperValve](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapperValve.java), [ApplicationFilterChain](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/ApplicationFilterChain.java) | Wrapper는 InstanceManager를 통한 생성 또는 setServlet로 받은 인스턴스 관리. Factory가 요청의 FilterChain 생성/재사용·설정 | `allocate → createFilterChain → doFilter/internalDoFilter → Filter.doFilter → Servlet.service`. 이후 chain.release·deallocate. deallocate는 destroy가 아님. unload의 유한 대기가 모든 요청 종료를 보장하지 않음 |
| [ApplicationFilterConfig](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/ApplicationFilterConfig.java) | `StandardContext.filterStart()`가 Config 생성, 클래스 등록이면 InstanceManager.newInstance; 기존 Filter도 전달 가능 | initFilter → Filter.init; 요청 중 doFilter; Context.filterStop → Config.release → Filter.destroy 및 조건부 InstanceManager 정리. 요청별 체인 정리와 구분 |
| [JspServlet](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/jasper/servlet/JspServlet.java), [JspServletWrapper](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/jasper/servlet/JspServletWrapper.java), [HttpJspBase](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/jasper/runtime/HttpJspBase.java) | JspServlet.init에서 JspRuntimeContext 생성. URI별 Jasper Wrapper와 생성 Servlet 관리 | `service → serviceJspFile → JspServletWrapper.service → 필요 시 compile/getServlet → 생성 Servlet.service → _jspService`. 유효 결과 재사용. JspServlet.destroy → runtime.destroy; 생성 Servlet은 Jasper Wrapper가 destroy |

Jasper EL은 JspApplicationContextImpl·ExpressionFactoryImpl, WebSocket은 WsSci·WsServerContainer·WsFilter·UpgradeUtil, JULI는 LogFactory, HA/Tribes는 SimpleTcpCluster·GroupChannel의 생성·직접 호출을 확인했다. 해당 패널에 고정 링크를 제공한다. 각 확장의 모든 오류·종료 정책까지 조사 완료했다는 뜻은 아니다.

## 실제 동작과 설명상의 차이·생략

| 항목 | 실제 Tomcat 동작 | 이번 자료 동작 | 이유 | 영향 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| 네트워크 내부 | Acceptor·Poller·Executor·SocketProcessor·Handler를 거침 | 기존 한 칸을 유지하고 Endpoint/ProtocolHandler 패널에서 순서와 스레드 인계 설명 | 기존 학습 그림 보존 | 애니메이션의 한 이동은 단일 Java 호출과 일대일 대응하지 않음 | 고정 소스 대조·Endpoint 패널 검사 | 내부 스레드 그림은 별도 요청 시 |
| Container 실행 | 각 Pipeline의 첫 Valve와 기본 Valve가 후속 호출 | 그림 유지, Pipeline/Valve와 각 Container 패널에서 풀어 설명 | 큰 흐름과 메서드 상세를 함께 제공 | 포함 관계와 실행 관계를 나눠 읽어야 함 | 네 기본 Valve 소스 대조 | 추가 Valve 정책은 범위 밖 |
| 응답·연결 | 조기 커밋·flush·keep-alive·오류 상태로 분기 | 대표 동기 반환 흐름 재생, 패널에 조기 커밋·SocketState·정리 설명 | 한 요청 학습에 집중 | 응답 이동을 항상 한 번의 최종 전송으로 해석하면 안 됨 | Processor·Adapter·Handler·Endpoint 소스 대조 | 비동기·TLS·HTTP/2 상세 별도 |
| 매핑 | 특수 매핑·welcome·redirect·버전 등 분기 존재 | 기존 일반/JSP 예시 유지, 조회와 직접 실행 구분 | 이번 변경 목적은 호출 관계 | 전체 매핑 정책의 재현 도구가 아님 | Mapper/Listener/Adapter 대조, 두 모드 재생 | 전체 매핑 실험 별도 |
| JSP | 설정에 따른 변경 검사·컴파일·캐시·언로드 | 필요 시 컴파일 및 생성 서블릿 실행 경로 설명 | 대표 분기만 제공 | 매 요청 재컴파일로 오해하지 않도록 명시 | JspServlet·Jasper Wrapper·HttpJspBase 대조 | 컴파일러 전체 분기 별도 |
| Spring·앱 | 선택한 Spring 버전과 앱 설정에 따라 생성·주입·호출이 달라짐 | 패키지 소속과 Servlet 경계만 확정; 앱 패키지·Spring 내부 순서는 미확인 표시 | 해당 버전·앱 소스가 없음 | 구체 핸들러 메서드 흐름은 아직 알 수 없음 | Tomcat 계약과 앱 설명 분리 검사 | 앱 소스 제공 시 조사 |
| 확장 모듈 | EL·WebSocket·JULI·HA·Tribes마다 별도 실행·종료 정책 | 확인한 생성·호출만 표시, 미조사 정책은 조사 항목 명시 | 일반 요청과 무관한 상세 확대 방지 | 확장 전체 생명주기 설명으로 사용할 수 없음 | 대표 클래스 소스 대조 | 패널에 남긴 조사 항목별 후속 |

## 검증 방법과 실제 결과 — 2026-11-05

브라우저 검사는 `doc/tests/architecture-check.cjs`를 사용한다. Node.js와 외부 테스트 도구 Playwright가 필요하며, 배포 HTML에는 이 의존성이 없다. Playwright가 설치된 Node 환경에서 다음을 실행한다.

```powershell
node doc/tests/architecture-check.cjs
```

기본 브라우저는 설치된 Microsoft Edge다. 다른 Chromium 실행 파일은 `ARCHITECTURE_BROWSER` 환경 변수로 지정한다. Playwright가 별도 경로에 설치되어 있으면 프로세스의 `NODE_PATH`를 해당 node_modules로 지정한다. 선택 사항인 `ARCHITECTURE_SCREENSHOT_DIR`로 스크린샷 저장 디렉터리를 지정할 수 있다.

테스트는 임의 loopback 포트를 사용하고, DOM 조건 대기·테스트 제한 시간·가상 시계로 재생을 검증한다. finally에서 브라우저·컨텍스트·서버를 정리한다. 네트워크 요청은 로컬 HTML 열람에 한정하며 GitHub 소스 링크를 클릭하지 않는다.

| 검사 | 실제 결과 | 범위·제약 |
|---|---|---|
| 변경 전 브라우저 검사 | 새 패널 부재로 상세·키보드·터치 검사 실패, 기존 재생 검사 통과 | 최초 실행의 테스트 도구 import 오류를 수정한 뒤 동작 부재에 따른 실패 확인 |
| hover / 클릭 / 전체 용어 | 통과 | 노출된 38개 용어를 선택해 제목·내용 교체, 클래스·관계·고정 태그 링크 확인 |
| 키보드 / 닫기 / 모바일 | 통과 | 포커스 요약, Enter·Space, Escape·닫기 버튼의 포커스 복귀, 390px 터치 화면에서 긴 패키지명과 가로 넘침 검사 |
| 추가한 키보드 포커스 검사 | 최초 실패 후 수정·통과 | 화면 밖 용어의 focusin에서 열린 요약이 이어지는 자동 scroll 이벤트로 닫힘을 이벤트 추적으로 재현. 포커스를 유지한 보이는 용어의 요약은 스크롤 시 재배치하도록 수정 |
| 일반·JSP 재생 | 통과 | 2배속, 일시정지·재개, 완료 상태와 각 응답, 이전·다음·초기화; JavaScript 오류 없음 |
| 화면 확인 | 데스크톱·모바일 스크린샷 확인 | 자동화 Chromium/Edge의 렌더링이며 실제 모바일 기기 검증은 아님 |
| HTML 직접 열기 | 통과 | file URL로 열어 상세 패널 확인; 추가 HTTP 리소스 요청 없음 |
| 최종 브라우저 재실행 | 6개 시나리오 통과, 상위 테스트 포함 7 pass / 0 fail | 소스 대조·포커스 수정·MappingData 설명을 포함한 최종 HTML 기준 |
| Maven `clean verify` | BUILD SUCCESS, 12개 통과 | 최초 Wrapper 실행은 프로세스 PATH에 powershell이 없어 실패; 기존 준비 문서의 JDK 21·Windows 시스템 경로를 프로세스에 지정해 재실행. Java 변경 없음 |

위 결과는 이번 학습 자료 변경의 실행 기록이다. Java 학습 단계의 새로운 구현 완료나 새 HTTP 서버 기능 검증을 뜻하지 않는다.
