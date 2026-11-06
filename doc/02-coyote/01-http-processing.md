# 2단계 · Coyote HTTP 처리와 책임 분리

2026-11-06 실제 구현·검증 기록이다. 기준 소스는 Tomcat **10.1.34**로 고정한다. 구현 경계는 `learn/step-02`, 시작 커밋은 `ae0d1bd`다. 3~7단계는 구현하지 않았다.

## 이전 한계와 최소 해결

1단계 Endpoint는 CRLFCRLF만 찾았다. `not-http`와 Host 없는 HTTP/1.1에도 200을 보냈다. Host 검사 하나를 Endpoint에 붙일 수 있지만, 요청 행·반복 헤더·본문 프레이밍·응답 상태를 추가하면 소켓 종료와 HTTP 정책이 섞인다. HTTP 테스트마다 서버를 띄우는 비용도 생긴다.

2단계에서는 스트림을 받는 Processor를 추출했다. Endpoint는 소켓 수락·현재 연결 등록·최종 close를 맡고, Processor는 HTTP 검증·앱 호출·응답 직렬화를 맡는다. Adapter는 요청을 받아 메모리 응답을 작성한다. 이 분리로 HTTP 문법·응답 오류는 소켓 없이 검사하고, TCP 테스트는 연결 수명·종료·오류 복구에 집중한다.

## 호출 순서와 소유권

```text
Http11Protocol(port, adapter)
  ├─ Http11Processor(adapter)
  └─ Endpoint(port, processor::process)

호출자 스레드 → Protocol.serve → Endpoint.serve
  accept → lifecycle 잠금 아래 current 등록
  → Handler.process(input, output)
    → Processor.parse → Request → Adapter.service → Response
    → UTF-8 바이트 생성 → 상태·헤더 전송 → 허용된 본문 → flush
  → Endpoint가 연결 close → current 해제 → 다음 accept
```

Endpoint가 listener와 수락 소켓을 소유한다. Handler·Processor는 스트림을 빌리며 닫지 않는다. Protocol은 Endpoint를 소유하고 생성·serve·close를 연결한다. Processor는 Adapter 참조만 보관하고 Request·Response·파싱 상태는 호출마다 새로 만든다. 스트림 출력은 앱 예외 변환 영역 밖에서 수행하므로 쓰기·flush 실패에 500을 다시 보내지 않는다.

앱 예외는 아직 응답을 전송하기 전이므로 작성 중이던 Response 전체를 버리고 500을 만든다. 파싱 오류에는 Adapter를 호출하지 않는다. 일반 입력 IOException은 상위로 전달하고, 완성 전 EOF에는 무응답으로 끝낸다. Endpoint는 연결 I/O 실패 뒤 다음 연결을 수락한다. Error를 일반 앱 예외로 숨기지 않는다.

close는 기존과 같이 잠금 안에서 closed를 먼저 공개하고 listener·current를 닫는다. serve 전 close·반복 close·accept/읽기 대기 취소·연결 등록 경합을 유지했다. 실행 스레드와 완료 대기는 호출자가 소유한다. 앱 코드 자체가 블로킹하면 소켓 close가 앱 실행을 중단시키지는 않는다.

## 계약과 입력 정책

| 대상 | 실제 프로젝트 동작 |
|---|---|
| Request | method, requestURI, queryString, protocol, 대소문자 무관 헤더 조회; 반복 헤더 순서 보존, 불변 컬렉션 |
| URI | origin-form `/...`, `OPTIONS *` 허용. 쿼리 없는 경우 null, 빈 쿼리는 빈 문자열. 퍼센트·문자열 원문 보존, 디코딩·정규화 없음 |
| 요청 행 | token 메서드, 단일 SP 두 개, `HTTP/DIGIT.DIGIT`. 본문 없는 POST·PATCH·확장 메서드도 앱에 전달 |
| 줄·헤더 | CRLF만 허용. token 이름, 접힌 줄·제어문자 거부, 값은 ISO-8859-1로 읽고 양끝 공백 제거 |
| 입력 상한 | 요청 행·헤더·마지막 빈 줄 합계 8192바이트, 헤더 100개. 초과는 400 |
| Host | HTTP/1.1 필수, 중복·빈 값 거부. java.net.URI의 authority/host 검사와 포트 0~65535 검사; Tomcat Host 파서와 동일하다고 보장하지 않음 |
| 프레이밍 | 단일 0 길이는 허용. 음수·비숫자·long 초과·중복 CL·TE+CL은 400. 양수 CL·유효 TE는 본문을 읽지 않고 501 |
| TE 검사 | 반복문으로 token·매개변수·quoted-string·escape·쉼표 검사. chunked는 마지막이며 매개변수 불허. 빈 요소·문법 오류는 400 |
| 버전 | 완성된 지원 외 버전은 505. HTTP/1.0 정상·오류 응답은 HTTP/1.0, 미확정 버전은 HTTP/1.1 |
| Response | 200~599 최종 상태·ASCII 헤더·UTF-8 문자열 본문. 헤더 주입·1xx 상태는 거부되어 앱 오류 500 처리 |
| 전송 | Processor가 Content-Length·Connection을 결정하고 앱의 Transfer-Encoding·Trailer를 제거. 연결당 한 응답 뒤 close |
| 본문 억제 | HEAD는 오류 응답에도 표현 길이만 전송. 204·304는 Content-Length 없이, 205는 Content-Length 0, 모두 본문 없음 |

