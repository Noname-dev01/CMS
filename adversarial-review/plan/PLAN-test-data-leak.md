# PLAN-test-data-leak — 시험 파일 저장소 격리와 누수 검사

## 개정 이력
- v1 (2026-11-26): 최초 작성.
- v2 변경 (1라운드 codex 리뷰 4건 전부 수용): ① 저장소를 쓰지 않는 부분 실행에서 폴더가 없으면 0개로 본다(Groovy `eachFileRecurse`는 없는 폴더에 `FileNotFoundException`) ② 확인 시험에 태그 `storage-isolation`을 붙여 `-PbrowserTests=only`에서도 돌리고, `doFirst`가 태스크 환경변수 값 = 검사 경로를 확인한다 ③ 비우기·열거를 `java.nio.file.Files.walk`로 바꿔 입출력 오류를 예외로 받는다(`File.deleteDir()`는 `false` 반환, `eachFileRecurse`는 `listFiles()`가 `null`이면 건너뜀) ④ 보장 범위를 "성공한 실행이 끝날 때 남은 파일"로 한정하고, `cleanLibrary`가 앞선 시험의 누수를 가릴 수 있음을 한계로 적는다. 변이 확인에 실제 시험의 정리 제거를 추가한다.
- v3 변경 (2라운드 codex 리뷰 3건 전부 수용): ① `environment` 줄 삭제 변이는 `doFirst` 사전 검사 실패를 기대하고, 확인 시험은 그 시험에만 `app.file-storage.root`를 덮어쓰는 별도 변이로 단언 실패(시험 결과 XML)를 확인한다 ② 루트 부재 판정은 `Files.readAttributes`의 `NoSuchFileException`만 0개로 보고 접근 오류는 실패시킨다(`Files.exists`는 접근 오류도 `false`) ③ 브라우저 전용 실행에서는 확인 시험이 컨텍스트를 하나 더 만든다 — 비용으로 기록하고 실측한다.
- v4 변경 (3라운드 codex 리뷰 2건 전부 수용): ① 루트 부재 판정(`NoSuchFileException`만 부재)을 실행 전 비우기에도 적용한다 — 새 worktree·`clean test`의 첫 실행 ② `Files.walk` 스트림은 비우기·검사 모두 Groovy `withCloseable`로 성공·실패와 관계없이 닫는다.
- v5 변경 (4라운드 codex 리뷰 1건 수용, 방법 조정): 재실행으로 확인하는 검증(F2·성공 기준 4·5)은 `cleanTest test`로 `test`를 강제 실행하고, `> Task :test`가 UP-TO-DATE가 아님·시험 결과 XML 시각·심어 둔 파일 삭제로 실제 실행을 증명한다. 리뷰가 제안한 `--rerun-tasks`는 프런트엔드 빌드까지 다시 돌려 느리기만 해서 쓰지 않는다(이 프로젝트의 기존 관례도 `cleanTest` — 루트 `CLAUDE.md` 시각 검증).
- v5 리뷰(5라운드): **ship**. `@TempDir` 정리 동작의 출처만 보충했다.
- v6 변경 (사용자 요청, 승인 시 보강): 누수 검사 실패 메시지에 파일마다 수정 시각·크기를 넣고, `src/test/java/CLAUDE.md`에 범인 찾는 방법(시험 결과 XML의 `timestamp`와 대조 → 후보 클래스 `--tests` 단독 재현)을 적는다. 실패한 실행의 잔여는 다음 `test` 실행의 `doFirst`가 지우므로 재실행 전에 확인한다는 점도 함께 적는다.

## Context

시험의 파일 저장소가 개발 서버와 같은 `./data/attachments`(`application.yml:85`의 `${APP_FILE_STORAGE_ROOT:./data/attachments}` 기본값)를 쓴다.
`build.gradle`의 `test` 태스크가 `APP_FILE_STORAGE_ROOT`를 넘기지 않기 때문이다. 그래서:

