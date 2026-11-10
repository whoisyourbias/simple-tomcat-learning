# 4단계 — 웹앱 선택과 Pipeline 호출

## 이전 한계와 해결

3단계 고정 Wrapper는 `/shop/status`와 `/admin/status`를 같은 서블릿으로 보냈다. Adapter에 if문을 늘리면 앱 경계·선택·호출·대여 책임이 한 곳에 모인다. Host는 서버명, Context는 앱 경로, Wrapper는 서블릿 정의를 나타낸다. Mapper는 이 셋을 선택할 뿐 앱 콜백을 실행하지 않는다.

```text
Endpoint → Processor → CoyoteAdapter
  → Mapper.map(serverName, URI) → MappingData
  → connector.Request에 선택·경로 연결
  → Engine Pipeline → Host Pipeline → Context Pipeline → Wrapper Pipeline
  → WrapperValve.allocate → Servlet.service → finally deallocate
  → Processor 응답 직렬화 → Endpoint 연결 close
serve 종료 → 예제 실행자 unloadServlets → 각 유휴 Wrapper.destroy
```

각 컨테이너는 Pipeline과 기본 Valve를 소유한다. 기본 Valve는 이미 선택된 다음 계층의 Pipeline을 호출한다. Wrapper의 생명주기 소유권은 그대로이고 대여·호출·반환 책임은 고정 Adapter에서 WrapperValve로 이동했다. 3단계 고정 Wrapper Adapter 생성자도 유지해 당시 예제와 테스트를 실행할 수 있다.

## 이관 범위와 소스 대조

원본 폴더 `D:\spring-study\simple-tomcat`의 기준은 `937bebd2f2e74179cdda61d28f69cb38ca28784c`다. 3단계는 `d170166`, 4단계는 `937bebd`에 구현되어 있었다. 그곳의 계획 2개와 servlet·catalina 코드·테스트를 읽고 Wrapper·ContainerBase·계층·Pipeline·Valve·관련 테스트를 이관했다. 그쪽의 4단계는 수동 선택한 객체로 호출하는 범위이므로 Mapper·실제 HTTP 연결·경로 정책·예제·TCP 검증은 여기서 추가했다. 그쪽 문서·빌드 설정·2단계 HTTP 코드는 덮어쓰지 않았고 원본 폴더도 수정하지 않았다.

실제 기준은 Tomcat **10.1.34**다. [Mapper](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/mapper/Mapper.java)의 internalMap/internalMapWrapper와 [CoyoteAdapter](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/connector/CoyoteAdapter.java)의 service/postParseRequest를 재조회했다. 등록과 Pipeline 위임, WrapperValve 정리, Context 보호 경로도 고정 소스에서 확인했다. 핵심 대응·실제 차이·이유·영향·검증·후속은 [구현 계획의 표](../plans/2026-11-10-step-03-04-implementation.md)를 따른다.

## 실제 선택 정책

Host는 대소문자를 무시하고 Adapter가 Host 헤더의 포트를 분리한다. 등록되지 않은 Host와 Host 없는 HTTP/1.0은 명시한 기본 Host가 있을 때만 fallback한다. 기본 Host가 없으면 EngineValve에서 404다.

Context는 세그먼트 경계가 맞는 최장 경로를 선택한다. `/shop/deep`은 `/shop`보다 우선하고 `/shopper`는 `/shop`에 속하지 않는다. root 앱 이름은 빈 문자열이다. 이 모델에서 `/shop`은 앱 내부 `/`로 취급하며 Tomcat의 Context 루트 redirect는 생략한다.

