# 1단계 학습·실행 기록 — 연결과 서버의 수명

## 이전 한계 → 최소 해결

0단계는 빌드와 환경 테스트만 있었다. TCP 연결을 받아 HTTP 응답을 보낼 실행 클래스가 없었다. 이번에는 `simpletomcat.util.net.Endpoint` 한 클래스에 생성 시 바인딩, 순차 accept, 헤더 끝 읽기, 고정 응답, 연결 정리를 넣었다. `getPort()`로 실제 바인딩 포트를 조회하고, `serve()`는 호출 스레드를 사용한다. 포트 0은 OS가 임의 포트를 배정한다.

변경 전: Maven → 환경 테스트 → 클래스 없는 JAR.

변경 후: Endpoint 생성·바인딩 → serve → accept → 연결 등록 → CRLFCRLF 읽기 → UTF-8 응답 → **요청 연결만 close** → 다음 accept. 서버 수신 소켓은 Endpoint.close 때 닫는다. 두 요청 테스트로 첫 응답 후에도 서버가 살아 있음을 확인했다. 본문은 `안녕하세요, simple-tomcat!\n`이며 UTF-8로 32바이트다.

## 책임·소유권·종료

- Endpoint는 수신 소켓과 현재 수락 연결을 소유한다. 연결은 각 반복의 try-with-resources가 정리한다. 생성자 바인딩 실패도 수신 소켓을 정리한다.
- serve는 한 번만 호출할 수 있고 중복 호출은 IllegalStateException이다. close 뒤 첫 serve는 정상 반환한다. 재시작은 새 Endpoint로 한다.
- 수락은 잠금 밖에서 대기한다. 연결 등록과 close는 같은 lifecycle 잠금을 쓴다. 종료가 먼저면 수락된 연결을 등록하지 않고 닫는다. 등록이 먼저면 close가 current를 찾아 닫는다. read/write 동안 잠금을 점유하지 않아 다른 스레드가 종료를 요청할 수 있다.
- close는 종료 플래그 → 수신 소켓 close → 현재 연결 close 순서다. 반복 호출은 허용한다. accept/read 대기를 깨우지만 serve 스레드 종료를 join하지는 않는다. 호출자가 Future.get 등으로 완료를 기다린다.
- 연결 EOF·IOException은 연결만 정리하고 계속한다. 미완성 EOF에는 응답하지 않는다. 수락 IOException은 종료 중이면 정상 종료하고 그 외에는 호출자에게 전파하며 finally에서 Endpoint를 정리한다.
- main은 기본 18081, 인자로 포트를 받는다. 실제 포트를 출력하고 shutdown hook에서 close한다. 정상 반환 때 hook을 제거한다.

## 아직 분리하지 않은 이유·다음 문제

이 단계는 연결과 서버의 수명을 비교하는 중간 모델이다. HTTP 해석을 Endpoint에 두어 요청 문법과 소켓이 결합되어 있다. 헤더는 저장하지 않고 4바이트 이동 창으로 끝만 찾는다. 따라서 잘못된 요청이나 Host 없는 요청도 고정 200을 받는다. 2단계에서는 Processor·Adapter 경계를 만들고 소켓 없이 HTTP 규칙과 오류를 검증한다. [다음 단계 계획](../plans/2026-11-05-step-02-coyote.md)을 따른다.

## 재현 명령

이 PC에서 확인한 JDK 경로다. 다른 PC는 JAVA_HOME을 설치한 JDK 21로 바꾼다.

```powershell
$env:JAVA_HOME = 'C:\ssafy\zulu21.50.19-ca-jdk21.0.11-win_x64'
$env:PATH = "$env:JAVA_HOME\bin;${env:SystemRoot}\System32;${env:SystemRoot}\System32\WindowsPowerShell\v1.0;$env:PATH"
.\mvnw.cmd -B -Dtest=EndpointTest test
.\mvnw.cmd -B clean verify
& "$env:JAVA_HOME\bin\java.exe" -cp target/classes simpletomcat.util.net.Endpoint 0
# 출력된 포트를 별도 터미널에서 사용한다. 아래 61351은 실제 실험 당시 값이다.
& "$env:SystemRoot\System32\curl.exe" --max-time 3 -i http://127.0.0.1:61351/hello
& "$env:SystemRoot\System32\curl.exe" --max-time 3 -i http://127.0.0.1:61351/hello
# 서버 터미널에서 Ctrl+C
```