URI는 공백·비ASCII·fragment·일부 특수문자를 거부하는 최소 검사다. 잘못된 `%nn` 디코딩이나 경로 정규화는 하지 않는다. 현재 앱은 모든 유효 경로에 같은 200을 보낸다. 따라서 `/missing`의 404 처리는 아직 없다.

## Tomcat 10.1.34 핵심 동작 대조

소스 열람으로 확인한 사실이며 실제 Tomcat 서버와 차등 실행한 결과는 아니다.

| 핵심 | 고정 소스의 실제 메서드·상태·책임 | 프로젝트 대응 |
|---|---|---|
| 연결 호출·종료 | [NioEndpoint.SocketProcessor.doRun](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/tomcat/util/net/NioEndpoint.java#L1644)은 Handler 결과가 CLOSED이면 wrapper를 닫는다 | Endpoint.serve가 Handler 반환 후 항상 소켓을 닫음 |
| Processor 확보·반환 | [AbstractProtocol.ConnectionHandler.process](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/AbstractProtocol.java#L830)는 재사용/생성한 Processor의 process를 호출하고 SocketState별로 유지·반환한다 | Protocol이 단일 Processor 조립, 연결별 상태 반환 없음 |
| 파싱 | [Http11InputBuffer.parseRequestLine/parseHeader/fill](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/http11/Http11InputBuffer.java#L308)은 단계·버퍼 위치를 보존하며 불완전 입력·크기 제한을 처리한다 | HeaderReader가 CRLF·총량을 검사, 블로킹으로 완성까지 읽음 |
| HTTP 처리 순서 | [Http11Processor.service](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/http11/Http11Processor.java#L235)는 요청 행·버전·헤더·prepareRequest·Adapter·endRequest 순서로 처리하고 오류·keepAlive·async에 따라 상태를 반환한다 | parse → Adapter → write의 단발 동기 호출 |

## 차이·생략 표

아래 Processor 근거는 모두 [고정 Http11Processor](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/coyote/http11/Http11Processor.java)에서 확인했다. InputBuffer 근거는 위 링크를 따른다.

| 항목 | 실제 Tomcat 동작·근거 | 프로젝트 동작 | 이유 | 영향 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| 연결 상태·재사용 | ConnectionHandler가 OPEN/LONG/CLOSED 등을 분기하고 Processor를 recycle | 연결당 한 요청, 단일 Processor·호출별 데이터 | 학습 범위 축소 | keep-alive·async·upgrade 없음 | TCP 응답 EOF·다음 연결 | 단발 정책 유지 |
| 요청 문법 | InputBuffer.parseRequestLine은 복수 SP·HT와 선행 빈 줄을 허용하는 분기 존재; parseHeader는 LF와 접힘 처리 존재 | 단일 SP·CRLF, 접힘 거부 | 문법 상태 축소 | 일부 호환 입력 거부 | malformed 매개변수 테스트 | 의도적 차이 유지 |
| 요청 대상 | Processor.prepareRequest는 absolute URI의 authority와 경로 처리 분기 존재 | origin-form·OPTIONS *만 허용 | 프록시·CONNECT 제외 | absolute-form·authority-form 미지원 | URI 전달·OPTIONS 테스트 | 디코딩·매핑은 후속 조사 |
| 크기·개수 | InputBuffer.fill의 headerBufferSize와 service의 maxHeaderCount 설정 | 8192바이트·100개 고정, 초과 400 | 유한 메모리 사용 | 설정 API 없음 | 정확한 경계·초과 테스트 | 설정 확장은 필요 시 |
| Host | prepareRequest는 Host 중복·1.1 누락 검사 후 parseHost 호출 | URI 기반 최소 authority 검사 | Tomcat 파서 복제 축소 | IDN·IPv6 zone·특수 호스트 호환성 차이 가능 | 누락·빈 값·중복·공백 | 상세 호스트 문법은 조사 항목 |
| 본문 | prepareInputFilters의 identity/chunked 필터 | 유효 본문 요구 501 | 본문 미지원 정책 | Tomcat과 다른 501 의미, 업로드 불가 | CL·TE·앱 미호출 | 의도적 차이 유지 |
| TE+CL | prepareInputFilters는 chunked 적용 시 CL 제거·keepAlive 해제 분기 존재 | 항상 400 | 모호한 프레이밍 거부 | 유효성 허용 범위 차이 | TE+CL 400 테스트 | 유지 |
| 오류 진입 | service는 ErrorState의 I/O 허용 여부로 Adapter 경로를 결정 | 파싱 실패 자체 응답, 앱 Exception은 새 500 | 최소 오류 모델 | 오류 페이지·dispatch 없음 | 앱 호출 여부·내용 누출 방지·복구 | 3단계 이후 앱 경계 학습 |
| 출력·상태별 본문 | prepareResponse는 Void 필터로 HEAD·204·205·304 억제, 205 길이 0 설정 | 메모리 본문을 억제하고 길이 직접 관리 | 필터·commit 상태 축소 | 스트리밍·압축·sendfile 없음 | UTF-8·HEAD 오류·상태별 본문·출력 실패 | 유지 |
| Expect·Upgrade | prepareExpectation·service에 협상 분기 존재 | 일반 헤더로 전달, 협상·1xx 없음 | 추가 프로토콜 제외 | Expect 요구와 Upgrade 실행 미지원 | 본문 있는 요청은 501로 즉시 종료 | 의도적 생략 |
| 종료·실행 | NioEndpoint는 Poller·SocketProcessor를 사용하고 wrapper 상태로 종료 | 블로킹 순차 실행, 소켓 즉시 close | 1단계 수명 계약 유지 | 읽기 제한 시간·앱 완료 대기·graceful 없음 | accept/읽기/등록 경합·포트 반환 | 6단계에서 동시성·종료 정책 |

Tomcat의 Host 전체 문법·설정별 제한 기본값·모든 오류 조합의 우선순위는 이번 조사로 확정하지 않았다. 직접 검증하지 않은 호환성을 보장하지 않는다.

## 테스트와 실패 이력

환경: Windows PowerShell, Zulu OpenJDK 21.0.11+10-LTS, Maven 3.9.9, Wrapper 3.3.2. 시스템 JDK/Maven 설정은 바꾸지 않고 현재 프로세스 환경만 지정했다.

| 실행 | 실제 결과 |
|---|---|
| 변경 전 mvnw.cmd -B test | 기존 12개 통과 |
| Processor 테스트 최초 실행 | Adapter·Processor 미구현으로 컴파일 실패 |
| 계약·빈 Processor 추가 후 테스트 | 35개 실패, 오류 0: 응답·앱 호출·오류 전파가 없어 실패 |
| HTTP 구현 후 관련 테스트 | 35개 통과 |
| Protocol 연결 테스트 최초 실행 | Protocol 미구현 컴파일 실패 |
| Endpoint 추출 후 전체 테스트 | 48개 통과 |
| 리뷰 HEAD 오류 회귀 테스트 | 2개 실패: 400·501 본문 전송 재현 |
| 리뷰 TE 매개변수 1000개 회귀 | StackOverflowError 재현: 반복 정규식 원인 |
| 수정 후 전체 테스트 | 51개 통과, 반복 파서·HEAD 오류 상태 보존 적용 |
| 추가 전송 코딩·입력/flush 검사 후 clean verify | **53개 통과, 실패·오류·건너뜀 0, JAR 생성** |

최종 테스트 구성은 환경 2, Processor 40, Protocol TCP 1, Endpoint 10이다. 네트워크 테스트는 포트 0·소켓 제한 시간·latch/Future·명시적 close·executor 종료를 사용한다. 기존 Endpoint 테스트의 문법 무검사 기대만 400으로 전환했고, 종료 시나리오는 유지했다. 스택 관찰 동기화는 기존과 같이 JDK 내부 소켓 프레임에 의존한다.

별도 리뷰어가 HEAD 오류와 반복 정규식 스택 소진을 지적했고, 회귀 테스트로 직접 재현 후 수정했다. 재검토에서 두 수정 확인 및 추가 차단 사항 없음으로 보고됐다. 단위 테스트의 따옴표·escape·빈 코딩 검사와 입력/flush 실패 전파 검사도 최종 검증에 포함했다.

## 실행 방법과 실제 HTTP 실험

```powershell
$env:JAVA_HOME = 'C:\ssafy\zulu21.50.19-ca-jdk21.0.11-win_x64'
$env:PATH = "$env:JAVA_HOME\bin;${env:SystemRoot}\System32;${env:SystemRoot}\System32\WindowsPowerShell\v1.0;$env:PATH"
.\mvnw.cmd -B clean verify
& "$env:JAVA_HOME\bin\java.exe" -cp target/simple-tomcat-0.0.1-SNAPSHOT.jar simpletomcat.coyote.http11.Http11Protocol 0
```

기존 `Endpoint.main`과 `Endpoint(int)` 대신 `Http11Protocol.main`·`Http11Protocol(int)`를 사용한다. 직접 Endpoint를 만들 때는 `Endpoint(int, Handler)`로 조립한다. 1단계 실행 방법은 `learn/step-01`에서 그대로 재현할 수 있다.

2026-11-06 최종 JAR을 별도 PTY 프로세스에서 포트 0으로 실행해 **54280**을 배정받았다. 아래 명령의 포트는 다음 실행 시 출력값으로 바꾼다.

```powershell
$curl = "$env:SystemRoot\System32\curl.exe"
& $curl -sS --max-time 3 -i 'http://127.0.0.1:54280/hello?q=one'
& $curl -sS --max-time 3 -I http://127.0.0.1:54280/hello
& $curl -sS --max-time 3 -i -H 'Host:' http://127.0.0.1:54280/hello
& $curl -sS --max-time 3 --http1.0 -i http://127.0.0.1:54280/hello
& $curl -sS --max-time 3 -i -X POST -H 'Content-Length: 1' http://127.0.0.1:54280/hello
```

| 최종 JAR의 실제 입력 | 결과 |
|---|---|
| HTTP/1.1 GET /hello?q=one | 200, Content-Length 32, 한글 고정 본문 |
| HEAD /hello | 200, Content-Length 32, 본문 없음 |
| Host 제거 | 400, 오류 본문 16바이트 |
| HTTP/1.0 GET | HTTP/1.0 200, 32바이트 |
| POST·Content-Length 1, 본문 미전송 | 대기 없이 501, 20바이트 |
| raw TCP HTTP/2.0 | 505, 31바이트·EOF |
| raw TCP HEAD·Host 누락 | 400, 본문 0바이트·EOF |
| raw TCP TE 매개변수 1000개 | 501, 20바이트·EOF |
| 이후 raw TCP GET /after-error | 200, 32바이트·EOF |
| PTY Ctrl+C 뒤 같은 54280 바인딩 | 성공, 프로세스 종료·포트 반환 확인 |

raw TCP 실험은 .NET TcpClient의 3초 연결·송수신 제한과 finally Dispose를 사용했다. 앱 500과 204·205·304는 주입 Adapter를 사용하는 자동 테스트에서 검증했으며 기본 CLI 앱의 실행 결과로 주장하지 않는다. 이전 중간 JAR 실험은 포트 54044에서 GET·HEAD·400·HTTP/1.0·501·후속 200과 Ctrl+C 뒤 재바인딩을 확인했다. 최종 표는 수정 뒤 다시 빌드한 JAR의 별도 실행 결과다.

## 다음 문제

HTTP 정책은 독립했지만 기본 Adapter는 모든 유효 경로에 같은 응답을 작성한다. 다음 [3단계](../plans/2026-11-05-step-03-servlet.md)에서 Servlet 계약·Wrapper·인스턴스 init/service/destroy 소유권을 도입한다. 이 문서는 3단계 실행 결과를 포함하지 않는다.
