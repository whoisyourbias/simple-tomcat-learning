# 3단계 · Catalina 진입과 서블릿 관리

상태는 예정이다. 기준 Tomcat 태그는 10.1.34다.

## 이전 문제와 최소 해결

응답을 바꾸려면 Adapter를 수정해야 한다. Adapter에 앱 객체를 주입해 호출할 수 있지만 요청마다 객체를 만들거나 임의로 초기화하면 재사용과 해제의 주인이 모호하다. 두 요청과 서버 종료를 직접 실행하며 init·service·destroy의 수명을 구별한다.

## 예정 인터페이스와 작업

- servlet의 Servlet·ServletRequest·ServletResponse 계약을 추가한다. init/service/destroy, 요청 조회와 응답 작성에 필요한 최소 API만 제공한다.
- catalina.connector.Request·Response로 Coyote 데이터를 앱 계약에 연결한다. CoyoteAdapter가 경계를 넘는다.
- StandardWrapper가 한 인스턴스의 초기화·할당·반환·해제를 소유한다. 이번 단계는 고정 Wrapper 하나를 호출한다.
- 실제 HTTP 실행 예제와 테스트를 만든다. 소켓 없는 lifecycle 테스트만으로 완료하지 않는다.

## Tomcat 구현 근거와 대응

[StandardWrapper.allocate/deallocate/initServlet/unload](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapper.java)와 [StandardWrapperValve.invoke](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardWrapperValve.java)를 읽는다.

| 핵심 동작 | Tomcat 동작 | 프로젝트 예정 흐름 | 테스트 |
|---|---|---|---|
| 인스턴스 관리 | Wrapper 초기화·할당 수·해제 | Adapter → 고정 Wrapper.allocate → service → finally deallocate | 동일 인스턴스·1/2/1 |
| 사용 중 해제 | unload가 활성 할당과 대기 고려 | 활성 호출이면 해제 보류·실패 | service 중 종료 |

## 완료 테스트·실행

- 실제 HTTP 두 요청에서 같은 객체의 init/service/destroy 횟수 1/2/1을 검증한다.
- init 실패이면 service를 부르지 않고 정상 초기화되지 않은 인스턴스를 정상 해제했다고 기록하지 않는다.
- service 예외 500 뒤 다음 요청이 성공하고 할당이 반환된다.
- 반복 종료 시 destroy를 중복 호출하지 않고 활성 호출 중 해제를 방지한다.

테스트 실패 확인 → 계약·Wrapper → HTTP 연결 → clean verify → 실제 차이·실행 기록 → 완료 커밋·learn/step-03 순서다. 예정 실행 클래스는 `simpletomcat.examples.ServletServer`이며 포트 18081에서 /hello를 두 번 호출한다. 현재 결과는 없다.

## 차이와 생략

| 항목 | 실제 Tomcat·근거 | 예정 동작 | 이유 | 영향·제약 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| 호출 경로 | Engine부터 WrapperValve·필터 체인 | 고정 Wrapper 호출 | 생명주기부터 관찰 | 실제 Catalina 호출 계층 미완성 | HTTP 두 요청 | 4·5단계 해소 |
| API·로딩 | Jakarta API·컨테이너 로딩 | 자체 계약·프로그램 객체 등록 | 내부 소유권 집중 | 표준 Servlet 비호환 | 요청·응답 전달 | 유지 |
| 초기화 실패 | 로딩·unavailable 관련 정책 | 실패 상태·재시도 여부를 명시 | 숨은 재시도 방지 | 재사용 가능 여부 달라짐 | 초기화 실패·다음 요청 | 구현 전 상세 소스 조사 |
| 종료 대기 | unload의 제한 대기 | 활성 호출 해제 방지 | 안전한 중간 모델 | 전체 종료 조정 없음 | 할당 반환·종료 | 6단계 조정 |

다음은 두 앱이 각자 /status를 등록하는 문제다. [4단계](2026-11-05-step-04-container.md)에서 선택과 호출을 분리한다.
