# 문서 목차

## 시작

- [현재 상태](00-overview/01-progress.md)
- [Tomcat 전체 구조](00-overview/02-architecture.md)
- [인터랙티브 구조도](00-overview/tomcat-architecture.html) — 내려받은 HTML을 브라우저로 열어 일반 요청·JSP 요청과 Mapper 조회 흐름을 확인한다.
- [전체 7단계 로드맵](plans/2026-11-05-tomcat-learning-roadmap.md)

- [0단계 환경 구성·실행 기록](00-overview/03-step-00-setup.md)

## 단계별 구현 계획

0. [빌드 환경 준비](plans/2026-11-05-step-00-setup.md)

1. [연결·요청·서버 수명](plans/2026-11-05-step-01-socket.md)
2. [Coyote HTTP 처리](plans/2026-11-05-step-02-coyote.md)
3. [Catalina 진입·Servlet·Wrapper](plans/2026-11-05-step-03-servlet.md)
4. [컨테이너·Mapper·기본 Pipeline](plans/2026-11-05-step-04-container.md)
5. [사용자 Valve·Filter](plans/2026-11-05-step-05-filter.md)
6. [동시성·생명주기](plans/2026-11-05-step-06-lifecycle.md)
7. [내장 컨테이너·사용자 디스패처](plans/2026-11-05-step-07-embedded.md)

## 구현할 때 지킬 기준

- [공식 소스 지도](reference/01-tomcat-source-map.md)
- [범위·차이·설명 전용 개념](reference/02-scope-and-differences.md)
- [학습·테스트·실행 증거·커밋](reference/03-learning-workflow.md)

단계별 계획의 결과는 예정 완료 조건이다. 실제 통과 결과는 구현 후 학습 문서에 날짜와 커밋을 붙여 기록한다.
