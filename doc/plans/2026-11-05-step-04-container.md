# 4단계 · 웹앱 경계와 요청 대상 선택

상태는 예정이다. 기준 Tomcat 태그는 10.1.34다.

이하 본문은 2026-11-05 초기 계획으로 보존한다. 2026-11-10 사용자의 구현 요청으로 Mapper·실제 HTTP 연결까지 구현·검증했다. 실제 결과는 [4단계 기록](../04-container/01-mapping-and-pipeline.md), 보완한 소스 근거·차이는 [이관 계획](2026-11-10-step-03-04-implementation.md)을 따른다.

## 이전 문제와 최소 해결

고정 서블릿 하나로는 두 웹앱의 같은 내부 경로를 처리할 수 없다. /shop/status와 /admin/status를 if로 분기해 보고, 앱 경로와 앱 내부 서블릿 경로가 다르다는 점을 드러낸다. 이 요구에서 Host·Context·Wrapper 선택과 실제 호출을 나눈다.

## 개념·인터페이스·작업

StandardEngine·StandardHost·StandardContext·StandardWrapper의 구성 관계를 만들고 Mapper·MappingData를 추가한다. MappingData는 선택된 Host·Context·Wrapper와 contextPath·servletPath·pathInfo를 담는다. 등록은 프로그램에서 명시적으로 수행한다.

Mapper는 대상만 선택한다. CoyoteAdapter가 선택 결과를 요청에 연결한 뒤 Engine Pipeline을 호출한다. 각 기본 Valve는 선택된 다음 계층을 호출한다. 4단계에서 기본 Pipeline을 만들고 사용자 Valve는 5단계에서 확장한다.

## Tomcat 구현 근거와 대응

[Mapper.map/internalMap/internalMapWrapper](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/mapper/Mapper.java), [CoyoteAdapter.service/postParseRequest](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/connector/CoyoteAdapter.java), [StandardContextValve.invoke](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardContextValve.java)를 대조한다.

| 핵심 동작 | Tomcat 동작 | 프로젝트 예정 흐름 | 테스트 |
|---|---|---|---|
| 대상 선택 | Mapper가 Host·Context·Wrapper 결과 기록 | 서버명·URI → MappingData | 다중 Host·Context·우선순위 |
| 대상 호출 | Adapter가 Engine, ContextValve가 Wrapper Pipeline 진입 | Engine → Host → Context → Wrapper Pipeline | 선택만 하면 호출 0회, 실행 후 선택 대상 1회 |
| 미등록 대상 | 계층별 Valve가 누락 대상 처리 | 호출 경로에서 404 작성 | 미등록 뒤 정상 요청 |

## 구현 순서·완료 테스트

테스트 실패 확인 → 계층 등록 → Mapper → 기본 Pipeline 연결 → CoyoteAdapter 교체 → 전체 검증 → 문서·완료 커밋·learn/step-04 순서다.

- 두 Host가 같은 경로에 서로 다른 응답을 제공한다.
- /shop·/admin의 /status, 중첩 Context의 최장 경로, /shop과 /shopper 경계를 확인한다.
- 정확 경로 → 최장 접두어 → 확장자 → 기본 매핑 순서를 충돌 사례로 확인한다.
- Mapper 단독 호출은 service를 실행하지 않고 Pipeline은 선택 대상만 실행한다.
- 경로 분리 값·미등록 대상 404·오류 뒤 다음 정상 요청을 실제 HTTP로 확인한다.

예정 실행은 `simpletomcat.examples.ContainerServer 18081`이며 /shop/status와 /admin/status를 비교한다. 현재 구현·실행 결과는 없다.

## 차이와 생략

| 항목 | 실제 Tomcat·근거 | 예정 동작 | 이유 | 영향·제약 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| 검색 | Mapper 배열 검색·버전·welcome·redirect | 단순 자료구조·네 매핑 | 핵심 선택에 집중 | 특수 매핑 제외 | 경계·충돌 | 유지 |
| 기본 Host | 설정한 defaultHost fallback | 기본 Host를 명시해 등록 | 숨은 fallback 방지 | 설정 필요 | 미등록 Host·기본 Host | 유지 |
| URI 준비 | 디코딩·정규화·파라미터 처리 | 지원하는 정규 경로만 허용 | URI 파서 확장 제한 | 인코딩·모호한 경로 제약 | 입력별 수용·거부 표 | 구현 전 정책 소스 조사 |
| 배포 | 동적 등록·앱 버전·로더 | 시작 전 등록·공유 로더 | 배포 범위 제외 | 경로 격리만 제공 | 두 앱 | 유지·설명 문서 |

다음은 모든 서블릿 전후에 반복되는 로그·검사 코드다. [5단계](2026-11-05-step-05-filter.md)에서 컨테이너 Valve와 앱 Filter를 도입한다.
