# 5단계 · Context Filter와 요청별 체인

2026-11-10, 기준 커밋 `76f20ec`·`learn/step-04`, 완료 경계 `learn/step-05`. Windows PowerShell, Zulu JDK 21.0.11, Maven Wrapper 3.3.2 / Maven 3.9.9.

## 이전 한계와 책임 분리

4단계는 컨테이너별 Valve를 연결하고 선택된 Wrapper가 Servlet을 직접 호출했다. 공통 처리를 Servlet마다 복사하거나 모든 처리를 Engine Valve에 넣으면 앱별 범위가 섞인다. 최소 해결은 service 앞뒤를 함수로 감싸는 것이다. 여기서 여러 전후 처리·다음 호출 생략·필터 수명·요청 위치가 추가되므로 Filter와 FilterChain으로 분리했다.

Valve는 컨테이너 Pipeline에 속해 next를 통해 하위 계층에 위임한다. Filter는 Context 전체 REQUEST에 등록 순서로 적용된다. 기존 Pipeline의 사용자 Valve 연결·중단 기능은 다시 구현하지 않았다.

```text
기존: Wrapper.allocate → Servlet.service → finally deallocate
현재: Wrapper.allocate → Context.getFilters → 새 ApplicationFilterChain
      → A 전 → B 전 → Servlet.service → B 후 → A 후 → finally deallocate
```

필터가 next를 호출하지 않으면 뒤 필터와 Servlet service는 실행되지 않는다. 다만 WrapperValve는 필터 전에 Servlet을 allocate하므로 중단 요청도 Servlet init은 발생할 수 있다. 중단·체인 준비 실패·필터 예외·service 예외 모두 대여 성공 후에는 반환한다. 반환 실패는 원래 실패에 suppressed로 남긴다. Context 선택이 Wrapper 부모와 다르면 거부해 잘못된 필터 범위 호출을 막는다.

## 수명·상태·자원 소유권

Context가 이름별 Supplier와 초기화 성공 Filter 목록을 소유한다. `startFilters`는 수신 전 등록 순서로 init하고 모두 성공해야 불변 목록을 공개한다. Filter 인스턴스는 요청 간 공유하지만 체인 위치는 매 요청 새 객체에만 있다. 목록은 체인 생성 시 복사한다.

필터 전용 내부 상태는 NEW→STARTING→STARTED, 일반 초기화 실패 시 FAILED다. 정지는 NEW/STARTED/FAILED→STOPPING→STOPPED다. STARTED 재시작·STOPPED 재정지는 no-op, 그 외 재시작·준비 중 재진입은 거부한다. 등록은 NEW에서만 가능하다. 기존 무필터 예제 호환을 위해 정의 없는 NEW의 조회만 빈 목록을 허용한다.

일반 init 실패는 뒤 정의를 시도하지 않고 성공분만 역순 destroy한다. 실패 init 객체에는 destroy를 호출하지 않는다. **init 내부에서 얻고 아직 공개하지 않은 부분 자원은 필터 구현자가 실패 경로에서 정리해야 한다.** 일반 destroy 실패는 형제 정리를 계속하고 주원인과 suppressed로 보고한다. 반복 stop은 다시 destroy하지 않는다. Error는 전파하며 모든 형제 정리를 보장하지 않는다.

FilterServer는 앱 준비→Protocol 바인딩→serve 순서다. 뒤 앱 준비 실패는 앞 앱 성공분도 정리하고, 바인딩 실패면 필터를 정리한다. 실행 종료 후 Wrapper unload→Filter stop 순서를 사용하며 일반 Servlet destroy 실패에도 Filter 정리를 시도한다. Endpoint가 소켓을 소유하고 Adapter·필터에 소켓 소유권은 넘기지 않는다.

## Tomcat 10.1.34 대응과 차이

