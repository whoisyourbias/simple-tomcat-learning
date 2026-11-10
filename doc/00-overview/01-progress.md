# 현재 진행 상황

**5단계 구현 후 최신 상태 (2026-11-10):** 0~5단계 구현. 전체159사례·clean verify·JAR 생성, 기존 Servlet/Container HTTP와 신규 Filter curl9·오류 복구·반복 해제·포트 반환 검증. 완료 경계는 `learn/step-05`다. [5단계 학습·실행 기록](../05-filter/01-filter-chain.md)을 따른다. 6~7단계는 예정이며 아래 요약은 5단계 구현 전 시점으로 보존한다.

**최신 요약 (2026-11-10):** 0~4단계 구현, HEAD·`learn/step-04`는 `76f20ec`. 이번 상태 재확인에서 전체 138사례·clean verify·JAR 생성과 기존 Servlet/Container HTTP 실험을 통과했다. 5~7단계는 미구현이다. [5단계 상세 구현 계획과 6/7단계 착수 조건](../plans/2026-11-10-next-implementation.md)을 작성했으며, 아래 과거 기록을 보존한다.

2026-11-05 기준. 이 저장소는 로드맵과 단계별 설계를 문서화한 상태다.

| 항목 | 상태 |
|---|---|
| 전체 로드맵·7개 단계별 계획 | 문서 작성 |
| 0단계 빌드 환경 | 예정 |
| 1~7단계 구현·테스트 | 모두 예정 |
| 서버 실행·Java 검증 결과 | 실행 기록 없음 |
| learn/step-00~07 | 생성 전 |

초기 커밋은 문서만 담는다. 이후 각 단계의 실제 구현·실행 결과를 날짜와 함께 누적한다. 예정 테스트를 통과 결과로 표현하지 않는다.

## 2026-11-05 — 0단계 환경 구성

위 표는 초기 문서 작성 시점의 기록이다. 이후 사용자의 0단계 요청으로 다음을 구성·검증했다.

| 항목 | 현재 상태 |
|---|---|
| JDK 21·Maven Wrapper 3.3.2·Maven 3.9.9 | 구성·실행 확인 |
| JUnit Jupiter·Compiler·Surefire·JAR 설정 | 구성 완료 |
| 환경 테스트·전체 clean verify | 테스트 2개 통과·JAR 생성 |
| 1~7단계 구현·HTTP 실험 | 예정 |
| 완료 커밋·태그 | 설정·테스트·학습 문서를 함께 담는 learn/step-00 |

환경·실패 이력·실행 명령·제약은 [0단계 실행 기록](03-step-00-setup.md)에 보존한다.

## 2026-11-05 — 1단계 소켓 서버

앞선 표는 각 작성 시점의 기록이며 아래가 최신 상태다. 사용자의 명시적 구현 요청으로 단일 Endpoint와 실제 소켓 테스트를 추가했다.

| 항목 | 현재 상태 |
|---|---|
| 0단계 환경 | 완료, 기존 실행 기록 보존 |
| 1단계 순차 서버 | 구현·실행 확인: accept → 헤더 끝 → 고정 응답 → 연결 close |
| 테스트·전체 clean verify | Endpoint 10개 + 환경 2개 통과, JAR 생성 |
| 실제 HTTP·종료 | JAR 프로세스에서 두 요청 200·32바이트 본문, Ctrl+C 뒤 포트 재바인딩 성공 |
| 완료 태그 | learn/step-00, 코드·테스트·학습 문서를 함께 담는 learn/step-01 |
| 2~7단계 | 예정; 다음은 Processor·Adapter로 HTTP 책임 분리 |

실패 이력과 실제 검증 범위는 [1단계 실행 기록](../01-socket/01-endpoint.md), 현재 제약은 [범위와 차이](../reference/02-scope-and-differences.md)를 따른다.

## 2026-11-06 — 2단계 Coyote HTTP 처리

위 기록은 각 단계 검증 당시의 결과로 보존한다. 사용자의 `2단계구현` 요청으로 아래를 구현·검증했다.