- 시험이 남긴 파일이 개발 파일과 섞인다. 메인 폴더에 217개(1.3MB)가 남아 있고, 내용 해시로 분류하면 #324(900×900 PNG 73개)·#175 이전 팝업 시험(30×12 PNG 75개)의 누수다.
- worktree에서는 무시된 파일로 남아 Orca 자동 정리를 막았다(#324 커밋 본문).
- 누수가 생겨도 아무 시험도 실패하지 않는다. #324·#175의 누수는 각각 한 달·열흘 동안 실행마다 쌓였다.

2026-11-26 실측(저장소 코드 무변경, 전체 시험 3,491건, `APP_FILE_STORAGE_ROOT`를 scratchpad로 돌림):

| 대상 | 결과 |
|---|---|
| 업로드 파일 | 0개 남음(현재 누수 없음) |
| `%TEMP%\notice-attachment-test*` | 실행마다 1개 증가 — `LocalDiskFileStorageTest.java:83`이 `Files.createTempDirectory`로 만든 폴더를 지우지 않음 |
| DB(시험 JVM 종료 직전 덤프, 기준 실행과 비교) | `member` +1(`regtest-…` ADMIN — `MemberProfileImageInsertIntegrationTest`), `notification` +22(남은 ADMIN 2명에게 간 알림), `visit_log` +206, `admin_action_log` +158 |

사용자 결정(2026-11-26):
- 저장소 격리·누수 검사·확정 누수 2건(임시 폴더, `regtest` 회원)을 한 PR로 한다.
- `visit_log`·`admin_action_log` 잔여는 고치지 않는다 — 이 행에 기대는 시험이 없다(`VisitLogRepositoryDataJpaTest`의 전체 건수 시험은 먼저 `deleteAll()`).
- 사용자는 IntelliJ 대신 Orca만 쓴다. IDE에서 Gradle 없이 실행한 시험이 격리되지 않는 한계는 한 줄 기록으로 충분하다(기존 `test` 태스크 환경변수 3개도 같은 한계 — `.idea/gradle.xml`의 `delegatedBuild=false`).
- 문의 알림 시험의 간헐 실패는 별도 이슈 #327.



## 핵심 확정 사항

### 결정 1 — 격리 방식: `test` 태스크 환경변수
- 선택지: (A) `test` 태스크에 `environment 'APP_FILE_STORAGE_ROOT', <build 아래 절대경로>` (B) `src/test/resources`에 `application-*.yml`을 두어 시험 프로파일로 덮어쓰기 (C) 각 시험·지원 클래스에서 `@DynamicPropertySource`(`MariaDbContainerSupport`)로 넣기.
- **A 채택.** 이유: 기존 환경변수 3개(`SPRING_PROFILES_ACTIVE`·`CMS_RATE_LIMIT_ENABLED`·`CMS_PASSWORD_BREACH_CHECK_ENABLED`)와 같은 자리·같은 방식이고, 경로를 Gradle이 알아야 실행 전 비우기·실행 후 검사(결정 2·3)를 같은 곳에서 할 수 있다. B는 시험 리소스 쪽 프로파일 체계를 새로 만든다. C는 `MariaDbContainerSupport`를 상속하지 않는 Spring 시험(슬라이스 등)이 빠지고, 경로를 Gradle과 공유할 수 없다.
- 경로: `layout.buildDirectory.dir('test-file-storage')`의 절대경로. `build/` 아래라 `.gitignore`(`/build`) 대상이고 worktree마다 따로 생긴다.
- 전제(출처): 시험 워커 JVM은 `Test.environment`로 준 환경변수를 받는다 — 이미 이 태스크의 기존 3개가 같은 방식으로 동작 중(실측: 2026-11-26 셸 환경변수로 같은 키를 넘긴 실행에서 격리 경로에만 폴더 16개 생성). 브라우저 시험의 서버도 같은 JVM 안에서 뜬다(`@SpringBootTest(webEnvironment = RANDOM_PORT)`).
- 셸에 `APP_FILE_STORAGE_ROOT`가 이미 있어도 태스크의 `environment`가 덮는다(Gradle `Test`의 환경변수 맵에 키를 넣는 동작 — 미확인, 구현 뒤 실측으로 확인).

### 결정 2 — 실행 전 비우기: `doFirst`
- 매 실행 시작 때 격리 폴더를 통째로 지운다. 이유: 이전 실행이 시험 실패·강제 종료로 남긴 파일을 이번 실행의 누수로 오인하지 않게 하고, 실패한 실행의 잔여가 쌓이지 않게 한다.
- 루트가 없으면 비울 것이 없다(새 worktree·`clean test`의 첫 실행). 부재 판정은 결정 3과 같은 방식(`Files.readAttributes`의 `NoSuchFileException`만 부재)을 `build.gradle` 안의 함수 하나로 두고 비우기·검사가 함께 쓴다(v4, 리뷰 3-1). 하위 경로의 오류는 실패시킨다.
- `Files.walk`가 돌려주는 스트림은 열린 디렉터리 자원을 쥐므로 비우기·검사 모두 `Files.walk(root).withCloseable { … }`로 성공·실패와 관계없이 닫는다(출처: JDK 21 `Files.walk` Javadoc의 "try-with-resources로 닫아야 한다" 주의, Groovy GDK `IOGroovyMethods.withCloseable(AutoCloseable, Closure)` — Groovy 2.5부터, Gradle 8.14.8은 Groovy 3.0.25 — v4, 리뷰 3-2). Windows에서 핸들이 남으면 이어지는 삭제가 실패할 수 있다.
- 삭제는 `java.nio.file.Files.walk`(깊은 것부터)와 `Files.delete`로 한다 — 입출력 오류가 예외로 나와 태스크가 실패한다. 비운 뒤 폴더가 남아 있으면 실패시킨다. `File.deleteDir()`는 실패를 `false`로만 알려 잠긴 파일이 남으면 이번 누수로 오인한다(v2, 리뷰 1-3). `project.delete`도 쓰지 않는다 — 실행 시점에 `project`를 참조하지 않게(configuration cache는 지금 꺼져 있지만 — `gradle.properties` 없음 — 나중에 켜도 걸리지 않게).
- 같은 `doFirst`에서 태스크의 `environment['APP_FILE_STORAGE_ROOT']`가 검사 경로와 같은지 확인한다 — 누군가 환경변수 줄만 다른 경로로 바꾸면 검사가 엉뚱한 폴더를 보게 되기 때문이다(v2, 리뷰 1-2). 환경변수와 검사 경로는 `build.gradle`의 한 변수에서 나온다.

### 결정 3 — 누수 검사: `doLast`에서 남은 일반 파일이 있으면 빌드 실패
- 선택지: (A) Gradle `doLast` (B) JUnit `LauncherSessionListener`/`TestExecutionListener`(서비스 등록) (C) 경고만 출력.
- **A 채택.** 이유: 시험 JVM이 끝난 뒤 검사하므로 Spring 컨텍스트 종료 중에 생기는 파일까지 본다. 실패 메시지에 남은 경로를 바로 보일 수 있다. B는 리스너에서 실패를 시험 결과로 드러내기 어렵다(실패를 보고할 시험 단위가 없다). C는 사용자가 요청한 "실패시키는 검사"가 아니고 #324처럼 묻힌다.
- 동작: 폴더가 없으면 0개다(저장소를 쓰지 않는 `--tests` 실행 — v2, 리뷰 1-1). 부재는 `Files.readAttributes(root, BasicFileAttributes)`가 `NoSuchFileException`을 던질 때만 인정하고, 다른 입출력 오류는 그대로 실패시킨다 — `Files.exists`는 접근 권한 문제처럼 존재를 확인할 수 없을 때도 `false`를 돌려주기 때문이다(JDK 21 `Files.exists` Javadoc, v3 리뷰 2-2). 순회 중 하위 경로 오류는 부재로 묶지 않는다. 있으면 `Files.walk`로 돌며 **일반 파일**만 센다(날짜 샤딩 디렉터리는 비어 있어도 남으므로 제외). 남은 파일이 있으면 개수와 파일별 상대경로·수정 시각(로컬 시각)·크기(최대 20개)를 담아 `GradleException`. 수정 시각은 시험 결과 XML의 `timestamp`와 대조해 누수를 낸 시험 클래스를 좁히는 단서다(v6).
- 쓰지 않는 API(출처: 리뷰가 확인한 Groovy 3.0.25 `ResourceGroovyMethods` — Gradle 8.14.8 배포본에 들어 있는 버전): `eachFileRecurse`는 없는 폴더에 `FileNotFoundException`을 던지고 `listFiles()`가 `null`이면 그 하위를 조용히 건너뛴다. `fileTree`는 기본 제외 목록(`.DS_Store`·`*~` 등)이 있다. `Files.walk`는 읽지 못한 폴더를 `UncheckedIOException`으로 알린다(JDK 21 `java.nio.file.Files` Javadoc).
- **보장 범위(v2, 리뷰 1-4)**: "성공한 `test` 실행이 끝났을 때 저장소에 남은 파일"을 잡는다. 시험마다 자기 파일을 지웠는지는 보장하지 않는다 — `MediaTestSupport.cleanLibrary`는 라이브러리 계열(LIBRARY·BANNER·POPUP) 전체를 지우므로 앞선 시험의 누수를 뒤 시험이 가릴 수 있고, 그 시험만 따로 돌리면 드러난다. 시험 경계마다 검사하는 것은 이번 범위에서 하지 않는다(누수를 낸 과거 2건은 모두 실행 끝 검사로 잡혔을 유형이다).
- 시험이 실패한 실행: `Test` 태스크가 실패하면 `doLast`는 돌지 않는다 — 시험 실패와 누수 실패가 겹쳐 보이지 않고, 남은 파일은 다음 실행의 `doFirst`가 지운다.
- `--tests`로 일부만 돌린 실행에도 검사한다. 시험마다 자기가 만든 파일을 지우는 것이 규약이라 일부 실행에서도 0이어야 한다.

### 결정 4 — 격리가 빠졌을 때 검사가 공허하게 통과하지 않게: 확인 시험 1개
- 문제: 누군가 `environment` 줄을 지우면 시험은 다시 `./data/attachments`에 쓰고, 격리 폴더는 비어 있으니 결정 3의 검사가 항상 통과한다(가장 놓치기 쉬운 실패 모드).
- 선택지: (A) Gradle 아래에서 돌 때만 "Spring이 실제로 쓰는 저장소 루트 = 환경변수 값이고 `build` 아래"를 단언하는 시험 (B) `doLast`가 "폴더가 존재함"을 요구 (C) 대책 없음.
- **A 채택.** B는 저장소를 쓰지 않는 시험만 `--tests`로 돌리면 폴더가 생기지 않아 거짓 실패한다.
- Gradle 아래인지는 시험 워커에 붙는 시스템 속성 `org.gradle.test.worker`로 판정한다(출처: Gradle 사용자 가이드 `java_testing.adoc` "Test execution" — "the value of the `org.gradle.test.worker` property, which is unique for each process"). 속성이 없으면(IDE 단독 실행) `Assumptions`로 건너뛴다.
- **브라우저 전용 실행에서도 돈다(v2, 리뷰 1-2)**: 이 시험에 `@Tag("storage-isolation")`을 붙이고 `build.gradle`의 `-PbrowserTests=only` 분기를 `includeTags 'browser', 'storage-isolation'`으로 바꾼다. `exclude` 분기는 `browser`만 빼므로 그대로 돈다. 브라우저가 필요 없는 시험이라 CI `browser-test` job에 Playwright 의존을 더하지 않는다.
- 한계: `--tests`로 일부만 고른 실행에는 이 시험이 빠질 수 있다. 그 실행에서 격리 설정이 빠져 있으면 검사는 공허하게 통과한다 — 전체 실행(CI 두 job)이 잡는다.
- 위치: `src/test/java/com/cms/support/TestFileStorageIsolationTest`. `@SpringBootTest(classes = CmsTestApplication.class)` + `MariaDbContainerSupport` 상속, 추가 설정 없음. 브라우저 제외 실행에서는 같은 구성의 기존 시험(`MemberProfileImageInsertIntegrationTest` 등)과 컨텍스트를 공유할 것으로 기대한다(미확인 — 실측). **브라우저 전용 실행에서는 컨텍스트가 하나 늘어난다** — 브라우저 시험은 `webEnvironment = RANDOM_PORT`라 캐시 키가 다르다(Spring Framework 7.0 TestContext 캐시 키에 웹 환경·프로퍼티·커스터마이저가 들어간다, v3 리뷰 2-3). 비용은 컨텍스트 기동 수 초로 보고 실측해 기록한다. 단언 대상은 `FileStorageProperties.getRoot()`(실제 바인딩 값).

### 결정 5 — 확정 누수 2건
- `LocalDiskFileStorageTest.store_sameFilenameTwice_distinctKeysAndContent`: `tempDirForTest()`(유일한 호출처) 대신 `@TempDir Path tempDir` 매개변수를 쓰고 헬퍼를 지운다. 다른 시험은 이미 `@TempDir`. `@TempDir`는 기본 정리 모드가 `ALWAYS`이고 삭제 실패는 시험 실패가 된다(출처: JUnit 6.0 `org.junit.jupiter.api.io.TempDir` Javadoc — 5라운드 리뷰 보충).
- `MemberProfileImageInsertIntegrationTest`: 만든 회원 ID를 기억해 `@AfterEach`에서 `TestMembers.delete(jdbc, …)`로 지운다(종속 행 FK 순서를 이미 처리하는 기존 도우미 — `src/test/java/CLAUDE.md`). 알림은 `fk_notification_member ON DELETE CASCADE`라 함께 지워진다.

### 범위 밖
- `visit_log`·`admin_action_log` 잔여(사용자 결정).
- 기존 `data/attachments` 217개 정리 — 개발 DB가 참조하는 파일과 대조가 필요해 자동 삭제하지 않는다(사용자가 원하면 별도로).
- `AdminBootstrapStartupIntegrationTest`가 시드 `admin`을 다시 만들며 ID가 바뀌는 문제 — 지금 영향받는 시험이 없다.

## 성공 기준
1. `./gradlew test` 실행 뒤 `data/attachments`에 새 파일이 생기지 않고, 시험이 만든 파일은 `build/test-file-storage`에만 생긴다.
2. 시험 하나가 파일을 남기도록 일부러 바꾸면 빌드가 `GradleException`으로 실패하고 메시지에 그 파일의 상대경로가 보인다(변이 확인 후 원복).
3. 격리 확인이 실제로 동작한다(변이 확인 후 원복, v3 리뷰 2-1):
   - `build.gradle`에서 `environment 'APP_FILE_STORAGE_ROOT'` 줄을 지우면 `doFirst` 사전 검사가 시험 시작 전에 빌드를 실패시킨다.
   - 태스크 설정은 그대로 두고 `TestFileStorageIsolationTest`에만 `@TestPropertySource(properties = "app.file-storage.root=<다른 경로>")`를 붙이면 그 시험이 단언 실패한다(시험 결과 XML의 `<failure>`로 확인).
4. 셸에 `APP_FILE_STORAGE_ROOT=<다른 경로>`를 넣고 돌려도 격리 경로가 쓰인다.
5. 저장소를 쓰지 않는 시험만 `--tests`로 돌려도(`LocalDiskFileStorageTest`) 통과한다. 격리 폴더가 없는 상태의 첫 실행(`build/test-file-storage` 삭제 후)과, 그 뒤 같은 명령을 한 번 더 돌린 연속 실행 모두 통과한다(v4).
6. `-PbrowserTests=only` 실행에 `TestFileStorageIsolationTest`가 포함된다(시험 결과 XML로 확인).
7. 전체 시험 1회(브라우저 제외 + 브라우저) 통과, 그 뒤 `%TEMP%\notice-attachment-test*` 수가 늘지 않는다.
8. `MemberProfileImageInsertIntegrationTest` 실행 뒤 `regtest-%` 회원 행이 없다.

재실행으로 확인하는 항목(F2·성공 기준 4·5)은 모두 `./gradlew cleanTest test …`로 돌리고, 출력의 `> Task :test`가 UP-TO-DATE가 아닌지와 시험 결과 XML의 시각으로 실제 실행을 확인한다 — 입력이 같으면 `test`와 그 `doFirst`·`doLast`가 통째로 건너뛰어져 공허하게 통과하기 때문이다(v5, 리뷰 4-1).

## 실패 모드와 고정 시험
| # | 실패 모드 | 고정 |
|---|---|---|
| F1 | 격리 설정이 빠져 검사가 공허하게 통과 | `TestFileStorageIsolationTest`(결정 4) + 성공 기준 3 변이 |
| F2 | 이전 실패 실행의 잔여를 이번 누수로 오인 | `doFirst` 비우기(`Files.walk`, 실패 시 예외) — 실기: 격리 폴더에 가짜 파일을 넣고 `cleanTest test`로 실행해 통과하고 가짜 파일이 지워졌는지 확인 |
| F3 | 폴더 부재·읽기 실패·기본 제외 목록 때문에 검사가 틀림 | `Files.walk` + `NoSuchFileException`만 부재 — 성공 기준 2·5. 접근 오류 경로는 Windows에서 재현이 어려워 코드 리뷰로 확인한다 |
| F4 | 셸 환경변수가 태스크 설정을 이김 | 성공 기준 4 실측 |
| F6 | 환경변수 경로와 검사 경로가 어긋남 | `doFirst` 일치 확인 — 변이: 환경변수만 다른 경로로 바꿔 실패 확인 |
| F5 | 회원 정리가 FK로 실패해 조용히 남음 | `TestMembers.delete`는 예외를 삼키지 않음 — 시험 실행 뒤 DB 확인(성공 기준 6, 붙잡기 실행 또는 단언) |

## 작업 단계
1. 브랜치 `test/test-data-leak`.
2. `TestFileStorageIsolationTest` 작성 → 현재 `build.gradle`로 실패 확인(TDD).
3. `build.gradle`에 환경변수·`doFirst`·`doLast` 추가 → 2번 통과.
4. `LocalDiskFileStorageTest`·`MemberProfileImageInsertIntegrationTest` 수정.
5. 관련 시험(`com.cms.support.*`·`com.cms.common.storage.*`·`MemberProfileImageInsertIntegrationTest`·`PopupFileCleanupIntegrationTest`·`*ConventionTest`) → 변이 확인: 성공 기준 2(임의 파일 + 실제 시험의 정리 제거 — #324 수정을 되돌린 `PostCoverImageApiIntegrationTest`를 단독으로), 3, 4, 5, 6, F2, F6.
6. 코드 리뷰 → 최신 `master` 반영 → 로컬 전체 시험 1회(`build.gradle`·`support/**` 변경이라 필수). 이때 두 실행(`exclude`·`only`)의 컨텍스트 수를 비교해 결정 4의 비용을 기록한다.
7. 기록: `src/test/java/CLAUDE.md`(격리 경로·누수 검사·IDE 한계 한 줄), `docs/development-workflow.md` worktree 정리 절의 "첨부" 언급 현행화, 이 문서 결과 갱신.

## 리스크
- 누수 검사가 CI에서 처음 실패하면 머지가 막힌다 — 2026-11-26 전체 실행 실측이 0개라 낮다. 실패하면 그것이 이 검사의 목적(누수 발견)이다.
- `doLast`는 `Test` 태스크가 UP-TO-DATE로 건너뛰어지면 돌지 않는다 — 그 경우 시험도 돌지 않았으므로 정상.

## 구현 파일
- `build.gradle`: `testFileStorageRoot`(= `build/test-file-storage` 절대경로)·`testFileStorageAttributes`(루트 부재만 `null`), `test` 태스크의 `environment 'APP_FILE_STORAGE_ROOT'`·`doFirst`(경로 일치 확인 + 비우기)·`doLast`(누수 검사), `-PbrowserTests=only`의 `includeTags 'browser', 'storage-isolation'`.
- `src/test/java/com/cms/support/TestFileStorageIsolationTest.java`(신규, 태그 `storage-isolation`).
- `src/test/java/com/cms/common/storage/LocalDiskFileStorageTest.java`: `tempDirForTest()` 제거, `@TempDir` 매개변수.
- `src/test/java/com/cms/admin/member/MemberProfileImageInsertIntegrationTest.java`: `@AfterEach`에서 `TestMembers.delete`.
- 문서: `src/test/java/CLAUDE.md`(격리·누수 검사·범인 찾는 법·한계), `docs/development-workflow.md`(worktree 정리의 `data/**` 출처 설명).

## 구현 중 계획과 달라진 점
- 누수 메시지의 수정 시각을 로컬 시각에서 **UTC**(`2026-11-26T07:50:42Z`)로 바꿨다 — 시험 결과 XML의 `timestamp`가 UTC(`2026-11-26T07:50:40.922Z`)라 같은 형식이어야 바로 대조된다(실측).
- 셸에 남은 로그를 볼 때 한글이 깨져 보였다 — Gradle이 Windows 기본 인코딩(CP949)으로 출력한 것을 UTF-8로 읽은 탓이고 메시지 자체는 정상이다(`iconv -f CP949`로 확인). 콘솔에서는 정상 표시된다.

## 검증 결과 (2026-11-26, 영향 범위 시험 + 변이)
| 항목 | 명령·방법 | 결과 |
|---|---|---|
| TDD red | 격리 전 `TestFileStorageIsolationTest` | 실패(`APP_FILE_STORAGE_ROOT` null) — `org.gradle.test.worker`가 있어 건너뛰지 않음도 확인 |
| 관련 시험 | `cleanTest test --tests 'com.cms.support.*' 'com.cms.common.storage.*' MemberProfileImageInsertIntegrationTest PopupFileCleanupIntegrationTest '*ConventionTest'` | 137건 통과(건너뜀 12 — 기존 OS 조건), 격리 폴더에 `popup/` 생성·남은 파일 0, `data/attachments` 새 파일 0 |
| 성공 기준 2 | #324 수정을 되돌린 `PostCoverImageApiIntegrationTest` 단독 | 빌드 실패, 2,435바이트 PNG 1개 경로·UTC 시각 표시(XML timestamp와 2초 차) |
| 성공 기준 3-1 | `environment` 줄 삭제 | 시험 시작 전 `doFirst` 실패(`APP_FILE_STORAGE_ROOT(null)`) |
| 성공 기준 3-2 | 확인 시험에만 `@TestPropertySource(app.file-storage.root=build/mutant-storage)` | 시험 결과 XML에 단언 실패 |
| F6 | 환경변수만 `build/other-storage`로 | `doFirst` 실패(두 경로 표시) |
| 성공 기준 4 | 셸 `APP_FILE_STORAGE_ROOT=<scratchpad>` | 통과, 셸 경로에는 아무것도 안 생기고 격리 폴더만 사용 |
| 성공 기준 5·F2 | 격리 폴더에 가짜 파일을 둔 뒤 `LocalDiskFileStorageTest`만, 이어서 폴더 없는 상태로 한 번 더 | 두 번 모두 통과(`> Task :test` 실제 실행), 가짜 파일·폴더 삭제됨 |
| 성공 기준 6 | `-PbrowserTests=only --tests` 확인 시험 + `LocalDiskFileStorageTest` | 확인 시험만 결과 XML 생성(태그 포함 동작) |
| 성공 기준 7 일부 | 위 실행들 전후 `%TEMP%\notice-attachment-test*` | 31 → 31(`LocalDiskFileStorageTest` 2회 실행) |
| 성공 기준 8 | 시험 JVM 종료 직전 DB 덤프(조사용 리스너) | `member` 1(시드만)·`notification` 0, `regtest` 0건 |

### 로컬 전체 시험 (코드 리뷰 1라운드 통과 뒤, `master` 반영할 커밋 없음)
| 실행 | 결과 |
|---|---|
| `cleanTest test -PbrowserTests=exclude` | 3,288건 통과(건너뜀 12), 5분 51초 |
| `cleanTest test -PbrowserTests=only` | 205건 통과, 3분 32초 — `TestFileStorageIsolationTest` 포함 |
| 누수 검사 | 두 실행 모두 통과, 격리 폴더 남은 파일 0 |
| 성공 기준 1·7 | `data/attachments` 217 → 217, `%TEMP%
otice-attachment-test*` 24 → 24(실행 사이에 다른 정리로 31에서 줄었다 — 이번 실행 중 증가 없음) |
| 결정 4 비용 | 브라우저 제외 실행: 확인 시험이 기존 컨텍스트를 재사용(새 컨텍스트 기동 로그 없음). 브라우저 전용 실행: 컨텍스트 1개 추가, 기동 2.57초 |

## 이슈
- 구현 중 메인 폴더의 브랜치가 다른 세션에 의해 `master`로 바뀌어 있었다(같은 커밋 `72ec929`라 작업 트리 변경은 그대로). `git checkout test/test-data-leak`으로 되돌렸고, 검증은 같은 커밋·같은 작업 트리에서 돌아 유효하다.

## 후속
- #327 문의 알림 대기 상한 시험의 간헐 실패
- #328 부트스트랩 시험이 시드 `admin`을 다른 ID로 다시 만듦
- #329 `data/attachments`의 옛 시험 잔여 정리(이 PR 머지 후)
