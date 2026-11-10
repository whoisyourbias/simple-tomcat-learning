# 구현 범위와 차이·생략

아래 공통 표는 전체 과정의 예정 설계다. 2026-11-05 구현은 1단계까지였으며 당시 동작·검증·차이는 [1단계 기록](../01-socket/01-endpoint.md)에 보존한다. 2026-11-06 최신 구현은 2단계까지다. 기준 소스는 [공식 소스 지도](01-tomcat-source-map.md)의 Tomcat 10.1.34다.

## 과거 구현 제약 — 1단계 (2026-11-05 기록)

단일 블로킹 루프, 연결당 고정 응답 한 번, UTF-8 본문 32바이트다. 요청 경로·메서드·Host·버전·헤더 크기·본문을 검사하지 않고 CRLFCRLF만 기다린다. 따라서 아래 표의 400/500/501/505 정책은 아직 구현하지 않았다. 헤더 읽기 제한 시간이 없어 느린 연결이 서버를 점유하며 close로 취소한다. close는 연결을 즉시 끊고 serve 종료 대기는 호출자 책임이다. graceful 종료·풀·Servlet·매핑은 없다. 실제 차이별 이유·영향·검증·후속은 1단계 기록의 비교표에 정리했다.

## 현재 구현 제약 — 2단계 (2026-11-06)

Endpoint의 HTTP 처리를 Processor·Adapter로 분리했다. CRLF·단일 SP·origin-form/OPTIONS *·8192바이트·100헤더의 최소 문법을 검사하고, malformed 400·앱 예외 500·본문 미지원 501·지원 외 버전 505를 전송한다. UTF-8 길이와 HEAD·204·205·304 본문 억제를 적용했다. 실제 검증은 53개 테스트·clean verify·최종 JAR HTTP 실험이다.

단일 블로킹 루프·연결당 한 요청·읽기 제한 시간 없음·즉시 소켓 close는 유지한다. URI 디코딩·정규화·요청 본문·Expect/Upgrade 협상·1xx 응답·스트리밍·Servlet·매핑은 없다. 앱 응답은 메모리에 저장하며 크기 제한은 아직 없다. Host는 Java URI 기반 최소 검사로 Tomcat 전체 문법과의 동등성을 보장하지 않는다. 차이마다 실제 소스·이유·영향·검증·후속을 [2단계 비교표](../02-coyote/01-http-processing.md)에 기록했다.

## 전체 과정 예정 차이

| 항목 | 실제 Tomcat 동작·소스 근거 | 예정 구현 | 이유 | 영향·제약 | 검증 방법 | 후속 처리 |
|---|---|---|---|---|---|---|
| 연결 실행 | S1·S2·S9의 NIO Poller·SocketProcessor·Executor | 블로킹 소켓, 6단계 제한 풀·큐 | Selector 구현 비용 제한 | 느린 연결 하나가 작업자 하나 점유 | 느린 연결 중 다른 요청·포화·타임아웃 | 의도적으로 유지, NIO 구조 설명 |
| keep-alive | S3 요청 반복·연결 상태 판단 | 한 연결 한 요청 후 close | 요청·연결 프레이밍 범위 제한 | 연결 재사용 없음 | 응답 뒤 EOF와 다음 연결 | 의도적으로 유지 |
| 요청 본문 | S3 identity·chunked 입력 필터 | 본문 없는 요청만 지원 | HTTP 전체 구현 범위 제한 | 전송 본문을 받는 앱 미지원 | 유효한 본문 요구 거부, 잘못된 framing 거부 | 의도적으로 유지 |
| HTTP 정책 | S3의 문법·프로토콜·오류 상태 분기 | malformed 400, 일반 앱 예외 500, 유효한 본문 미지원 501, 지원 외 버전 505 | 학습 서버의 명시적 계약 | 501 본문 정책은 Tomcat과 다름 | 각 오류와 다음 요청 테스트 | 2단계 상세 비교표 |
| 오류 페이지 | S3·S5·S8의 오류 처리 경로 | 기본 오류 응답, 파싱 실패는 자체 처리 | 컨테이너 오류 dispatch 축소 | 파싱 오류도 Adapter에 진입하는 실제 경로와 차이 | 응답·Adapter 호출 횟수 검증 | 의도적으로 유지 |
| Servlet API | S4·S8의 Jakarta 계약 | init/service/destroy, 최소 요청/응답·Filter | 호출·소유권에 집중 | 표준 Servlet 바이너리 호환 없음 | 동일 인스턴스·호출·필터 테스트 | 의도적으로 유지 |
| 비동기·dispatch | S8의 DispatcherType·비동기 경로 | 동기 REQUEST만 지원 | 상태기계 확장 제한 | AsyncContext·forward/include·multipart 제외 | 지원 API와 미지원 API 표 | 설명 전용 |
| 매핑 | S6의 버전·welcome·redirect 등 | 정확·최장 접두어·확장자·기본, 시작 전 정적 등록 | 핵심 선택 의미에 집중 | 특수 매핑·동적 배포 제외 | 충돌·경계·다중 앱 | 의도적으로 유지 |
| 클래스 로더·배포 | 아래 소스의 앱별 로딩과 배포 | 공유 로더·프로그램 등록 | WAR와 hot reload 비용 제한 | 경로 격리는 클래스 격리를 보장하지 않음 | 같은 내부 경로의 두 앱, 문서 대조 | 설명 전용 |
| 세션·인증 | Manager·Cookie·Realm·Authenticator | 미구현 | 별도 상태 관리 주제 | 로그인·세션 보장 없음 | 요청 흐름에 개입 위치 설명 | 설명 전용 |
| Jasper | JSP 변환·컴파일·로딩·재사용 | 미구현 | 내장 컨테이너 완주 경계 | JSP 실행 없음 | JSP에서 Servlet까지 소스 추적 | 설명 전용 |
| TLS·HTTP/2·WebSocket | 전송 보안·다중 스트림·Upgrade | 미구현 | 별도 프로토콜 주제 | 평문 HTTP/1.x만 지원 | 구조도와 적용 위치 설명 | 자동 후속 구현 없음 |
| 클러스터·JMX | 세션 복제·노드 통신·관리 | 미구현 | 운영 플랫폼 범위 제한 | 단일 프로세스 학습 서버 | 역할과 책임 설명 | 자동 후속 구현 없음 |
| 종료 | S9 설정별 graceful·pause·stop | 유한 대기, 활성 작업이면 실패 반환·해제 보류 | 실행 중 서블릿 해제 방지 | 비협조 작업이면 종료 실패 | latch로 완료·실패·재시도 검증 | 6단계 정책으로 기록 |
| simple-spring | 외부 앱·프레임워크 연동 가능 | 내부 DispatcherServlet 예제 | 외부 저장소 의존 제거 | 주문 저장·DB 연동 미검증 | 두 업무 경로와 404/500 복구 | 별도 계획으로만 진행 |