| 충돌 입력 예시 | 선택 규칙 | contextPath | servletPath | pathInfo |
|---|---|---|---|---|
| /shop/api/exact.do | 정확 /api/exact.do | /shop | /api/exact.do | null |
| /shop/api/deep/a.do | 최장 접두어 /api/deep/* | /shop | /api/deep | /a.do |
| /shop/api | 접두어 /api/* | /shop | /api | null |
| /shop/api/ | 접두어 /api/* | /shop | /api | / |
| /shop/a.do | 확장자 *.do | /shop | /a.do | null |
| /shop/other | 기본 / | /shop | /other | null |
| /shop/a.do, /* 등록 | 접두어 /*가 확장자보다 우선 | /shop | 빈 문자열 | /a.do |

MappingData는 요청마다 생성하는 불변 record다. 누락된 계층은 null이고, 누락 대상의 404 작성은 Mapper가 아닌 호출 경로의 기본 Valve 책임이다. ContextValve는 앱 내부 WEB-INF/META-INF 경로를 대소문자 무시로 막는다. 일반 service 예외는 WrapperValve에서 반환 후 전파하며 기존 Processor가 부분 응답을 버리고 500을 작성한다.

### 경로 입력 수용·거부

| 입력 | 프로젝트 동작 | 근거·영향 |
|---|---|---|
| /shop/status, /shop/api/, / | 수용 | ASCII 정규 절대 경로 |
| /shop/status?raw=%2F | 경로는 /shop/status, query 원문 유지 | query는 Processor가 분리하며 경로 검사에 넣지 않음 |
| /shop/%73tatus, /%2f, /%GG | 400 | 디코딩을 구현하지 않으므로 유효·무효 인코딩 모두 거부 |
| /shop/a;b, /shop//a, /shop/./a, /shop/../a | 400 | 경로 파라미터·정규화 기능 생략, 해석 차이 방지 |
| 역슬래시·제어문자·공백·비ASCII·?·# 포함 경로 | Mapper 거부, TCP는 Processor 또는 Adapter에서 400 | 파서가 먼저 거부하는 입력도 있음 |
| OPTIONS * | 컨테이너 모드에서 400 | 2단계 파서는 허용하지만 컨테이너 경로 정책은 절대 경로만 지원 |

Tomcat은 경로 파라미터 제거·디코딩·정규화 뒤 매핑한다. 위의 전면 거부 정책이 Tomcat 전체 동작과 같다는 뜻이 아니다. 디코딩·welcome·redirect·빈 특수 패턴·별칭·wildcard Host·버전·동적 배포는 의도적으로 생략했다. 확장자 패턴은 단일 ASCII 영숫자/밑줄/하이픈 확장자로 제한한다. 계층·매핑·Valve 등록은 시작 전에 끝내야 하며 Valve 인스턴스는 한 Pipeline에서만 사용한다.

## 2026-11-10 실제 검증

기준 커밋 `fddcb7d`(learn/step-03), 완료 경계 `learn/step-04`. Windows PowerShell, Zulu JDK 21.0.11, Wrapper 3.3.2 / Maven 3.9.9. 다른 폴더의 123개 테스트 결과는 이번 결과로 사용하지 않았다.

| 실행 | 실제 결과 |
|---|---|
| 이관 계층 테스트만 추가 | 타입 미구현 컴파일 실패 |
| 계층·Pipeline 이관 후 관련 실행 | 47개 모두 통과 |
| Mapper·HTTP 테스트만 추가 | 타입 미구현 컴파일 실패 |
| Mapper·Adapter 미구현 stub 실행 | 34사례 중 실패 25·오류 9 |
| 전체 관련 테스트 실행 | 85사례 모두 통과 |
| `mvnw.cmd -B clean verify` | 종료 0, BUILD SUCCESS, 138사례 실패/오류/건너뜀 0, JAR 생성 |
| 최종 JAR ServletHttpExperiment | 3단계 HTTP·500·HEAD·복구·해제·포트 재사용 회귀 통과 |
| 최종 JAR ContainerHttpExperiment | 실제 curl 16사례·응답 본문 일치, 반복 unload·포트 재바인딩 성공 |
| 독립 코드 리뷰 | P1/P2 수정 필수 결함 없음. 단일 Pipeline 사용 전제·Error 전파 제약을 확인 |

138 = 기존 53 + Wrapper 16 + Servlet HTTP 4 + 계층/Pipeline/Valve 31 + Mapper 32 + Container HTTP 2. 매개변수화 테스트는 실행 사례 수로 센다. Mapper만 실행했을 때 init/service 0회, 선택 계층 Pipeline의 전후 순서·대상 동일성, allocate/service/deallocate 실패·원인 보존, 정상 대여 반환 후 destroy도 테스트했다.

curl 실험은 예제와 같은 createEngine 구성을 JAR에서 조립한다. `/shop/status`, `/admin/status`, 다른 Host, 중첩 Context, 정확·긴 접두어·접두어·확장자·기본 패턴, 기본 Host fallback, `/shopper`·미등록·보호 경로 404, 퍼센트·dot 경로 400, service 500·다음 정상 200을 실제로 확인했다. 경로 정규화를 curl이 먼저 하지 않도록 `--path-as-is`를 사용했다. 대기 시간은 유한하고 포트는 임의이며 finally에서 서버·실행자를 종료했다.

재현:

```powershell
.\mvnw.cmd -B clean verify
New-Item -ItemType Directory -Force target/experiments
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar -d target/experiments doc/experiments/ServletHttpExperiment.java doc/experiments/ContainerHttpExperiment.java
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' ServletHttpExperiment
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' ContainerHttpExperiment
& "$env:JAVA_HOME/bin/java.exe" -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar simpletomcat.examples.ContainerServer 18081
# 별도 터미널
curl.exe --max-time 3 -i -H 'Host: localhost' http://127.0.0.1:18081/shop/status
curl.exe --max-time 3 -i -H 'Host: other.local' http://127.0.0.1:18081/shop/status
```

## 제약과 다음 문제

블로킹·순차 처리·연결당 한 요청·본문 미지원·읽기 타임아웃 없음은 유지한다. 경로 격리는 클래스 로더 격리를 뜻하지 않는다. Wrapper 사용 중 unload는 즉시 거부하며, 예제는 serve가 반환한 뒤만 해제한다. 일반 destroy 예외는 모아 다른 Wrapper도 정리하지만 Error는 즉시 전파한다. shutdown hook은 최대 5초 대기이므로 무한 앱 콜백·강제 종료를 안전하게 마무리하는 보장은 없다. 테스트 도구 생성 중 인터럽트 실패 경로 정리는 별도 후속 보완 항목이다.

5단계는 앱 공통 전후 처리를 FilterChain으로 분리한다. 일반 Valve 연결 도구는 이관했으나 실제 사용자 정책·Filter 기능은 아직 구현하지 않았다. 6단계는 전체 Lifecycle·동시성·읽기 제한·종료 대기를, 7단계는 내장 API·DispatcherServlet 예제를 다룬다.