| 항목 | 최신 상태 |
|---|---|
| 소켓·HTTP·앱 경계 | Endpoint.Handler → Http11Processor → Adapter, Http11Protocol 조립 |
| HTTP 처리 | 원문 URI·query·반복 헤더, HTTP/1.0·1.1, 400/500/501/505, UTF-8 길이, HEAD·204·205·304 본문 억제 |
| 테스트·전체 clean verify | 환경 2 + Processor 40 + Protocol TCP 1 + Endpoint 10 = 53개 통과, JAR 생성 |
| 실제 HTTP·종료 | 최종 JAR의 GET·HEAD·400·HTTP/1.0·501·505·오류 후 200, Ctrl+C 뒤 같은 포트 재바인딩 |
| 리뷰 수정 | HEAD 오류 본문 누출·TE 정규식 스택 소진을 재현 테스트 후 수정, 재검토에서 추가 차단 사항 없음 |
| 완료 경계 | 코드·테스트·학습 문서를 함께 담는 learn/step-02 |
| 3~7단계 | 예정, Servlet·매핑·동시성·Lifecycle 미구현 |

명령·실패 이력·Tomcat 소스 대조·소유권·남은 제약은 [2단계 학습·실행 기록](../02-coyote/01-http-processing.md)에 남겼다.

## 2026-11-10 — 3단계 Servlet·Wrapper·HTTP 연결

앞선 기록은 당시 상태로 보존한다. 사용자 요청에 따라 다른 폴더의 동일 기능을 비교·이관하고 이 저장소의 HTTP 완료 조건을 보완했다.

| 항목 | 최신 상태 |
|---|---|
| Servlet·생명주기 | 최소 계약·Wrapper 지연 init·공유·대여 반환·유휴 unload |
| HTTP 연결 | connector Request/Response·고정 Wrapper CoyoteAdapter·ServletServer |
| 테스트 | 기존 53 + Wrapper 16 + 실제 HTTP 4 = 73개, clean verify·JAR 생성 성공 |
| JAR 실험 | 두 HTTP 요청 1/2/0, 500·HEAD·복구, 종료 후 destroy 1회·포트 재사용 |
| 완료 경계 | 코드·테스트·학습 문서를 포함한 learn/step-03 |
| 4~7단계 | 예정. 전체 Lifecycle·동시 처리·Filter·내장 API 없음 |

실제 실패·성공·검증 범위와 재현 방법은 [3단계 기록](../03-servlet/01-servlet-and-wrapper.md)에 남겼다.

## 2026-11-10 — 4단계 컨테이너·Mapper·기본 Pipeline

위 표들은 단계별 당시 상태다. 아래가 최신 구현 상태다.

| 항목 | 최신 상태 |
|---|---|
| 0~4단계 | 구현·검증 완료 |
| 계층·호출 | Engine→Host→Context→Wrapper Pipeline, WrapperValve가 대여·반환 |
| 매핑 | 다중 Host·명시 기본 Host·최장 Context·정확→최장 접두어→확장자→기본 |
| 요청 데이터·입력 | MappingData와 경로 3필드, query 원문, 비정규 경로 400·보호 경로 404 |
| 검증 | 전체 138개 통과·clean verify·JAR 생성, curl 16사례·오류 뒤 정상 요청·포트 재사용 |
| 완료 경계 | learn/step-03 = fddcb7d, learn/step-04는 코드·테스트·이번 학습 문서를 포함 |
| 5~7단계 | 예정. Filter·전체 Lifecycle·동시성·내장 API 미구현 |

참고 폴더 기준 `937bebd`의 동일 기능을 재사용했으며 그곳에서 빠진 HTTP·Mapper 범위를 보완했다. [4단계 기록](../04-container/01-mapping-and-pipeline.md)에 실제 이관 범위·실험·제약을 남겼다.

## 2026-11-10 — 현재 상태 재확인·다음 구현 계획

사용자 요청은 작업 상태 확인과 구현 계획 작성이며 새 단계 구현은 수행하지 않았다. 시작 시 `main`·HEAD `76f20ec`·작업 트리 변경 없음·완료 태그 00~04를 확인했다. 최초 clean verify는 샌드박스 TCP 권한 제한으로 실패 1·오류 14였고, 코드 변경 없이 권한 적용 재실행에서 138사례 실패/오류/건너뜀 0·BUILD SUCCESS·JAR 생성을 확인했다. 생성 JAR의 기존 Servlet 실험과 Container curl 16사례도 종료 0·오류 복구·반복 unload·포트 재사용 PASS였다.

Tomcat 10.1.34의 필터 관련 6개 고정 소스를 재조회했다. [새 계획](../plans/2026-11-10-next-implementation.md)은 이미 있는 사용자 Valve 기반을 재사용하고 Filter 계약→Context 수명→WrapperValve 연결→실제 HTTP·문서·완료 태그 순서로 제안한다. 초기화 실패·정리·미조사 정책과 6/7단계 착수 조건을 구분했다. 5단계 테스트·구현·태그는 아직 없으며 이번 재검증은 4단계 기준선 결과다.
