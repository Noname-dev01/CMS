# PLAN — 첨부 확장자 소문자화의 `Locale.ROOT` 고정

> 상태: ✅ 완료 (2026-10-07 · #109 `c01ff79`, PR·master CI `test`·`prod-smoke` success — Linux CI 신규 6케이스 success) — v2 승인(적대적 리뷰 2라운드 ship) → 구현 → 테스트(판별력 확인 포함) → dev Docker 스택 실기(en_US·tr_TR). 결과는 문서 끝 "구현·검증 결과" 참조
> 출처: `PLAN-java-21.md` §8 "후속"(로드맵 27차 갱신의 범위 밖 후속) — `/suggestRoadmap` 2026-10-07 선택
> 유형: fix(로케일 독립성) · 브랜치 `fix/extension-locale-root` · **스키마 변경 없음 · 인가 정책 변경 없음 · 신규 의존성 없음**

## 개정 이력

- v1 (2026-10-07): 최초 작성.
- v2 변경(1라운드 codex): (1) **수용 — 영향 확장자 정정**: v1의 "`I`를 포함한 것은 `gif`뿐"은 착오였다. `zip`도 포함된다. jshell로 화이트리스트 15개를 전수 실측해 `GIF`·`ZIP` 두 개임을 확인했다. 정찰 표·쟁점 3·테스트를 두 입력으로 매개변수화(`@ValueSource`, 기존 선례 `AdminSidebarAdviceTest`)하도록 고쳤다. (2) **수용 — 허용 입력이 줄어드는 변화 명시**: tr/az 기본 로케일에서는 `GİF`·`ZİP`(U+0130)이 `gif`·`zip`이 되어 **지금은 통과**한다. `Locale.ROOT` 적용 후에는 `i̇`(i + U+0307)가 되어 거부된다. 실측 결과 현재 운영(en_US)·로컬(ko_KR)에서도 같은 입력이 거부되므로, 이 변화는 결과를 로케일과 무관하게 맞추는 방향이다. 쟁점 3에 명시하고, tr 로케일에서 이 입력이 거부되는지와 저장 키에 확장자가 붙지 않는지를 테스트로 고정했다.

## 1. Context

`src/main/java`에서 인자 없는 `toLowerCase()`·`toUpperCase()`는 2곳만 남아 있다. 나머지 9곳(`EmailNormalizer`·`MessageBox`·`ProfileImageValidator`·`AdminSearchService`·`ProfileImageMigrationRunner`·`PublicNoticeAttachment` 등)은 이미 `Locale.ROOT`를 쓴다.

| 위치 | 용도 | 결과의 쓰임 |
|---|---|---|
| `NoticeAttachmentService.requireAllowedExtension()` (`:208`) | 업로드 파일명의 마지막 `.` 뒤를 소문자화 | 확장자 화이트리스트(`ALLOWED_CONTENT_TYPES_BY_EXTENSION`) 조회 → 없으면 400, 있으면 Content-Type 검증에 사용 |
| `LocalDiskFileStorage.extractExtension()` (`:345`) | 저장 키에 붙일 확장자 | `[a-z0-9]{1,10}`이 아니면 확장자 없이 저장. 검증에는 쓰지 않음(가독성용) |

인자 없는 `String.toLowerCase()`는 JVM 기본 로케일(`Locale.getDefault()`)의 대소문자 규칙을 따른다.

### 정찰에서 확인한 사실 (2026-10-07)

| 항목 | 확인 결과 | 근거 |
|---|---|---|
| 로케일별 결과 | `"GIF".toLowerCase(tr/az)` = `"gıf"`(U+0131 점 없는 i) → `[a-z0-9]` 불일치. `lt`·`ko-KR`·`en-US`는 `"gif"`. `"PDF"`는 모든 로케일에서 `"pdf"` | jshell(Corretto 21.0.9) 실측 |
| 영향받는 화이트리스트 항목 | **(v2 정정)** 화이트리스트 15개(`pdf doc docx xls xlsx ppt pptx hwp txt csv zip png jpg jpeg gif`) 중 `I`를 포함한 것은 **`gif`·`zip`** → tr/az 기본 로케일에서 대문자 `.GIF`·`.ZIP` 업로드가 "허용되지 않는 파일 형식입니다"(400)로 거부된다. `LocalDiskFileStorage`는 확장자 없이 저장(기능 영향 없음, 키 가독성만) | `NoticeAttachmentService.java:226-246`, jshell 전수 실측(대문자화 → tr 소문자화가 원래와 다른 항목 = `ZIP`·`GIF`) |
| **(v2)** tr/az에서 지금 통과하는 비ASCII 입력 | `GİF`·`ZİP`(U+0130)은 tr/az에서 `gif`·`zip`이 되어 화이트리스트를 **통과**한다. `Locale.ROOT`·`ko_KR`에서는 `gi̇f`(4글자)가 되어 거부된다 | jshell 실측 |
| 현재 런타임 로케일 | 운영 이미지 `LANG=en_US.UTF-8`(PLAN-java-21 정찰) → `en_US`. 로컬 JVM `ko_KR`. `CmsApplication.main()`은 시간대만 고정하고 로케일은 건드리지 않는다. `Dockerfile` ENTRYPOINT에 `-Duser.language` 없음 | `Dockerfile:40`, jshell `Locale.getDefault()` |
| 즉 현재 영향 | **없다**(잠재 결함). 운영 호스트·컨테이너 로케일이 tr/az로 바뀌거나 `JAVA_TOOL_OPTIONS`로 언어를 지정하면 드러난다 | 위 |
| 테스트 실행 방식 | `src/test/resources/junit-platform.properties` 없음 → JUnit 병렬 실행 비활성. `build.gradle` `maxParallelForks=1` → 한 JVM에서 순차 실행. 기본 로케일을 바꾸는 테스트는 **반드시 복원**하면 다른 테스트에 새지 않는다 | `ls src/test/resources`, `src/test/java/CLAUDE.md` |
| 로케일 테스트 선례 | `Locale.setDefault`를 쓰는 테스트 없음. JUnit Pioneer(`@DefaultLocale`) 같은 의존성 없음 | `grep -rn Locale.setDefault src/test/java` |
| 테스트 파급 | `NoticeAttachmentServiceTest`(Mockito 단위, 확장자 테스트 다수 — 모두 소문자 파일명), `LocalDiskFileStorageTest`(순수 단위). 대문자 확장자 테스트는 없다 | `grep` |
| 같은 계열 다른 호출 | `String.format("\\u%04x")`(`AdminActionLogAspect:203`)·`String.format("%02x")`(`ProfileImageUrls:60`)는 로케일 없이 호출하지만 `%x`는 `Formatter` 사양상 지역화가 적용되지 않는다 → 범위 밖 | `java.util.Formatter` Javadoc("No localization is applied" for `'x'`) |

## 2. 핵심 쟁점과 결정

### 쟁점 1 — 고치는 위치

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 두 호출에 `toLowerCase(Locale.ROOT)`** | 프로젝트 기존 관례(나머지 9곳)와 같음. 변경 2줄. 호출 지점에서 의도가 드러남 | 앞으로 새 호출이 같은 실수를 할 수 있음(쟁점 2) |
| B. `main()`에서 `Locale.setDefault(Locale.ROOT)` | 전역 일괄 | 메시지·포맷 등 무관한 동작까지 바뀜. `main()`을 거치지 않는 테스트 JVM에는 적용되지 않음(시각 원천에서 겪은 문제와 같은 구조 — CLAUDE.md "시각 원천은 KST Clock 하나") |
| C. ASCII 전용 소문자화 유틸 | 로케일·Unicode 버전 모두와 무관 | 2곳을 위해 유틸을 새로 만드는 과설계. `Locale.ROOT`로 이미 ASCII 결과가 결정적이다 |

**결정: A.** 기존 관례와 같고 최소 변경이다. B는 범위가 너무 넓고 테스트 JVM에 적용되지 않는 모순이 있다. C는 과설계다.

### 쟁점 2 — 재발 방지(관례 테스트)

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 추가하지 않음** | 최소 변경. 남은 호출은 0곳이 된다 | 새 코드가 인자 없는 호출을 다시 넣을 수 있음 |
| B. `ClockUsageConventionTest`처럼 `src/main/java`를 스캔해 인자 없는 `toLowerCase()`·`toUpperCase()`를 금지하는 테스트 | 재발을 기계적으로 막음 | 요청 범위(2곳 고정) 밖의 새 규칙. 정규식 스캔은 주석·문자열 오탐을 다뤄야 해 Clock 테스트 수준의 복잡도가 붙는다. 이 결함은 tr/az 로케일에서만 드러나고 지금 운영 로케일에서는 영향이 없어 방치 비용이 낮다 |

**결정: A.** 위험이 낮은 잠재 결함 2곳을 위해 새 관례 규칙과 스캐너를 들이는 것은 비용이 크다. 대신 CLAUDE.md 코딩 컨벤션에 "대소문자 변환은 `Locale.ROOT`" 한 줄을 남겨 리뷰 기준으로 삼는다.

### 쟁점 3 — 동작 호환성

- 대상 입력은 파일명 마지막 `.` 뒤의 문자열이다. `ko_KR`·`en_US`에서는 `Locale.ROOT`와 결과가 같다(Unicode 기본 대소문자 매핑을 그대로 쓰고, 언어별 특수 규칙은 tr·az·lt에만 있음). **따라서 현재 런타임에서는 동작 변화가 0이다.**
- tr/az에서는 `.GIF`·`.ZIP` 업로드가 400 → 정상 허용으로 바뀐다. 이는 결함 수정이다.
- **(v2) tr/az에서 허용 입력이 줄어드는 변화**: `GİF`·`ZİP`(U+0130)은 지금 tr/az에서만 통과한다. 변경 후에는 거부되고, 저장 키에도 확장자가 붙지 않는다. 이 입력은 현재 운영(en_US)·로컬(ko_KR)에서 이미 거부되고 있으므로, 결과를 로케일과 무관하게 같게 만드는 의도된 변화로 받아들인다. 정상 사용자가 확장자에 U+0130을 쓸 일은 사실상 없다.
- 비ASCII 우회 검토: `Locale.ROOT` 소문자화로 비ASCII 문자가 ASCII가 되는 경우는 U+212A(켈빈 기호) → `k` 정도다. 화이트리스트에 `k`가 들어간 확장자는 없다. 게다가 이것은 지금 `ko_KR`·`en_US` 기본 로케일에서도 똑같이 일어나는 기존 동작이라 이번 변경이 새로 여는 경로가 아니다. U+0130(İ)은 `i̇`(i + U+0307)가 되어 화이트리스트·정규식에 걸리지 않는다.
- 저장된 데이터: 확장자 소문자화 결과는 DB에 저장되지 않는다(원본 파일명을 저장). 기존 저장 키에도 영향이 없다. 마이그레이션은 필요 없다.

### 쟁점 4 — 테스트 방법

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 테스트 안에서 `Locale.setDefault(tr)` 후 `finally`로 복원** | 의존성 없음. 결함 재현 조건을 정확히 만든다 | JVM 전역 상태 변경 — 복원을 빠뜨리면 다른 테스트에 샌다 |
| B. JUnit Pioneer `@DefaultLocale` | 선언적·자동 복원 | 신규 의존성(CLAUDE.md: 검증되지 않은 의존성은 먼저 제안) |
| C. 테스트 JVM 전체를 `-Duser.language=tr`로 한 번 돌리기 | 전 경로 검증 | CI 구성 변경, 범위 과다 |

**결정: A.** 복원 범위는 `Locale.getDefault()`와 두 카테고리(`DISPLAY`·`FORMAT`)를 모두 저장했다가 되돌린다. `setDefault(Locale)`이 카테고리까지 덮어쓰기 때문이다. JUnit이 병렬로 돌지 않으므로(정찰) 테스트 메서드 안의 `try/finally`면 충분하다. 테스트는 두 클래스에 1건씩 넣으므로 공용 헬퍼를 만들지 않고 각 클래스에 작은 private 헬퍼를 둔다.

추가할 테스트(v2: `GIF`·`ZIP` 매개변수화 + 비ASCII 거부):
1. `NoticeAttachmentServiceTest.upload_uppercaseExtensionUnderTurkishLocale_allowed` — `@ValueSource({"GIF","ZIP"})`. 기본 로케일 `tr`에서 `"FILE." + ext` 업로드(Content-Type `application/octet-stream` — 확장자별 허용 목록과 무관하게 통과하는 기존 규칙)가 성공하고 `fileStorage.store`가 호출된다.
2. `NoticeAttachmentServiceTest.upload_dottedCapitalIExtensionUnderTurkishLocale_rejected` — `@ValueSource({"GİF","ZİP"})`. 기본 로케일 `tr`에서 400(`InvalidRequestException`)이고 `fileStorage.store`가 호출되지 않는다(v2 허용 입력이 줄어드는 변화 고정).
3. `LocalDiskFileStorageTest.store_uppercaseExtensionUnderTurkishLocale_keepsAsciiExtension` — `@ValueSource({"GIF","ZIP"})`. 기본 로케일 `tr`에서 `store(.., "A." + ext)`의 키가 `"." + ext 소문자`로 끝난다.
- 1·3은 **수정 전 코드에서 실패해야 한다.** 2는 수정 전 코드에서 tr 로케일 통과(=업로드 성공)라 역시 실패해야 한다(판별력 확인). 각 테스트 끝에서 `Locale.getDefault()`가 원래 값으로 복원됐는지 단언한다.

### 쟁점 5 — 실기 검증

현재 런타임(ko_KR/en_US)에서는 동작 변화가 없으므로 실기 검증은 두 가지다.
1. **회귀**: dev Docker 스택(기본 로케일 `en_US`)에서 관리자 화면으로 `.pdf`·대문자 `.GIF`·`.ZIP` 첨부 업로드 → 목록 표시 → 다운로드 바이트 일치 → 삭제(원복). 허용되지 않는 확장자(`.exe`)는 400, 비인증 업로드는 401.
2. **결함 수정 실증**: 같은 스택을 `JAVA_TOOL_OPTIONS=-Duser.language=tr -Duser.country=TR`로 띄워(임시 compose override, 커밋하지 않음) 대문자 `.GIF`·`.ZIP` 업로드가 성공하고 저장 키가 `.gif`·`.zip`으로 끝나는지 확인한다. 수정 전 동작(400)은 단위 테스트의 판별력 확인으로 갈음한다(master 이미지를 따로 빌드하지 않음).

## 3. 작업 단계

1. master 작업 트리의 미커밋 로드맵 문서 3개(이전 작업분)는 건드리지 않고 브랜치 `fix/extension-locale-root` 생성(문서 변경은 브랜치로 따라온다).
2. `NoticeAttachmentService:208`·`LocalDiskFileStorage:345`에 `Locale.ROOT`(필요 시 `import java.util.Locale`). `./gradlew compileJava`.
3. 테스트 3개 메서드(매개변수 6케이스) 추가 → 수정 전 코드로 실패 확인(판별력) → 수정 후 통과.
4. `./gradlew test` 전체.
5. 실기 검증(쟁점 5).
6. 기록: CLAUDE.md 코딩 컨벤션 한 줄, 이 계획서 결과, `plan/README.md` 34행, `PLAN-java-21.md` §8 해당 항목에 해소 포인터(이력 본문은 다시 쓰지 않음).

## 4. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| 테스트의 기본 로케일 변경이 다른 테스트로 샘 | 무관한 테스트가 tr 로케일로 돌아 간헐 실패 | `try/finally`로 기본·DISPLAY·FORMAT 모두 복원 + 복원 단언. JUnit 병렬 비활성 확인됨 |
| 향후 인자 없는 대소문자 변환이 다시 들어옴 | 같은 잠재 결함 | CLAUDE.md 컨벤션 한 줄. 관례 테스트는 비용 대비 효과가 낮아 미도입(쟁점 2) |
| 현재 런타임에서 변화가 없어 실기에서 수정 효과가 안 보임 | 검증 착시 | tr 로케일로 띄운 스택에서 실증(쟁점 5-2) |

## 5. 완료 기준

- [x] 두 호출이 `toLowerCase(Locale.ROOT)`이고 `src/main/java`에 인자 없는 `toLowerCase()`/`toUpperCase()`가 0곳이다.
- [x] 신규 테스트(6케이스)가 수정 전 코드에서 실패하고 수정 후 통과한다. 테스트 후 기본 로케일이 복원된다.
- [x] `./gradlew test` 전체 통과.
- [x] 실기: en_US 회귀(업로드·다운로드·거부·401)와 tr 로케일에서 대문자 `.GIF`·`.ZIP` 업로드 성공, 데이터 원복.
- [x] CLAUDE.md·계획 인덱스·`PLAN-java-21.md` §8 포인터 갱신.

## 구현·검증 결과 (2026-10-07)

### Context
첨부 확장자를 소문자로 바꾸는 두 호출이 JVM 기본 로케일을 따라, tr/az 로케일에서 대문자 `.GIF`·`.ZIP` 업로드가 거부되고 있었다. v2 계획대로 `Locale.ROOT`로 고정했다. 브랜치는 `fix/extension-locale-root`이고 커밋·PR 전이다. 직전 작업(PR #108)의 미커밋 로드맵 문서 3개도 이 브랜치 작업 트리에 함께 있으며 이번 변경과 별도로 커밋한다.

### 핵심 확정 사항
- `NoticeAttachmentService.requireAllowedExtension()`과 `LocalDiskFileStorage.extractExtension()`이 `toLowerCase(Locale.ROOT)`를 쓴다. `src/main/java`의 인자 없는 `toLowerCase()`·`toUpperCase()`는 0곳이다.
- 관례 테스트(스캐너)는 넣지 않았다(쟁점 2). 대신 CLAUDE.md 코딩 컨벤션에 한 줄을 남겼다.
- **계획과의 차이(순서만)**: 판별력을 확인하려고 테스트를 운영 코드 수정보다 먼저 추가했다. 결과물은 계획과 같다.

### 구현 파일
- `src/main/java/com/cms/admin/notice/service/NoticeAttachmentService.java` — `Locale.ROOT` + import
- `src/main/java/com/cms/common/storage/LocalDiskFileStorage.java` — `Locale.ROOT` + import
- `src/test/java/com/cms/admin/notice/service/NoticeAttachmentServiceTest.java` — 매개변수 테스트 2개(GIF·ZIP 허용, GİF·ZİP 거부) + 로케일 복원 헬퍼
- `src/test/java/com/cms/common/storage/LocalDiskFileStorageTest.java` — 매개변수 테스트 1개(GIF·ZIP 키 확장자) + 로케일 복원 헬퍼
- `CLAUDE.md` — 코딩 컨벤션 한 줄

### 검증 결과
| 검증 | 결과 |
|---|---|
| 판별력 — 운영 코드 수정 전 신규 테스트 실행 | 신규 6케이스 **전부 실패**(서비스 4: GIF·ZIP 거부됨, GİF·ZİP 통과됨 / 저장소 2: 키에 확장자 없음). 같은 실행에서 기존 테스트는 모두 통과 → 로케일 변경이 다른 테스트로 새지 않음 |
| 수정 후 대상 2클래스 | `NoticeAttachmentServiceTest` 26/0 실패, `LocalDiskFileStorageTest` 32건 실패 0(건너뜀 11은 Windows 링크 테스트) |
| `./gradlew test` 전체(Windows, Testcontainers) | 1374건(기존 1368 + 6), 실패 0, 건너뜀 11 |
| 실기 en_US — dev Docker 스택(현재 브랜치 빌드, `user.language=en`) | `report.pdf`·`IMAGE.GIF`·`ARCHIVE.ZIP` 201 → 다운로드 200 **바이트 일치**, 저장 키 `.pdf`·`.gif`·`.zip`. `malware.exe` 400, `IMAGE.GİF` 400("…: .gi̇f"). 비인증 첨부 목록 401, 비인증 업로드 401. 화면에 첨부 표시(`screenshots/extension-locale-root-01-en-US.png`) |
| 실기 tr_TR — 같은 스택의 앱만 `JAVA_TOOL_OPTIONS=-Duser.language=tr -Duser.country=TR`로 재기동(임시 compose override, 미커밋. `user.language=tr` 확인) | `TR-IMAGE.GIF`·`TR-ARCHIVE.ZIP` **201**, 다운로드 200 바이트 일치, 저장 키 `.gif`·`.zip`(ASCII). `TR-IMAGE.GİF` 400. 화면에 첨부 5개 표시(`screenshots/extension-locale-root-02-tr-TR.png`). 수정 전 동작(400)은 위 판별력 실행으로 갈음 |
| 원복 | 첨부 5개·공지 삭제(204), 첨부 행 0·저장 볼륨 파일 0, dev 스택 종료(볼륨 보존). 스크린샷은 `.gitignore`(`adversarial-review/**/*.png`)로 커밋 제외 |

### 이슈
- 비인증 업로드 확인 때 Git Bash의 curl이 `-F file=@/dev/null`·POSIX 경로를 읽지 못해(오류 26) 응답 코드 `000`이 나왔다. Windows 경로(`pwd -W`)로 다시 보내 401을 확인했다(셸 환경 문제, 앱과 무관).

### 후속
- 없음. 같은 계열의 로케일 없는 `String.format`(`%04x`·`%02x`)은 16진수 변환이라 `Formatter` 사양상 지역화가 적용되지 않아 범위 밖이다(정찰 표).
