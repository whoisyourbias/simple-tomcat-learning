# 3·4단계 구현·이관 계획

작성일: 2026-11-10. 사용자 요청: 현재 저장소의 3·4단계를 구현하고 잘못 작업한 `D:\spring-study\simple-tomcat`의 같은 단계 계획·실구현을 참고하여 이관한다.

## 기준과 이관 판단

시작 커밋 `cbfef0e`, 완료 태그 `learn/step-00~02`. 기준 테스트 53개를 재실행했다. 최초 실행은 샌드박스의 TCP 연결 거부 9건으로 실패했고 권한 적용 재실행은 53개 통과했다. 기존 코드를 수정해 환경 실패를 숨기지 않는다.

원본 폴더의 `doc/plans/2026-11-10-step-3-servlet-wrapper.md`, `2026-11-10-step-4-catalina-pipeline.md`와 servlet/catalina 코드·테스트를 확인했다. 그곳은 3단계가 소켓 없는 Wrapper, 4단계가 수동 선택 Pipeline이다. 이 저장소의 [3단계](2026-11-05-step-03-servlet.md)·[4단계](2026-11-05-step-04-container.md)는 HTTP 연결·Mapper까지 요구하므로 단계 번호만으로 전체 복사하지 않는다. 원본 폴더는 변경하지 않는다.

재사용: 최소 Servlet 계약, Wrapper 생성·초기화·대여·반환·해제, ContainerBase와 타입별 컨테이너, Pipeline·기본 Valve 및 관련 테스트. 보완: connector 요청·응답, CoyoteAdapter, 실제 HTTP 예제·검증, Mapper·MappingData·URI 입력 정책. 런타임 의존성 없이 Java 21·기존 Maven/JUnit을 유지한다.

## Tomcat 10.1.34 구현 근거

2026-11-10 공식 릴리스 태그 고정 소스를 재조회했다. 아래는 소스에서 확인한 사실이며 다음 표의 프로젝트 정책과 구분한다.

| 소스·메서드 | 책임·순서·상태·오류·소유권 |
|---|---|
| [StandardWrapper.allocate/deallocate/loadServlet/initServlet/unload](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapper.java) | 로딩·init 성공 후 공유 인스턴스 반환, 대여 수 증가/감소. 일반 init 예외와 UnavailableException 정책 구분. unload는 제한 대기 후 초기화된 인스턴스 destroy·참조 정리 |
| [StandardWrapperValve.invoke](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapperValve.java), [ApplicationFilterChain.internalDoFilter](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/ApplicationFilterChain.java) | Valve가 allocate, 체인이 service, 정리에서 deallocate. 일반 처리 실패는 HTTP 오류 상태로 기록 |
| [ContainerBase.addChildInternal](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/ContainerBase.java), [StandardPipeline.addValve/getFirst](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardPipeline.java) | 부모 연결·이름별 자식 등록, 각 컨테이너가 Pipeline 소유. 마지막 기본 Valve가 하위 Pipeline으로 위임 |
| [CoyoteAdapter.service/postParseRequest](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/connector/CoyoteAdapter.java) | Coyote 데이터를 connector 요청·응답에 연결. 경로 파라미터 제거·디코딩·정규화 뒤 Mapper, 그 뒤 Engine Pipeline 호출. 소켓 소유권은 이동하지 않음 |
| [Mapper.map/internalMap/internalMapWrapper](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/mapper/Mapper.java) | Host 대소문자 무시 검색·명시한 defaultHost fallback, 경계가 맞는 최장 Context, 정확→최장 접두어→확장자→기본 매핑. welcome·redirect·버전 등 추가 규칙도 존재 |
| [StandardContextValve.invoke](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardContextValve.java) | WEB-INF/META-INF 직접 접근 거부, 선택된 Wrapper가 없거나 unavailable이면 404, 그 외 Wrapper Pipeline 진입 |

## 핵심 동작 대응

| 동작 | Tomcat 대응 | 프로젝트 흐름 | 검증 |
|---|---|---|---|
| 지연 init·공유·반환·destroy | Wrapper + WrapperValve | 3단계 Adapter가 고정 Wrapper allocate→service→finally 반환, 유휴 unload | Wrapper 단위 테스트 + 실제 TCP init/service/destroy 1/2/1 |
| 오류 뒤 복구 | Valve 오류 및 정리 | init 실패는 새 객체로 재시도, service 실패에도 반환, Processor가 500 새 응답 작성 | init 실패·중간 응답 후 500·다음 정상 요청 |
| 계층 등록·호출 | ContainerBase·기본 Valve | Engine→Host→Context→Wrapper의 Pipeline, WrapperValve가 대여·반환 | 이관 계층·순서·예외 보존 테스트 |
| 선택과 호출 분리 | Mapper + Adapter | map(serverName, URI)→MappingData, connector Request에 연결→Engine 진입 | Mapper만 호출하면 init/service 0, Pipeline 후 선택 대상만 1 |
| 경계와 우선순위 | internalMap/internalMapWrapper | 최장 Context와 정확/접두어/확장자/기본 검색 | Host·Context 경계, 우선순위 충돌, 경로 3필드 |
| 누락·보호 경로 | 기본 Valve | 누락은 Pipeline에서 404, ContextValve가 보호 경로 404 | 실제 HTTP 404 뒤 정상 요청 |

## 차이와 생략