## 설명 전용 개념의 읽기 과제

- **클래스 로딩·배포** — [WebappClassLoaderBase.loadClass](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/loader/WebappClassLoaderBase.java)의 캐시·위임·로컬 탐색과 예외 필터를 읽고, 단순한 parent-first/child-first 한마디로 설명하지 않는다. [ContextConfig](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/startup/ContextConfig.java)와 [HostConfig](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/startup/HostConfig.java)는 WAR·web.xml·스캔·재배포를 조사할 진입점이다.
- **세션** — [ManagerBase.createSession](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/session/ManagerBase.java)의 생성·유효 상태·생성 시각·타임아웃 설정과 요청의 Cookie 연결을 추적한다. 만료 실행 위치는 별도 조사 대상으로 남긴다.
- **인증** — [AuthenticatorBase](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/authenticator/AuthenticatorBase.java)와 [Realm](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/Realm.java)에서 요청 차단·인증 정보 검증·보안 제약 역할을 조사한다.
- **Jasper** — [JspServletWrapper.service](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/jasper/servlet/JspServletWrapper.java)의 compile·reload·getServlet 경로를 기준으로 [JspCompilationContext](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/jasper/JspCompilationContext.java)를 따라간다. 최초 요청과 이후 요청의 차이를 설명한다.
- **운영 확장** — [Http2Protocol](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/http2/Http2Protocol.java), [WsHttpUpgradeHandler](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/websocket/server/WsHttpUpgradeHandler.java), [SimpleTcpCluster](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/ha/tcp/SimpleTcpCluster.java), [JmxEnabled](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/JmxEnabled.java)를 읽기 시작점으로 둔다. 상세 동작은 조사 후 설명 자료에 기록한다.

설명 자료는 역할·개입 위치·상태·이번 구현에서 없는 보장을 포함한다. 미조사 분기를 확인한 사실처럼 서술하지 않는다. 구현 과정에서 새 차이가 생기면 실제 동작·예정 동작·이유·영향·검증·후속 여부를 표에 추가한다.
# 2026-11-10 추가 — 3단계 현재 제약

이하 기존 범위·과거 기록은 보존한다. 3단계에서 최소 Servlet 계약·Wrapper·실제 HTTP 연결을 구현했다. 모든 URI는 고정 Wrapper로 전달한다. 사용 중 unload는 즉시 거부하며 실행자가 요청 처리 종료 뒤 해제해야 한다. Jakarta 호환·UnavailableException·필터·다중 앱·전체 Lifecycle은 미구현이다. 근거와 검증은 [3단계 기록](../03-servlet/01-servlet-and-wrapper.md), 상세 차이는 [이관 계획](../plans/2026-11-10-step-03-04-implementation.md)을 따른다.
