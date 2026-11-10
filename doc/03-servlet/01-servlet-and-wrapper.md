# 3단계 — HTTP에서 Servlet 생명주기까지

## 이전 한계와 책임 분리

2단계는 Adapter를 바꿔야 앱 응답을 바꿀 수 있었다. 요청마다 앱 객체를 만들면 init/service/destroy 수명과 공유 자원 해제 책임도 불명확하다. 최소 해결은 Servlet 계약과 한 정의를 소유하는 Wrapper다.

```text
Endpoint → Http11Processor → CoyoteAdapter
  → connector.Request/Response → Wrapper.allocate → Servlet.service
  → finally Wrapper.deallocate → Processor 전송 → Endpoint 연결 close
서버 serve 반환 → 실행자 Wrapper.unload → Servlet.destroy
```

connector 요청·응답은 Coyote 데이터에 대한 요청별 어댑터다. 소켓·입출력 스트림은 전달하지 않는다. Wrapper는 지연 init 성공 후에만 인스턴스를 보관하며 여러 요청에 재사용한다. deallocate는 대여만 반환한다. unload는 유휴 상태에서 destroy하며 반복해도 중복 호출하지 않는다. 이후 allocate하면 새 인스턴스로 다시 로드한다.

init 실패 시 service·destroy를 실행하지 않는다. 다음 요청은 새 인스턴스 생성을 재시도한다. service 실패 시 대여는 반환하고 기존 Processor가 작성 중이던 응답 전체를 새 500으로 교체한다. 사용 중 unload는 기다리지 않고 거부한다. 이는 전체 graceful shutdown 구현이 아니며 6단계에서 조정한다. 예제 실행자는 프로토콜을 닫고 serve가 끝난 뒤 unload한다. JVM hook은 최대 5초 동안 정리를 기다리며, 임의로 오래 걸리는 앱 콜백의 안전 종료는 아직 보장하지 않는다.

## 이관과 실제 Tomcat 차이

`D:\spring-study\simple-tomcat`의 같은 날짜 3단계 계획, 최소 Servlet 계약·StandardWrapper·16개 테스트를 참고해 이관했다. 그쪽의 Container 의존성은 4단계에서 편입하므로 이 완료 경계에서는 제거했다. 그쪽은 실제 HTTP 연결을 후속으로 미뤘으나 여기서는 connector·Adapter·예제와 TCP 테스트를 추가했다. 다른 저장소의 완료 기록이나 92개 테스트 결과를 가져와 새 실행 결과로 표현하지 않는다.

Tomcat 10.1.34 고정 소스를 재확인했다. 메서드별 대응과 차이의 실제 동작·이유·영향·검증·후속은 [이관 계획](../plans/2026-11-10-step-03-04-implementation.md)의 두 표에 기록했다. 자체 API·Supplier 생성, 일반 init 실패 재시도, 활성 대여 중 unload 거부, Processor에서 500 변환은 프로젝트 정책이다. Jakarta API·ServletConfig·UnavailableException·클래스 로더·컨테이너 Lifecycle·필터는 구현하지 않았다.

## 2026-11-10 실제 검증

기준 커밋 `cbfef0e`, 완료 경계 `learn/step-03`. 환경: Windows PowerShell, Zulu JDK 21.0.11, Maven Wrapper 3.3.2 / Maven 3.9.9. 기존 검증 기록은 변경하지 않았다.

| 실행 | 실제 결과 |
|---|---|
| 기준 테스트 최초 실행 / 권한 적용 재실행 | TCP Permission denied 9건 / 53개 모두 통과 |
| Wrapper 테스트만 추가 | 타입 미구현 컴파일 실패 |
| Wrapper 계약·stub 실행 | 16개 중 실패 7·오류 9 |
| Wrapper 구현 후 | 16개 모두 통과 |
| HTTP Adapter stub 실행 | 실제 TCP 4개 모두 assertion 실패 |
| `mvnw.cmd -B "-Dtest=StandardWrapperTest,ServletHttpTest" test` | 20개 모두 통과 |
| `mvnw.cmd -B clean verify` | 종료 0, BUILD SUCCESS, 73개 실패/오류/건너뜀 0, JAR 생성 |
| 생성 JAR의 ServletHttpExperiment | 두 GET 200·UTF-8 본문 26바이트, init/service/destroy=1/2/0. 이후 500·HEAD 본문 0·정상 GET 복구, 종료 후 1/4/1·반복 unload·포트 재바인딩 성공 |

추가 HTTP 테스트는 method·원문 URI·query·대소문자 무시 헤더 전달, 201·Content-Type·한글 본문, init 실패 후 새 객체·정상 객체만 destroy, 부분 응답 폐기·복구, latch로 고정한 service 중 unload 거부를 확인한다. 모든 TCP 대기는 유한하며 포트 0·명시적 close/Future 종료를 사용한다.

재현:

```powershell
.\mvnw.cmd -B clean verify
New-Item -ItemType Directory -Force target/experiments
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar -d target/experiments doc/experiments/ServletHttpExperiment.java
& "$env:JAVA_HOME/bin/java.exe" -cp 'target/simple-tomcat-0.0.1-SNAPSHOT.jar;target/experiments' ServletHttpExperiment
& "$env:JAVA_HOME/bin/java.exe" -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar simpletomcat.examples.ServletServer 18081
# 별도 터미널: curl.exe --max-time 3 -i http://127.0.0.1:18081/hello
# /fail은 500, 종료는 Ctrl+C. shutdown hook의 OS 신호 동작은 자동 실험 범위 밖이다.
```

위 JAR 실험은 프로토콜·Adapter 공개 API를 직접 조립하고 정상 close→serve 종료→unload를 확인한다. macOS/Linux·강제 프로세스 종료·무한 앱 콜백은 검증하지 않았다.

## 다음 문제

모든 요청이 같은 Wrapper로 가므로 두 앱의 `/status`를 구별할 수 없다. 다음 단계에서는 Host·Context·Wrapper를 Mapper가 선택하고, 각 기본 Valve가 선택 결과만 사용해 Pipeline을 따라 호출한다.
