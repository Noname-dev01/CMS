# PLAN: SOLID 검토 1번 — 작은 정리 리팩터링

> 상태: **완료(2026-11-26 · #331 `e7f2f4b`)** — 설계 v3(리뷰 2라운드 ship) · 경로: **정식**(2단계 인증 코드 `config/auth`를 건드림)
> 위험 변경: **2단계 인증**(`TotpLoginService`·`PasskeyLoginService`의 재확인), **파일 저장**(`common/storage/FileStorage` 계약), **공개 경로**(공개 이미지 `open` 3곳) — 셋 다 동작 보존 리팩터링이다.

## 개정 이력

- v1 (2026-11-26): 최초 작성.
- v2 변경 (리뷰 1라운드, 3건 모두 수용): ① KST 성공 기준을 "`src/main/java`·규칙 문서의 실제 참조 0건"으로 좁히고 `ClockUsageConventionTest`의 검사기 입력 문자열을 명시적 예외로 둠(기준과 음성 시험이 모순이었음). ② F2 고정 시험 정정 — 인용한 스트리밍 시험은 `open()` 예외 경로를 검증하지 않음. 배너·팝업의 "행 있음·파일 없음 → 404"는 `BannerApiIntegrationTest:363`·`PopupApiIntegrationTest:521`이 이미 고정하므로 그것으로 바꾸고, 공백이던 **본문 이미지 원본(루트) 파일 없음 → GET·HEAD 404** 시험을 새로 추가. 실기 계획의 404 확인도 "행·참조는 있고 물리 파일만 없음"으로 구체화(없는 ID는 열기 전에 404라 이 경로를 못 봄). ③ 관련 시험 명령을 패턴마다 `--tests` 반복으로 수정(Gradle 8.14.8, `gradle/wrapper/gradle-wrapper.properties:3`).
- v3 변경 (리뷰 2라운드 **ship**, 경미 1건 수용): 설계 결정 3의 import 정리 대상을 실제 미사용인 `GrantedAuthority`로 한정 — `Objects`는 패스키 소유자 비교에 계속 쓰인다.

## 사용자 결정 (grilling, 2026-11-26)

| # | 결정 | 이유 |
|---|---|---|
| Q1 | 루트/네임스페이스 분기 통합은 **읽기 8곳**(관리자 `load` 5 + 공개 `open` 3) | 같은 중복을 한 번에 없앤다. 저장·삭제는 각 1곳이라 중복이 아니고 트랜잭션 정리(`FileStorageTransactionSupport`)와 얽혀 범위 밖 |
| Q2 | `FileStorage` 네임스페이스 메서드 4개의 **기본 구현(예외 던짐) 제거 → 추상 메서드** | "네임스페이스를 조용히 무시하지 않는다"는 보장이 실행 중 예외에서 컴파일 오류로 앞당겨진다. 구현체는 하나이고 이미 4개 모두 재정의 |
| Q3 | KST 상수는 **`com.cms.common.time.AppTimeZone.KST` 하나**, `AppConfig.KST` 제거 | `common` → `config` 의존 제거, 같은 값의 이름은 하나 |
| Q4 | `matchesLogin()` → **`CustomUserDetails.matchesCurrent(Member)`** | 로그인 당시 상태를 담는 객체라 의미가 맞고 이미 `Member`를 import한다(새 의존 없음). `Member`에 두면 `admin.member → config` 의존이 생긴다 |
| Q5 | 헬퍼는 **static 유틸** `admin/contentimage/service/ContentImageFiles` | 호출자의 빈 주입과 `FileStorage` 목 기반 시험(`BannerServiceTest` 등)을 그대로 둔다 |
| Q6 | "빈 문자열이면 루트" 판정식은 **`ContentImageFiles.isRoot(ns)` 하나** — 저장·삭제는 분기 코드는 두고 판정식만 교체. 쓰이지 않게 되는 `isRootNamespace()`는 삭제 | 규칙 정의가 5곳에 흩어져 있다 |

## Context

2026-11-25 SOLID 검토(대화)에서 나온 개선 항목 중 "작은 정리" 묶음이다. 출처 목록 통합(`MediaService.LIBRARY_SCOPES` ↔ `ContentImageService.isLibraryScope`)은 3번 작업(`ImageUsageProvider`)에 포함하므로 여기서 다루지 않는다. 관련 GitHub 이슈 없음(`FileStorage`·`matchesLogin`·`SOLID` 검색).

### 정찰 사실 (기준 커밋 `72ec929`)

**① 루트/네임스페이스 분기 — 10곳**

| 종류 | 위치 | 현재 판정식 |
|---|---|---|
| `load` | `BannerService:260`·`PopupService:258` | `MediaImageInfo.isRootNamespace()` (null·빈 값 = 루트) |
| `load` | `MediaService:136`·`MediaImageSelector:50`·`ContentImageThumbnailService:107` | `ContentImage.isRootNamespace()` (null·빈 값 = 루트) |
| `open` | `PublicBannerService:59`·`PublicPopupService:62` | `ref.namespace().isEmpty()` (null이면 NPE) |
| `open` | `PublicContentImageService:80` | `ns == null \|\| ns.isEmpty()` |
| 저장(범위 밖, 판정식만) | `MediaImageRegistrar:48` | `namespace == null \|\| namespace.isEmpty()` |
| 삭제(범위 밖, 판정식만) | `MediaImageRemover:69` | `ContentImage.isRootNamespace()` |

- 공개 배너·팝업의 null 처리 차이는 실제 동작 차이가 아니다: 값의 출처가 `content_image.namespace`이고 이 컬럼은 `NOT NULL`(`ContentImage.java:62` `nullable = false`, 기본값 `""`)이다. 헬퍼는 null도 루트로 본다.
- **헬퍼를 `FileStorage` 안에 두지 않는다**: `LocalDiskFileStorage.validateNamespace`(:288)는 빈 문자열을 `IllegalArgumentException`으로 거부한다. 빈 값이 실수로 넘어와도 루트에 섞이지 않게 막는 장치이며, 저장소가 빈 값을 루트로 받아 주면 이 방어가 약해진다. "빈 문자열 = 루트"는 `content_image` 도메인의 저장 형식(R4-1 — UNIQUE가 NULL 중복을 허용해 빈 문자열을 쓴다)이므로 그 도메인이 소유한다.
- 공개 `open` 3곳의 호출자는 `StorageFileNotFoundException`을 잡아 404(`Optional.empty()`)로 바꾼다. 헬퍼는 예외를 그대로 전파하므로 이 매핑은 바뀌지 않는다.

**② `matchesLogin()`** — `TotpLoginService:212`와 `PasskeyLoginService:150`이 글자 그대로 같다(private static). 시험 고정: `TotpLoginServiceIntegrationTest` "FM4"(상태·역할·해시 각각 불일치 → REJECTED), `PasskeyLoginIntegrationTest` "F2"(잠김·비활성·변경 강제). 패스키 단독 로그인 쪽은 역할·해시 축의 직접 시험이 없다 → 새 단위 시험으로 세 축을 직접 고정한다. `CustomUserDetails`의 하위 클래스는 없다(`extends CustomUserDetails` 검색 0건). `getAuthorities()`는 `member.getUserType().name()` 하나만 돌려준다(`CustomUserDetails:58`). 성공 처리기의 재확인(`VisitLoggingAuthenticationSuccessHandler.verifyFreshMemberState`)은 입력이 `MemberSnapshot`이고 중간 상태 토큰 처리가 달라 합치지 않는다.

**③ KST** — `AppConfig.KST` 참조: `AppConfig` 자신(:21), `ApiErrorResponse:18`, 시험 `PasskeyLoginTestConfig:32`, `ClockUsageConventionTest` 허용 목록(:53 문자열 `"AppConfig.KST"`)과 자기 시험(:96), 루트 `CLAUDE.md:70`. `CmsApplication.main()`의 `TimeZone.setDefault`는 별개 선언으로 남긴다(PLAN-clock-unification.md에서 수용한 한 줄 중복 — 범위 밖).

**LSP** — `FileStorage`의 `store/load/delete/open(…, namespace)` 4개가 `default`로 `UnsupportedOperationException`. 구현체는 `LocalDiskFileStorage` 하나이며 4개 모두 `@Override`. 이 기본 구현을 시험하는 것은 `LocalDiskFileStorageTest.defaultNamespaceMethods_throwUnsupportedOperationException`(:238) 하나다. 그 밖의 `FileStorage` 대역은 Mockito 목뿐이라 추상 메서드 전환의 영향을 받지 않는다(익명 구현은 위 시험 하나 — `new FileStorage()` 검색).

## 설계 결정

1. **`ContentImageFiles`** (`com.cms.admin.contentimage.service`, `final` 클래스·private 생성자)
   - `static boolean isRoot(String namespace)` — `namespace == null || namespace.isEmpty()`
   - `static byte[] load(FileStorage storage, String storageKey, String namespace)` — 루트면 `load(key)`, 아니면 `load(key, ns)`
   - `static StoredFileStream open(FileStorage storage, String storageKey, String namespace)` — 같은 규칙
   - 예외는 감싸지 않는다(호출자의 `StorageFileNotFoundException` → 404 매핑 유지).
   - 선택지: (A) static 유틸 ✅ / (B) 빈으로 감싸기 — 호출자 8곳 의존과 목 시험 구성이 바뀌어 기각 / (C) `FileStorage` 기본 메서드 — 위 "헬퍼를 `FileStorage` 안에 두지 않는다"로 기각.
2. **판정식 교체**: `MediaImageRegistrar:48`·`MediaImageRemover:69`는 `ContentImageFiles.isRoot(...)`만 쓰고 분기·저장·커밋 후 삭제 코드는 그대로. `ContentImage.isRootNamespace()`·`MediaImageInfo.isRootNamespace()`는 호출자가 없어져 삭제한다(시험 참조 0건 확인).
3. **`CustomUserDetails.matchesCurrent(Member current)`** — 기존 본문을 그대로 옮긴다(상태 ACTIVE ∧ 이 principal의 첫 권한 = `current.getUserType().name()` ∧ `Objects.equals(current.getPwd(), getPassword())`). 의미를 바꾸는 단순화(예: 필드 직접 비교)는 하지 않는다 — 동작 보존을 구조로 보장한다. 두 서비스는 `principal.matchesCurrent(member)`를 부르고 private 메서드를 지운다(실제로 쓰지 않게 되는 import만 정리 — `GrantedAuthority`. `Objects`는 패스키 소유자 비교(`TotpLoginService:199`·`PasskeyLoginService:145`)에 남는다).
4. **`AppTimeZone`** (`com.cms.common.time`, `final`·private 생성자, `public static final ZoneId KST = ZoneId.of("Asia/Seoul")`). `AppConfig.clock()`은 `Clock.system(AppTimeZone.KST)`. `AppConfig.KST` 필드 삭제. `ClockUsageConventionTest` 허용 인자를 `"AppTimeZone.KST"`로 바꾸고, 자기 시험에 "옛 이름 `AppConfig.KST`는 이제 위반" 사례를 더한다.
5. **`FileStorage` 네임스페이스 메서드 4개를 추상 메서드로** — Javadoc의 "기본 구현은 안전한 실패" 단락을 "모든 구현체가 네임스페이스 격리를 제공해야 한다"로 바꾼다. 기본 구현 시험 1건 삭제. 옛 주석의 존재하지 않는 `NoticeAttachmentService` 언급(`FileStorage:5`)은 이 파일을 고치는 김에 `PostAttachmentService`로 바로잡는다(같은 파일·같은 Javadoc 단락).

**과설계 경계**: 저장·삭제 헬퍼, `isRoot`의 enum화, 성공 처리기 재확인 통합은 하지 않는다.

## 성공 기준

- [x] 읽기 8곳이 모두 `ContentImageFiles.load/open`을 쓰고, `src/main/java`에 `isRootNamespace` 정의·호출과 `namespace().isEmpty()` 형태의 판정이 남지 않는다(`ContentImageFiles` 제외, grep으로 확인).
- [x] `matchesLogin` 정의가 0건이고 `matchesCurrent` 정의가 `CustomUserDetails`에 1건이다.
- [x] `src/main/java/com/cms/common`에 `import com.cms.config` 0건, `src/main/java`·`src/test/java`의 코드 참조와 `CLAUDE.md`류 규칙 문서에 `AppConfig.KST` 0건. **예외**: `ClockUsageConventionTest` 자기 시험의 검사기 입력 문자열(옛 이름이 위반으로 잡히는지 보는 음성 사례)과 `adversarial-review/**` 이력 문서.
- [x] `FileStorage`에 `default` 메서드 0건.
- [x] 관련 시험 + 로컬 전체 시험(`./gradlew test -PbrowserTests=exclude`, 공용 코드 변경) 통과.
- [x] 실기: 2단계 로그인·배너/팝업/미디어 미리보기·공개 배너/팝업/본문 이미지가 이전과 같게 동작.

## 실패 모드와 고정 시험

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 헬퍼 분기가 뒤집히거나 빈 값을 네임스페이스로 넘김(→ `IllegalArgumentException` 500) | 새 `ContentImageFilesTest`(목 `FileStorage`): `""`·`null` → `load(key)`/`open(key)`만, `"banner"` → `load(key,"banner")`/`open(key,"banner")`만 호출. 기존 `MediaLibraryApiIntegrationTest`(루트·banner·popup 왕복, :481 banner 이미지의 공개 본문 노출) |
| F2 | 공개 경로에서 파일 없음이 404가 아니라 500이 됨(헬퍼가 예외를 감쌈) | `ContentImageFilesTest`: `StorageFileNotFoundException`이 그대로 전파. 기존 `BannerApiIntegrationTest.publicImage_missingFile_404`(:363, banner 네임스페이스)·`PopupApiIntegrationTest.publicImage_missingFile_404`(:521, popup)·`PublicContentImageThumbIntegrationTest.missingThumbnailFile`(:186, thumb). **새 시험**: `PublicContentImageThumbIntegrationTest`에 "공개 글이 참조하는 본문 이미지의 원본(루트) 파일만 지우면 GET·HEAD 모두 404" — 루트 분기의 `open` 예외 경로 공백을 메운다 |
| F3 | `matchesCurrent`로 옮기며 축 하나(상태·역할·해시)가 빠지거나 뒤집힘 → 바뀐 계정이 2단계·패스키 로그인 통과 | 새 `CustomUserDetailsTest`: 일치 → true, 상태 LOCKED/DISABLED/PASSWORD_EXPIRED·역할 변경·해시 변경 각각 → false. 기존 `TotpLoginServiceIntegrationTest` FM4, `PasskeyLoginIntegrationTest` F2 |
| F4 | `FileStorage` 추상화로 숨은 구현·대역이 컴파일 실패 또는 Spring 컨텍스트 실패 | `compileTestJava` + 로컬 전체 시험 |
| F5 | KST 이동 뒤 컨벤션 시험이 옛 이름을 허용하거나 새 이름을 위반으로 봐 우회·오탐 | `ClockUsageConventionTest`: `now(AppTimeZone.KST)` 허용, `now(AppConfig.KST)` 위반 사례 추가, 본 검사 통과 |

## 작업 단계

1. 브랜치 `refactor/solid-small-cleanup`.
2. ③ KST: `AppTimeZone` 추가 → `AppConfig`·`ApiErrorResponse`·`PasskeyLoginTestConfig` → `ClockUsageConventionTest`(먼저 위반 사례를 추가해 실패 확인) → `compileJava`.
3. LSP: `LocalDiskFileStorageTest`의 기본 구현 시험 삭제 → `FileStorage` 추상화·Javadoc → `compileTestJava`.
4. ① 헬퍼: `ContentImageFilesTest`(실패) → `ContentImageFiles` → 읽기 8곳 교체 → 판정식 2곳 교체 → `isRootNamespace()` 2개 삭제 → `compileJava`.
5. ② 재확인: `CustomUserDetailsTest`(실패) → `matchesCurrent` → 두 서비스 교체.
6. 관련 시험(Gradle 8.14.8 — 패턴마다 `--tests`를 반복한다, Gradle 사용자 가이드 "Test filtering"):
   `./gradlew test -PbrowserTests=exclude --tests 'com.cms.admin.media.*' --tests 'com.cms.admin.banner.*' --tests 'com.cms.admin.popup.*' --tests 'com.cms.admin.contentimage.*' --tests 'com.cms.admin.board.PostCoverImageApiIntegrationTest' --tests 'com.cms.publicweb.banner.*' --tests 'com.cms.publicweb.popup.*' --tests 'com.cms.publicweb.contentimage.*' --tests 'com.cms.publicweb.support.*' --tests 'com.cms.config.auth.*' --tests 'com.cms.common.storage.*' --tests '*ConventionTest'` (`CustomUserDetailsTest`는 `com.cms.config.auth.*`에 포함).
7. 문서: 루트 `CLAUDE.md`(`AppConfig.KST` → `AppTimeZone.KST`), `common/storage/CLAUDE.md`(네임스페이스 메서드는 추상 — 구현체 필수), `admin/contentimage/CLAUDE.md`(루트 판정·읽기는 `ContentImageFiles`), `config/CLAUDE.md`(재확인은 `CustomUserDetails.matchesCurrent`).
8. 코드 리뷰 루프 → 최신 `master` 반영 → 로컬 전체 시험 1회(백그라운드) → 실기 검증.

## 실기 검증 계획

`bootRun`(dev) 뒤 Playwright로: ① ADMIN 비밀번호 + TOTP 코드 로그인 → 대시보드 ② 배너·팝업 관리 화면의 이미지 미리보기 ③ 미디어 라이브러리 상세 미리보기(루트·banner 네임스페이스 각 1건) ④ 공개 메인의 배너·팝업 이미지와 공개 게시글 본문 이미지 응답 200 ⑤ **공개 조건을 만족하는 행·참조는 그대로 두고 물리 파일만 옮겨 둔** 배너 이미지와 본문 이미지의 GET 404, 파일을 되돌린 뒤 200(없는 ID는 파일을 열기 전에 404가 되므로 이 경로를 확인하지 못한다). 화면 변경이 없으므로 스크린샷은 대표 1~2장만 남긴다. 패스키 경로는 dev 설정에서 켜져 있으면 확인하고, 아니면 시험 결과로 갈음하고 그 사실을 적는다.

## 범위 밖

- 출처 목록 통합(3번 작업), 저장·삭제 분기 헬퍼, `CmsApplication`의 `TimeZone.setDefault` 상수화, 성공 처리기 재확인 통합, 패키지 순환(`config` ↔ `admin.member`) 해소.

---

## 구현 결과 (2026-11-26, 브랜치 `refactor/solid-small-cleanup`)

### 핵심 확정 사항

설계 결정 1~5를 그대로 구현했다. 계획과 달라진 결정은 없다. 작업 공간만 바뀌었다: 메인 체크아웃(`master`)에서 다른 세션이 시험 데이터 누수 작업(`PLAN-test-data-leak.md`)을 진행 중이어서, 사용자 승인을 받아 Orca worktree(`orca/workspaces/CMS/solid-small-cleanup`, 기준 `origin/master` `72ec929`)에서 구현했다. 그 세션도 `LocalDiskFileStorageTest`를 고치고 있어(임시 폴더를 `@TempDir`로) 나중에 머지하는 쪽이 작은 충돌을 풀어야 한다.

### 구현 파일

- 새 파일: `common/time/AppTimeZone`, `admin/contentimage/service/ContentImageFiles`, 시험 `ContentImageFilesTest`(실제 `LocalDiskFileStorage`로 4건)·`CustomUserDetailsTest`(7건).
- ③ KST: `AppConfig`(필드 제거, `Clock.system(AppTimeZone.KST)`), `ApiErrorResponse`, 시험 `PasskeyLoginTestConfig`·`ClockUsageConventionTest`(허용 인자 `AppTimeZone.KST` + 옛 이름 위반 사례).
- LSP: `FileStorage`(네임스페이스 메서드 4개 추상화, Javadoc 정정 — 존재하지 않던 `NoticeAttachmentService` 언급도 `PostAttachmentService`로), `LocalDiskFileStorageTest`(기본 구현 시험 1건 삭제).
- ① 읽기 8곳: `BannerService`·`PopupService`·`MediaService`·`MediaImageSelector`·`ContentImageThumbnailService`(load), `PublicBannerService`·`PublicPopupService`·`PublicContentImageService`(open). 판정식 2곳: `MediaImageRegistrar`·`MediaImageRemover`. 삭제: `ContentImage.isRootNamespace()`·`MediaImageInfo.isRootNamespace()`. 새 시험: `PublicContentImageThumbIntegrationTest.missingRootOriginalFile`.
- ② 재확인: `CustomUserDetails.matchesCurrent`, `TotpLoginService`·`PasskeyLoginService`(private `matchesLogin` 제거, 미사용 `GrantedAuthority`·`MemberStatus` import 정리).
- 문서: 루트 `CLAUDE.md`, `common/storage`·`admin/contentimage`·`config`의 `CLAUDE.md`.

### 검증 결과

- **TDD**: ③ 컨벤션 시험 자기 시험 실패 → 구현 후 통과. ① `ContentImageFilesTest` 컴파일 실패(클래스 없음) → 구현 후 4건 통과. ② `CustomUserDetailsTest` 컴파일 실패 → 구현 후 7건 통과. LSP는 컴파일 보장이라 `compileJava`·`compileTestJava`로 확인.
- **변이 실험(F2)**: `ContentImageFiles.open`이 예외를 `IllegalStateException`으로 감싸게 바꾸자 3건 실패(`ContentImageFilesTest.missingFilePropagatesNotFound`, 새 `missingRootOriginalFile`, 기존 `missingThumbnailFile`) → 원복 확인.
- **관련 시험**(작업 단계 6의 명령): 77개 클래스·561건, 실패 0·건너뜀 12(운영체제 조건 등).
- **코드 리뷰**: 기본 리뷰 1회·위험 변경 적대적 리뷰 1회(F1~F5 초점, approve) 모두 지적 0건.
- **로컬 전체 시험**(`./gradlew test -PbrowserTests=exclude`, 코드 리뷰 뒤 — `origin/master`에 새 커밋이 없어 반영 전후 기준이 같다): 317개 클래스·3,298건, 실패 0·건너뜀 12.
- **성공 기준 검색**: `common` → `config` import 0건, `FileStorage` `default` 0건, `matchesLogin` 0건·`matchesCurrent` 정의 1건, `isRootNamespace` 0건, `AppConfig.KST`는 예외로 둔 검사기 음성 사례 1건뿐.
- **실기**(일회용 MariaDB `:3397` + bootRun `:8083`, 파일 저장소는 scratchpad — dev DB 미사용):
  - ADMIN 강제 등록 → 재로그인 → 비밀번호 → 코드 화면 → 코드 → `/admin` 대시보드(`TotpLoginService.verify` → `matchesCurrent` 성공 경로).
  - **거부 경로**: 코드 화면에 있는 동안 DB에서 비밀번호 해시를 바꾸고 올바른 코드를 제출 → `/admin/login-error`, 세션 없음(`/admin` → 로그인), `failed_login_count`·`member_totp.failed_attempts` 모두 0 그대로(REJECTED는 세지 않음). 해시 원복 확인.
  - 라이브러리 업로드(루트)·배너 직접 업로드(banner)·배너의 라이브러리 이미지 선택(`MediaImageSelector` 루트 load)·팝업 이미지형(popup)·게시판 본문 이미지 + 공개 글 + 대표 이미지(썸네일 서비스의 원본 load — `content_image_thumb` 행·`thumb/` 파일 생성 확인) 모두 201.
  - 관리자 미리보기 5건(배너 직접·배너 라이브러리·팝업·미디어 루트·미디어 banner) 200, 공개 6건(배너 둘·팝업·본문 원본 GET/HEAD·썸네일) 200.
  - 행·참조는 두고 물리 파일만 옮김: 공개 배너 GET/HEAD 404, 공개 본문 원본 GET/HEAD 404(썸네일은 200 그대로) → 파일 원복 뒤 200.
  - 스크린샷 1장(미디어 라이브러리 — 루트·배너·팝업·게시판 본문 네 영역 이미지 표시).
  - **패스키 경로는 실기로 확인하지 않았다**(가상 인증기 준비가 필요) — `PasskeyLoginIntegrationTest`·`TotpPasskeyLoginIntegrationTest`를 포함한 `com.cms.config.auth.*` 시험 통과와 `CustomUserDetailsTest`로 갈음.
- 정리: 브라우저 종료, bootRun PID 63076 대조 후 종료, 일회용 DB 정지(`--rm`), 이 세션의 MCP 스냅샷·콘솔 파일 삭제.

### 이슈

- 다른 세션과 같은 작업 디렉터리를 쓰다가 브랜치 전환이 그 세션의 작업 트리를 함께 옮겼다 → 즉시 `master`로 되돌리고 Orca worktree로 옮김(그 세션 파일은 건드리지 않음).
- `gradlew` 출력을 `grep`할 때 한글이 cp949로 나와 "Binary file matches"로 가려짐 → `grep -a`로 해결(측정 스크립트의 환경 문제, 제품 무관).

### 후속

- 시험 보조 코드 `MediaTestSupport:31`의 같은 판정식(`namespace == null || namespace.isEmpty()`)도 사용자 결정(2026-11-26)으로 이번 PR에서 `ContentImageFiles.isRoot`로 통일했다(분기·삭제 호출은 그대로, `support/**` 변경이라 로컬 전체 시험으로 검증).