| 항목 | 실제 Tomcat | 프로젝트 동작 | 이유 | 영향 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| API·생성 | Jakarta·ServletConfig·InstanceManager | 자체 최소 계약·Supplier | 생명주기 소유권 집중 | Jakarta 비호환, 팩터리는 새 객체 제공 | 생성·init 횟수, 요청·응답 전달 | 의도적 유지 |
| init 실패 | 일반 init 예외 전파, unavailable 별도 | 실패 객체 미보관·destroy 생략·다음 요청 새로 생성 | 숨은 실패 상태 방지 | unavailable 기능 없음 | init 실패 후 재시도 | 필요시 별도 조사 |
| 종료 | unload 제한 대기·Lifecycle 연계 | 사용 중 unload 즉시 거부, 유휴 반복 가능·재로드 가능 | 안전한 중간 단계 | 실행 스레드가 끝난 뒤 호출자 unload 필요 | 활성 service 중 거부, 반환 후 destroy | 6단계 전체 종료 조정 |
| 예외 | Valve가 오류 상태·페이지 처리 | service 예외 전파 후 기존 Processor가 새 500 작성 | 2단계 오류 경계 재사용 | 앱 오류 페이지·async 없음, Error는 전파 | 부분 응답 폐기·복구 | 오류 페이지 의도적 생략 |
| URI 준비 | 파라미터 제거·디코딩·정규화 | 경로는 ASCII 정규 경로만 허용, %, ;, 역슬래시, //, .·.. 세그먼트·제어문자·?·# 거부 | 해석 차이 방지 | 유효한 인코딩 경로도 400, query 원문은 유지 | 수용/거부 표를 매개변수화·실제 TCP 확인 | 의도적 제한, 확장 시 재조사 |
| 매핑 | 배열·버전·welcome·redirect·특수 빈 패턴 | 시작 전 등록, 네 패턴, Context 루트 요청은 내부 /로 처리 | 선택과 호출 학습 | redirect·welcome·빈 패턴 미지원 | 충돌·루트·pathInfo | 의도적 유지 |
| Host·등록 | 별칭·wildcard·동적 등록 | 소문자 이름·정확 검색, defaultHost 명시, 중복·계층 오류 거부 | 숨은 fallback 방지 | 실행 중 구성 변경 지원 안 함 | 기본 Host 유무·중복 등록 | 의도적 유지 |
| Pipeline·부가 기능 | 사용자 Valve·필터·Lifecycle·오류 이벤트 | 이관한 최소 체인과 기본 Valve, 필터 없음 | 기존 재사용 단위 보존 | 등록은 시작 전에만, 일반 Valve 확장은 다음 단계 | 순서·중단·예외 반환 | Filter 5단계·Lifecycle 6단계 |

## 작업 순서와 완료 기준

### Task 1: 3단계 Servlet·Wrapper·HTTP

- [x] 이관 Wrapper 테스트와 신규 `connector/ServletHttpTest`를 먼저 추가, 미구현 실패 확인.
- [x] `servlet/*`, `catalina/Wrapper`, `core/StandardWrapper` 이관. 이 단계에서는 Container 의존성을 제거한다.
- [x] `connector/Request`, `Response`, `CoyoteAdapter(Wrapper)`와 `examples/ServletServer` 구현. Adapter가 대여·반환, 예제 실행자가 프로토콜 종료 후 unload.
- [x] 관련 테스트, 전체 clean verify, JAR HTTP 두 요청·오류 복구·종료 실험.
- [x] `doc/03-servlet/` 학습 기록·현재 상태·제약 갱신, 코드·테스트·문서를 함께 커밋하고 `learn/step-03` 생성.

### Task 2: 4단계 계층·매핑·기본 Pipeline

- [ ] 이관 계층/Pipeline/호출 테스트 + 신규 Mapper·HTTP 테스트를 먼저 추가하고 실패 확인.
- [ ] Container·Engine·Host·Context·ContainerRequest·Pipeline·Valve·core·valves 이관, Wrapper를 Container로 확장.
- [ ] `mapper/MappingData` 불변 record와 `Mapper(Engine, String defaultHost)` 추가. `StandardContext.addServletMapping(String, Wrapper)`와 `getServletMappings()`는 등록된 Wrapper만 수용하고 중복 거부. map은 호출하지 않고 선택만 수행.
- [ ] `connector/Request`에 MappingData·경로 3필드 연결, `CoyoteAdapter(Engine, Mapper)`가 Engine Pipeline 진입. 고정 Wrapper 생성자 유지.
- [ ] `examples/ContainerServer`에서 두 Host·두 앱·중첩 Context·충돌 패턴 시연. 프로토콜 serve가 반환된 후 등록 Wrapper들을 unload.
- [ ] 관련 테스트, 전체 clean verify, JAR HTTP Host/Context/매핑/404/400/500/다음 정상 요청·종료 실험.
- [ ] `doc/04-container/` 기록과 현재 상태·제약·README 갱신, 함께 커밋하고 `learn/step-04` 생성. 기존 태그는 이동하지 않는다.

검토 초점: 실패 init의 destroy 오기록, service 예외 후 대여 누수, Context·접두어 경계, query/Host port 혼입, 비정규 경로 우회. 테스트 주석은 한국어 입력·기대 결과를 첫 테스트 애너테이션 바로 위에 쓴다. TCP 테스트는 임의 포트·유한 대기·latch/Future·확실한 close를 사용한다.

## 실제 실행 기록

계획 시점에는 3·4단계 신규 검증 미실행. 이후 각 단계 문서에 실제 명령·결과·실패 이력과 완료 태그를 기록한다. 원본 폴더의 92/123개 결과는 이 저장소 결과로 사용하지 않는다.
