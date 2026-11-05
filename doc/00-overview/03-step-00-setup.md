# 0단계 학습·실행 기록

## 이전 한계와 변경

문서만 존재해 테스트·패키징을 실행할 수 없었다. Maven 단일 모듈과 공식 Maven Wrapper를 추가했다. 서버 코드와 책임 분리는 다음 단계부터 시작한다.

변경 전: 문서 열람만 가능. 변경 후: Wrapper → JDK 21 컴파일 → JUnit 환경 검사 → 일반 JAR 생성. `src/test/java/org/example/tomcat/BuildEnvironmentTest.java`에서 실행 Java 주 버전 21과 시스템 컴파일러 존재를 확인한다. 모든 테스트 애너테이션 바로 위에 한국어 상황·기대 결과를 기록했다.

## 재현 방법

JDK 21을 설치하고 JAVA_HOME을 해당 JDK 디렉터리로 설정한다. Windows Wrapper는 Windows PowerShell을 PATH에서 찾을 수 있어야 한다. 처음 실행할 때 Maven·의존성을 내려받으므로 네트워크가 필요하다. 시스템 Maven 설치는 필요 없다.

```powershell
# 이 PC에서 확인한 경로. 다른 PC에서는 설치한 JDK 21 경로로 바꾼다.
$env:JAVA_HOME = 'C:\ssafy\zulu21.50.19-ca-jdk21.0.11-win_x64'
$env:PATH = "$env:JAVA_HOME\bin;${env:SystemRoot}\System32;${env:SystemRoot}\System32\WindowsPowerShell\v1.0;$env:PATH"
.\mvnw.cmd -B -Dtest=BuildEnvironmentTest test
.\mvnw.cmd -B clean verify
& "$env:JAVA_HOME\bin\jar.exe" tf target/simple-tomcat-0.0.1-SNAPSHOT.jar
```

macOS·Linux에서는 JDK 21의 JAVA_HOME을 설정하고 `sh ./mvnw -B clean verify`를 실행한다. 해당 운영체제에서의 실행은 아직 검증하지 않았다.

## 실제 실행 기록 — 2026-11-05

기준 커밋: `28a217e`. 검증한 설정·테스트·이 문서를 함께 담는 완료 커밋은 `learn/step-00`으로 식별한다. 기존 문서 전용 커밋의 기록은 보존한다.

환경: Windows PowerShell, Zulu OpenJDK 21.0.11+10-LTS, Maven 3.9.9, Wrapper 3.3.2.

| 명령·상황 | 실제 결과 | 범위·제약 |
|---|---|---|
| 초기 java/javac/mvn 조회 | 현재 셸에서 명령을 찾지 못함 | 사용자 환경의 JAVA_HOME에서 기존 JDK 21 위치 확인, 프로세스 환경만 지정 |
| 최초 mvnw.cmd 실행 | powershell을 PATH에서 찾지 못해 실패 | Windows 기본 시스템 경로를 프로세스 PATH에 추가 후 재실행 |
| mvnw.cmd -B -Dtest=BuildEnvironmentTest test | 테스트 2개, 실패 0, 오류 0, 건너뜀 0 | JDK 주 버전과 컴파일러 확인 |
| mvnw.cmd -B clean verify | BUILD SUCCESS, 테스트 2개 통과, JAR 생성 | 애플리케이션 소스가 없어 빈 JAR 경고 발생; 0단계 범위에 부합 |

JAR은 `target/simple-tomcat-0.0.1-SNAPSHOT.jar`다. main 클래스와 HTTP 서버가 없으므로 서버 실행·HTTP 실험을 수행한 것으로 기록하지 않는다. Wrapper의 POSIX 스크립트는 Git Bash의 `bash -n` 구문 검사를 통과했다. JAR 내용 확인 결과와 최종 검증은 아래에 누적한다.

- 최종 Windows Wrapper `clean verify` 재실행: 테스트 2개 통과, BUILD SUCCESS.
- Windows의 Git Bash에서 `bash ./mvnw -B verify`: 테스트 2개 통과, BUILD SUCCESS. macOS·Linux 실행을 증명하는 결과는 아니다.
- `jar tf`: MANIFEST와 Maven POM·좌표 메타데이터를 확인했다. 애플리케이션 클래스·Main-Class는 없다.
- `git diff --check`: 공백 오류 없음.

## Tomcat 대응과 다음 문제

[0단계 계획의 고정 소스·차이 표](../plans/2026-11-05-step-00-setup.md)를 따른다. Tomcat의 Ant 빌드와 이 프로젝트의 Maven 환경은 목적과 산출물이 다르다. 네트워크·Servlet 동작은 아직 없다. 다음은 [1단계 계획](../plans/2026-11-05-step-01-socket.md)의 연결 수락·고정 응답·연결 종료다.