고정 원본의 메서드·상태·예외·종료 조사와 SHA-256은 [상세 계획](../plans/2026-11-10-next-implementation.md#3-tomcat-10134-고정-소스-조사)에 보존했다.

| 항목 | 실제 Tomcat | 프로젝트 실제 동작 | 이유 | 영향 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| Filter 실행 | Factory가 Dispatcher·URL 후 Servlet 이름 매핑을 선택, Chain이 service 호출 | Context 전체 REQUEST·등록 순서 | 체인 의미에 집중 | 세부 매핑 없음 | 두 앱 trace·중단·순서 | 의도적 유지 |
| Filter 객체 | ApplicationFilterConfig·FilterConfig·InstanceManager·JMX | 자체 Filter·Supplier·인자 없는 init | 최소 API 유지 | Jakarta 비호환·설정 주입 없음 | init·공유·destroy 횟수 | 의도적 유지 |
| 시작 실패 | filterStart가 일반 실패 후 다른 정의도 시도, Context 시작 실패에 연결 | 첫 실패 중단·성공분 역순 정리·FAILED | 부분 성공 공개 방지 | 동일 재시작·롤백 정책 아님 | A 성공/B 실패/C 미생성·suppressed | 전체 Lifecycle은 6단계 |
| 실패 init 정리 | Config 생성 실패와 전체 Lifecycle 정리의 상세 연계 미조사 | 실패 객체 destroy 생략·init 자체 부분 정리 | 성공 인스턴스 소유권 명확화 | 실패 init 작성자 책임 | 실패 객체 destroy 0 | 미조사 사실을 확정하지 않음 |
| 체인 재사용 | Request 보관·재사용 가능, release로 참조와 위치 정리 | 요청마다 새 체인·종료 후 미보관 | 위치 공유 제거 | 할당 증가·async 사용 금지 | 독립 체인·오류 뒤 다음 요청 | 의도적 유지 |
| 오류 | WrapperValve가 오류 상태·unavailable 등 처리 | 예외 원형 반환 후 Processor가 일반 예외를 새 500으로 변환 | 기존 HTTP 경계 보존 | 오류 페이지·unavailable 없음·Error 복구 미보장 | 부분 본문 폐기·다음 200 | 의도적 유지 |
| 종료 | 자식 컨테이너 stop 후 filterStop, Config.release 일반 실패는 로그 | 실행자가 serve 완료 뒤 Wrapper→Filter 역순 해제, 일반 실패 보고 | 소유권·실패 관찰성 | 자동 활성 작업 보호 없음 | latch 요청·해제 순서·실패·포트 | 6단계 |

## 실제 검증과 실패 이력

| 실행 | 실제 결과 |
|---|---|
| 체인 테스트 먼저 추가 | Filter·FilterChain·ApplicationFilterChain 타입 미구현 컴파일 실패 |
| Context 수명 테스트 먼저 추가 | addFilter/startFilters/getFilters/stopFilters 미구현 컴파일 실패 |
| WrapperValve 연결 전 FilterInvocationTest | 3개 모두 실패: 중단 무시·필터 예외 미전파·미준비 조회 미발생 |
| 연결 후 관련 테스트 | 39사례 통과 |
| HTTP 테스트 먼저 추가 | FilterServer 타입 미구현 컴파일 실패 |
| 최초 HTTP 구현 검증 | 12사례 중 1실패: 테스트 기대 trace에 정상 지연 Servlet init 누락. 실제 Wrapper 정책 확인 후 기대 순서 수정 |
| 독립 리뷰 | Critical/Important 없음. 뒤 Context 준비 실패 rollback·Servlet destroy 실패 후 Filter 정리 테스트 2개 보강 |
| 최종 `mvnw.cmd -B clean verify` | 종료0·BUILD SUCCESS·159사례 실패/오류/건너뜀0·JAR 생성 |
| 생성 JAR ServletHttpExperiment | 두 요청 1/2/0, 500·HEAD·복구·최종 1/4/1·반복 해제·포트 반환 PASS |
| 생성 JAR ContainerHttpExperiment | 기존 curl16·400/404/500·복구·포트 반환 PASS |
| 생성 JAR FilterHttpExperiment | curl9·두 앱·중단 service0·필터/Servlet500·다음200·HEAD·init/destroy1·역순·반복 정리·포트 반환 PASS |

159 = 기존138 + 체인4 + Context수명6 + Filter호출3 + 실제HTTP6 + 예제실패정리2. TCP 검증은 sandbox 밖 권한 실행을 사용했다. 이전 상태 확인의 sandbox 실패1/오류14 기록은 상세 계획에 그대로 보존한다. 최종 추가 테스트는 기존 동작의 정리 보장을 고정하는 보강이며 새 제품 기능 변경은 없다.

Markdown 로컬 링크와 git diff 공백 검사를 통과했다. 구조도는 현재 구현 배지·설명을 5단계로 갱신했으며 이 환경에 Playwright가 없어 브라우저 자동 회귀는 재실행하지 않았다. 기존 구조도 실행 기록은 보존한다.

재현:

```powershell
.\mvnw.cmd -B clean verify
New-Item -ItemType Directory -Force target/experiments
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar -d target/experiments doc/experiments/ServletHttpExperiment.java doc/experiments/ContainerHttpExperiment.java doc/experiments/FilterHttpExperiment.java
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' FilterHttpExperiment
& "$env:JAVA_HOME/bin/java.exe" -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar simpletomcat.examples.FilterServer 18081
# 별도 터미널
curl.exe --max-time 3 -i http://127.0.0.1:18081/shop/hello
curl.exe --max-time 3 -i http://127.0.0.1:18081/admin/hello
curl.exe --max-time 3 -i http://127.0.0.1:18081/shop/blocked
```

## 남은 제약과 다음 단계

블로킹 순차·연결당 한 요청·요청 본문 없음·읽기 제한 없음·시작 전 구성은 유지한다. FilterChain은 동기 요청에서만 사용하고 각 필터의 다음 호출은 0회 또는 1회라는 계약이다. 중복 호출 탐지·async·세부 매핑은 없다. 공유 Filter와 Servlet 필드는 자동으로 스레드 안전해지지 않는다.

start/stop은 실행자가 요청 밖에서 직렬 호출한다. stopFilters만으로 활성 요청의 안전 종료를 보장하지 않는다. 무한 init/doFilter/destroy, Error 전체 정리, shutdown hook 최대5초의 강제 종료 한계는 남아 있다. 테스트 도구 생성 중 인터럽트 정리의 기존 후속 항목도 이번에 변경하지 않았다. 6단계에서 제한 풀·읽기 제한·전체 Lifecycle·수신 중단·진행 요청 대기·해제 보류와 실패 보고를 연결한다.
