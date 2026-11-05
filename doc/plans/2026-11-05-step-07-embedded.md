# 7단계 · 내장 컨테이너로 완주

상태는 예정이다. 기준 Tomcat 태그는 10.1.34다.

## 이전 문제와 최소 해결

사용자가 Engine·Context·Mapper·Adapter·Connector와 종료 순서를 직접 조립해야 한다. 먼저 예제 main에 조립 코드를 모은 뒤 등록·시작·종료의 공개 API만 SimpleTomcat으로 추출한다.

## 개념·인터페이스·작업

SimpleTomcat은 포트 설정, Context·Servlet·Filter 등록, start/getPort/close를 제공한다. 6단계 구성 객체를 재사용해 생명주기를 연결한다. 등록은 시작 전에 끝낸다.

사용자가 작성한 DispatcherServlet을 기본 매핑 /에 등록한다. /hello와 /orders/count 등 두 업무 경로를 같은 서블릿 내부에서 구별한다. 저장소 안의 작은 인메모리 예제로 경계를 검증한다. DB와 외부 simple-spring 구현은 요구하지 않는다.

catalina/startup/SimpleTomcat과 examples의 DispatcherServlet·EmbeddedServer를 추가한다. JAR Manifest에 예제 진입점을 설정하고 README에서 그대로 실행할 수 있게 한다.

## Tomcat 구현 근거와 대응

[startup.Tomcat.addContext/addServlet/start](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/startup/Tomcat.java)와 [StandardService](https://github.com/apache/tomcat/blob/10.1.34/java/org/apache/catalina/core/StandardService.java)를 조사해 프로그램 등록·구성·수명을 대조한다. 상세 공개 API와 실패 정책은 구현 전 해당 분기를 확인한다.

| 핵심 동작 | Tomcat 동작 | 프로젝트 예정 흐름 | 테스트 |
|---|---|---|---|
| 내장 구성 | 프로그램 등록 후 내부 구성 시작 | 사용자 main → SimpleTomcat → Server·Service·Connector | 내부 수정 없는 앱 등록 |
| 앱 디스패칭 | Mapper는 서블릿 선택까지만 수행 | 기본 Wrapper → 사용자 DispatcherServlet → 업무 핸들러 | 서로 다른 두 업무 경로 |

## 완료 조건·실행

- 서버 내부 코드를 수정하지 않고 사용자가 서블릿과 필터를 등록한다.
- 두 업무 경로의 응답과 동일 디스패처 인스턴스 사용을 확인한다.
- 미등록 업무 경로 404·업무 예외 500 후 다음 요청이 성공한다.
- 임시 포트 start/getPort/close·반복 종료·자원 반환을 확인한다.
- clean verify로 JAR을 만들고 `java -jar target/<실제 산출물>.jar 18081`로 시작한다. 구현 시 실제 파일명으로 README 명령을 확정한다.
- 별도 터미널의 curl로 두 경로·오류 복구를 검증하고 정상 종료를 확인한다.
- 0~7단계 태그를 각각 별도 체크아웃해 clean verify와 단계별 실행 명령을 확인한다.

테스트 실패 → 최소 공개 API → 사용자 예제 → JAR 실행 → 문서·범위 설명 점검 → 완료 커밋·learn/step-07 순서다. 현재 결과와 태그는 없다.

## 차이와 생략

| 항목 | 실제 Tomcat·근거 | 예정 동작 | 이유 | 영향·제약 | 검증 | 후속 |
|---|---|---|---|---|---|---|
| 공개 API | 표준 Servlet·배포·다양한 설정 | 최소 자체 등록 API | 내부 구조 학습 완주 | Jakarta 호환·일반 배포 없음 | 사용자 예제 | 유지 |
| 프레임워크 연동 | 다양한 외부 앱 실행 | 내부 디스패처 예제 | 외부 저장소 의존 제거 | simple-spring·DB 연동 미검증 | 두 업무 경로 | 별도 계획 |
| 문서 전용 영역 | Jasper·세션·배포·운영 확장 | 역할·소스·차이를 설명 | 필수 구현 경계 | 해당 기능 미지원 | 제외 범위 문서 검토 | 자동 후속 구현 없음 |

이 단계의 완료 조건과 제외 범위 설명이 갖춰지면 필수 과정을 끝낸다. 새 기능은 별도 목표·완료 조건을 가진 계획으로 제안한다.
