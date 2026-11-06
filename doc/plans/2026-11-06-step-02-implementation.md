# 2단계 구현 작업 기록

기준: 기존 [2단계 설계](2026-11-05-step-02-coyote.md), Tomcat 10.1.34. 사용자의 `2단계구현` 요청으로 이 단계만 구현한다.

## 작업 순서

1. 기존 12개 테스트를 확인한다. HTTP 스트림 테스트를 먼저 작성하고 미구현 실패를 확인한다.
2. Request·Response·Adapter·ProtocolHandler와 Http11Processor를 구현한다.
3. Endpoint에서 HTTP를 제거하고 Handler에 위임한다. 기존 연결·종료 테스트를 새 조립 방식으로 유지하고 TCP 오류 복구 테스트를 추가한다.
4. 관련 테스트, 전체 clean verify, 독립 프로세스 HTTP 실험을 수행한다.
5. 학습 기록·현재 상태·차이 표를 갱신하고 코드·테스트·문서를 커밋한 뒤 learn/step-02를 생성한다.

## 구현 결정

- 기존 설계의 스트림 Processor와 조립 Protocol을 따른다. Endpoint에 HTTP 정책을 계속 추가하는 대안은 독립 파싱 테스트와 계층 분리 목적에 맞지 않는다.
- 현재 작업 폴더에서 진행하며 사용자의 아키텍처 HTML·문서 목차·doc/tests 변경은 커밋에 섞지 않는다.
- CRLF, 단일 SP 요청 행, token 메서드·헤더명, origin-form과 OPTIONS *만 지원한다. ASCII URI를 원문 보존하고 쿼리를 분리한다. 디코딩·정규화는 하지 않는다.
- 요청 행부터 마지막 빈 줄까지 8192바이트, 최대 100헤더. 초과는 400. HTTP/1.1 Host 누락·중복, 잘못된 Host 형식, 잘못된 Content-Length·중복·TE+CL은 400.
- Content-Length 0·본문 없는 모든 token 메서드는 허용한다. 양수 길이·문법상 유효한 Transfer-Encoding은 501, 문법 오류는 400. Expect·Upgrade의 실제 협상은 제외한다.
- 완성 전 EOF는 이전 단계처럼 무응답 종료한다. 파싱 오류는 Adapter 미호출, 일반 Adapter Exception은 새 Response로 500. 출력 IOException은 전파하고 재응답하지 않는다.
- 응답은 메모리에 완성한 뒤 한 번 직렬화한다. UTF-8 길이는 Processor가 관리한다. HEAD는 표현 길이만, 204·304는 길이 없이, 205는 길이 0으로 본문을 억제한다. 중간 1xx 응답은 지원하지 않는다.

## 조사 근거

Tomcat 10.1.34의 Http11InputBuffer.parseRequestLine/parseHeader/fill, Http11Processor.service/prepareRequestProtocol/prepareRequest/prepareInputFilters/prepareResponse, AbstractProtocol.ConnectionHandler.process, NioEndpoint.SocketProcessor.doRun을 확인했다. 상세 메서드·상태·오류·소유권 비교와 검증 결과는 단계 학습 문서에 남긴다. Tomcat과 같은 정책이라고 가정하지 않는다.

## 실행 이력

- 2026-11-06: JDK 경로·Windows 시스템 PATH를 프로세스에 설정한 뒤 mvnw.cmd -B test, 기존 12개 통과.
- 후속 구현·검증 결과는 실제 실행 후 추가한다.

## 실제 후속 결과 — 2026-11-06

- Processor 계약·빈 구현으로 35개 테스트 실패를 확인한 후 구현해 통과했다. Protocol 미구현 실패 후 Endpoint Handler 추출과 조립을 구현했다.
- 기존 Endpoint의 HTTP 문법 무검사 기대만 400으로 변경했다. 연결·읽기·종료·경합 테스트는 유지하며 Processor readLine 경로를 명시적으로 관찰한다.
- 리뷰에서 HEAD 오류 본문과 TE 반복 정규식 스택 소진을 발견했다. 각각 2개 실패·StackOverflowError를 재현하고 ParsingContext·반복형 CodingReader로 수정했다. 재검토에서 추가 차단 사항 없음.
- 최종 clean verify: 53개 통과, JAR 생성. 독립 프로세스 최종 JAR 포트 54280에서 GET·HEAD·400·HTTP/1.0·501·505·TE 1000매개변수·후속 200, Ctrl+C 뒤 재바인딩 성공.
- 단계 결과는 [학습·실행 문서](../02-coyote/01-http-processing.md)에 기록했다. 이전 검증 이력은 보존한다.
- 브랜치 codex/step-02-coyote에서 단계 관련 파일만 완료 커밋·learn/step-02 경계로 남긴다. 원격 push·merge는 수행하지 않는다.