일반 JAR의 클래스로도 실행할 수 있다. Main-Class 매니페스트는 아직 제공하지 않는다.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar simpletomcat.util.net.Endpoint 0
```

## 실제 검증 — 2026-11-05

기준 커밋 `c35b0ec`에서 시작했다. 코드·테스트·문서를 함께 담는 완료 커밋은 `learn/step-01`로 식별한다. Windows PowerShell, Zulu OpenJDK 21.0.11+10-LTS, Maven 3.9.9, Wrapper 3.3.2에서 실행했다. 기존 0단계 기록은 변경하지 않았다.

| 명령·상황 | 실제 결과 | 검증 범위·제약 |
|---|---|---|
| 구현 전 mvnw.cmd -B test | 기존 환경 테스트 2개 통과 | 기준 상태 확인 |
| 테스트 선작성 뒤 -Dtest=EndpointTest test | Endpoint 클래스 없음으로 testCompile 실패 | 미구현 확인이며 assertion 실패는 아님 |
| Endpoint 구현 뒤 -Dtest=EndpointTest test | 테스트 10개 통과 | 아래 시나리오 |
| mvnw.cmd -B clean verify | 테스트 12개, 실패·오류·건너뜀 0, BUILD SUCCESS, JAR 생성 | 환경 2개 + Endpoint 10개 |
| jar tf target/simple-tomcat-0.0.1-SNAPSHOT.jar | Endpoint.class와 Maven 메타데이터 확인 | Main-Class 없는 일반 JAR |
| classes로 main 0 실행·Ctrl+C | 임의 포트 53952 출력, 종료 후 해당 포트 재바인딩 성공 | 이 실행의 curl은 PATH 누락으로 실행하지 못함 |
| 최초 curl.exe 호출 | 명령을 찾지 못함 | System32 절대 경로로 수정해 다음 실행에서 재검증 |
| JAR로 main 0 실행 | 임의 포트 61351 출력 | 별도 Java 프로세스·PTY |
| curl.exe --max-time 3 -i 두 번 | 모두 HTTP/1.1 200 OK, Content-Length: 32, Connection: close, 한글 본문 32바이트, exit 0 | 같은 서버·서로 다른 연결 |
| JAR 프로세스 Ctrl+C 후 TcpListener 재바인딩 | 61351 바인딩 성공 | PTY 종료 코드는 1로 보고됨; HTTP 실패와 구별 |

EndpointTest의 10개 시나리오는 연속 두 연결, 분할 헤더 끝, 미완성 EOF 후 복구, 잘못된 HTTP의 고정 응답, RST 후 복구, accept 중 종료·반복 close·포트 반환, read 중 종료·EOF·포트 반환, serve 전 종료, serve 중복 거부, 연결·종료 경합 30회다. 테스트 첫 애너테이션 바로 위에 한국어 입력·기대 결과를 기록했다.

임의 포트, connect/read 제한 3초, Future 제한 5초, executor 종료 대기 5초를 사용한다. 시작 latch 및 유한 스택 관찰로 accept/read 진입을 확인한다. 200ms read timeout은 헤더 미완성 동안 무응답을 관찰하는 용도이며 테스트 진행 순서를 임의 sleep에 맡기지 않는다. 스택 관찰은 `sun.nio.ch` 구현에 의존하므로 다른 JDK 구현에서 조정이 필요할 수 있다. 경합 테스트는 공통 latch를 사용하지만 accept 직후·등록 직전의 모든 스케줄을 강제로 재현하지 않는다. 해당 창은 잠금 순서 검토로 함께 확인했다.

별도 읽기 전용 코드 검토에서는 차단 이슈가 없었다. 수락 실패·응답 쓰기 실패를 강제 주입하는 테스트와 다른 OS 실행은 수행하지 않았다. 헤더 크기·읽기 시간 제한, HTTP 문법 검사, 동시 요청 처리는 현재 보장하지 않는다.

최종 확인: Content-Length 헤더의 전체 행을 비교하도록 assertion을 보강한 뒤 `mvnw.cmd -B clean verify`를 재실행해 테스트 12개 통과·JAR 생성을 확인했다. `git diff --check`에 공백 오류가 없고 README·doc 아래 Markdown의 로컬 링크가 모두 존재함을 확인했다.

## Tomcat 대응·실제 차이

고정 소스 조사 결과와 메서드별 호출·상태·오류·종료는 [1단계 계획의 착수 조사](../plans/2026-11-05-step-01-socket.md)에 기록했다. 아래는 구현 후 비교다.

| 항목 | Tomcat 10.1.34 근거·실제 동작 | 프로젝트 실제 동작 | 이유 | 영향 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| 수락·인계 | Acceptor.run → NioEndpoint.setSocketOptions → Poller 등록 | serve가 등록·읽기·응답까지 순차 실행 | 연결 수명 학습 | 느린 연결이 다음 요청 지연 | 분할 헤더·종료 | 2단계 HTTP 분리, 6단계 동시성 |
| 수락 직후 종료 | Acceptor가 stop/pause 재확인 후 destroySocket | 같은 잠금으로 closed 재확인, 종료 후 수락 연결도 close | 소유권 경합 축소 | 재시작 없음 | 경합 30회·잠금 검토 | 6단계 Lifecycle 확장 |
| HTTP·EOF | Acceptor에는 HTTP 해석 없음; 파서 오류별 정책은 이번 조사 범위 밖 | CRLFCRLF만 확인, 미완성 EOF 무응답 | 파서 이전 모델 | 잘못된 문법도 200 | malformed·EOF 후 정상 요청 | 2단계 파서 소스 조사·정책 교체 |
| 연결 오류·수락 오류 | Acceptor의 수락 예외 복구·지연, NioEndpoint의 설정 실패 연결 정리 | 연결 IOException 뒤 계속, 수락 오류는 종료 외 전파 | 최소 오류 경계 | 수락 장애 자동 재시도 없음 | RST 후 정상 요청; 수락 오류는 코드 검토 | 필요 시 별도 정책 조사 |
| 종료·포트 | AbstractEndpoint.pause/stop/destroy의 bind 상태별 unbind | close에서 수신·현재 연결 즉시 닫기, 스레드 대기는 호출자 책임 | 단일 루프 취소 | graceful 완료·재시작 미지원 | accept/read 취소·반복 close·포트 반환·Ctrl+C | 6단계 수명·실패 정책 |

아직 조사하지 않은 HTTP 오류 분기를 Tomcat의 확정 동작으로 기록하지 않는다. 최신 버전 권장이나 운영 서버 적합성을 뜻하지 않는다.

## 2026-11-05 — 줄별 주석·메서드 Javadoc 보강

사용자 요청으로 Endpoint와 두 테스트 클래스의 구현 줄에 한국어 설명을 추가했다. 생성자·공개 메서드·비공개 보조 메서드·테스트 메서드에는 Javadoc을 붙이고 해당하는 인자·반환값·예외 및 자원 소유권을 설명했다. 테스트 애너테이션 바로 위의 한국어 상황·기대 결과 주석도 보존했다.

기준 커밋 `22362b3`과 비교해 문자열을 보존한 채 주석·공백을 제외한 Java 코드가 세 파일 모두 동일함을 확인했다. 같은 환경에서 `mvnw.cmd -B clean verify`를 실행해 테스트 12개 통과·JAR 생성을 확인했다. JDK 21의 `javadoc -private -Xdoclint:all,-missing`으로 테스트를 포함한 전체 소스의 문서를 생성했고 오류·경고가 없었다. 결과는 `target/javadoc/index.html`이다. 누락 주석 경고는 필드 등의 일반 줄 주석을 허용하기 위해 검사에서 제외했고, 문서 구문·참조·태그 검사는 실행했다. `git diff --check`도 통과했다.

이번 변경은 주석 보강이므로 HTTP 실험은 다시 실행하지 않았다. 앞선 실제 HTTP·종료 기록은 해당 구현 검증 당시의 결과로 보존하고, `learn/step-01` 태그는 이동하지 않는다.
