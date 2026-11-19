# PLAN — CI browser-test의 Playwright 폰트(apt) 설치 시간 편차 줄이기 (이슈 #283)

> 상태: v5 (적대적 리뷰 5라운드 ship) · 구현·검증 완료(2026-11-20, §9) · 브랜치 `chore/ci-playwright-apt-cache` · 출처: `PLAN-passkey-login.md` "후속"

## 1. Context

CI `browser-test` job의 `Install Playwright browsers` 단계(`./gradlew installPlaywrightBrowsers` → `playwright install --with-deps chromium`)는 러너에 배정된 apt 미러의 속도에 따라 걸리는 시간이 크게 달라진다.

**실측 (러너 이미지 `ubuntu-26.04` 버전 `20261111.82.6`, 2026-11-19, 두 실행의 job 로그):**

| 구간 | 빠른 실행 run 44476953408 (azure 미러) | 느린 실행 run 44486452235 (us-east-2.ec2 미러) |
|---|---|---|
| Gradle 기동+컴파일(이 단계 안) | 약 31초 | 약 37초 |
| `apt-get update` | 1초 (4.2MB) | 약 19초 (3.5MB, 410kB/s) |
| 폰트 9개 다운로드 21.9MB | 1초 (37.8MB/s) | **2분 59초 (122kB/s)** |
| Chromium·headless shell·ffmpeg (Playwright CDN) | 몇 초 | 약 8초 |
| 단계 전체 | 44초 | 4분 17초 |

- apt가 **새로 설치한 것은 폰트 9개뿐**이다: `fonts-ipafont-gothic`·`fonts-freefont-ttf`·`fonts-tlwg-loma-otf`·`fonts-unifont`·`fonts-wqy-zenhei`·`xfonts-encodings`·`xfonts-utils`·`xfonts-cyrillic`·`xfonts-scalable`. 공유 라이브러리(`libnss3` 등)는 모두 "already the newest version"이었다. 두 실행에서 변동의 원인은 **미러에서 폰트 deb를 받는 구간**이었다(두 표본이라 앞으로의 변동 원인을 이것 하나로 단정하지는 않는다 — `apt-get update`도 미러에 묶인다).
- 덤으로 찾은 것: `Run browser tests` 단계에서 `Playwright.create()`가 **Firefox·WebKit을 추가로 내려받는다**(약 6초, 그리고 WebKit 의존성 누락 경고 "Host system is missing dependencies"). 시험은 Chromium만 쓴다(`BrowserTestSupport` 82행 `playwright.chromium().launch(...)`).

## 2. 설계 제약

- 폰트를 빼지 않는다. `HomeThumbFallbackBrowserTest`·`HomeNarrowLayoutBrowserTest`·`BoardThumbFallbackBrowserTest`·`MediaPickerNestedModalBrowserTest`가 한글 텍스트의 폭(`getBoundingClientRect`)과 요소 스크린샷을 단언한다. 한글 글꼴(`fonts-wqy-zenhei`·`fonts-unifont`)이 없으면 글자가 대체 상자로 그려져 시험 의미가 달라진다(러너 기본 이미지에 한글 글꼴이 있는지는 미확인 — 이 결정에는 영향 없음).
- 새 서드파티 액션·의존성을 추가하지 않는다. 이미 쓰는 `actions/cache@v6` 저장소의 하위 액션(`actions/cache/restore@v6`·`actions/cache/save@v6`)만 쓴다.
- Playwright가 정하는 의존성 목록(`--with-deps`)은 그대로 둔다. Playwright를 올려 목록이 바뀌어도 따라가게 하려는 것이다.
- **캐시는 신뢰하지 않는다.** 복원한 deb는 이번 실행의 `apt-get update`가 받은 서명된 색인과 대조해 통과한 것만 apt에 넘긴다(결정 1).
- 로컬 실행(Windows·macOS 첫 실행 자동 설치)은 바꾸지 않는다.

## 3. 쟁점과 결정

### 결정 1 — 대응 방식: **검증하는 apt deb 캐시 (A)**

| 방식 | 효과 | 단점 |
|---|---|---|
| **A. apt deb 캐시** — Playwright가 받은 `.deb`를 `actions/cache`에 두었다가, 다음 실행에서 색인과 대조한 뒤 `/var/cache/apt/archives/`로 넣는다. `--with-deps`는 그대로 | 캐시가 맞으면 폰트를 미러에서 받지 않는다. 미러에 남는 것은 `apt-get update`(3~4MB, 두 번)뿐이다 | 단계와 스크립트가 는다. 캐시가 맞을 때 `apt-get update`가 한 번 더 돈다 |
| B. 필요한 폰트만 설치 — `install chromium`(의존성 없이) + 한글 폰트 하나만 apt | 받는 양이 21.9MB에서 약 8MB(`wqy-zenhei`)로 준다 | 여전히 미러 속도에 묶인다(122kB/s면 약 1분). Playwright 의존성 목록을 따라가지 않게 되어, 러너 이미지에서 라이브러리가 빠지면 깨진다 |
| C. 미러 지정 — `/etc/apt/apt-mirrors.txt`를 azure 미러로 고정 | 미러가 빠르면 다 빠르다 | 러너 이미지 내부 구성에 기댄다. 고정한 미러가 느려지면 그대로 느리다 |
| D. 재시도 | 실패에는 효과가 있다 | 느린 것은 실패가 아니라 그냥 느린 것이라 효과가 없다 |

**왜 A인가**: 관측된 원인 구간(미러에서 폰트 받기)을 구조적으로 없애는 방식은 A뿐이다. B·C는 미러 속도에 덜 또는 다르게 묶일 뿐이다. B·C·D는 구조상의 이유로 기각하고 실측하지 않는다. A의 효과는 기존 실행(기준선)과 실험 실행의 로그를 비교해 측정한다(§6).

**전제와 출처:**
- `--with-deps`는 Linux에서 root로 `apt-get update`와 `apt-get install -y --no-install-recommends <목록>`을 실행한다 — 출처: `com.microsoft.playwright:driver:1.65.0` jar의 `driver/package/lib/coreBundle.js`(33789~33796행 `commands2.push("apt-get update")` …), 느린 실행 로그 "Switching to root user to install dependencies...".
- `apt-get`이 받은 deb를 `archives/`에 남기는지, 정리 훅(`APT::Update::Post-Invoke`·`DPkg::Post-Invoke`·`Keep-Downloaded-Packages`)이 있는지는 **미확인**이다. 실험 1회차에서 `apt-config dump`의 해당 줄과, 투입 직후·설치 뒤의 파일 수로 확인한다. 남지 않으면 원인(보존 옵션/훅)에 맞춰 설계를 바꾸고 보고한다.
- `apt-get install`은 `archives/`에 같은 파일 이름의 deb가 있으면 다시 받지 않는다. 다만 **이때 내용 해시를 검증한다고 기대하지 않는다**(1라운드 리뷰 — APT 3.0.3 `pkgAcqArchive`에 크기만 대조하는 경로가 있음). 그래서 투입 전에 직접 검증한다.

**투입 전 검증 (`scripts/ci/playwright-apt-cache.sh stage`):**
1. 복원된 deb가 없으면 아무것도 하지 않는다(캐시 미스는 지금과 같은 동작).
2. 있으면 `sudo timeout 120 apt-get update`를 한 번 돌린다(내부 제한은 환경변수 `APT_CACHE_UPDATE_TIMEOUT`으로 바꿀 수 있다 — 실험용, 기본 120초). 실패하거나 시간 초과면(종료 코드 ≠ 0) 투입을 건너뛰고 경고만 남긴다(지금과 같은 동작으로 돌아감). `timeout`이 apt를 끝내면 apt 잠금(`fcntl` 잠금)은 프로세스 종료와 함께 풀린다 — 뒤이은 Playwright 설치가 잠금 대기 없이 진행되는지는 실험 F6으로 확인한다.
   - 검증 기준은 "apt가 서명을 확인해 저장한 로컬 색인"이다. 일시적인 네트워크 오류로 update가 일부 색인을 갱신하지 못해도(APT 기본 오류 모드에서는 경고로 끝날 수 있음 — 2라운드 리뷰), 옛 색인 역시 서명이 확인된 것이다. 옛 색인 때문에 생기는 최악은 **캐시를 못 쓰는 것**이지 잘못된 설치가 아니다. 뒤이은 Playwright의 `apt-get update`가 다시 갱신한 색인으로 후보를 정하고, 버전이 다르면 파일 이름이 달라 apt가 새로 받는다. Ubuntu 아카이브에서는 같은 패키지·버전의 내용이 바뀌지 않는다. 그래서 `APT::Update::Error-Mode`는 쓰지 않는다.
3. deb마다 아래를 확인한다. 하나라도 어긋나면 **그 파일만** 버리고 이유를 로그에 남긴다(스크립트는 캐시 문제로 실패하지 않는다 — 파일별 오류는 모두 "버림"으로 처리):
   - `dpkg-deb -f <파일> Package Version Architecture`가 성공하고 세 값이 모두 비어 있지 않다(절단·헤더 손상·필드 누락 → 버림. `dpkg-deb -f`는 없는 필드에 오류를 내지 않으므로 빈 값을 따로 검사한다)
   - `Version`이 `apt-cache policy <패키지>`의 후보(Candidate) 버전과 같다(후보 없음 → 버림)
   - 파일의 `sha256sum`이 `apt-cache show <패키지>=<버전>`의 `SHA256`과 같다(SHA256 없음 → 버림)
4. 통과한 파일은 **원래 파일명을 쓰지 않고**, 검증한 값으로 apt 규칙의 이름 `<패키지>_<버전>_<아키텍처>.deb`(버전의 `:`는 `%3a`)를 만들어 `archives/`에 복사한다(임시 이름으로 복사한 뒤 `mv` — 단계가 중간에 끊겨도 그 이름의 반쪽 파일이 남지 않게). 이름만 바꾼 다른 패키지가 그 이름 자리에 들어가는 것을 막는다(2라운드 리뷰 — apt는 그 이름의 파일이 있고 크기가 같으면 다시 받지 않을 수 있다).
5. 넣은 개수·버린 개수(이유별)를 출력한다.

**회수 (`scripts/ci/playwright-apt-cache.sh collect`):** `.ci-apt-cache/`를 비운 뒤, `archives/*.deb` 중 **지금 설치된 버전과 같은 것**(`dpkg-query -W -f='${Version}' <패키지>`)만 복사한다. 그래서 옛 버전이 쌓이지 않는다. 이때 apt가 실제로 쓴 파일 이름과 4의 규칙으로 만든 이름이 같은지 대조해, 다르면 경고한다(이름 규칙 전제 확인 — 다르면 다음 실행의 투입이 효과가 없다). 개수를 단계 출력으로 넘긴다.

**캐시 키:**
- 접두어: 셸 단계에서 `/etc/os-release`의 `ID`·`VERSION_ID`로 `playwright-apt-<ID>-<VERSION_ID>-`를 만들어 단계 출력으로 넘긴다(예: `playwright-apt-ubuntu-26.04-`). 비어 있으면 그 단계가 실패한다. `${{ env.ImageOS }}`는 쓰지 않는다(`env` 컨텍스트는 워크플로가 정의한 변수만 담는다 — 1라운드 리뷰).
- 복원: `actions/cache/restore@v6`, `key: <접두어>restore`, `restore-keys: <접두어>` → 같은 OS 버전에서 가장 최근에 저장된 캐시를 복원한다. 정확한 키로 저장하지 않으므로 `cache-hit`은 늘 false다. 복원 여부는 `cache-matched-key` 출력과 `stage`의 개수로 판정한다.
- 저장: `actions/cache/save@v6`, `key: <접두어>${{ hashFiles('.ci-apt-cache/*.deb') }}`. **시험 단계 뒤**에 두어 job이 그때까지 성공했을 때만 저장한다. 회수 개수가 0이면 건너뛴다. 내용이 같으면 키가 같아 저장이 생략된다. 이미 있는 키라도 job을 실패시키지 않는지는 실험 2회차에서 확인한다(**미확인**).
- `hashFiles`는 작업 공간 안의 파일만 본다(GitHub Actions 표현식 문서) — 그래서 `.ci-apt-cache/`를 작업 공간 안에 둔다.
- 캐시 범위: PR에서 만든 캐시는 그 PR에서만 쓰이고, `master` push 실행이 만든 캐시를 모든 PR이 복원한다(GitHub 캐시 범위 규칙). 7일 동안 쓰이지 않은 항목은 GitHub가 지운다 — 옛 키 항목은 따로 지우지 않는다.

### 결정 2 — 설치 단계 시간 제한: **`timeout-minutes: 10`**

- 지금은 job 전체 30분 제한뿐이라, 미러가 멈추면 30분 뒤 job 시간 초과로 끝난다. 그러면 어느 단계 문제였는지가 덜 드러난다.
- 단계 제한을 두면 해당 단계 이름과 함께 시간 초과로 끝나 원인 단계가 바로 보인다(완료 기준 ②의 "원인이 드러나는 메시지").
- 10분인 이유: 관측된 최악이 4분 17초다(캐시 없음 + 느린 미러). 그 두 배 이상을 두어, 캐시가 빗나간 실행이 느린 미러를 만나도 끊길 가능성을 낮춘다. 이것은 두 표본으로 정한 운영 기준이지 보장이 아니다. `stage` 단계에는 5분 제한과 함께 `continue-on-error: true`를 붙인다. `stage`는 최적화일 뿐이므로, 내부 제한(120초)을 넘어서도 바깥 단계 제한에 걸려 끊기면 그 실패를 무시하고 설치·시험을 그대로 진행한다(3라운드 리뷰 — 캐시 최적화가 필수 체크를 떨어뜨리지 않게). 회수(`collect`)에도 같은 이유로 `continue-on-error: true`를 붙인다. 캐시 **복원** 단계에는 `timeout-minutes: 3`·`continue-on-error: true`·환경변수 `SEGMENT_DOWNLOAD_TIMEOUT_MINS: 2`(actions/cache v6 README "Environment Variables" — 세그먼트 다운로드 기본 제한 10분)를, **저장** 단계에는 `timeout-minutes: 3`·`continue-on-error: true`를 붙인다(4라운드 리뷰 — 캐시 전송이 멈춰도 필수 체크가 떨어지거나 job 30분 제한을 먹지 않게). 복원이 끊겨 남은 부분 파일은 `stage` 검증이 버린다.
- 재시도는 넣지 않는다(결정 1의 D와 같은 이유).

### 결정 3 — `Run browser tests` 단계에 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: 1`

- 출처: `com.microsoft.playwright:driver:1.65.0`의 `DriverJar.installBrowsers` 바이트코드 — 환경변수 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD`가 있고 `"0"`·`"false"`가 아니면 브라우저 설치를 건너뛴다(javap로 확인).
- 효과: Firefox·WebKit 다운로드(약 6초)와 WebKit 의존성 경고가 사라진다. Chromium은 앞 단계가 설치했다.
- 위험: Chromium이 없으면 실행 시 "Executable doesn't exist"로 실패한다. 원인이 드러나는 메시지라 수용한다(앞 단계가 실패하면 이 단계는 애초에 돌지 않는다).
- 전제: Gradle 데몬은 앞 단계(`installPlaywrightBrowsers`)에서 이 변수 없이 떴다. 래퍼 Gradle 8.14.7이 빌드마다 클라이언트 환경변수를 데몬에 넘기고, `Test` 태스크의 포크 JVM이 그것을 물려받아야 이 설정이 먹는다 — **미확인**. 실험 로그에서 "Downloading Firefox"가 사라지고 시험이 실제로 실행됐는지(`Run browser tests`의 시험 수)로 확인한다.

### 하지 않는 것

- Playwright 브라우저 디렉터리(`~/.cache/ms-playwright`) 캐시: CDN 다운로드가 실측 몇 초라 이득이 작다. 캐시 크기(약 300MB)만 차지한다.
- `test` job·`prod-smoke` job 변경: 이 문제와 무관하다.
- `build.gradle`의 `installPlaywrightBrowsers` 인자 변경: 결정 1이 `--with-deps`를 유지하므로 바꾸지 않는다.

## 4. 변경 파일

- `.github/workflows/ci.yml` — `browser-test` job: 키 계산 → 캐시 복원 → `stage`(제한 5분) → 설치(제한 10분) → `collect` → 시험(환경변수 추가) → 캐시 저장.
- `scripts/ci/playwright-apt-cache.sh` — 신규(`stage`·`collect`). 다른 `scripts/ci/*.sh`와 달리 Docker 자원을 만들지 않는다.
- `.gitignore` — `.ci-apt-cache/` 추가(로컬에서 실수로 커밋하지 않게).
- `src/test/java/CLAUDE.md` — "브라우저 설치" 문단 갱신.
- 이 계획서·`plan/README.md` 인덱스.

변경하지 않는 것: 앱 코드·시험 코드·`build.gradle`·스키마·인가 정책·`SecurityConfig`.

## 5. 성공 기준

1. **(1차) 다운로드 제거**: 캐시가 복원된 실행에서 `stage`가 9개(그때의 폰트 수)를 넣고, `Install Playwright browsers`의 apt가 폰트를 받지 않는다(`Need to get 0 B`).
2. **(2차) 시간**: job 전체 시간(캐시 복원·`stage`·`collect`·저장 포함)을 기준선 실행들과 비교해 표로 남긴다. 미러가 느린 경우를 일부러 만들 수 없으므로, "미러에서 받는 바이트"(로그의 `Fetched`)가 21.9MB+에서 `apt-get update` 분량으로 줄었다는 것을 근거로 삼는다. 미러와 완전히 무관하다고 주장하지 않는다(`apt-get update`는 남는다).
3. 캐시가 없는 실행은 지금과 같게 동작한다(회귀 없음). 그리고 저장 단계가 캐시를 만든다.
4. `Run browser tests` 로그에 "Downloading Firefox"·"Downloading WebKit"이 없고, 브라우저 시험이 실제로 실행되어 모두 통과한다.
5. `browser-test`의 job 이름·트리거는 그대로다(브랜치 보호 필수 체크 영향 없음).

## 6. 실패 모드와 고정 방법

CI 설정이라 단위 시험 대신 **실험 실행(초안 PR)의 로그**로 고정한다. 결과는 §9에 실행 번호와 함께 남긴다. 실험용 단계는 확인 뒤 지운다.

| # | 실패 모드 | 확인 방법 |
|---|---|---|
| F1 | deb가 남지 않거나 정리 훅이 지워 캐시 효과가 0 | 실험 1회차(캐시 없음): `apt-config dump`의 Keep/Clean/Post-Invoke 줄, 설치 뒤 `archives/` 파일 수, `collect` 개수. 실험 2회차: `stage` 직후와 설치 직전·직후의 파일 수(투입분이 Playwright의 `apt-get update`에 지워지지 않는지) |
| F2 | 캐시가 있어도 apt가 다시 받는다 | 실험 2회차: `Need to get 0 B`·`cache-matched-key` |
| F3 | 손상·변조·누락·이름 바꿈 deb | 실험 3회차: 복원 뒤 임시 단계가 ⓐ deb 하나의 **끝부분** 바이트 하나를 바꾸고(크기 유지 — 본문 변조, 해시 불일치로 버림), ⓑ 하나를 절반으로 자르고(분석 실패로 버림), ⓒ 하나를 지우고(누락), ⓓ 정상 deb 하나를 다른 패키지의 이름으로 복사한다(이름 바꿈 — 원래 이름을 쓰지 않으므로 그 패키지 자리에 들어가지 않음), ⓔ ar 형식이 아닌 파일(임의 바이트)을 `.deb` 이름으로 넣고(분석 실패로 버림), ⓕ `dpkg-deb --nocheck --build`로 `Architecture`가 없는 deb를 만들어 넣는다(필드 누락으로 버림). ⓑ(절단)는 제어 영역이 남으면 해시 불일치로 버려질 수 있어 분기를 가리지 않는다 — 이유만 기록한다. 확인: `stage`의 이유별 버림 개수, 각 버림 뒤에도 다음 정상 파일이 처리되는지, apt는 ⓐⓑⓒ 해당분만 받음(`Need to get`), 회수된 집합의 해시가 원래 키와 같아 저장이 생략되는지, `stage`가 실패하지 않는지. 오래된 버전 deb는 같은 "버림" 분기(후보 버전 불일치)를 타지만 실제 버전 갱신은 재현하지 못한다 — 한계로 기록 |
| F6 | `stage`의 update가 멈추면 필수 체크가 떨어진다 | 실험 4회차: `APT_CACHE_UPDATE_TIMEOUT=1`로 내부 제한에 일부러 걸리게 해, `stage`가 투입을 포기하고 끝나며 뒤이은 설치가 잠금 대기 없이 지금처럼 진행되는지(폰트를 미러에서 받음). 바깥 단계 제한(5분)+`continue-on-error`는 재현하지 않는다(GitHub Actions 워크플로 문법의 단계 `timeout-minutes`·`continue-on-error` 동작에 기댄다) |
| F4 | `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD`가 시험 JVM에 전달되지 않음 | 실험 실행의 `Run browser tests` 로그에 "Downloading Firefox"가 없고, 시험 결과에 실행된 시험이 있는지 |
| F5 | 저장 단계가 이미 있는 키 때문에 job을 실패시킴 | 실험 2회차(같은 내용 → 같은 키): 저장 단계 결과와 job 결과 |

설치 단계의 10분 제한(결정 2)은 Playwright의 apt를 지연시키려면 실험이 설치 경로 자체를 바꿔야 해서 재현하지 않는다(GitHub Actions 워크플로 문법의 단계 `timeout-minutes` 동작에 기댄다). `stage`의 복귀 경로는 F6으로 재현한다.

## 7. 위험 변경

없음(인가·데이터 정합성·동시성·마이그레이션을 건드리지 않음). 공급망 관점: 캐시는 신뢰하지 않는다. apt가 서명을 확인해 저장한 색인의 SHA256·후보 버전과 대조한 deb만, 검증한 값으로 만든 파일 이름으로 설치에 쓴다(결정 1). 캐시를 쓸 수 있는 주체는 이 저장소의 워크플로 실행뿐이다(PR 캐시는 그 PR 범위).

## 8. 개정 이력

- v1: 최초 작성.
- v2 변경 (1라운드 리뷰 반영):
  - 수용: 캐시된 deb의 무결성을 APT에 맡기지 않고 `stage`가 색인 SHA256·후보 버전으로 검증(지적 1). `env.ImageOS` 대신 `/etc/os-release`로 키 접두어를 만들고 비면 실패(지적 2). F3를 변조·누락 실험으로 바꾸고 버전 갱신 재현 불가를 한계로 기록(지적 3). 회수 전 비우고 설치된 버전만 회수(지적 4). 저장을 시험 뒤로 이동(지적 5). F1에 `apt-config dump`·단계별 파일 수 추가(지적 6). 성공 기준을 다운로드 제거(1차)와 job 시간(2차)으로 분리하고 "미러와 무관" 주장 철회(지적 7).
  - 수용(출처): Playwright apt 명령을 driver-1.65.0 jar의 `coreBundle.js`로 확인. 적중 판정은 `cache-matched-key`. 이미지·APT 버전은 실험 로그로 기록. Gradle 버전 8.14.7 명시.
  - 추가: `stage`에 5분 제한, `.gitignore`에 `.ci-apt-cache/`, 스크립트를 `scripts/ci/playwright-apt-cache.sh`로 분리.
- v3 변경 (2라운드 리뷰 반영):
  - 수용: `stage`가 원래 파일명을 버리고 검증한 `Package`·`Version`·`Architecture`로 apt 이름(epoch `%3a`)을 만들어 넣고, `collect`가 apt가 실제로 쓴 이름과 대조(지적 1). 파일별 분석 실패·빈 필드·후보 없음·SHA256 없음은 그 파일만 버리고 스크립트는 실패하지 않음. F3를 본문 변조·절단·누락·이름 바꿈으로 세분(지적 3). 러너의 apt·dpkg 버전을 실험 로그로 기록.
  - 부분 수용(지적 2): "이번 실행에서 받은 서명된 색인"이라는 표현을 "apt가 서명을 확인해 저장한 로컬 색인"으로 고침. update가 실패하면 투입을 건너뜀.
  - (v4로 이어짐) 기각(지적 2의 `APT::Update::Error-Mode=any`): 옛 색인도 서명이 확인된 것이고, 옛 색인 때문에 생기는 최악은 캐시 미사용(뒤이은 Playwright update가 최신 색인으로 후보를 정하고, 버전이 다르면 파일 이름이 달라 새로 받음)이지 잘못된 설치가 아니다. 색인 일부 실패를 일부러 만드는 실험도 하지 않는다(같은 이유).
- v4 변경 (3라운드 리뷰 반영):
  - 수용: `stage`의 update를 내부 `timeout 120`으로 감싸 시간 초과면 투입을 포기하고, `stage`·`collect`·저장 단계에 `continue-on-error: true` — 캐시 최적화가 필수 체크를 떨어뜨리지 않게(지적 1). 투입은 임시 이름 복사 후 `mv`. 복귀 경로를 실험 F6(내부 제한 1초)으로 재현.
  - 수용: F3에 분석 실패(ar 아닌 파일)·필드 누락(`--nocheck`로 만든 deb)을 따로 추가하고, 절단은 분기를 가리지 않는 사례로 둠(지적 2).
- v5 변경 (4라운드 리뷰 반영):
  - 수용: 캐시 복원 단계에 `timeout-minutes: 3`·`continue-on-error: true`·`SEGMENT_DOWNLOAD_TIMEOUT_MINS: 2`, 저장 단계에 `timeout-minutes: 3`·`continue-on-error: true`.
  - 기각(복원 시간 초과 재현 실험): 캐시 서비스 전송 정지를 일부러 만들 수단이 없다. 문서화된 단계 `timeout-minutes`·`continue-on-error` 동작에 기대고, 부분 복원 파일은 `stage` 검증(F3)이 처리한다.

## 9. 구현·검증 결과

### Context
v5(적대적 리뷰 5라운드 ship) 승인 후 구현(2026-11-20). PR #291(초안으로 실측 후 정식 전환).

### 핵심 확정 사항
- 결정 1~3을 계획대로 구현했다. 계획과 달라진 점:
  - **후보 기록 조회를 `apt-get download --print-uris <패키지>`로 했다.** 이것이 apt가 보관 디렉터리에 쓰는 이름과 색인의 해시(가장 강한 것 — `SHA256`/`SHA512` 둘 다 처리)를 한 번에 준다. 그래서 §3의 "`Package`·`Version`·`Architecture`로 이름을 만든다"를 이것으로 바꿨다. 실측상 꼭 필요했다: 러너는 `xfonts-utils`를 `…_amd64v3.deb`로 보관하는데 deb 안의 `Architecture`는 `amd64`다. deb 값으로 이름을 만들었다면 그 파일은 투입 효과가 없었다.
  - 실험 ③(F3 손상)·④(F6 시간 초과)는 스크립트 논리 시험이라, 같은 OS(`ubuntu:26.04`)의 실제 apt로 **로컬 Docker**에서 확인했다. CI 실행 횟수가 약 5회에서 2회로 줄었다. 러너에서만 볼 수 있는 것(정리 훅·Playwright update 뒤 보존·러너 이름 규칙·Gradle 환경 전달)은 CI 실행 2회로 확인했다.
  - 키 계산 단계도 `continue-on-error`로 두고, 복원·저장은 접두어가 있을 때만 돈다(계획의 "비면 실패"를 "비면 캐시 단계만 건너뜀"으로 — 최적화가 필수 체크를 떨어뜨리지 않는다는 결정 2와 맞춤).

### 구현 파일
- `scripts/ci/playwright-apt-cache.sh` (신규 — `stage`·`collect`)
- `.github/workflows/ci.yml` (`browser-test` job)
- `.gitignore` (`.ci-apt-cache/`)
- `src/test/java/CLAUDE.md` ("브라우저 설치" 문단)

### 검증 결과
**정적 검사**: actionlint 1.7.7·shellcheck 0.10.0 경고 없음.

**로컬 Docker (`ubuntu:26.04`, root, 폰트 9개 + 의존 9개 = 18개 deb):**

| 시나리오 | 결과 |
|---|---|
| A 정상 캐시 | 18개 투입, `Need to get 0 B/23.3 MB`, 회수 집합 해시 동일 |
| B ⓐ끝부분 1바이트 변조(크기 유지) | 버림(해시 불일치) |
| B ⓑ절반 절단 | 버림(분석 실패) — 이 파일은 제어 영역도 깨져 분석 분기를 탔다 |
| B ⓒ누락 / ⓓ wqy 자리에 tlwg 내용 | ⓓ는 tlwg 이름으로 들어가고 wqy 자리는 비어 다시 받음 |
| B ⓔ임의 바이트 / ⓕ `--nocheck`로 만든 Architecture 없는 deb | 둘 다 버림(분석 실패 또는 필드 누락) |
| B 종합 | `stage` 종료 코드 0, `Need to get 20.4 MB`(= unifont 3.17 + freefont 5.66 + ipafont 3.70 + wqy 7.86), 회수 집합이 원래 해시로 돌아옴 |
| C update 내부 제한 0.05초 | 투입 포기(경고), 종료 코드 0, 뒤이은 update가 잠금 대기 없이 성공, 미러에서 23.3MB 받음 |

**CI (러너 `ubuntu26` 이미지 `20261111.196.1`, apt 3.2.1, dpkg 1.23.7):**

| 실행 | 캐시 | apt 폰트 다운로드 | 설치 단계 | 캐시 단계 합(복원·`stage`·`collect`·저장) | `Run browser tests` |
|---|---|---|---|---|---|
| 기준선 run 44476953408 (azure 미러) | — | 21.9MB in 1s | 44초 | — | 1분 58초 (Firefox·WebKit 다운로드 포함) |
| 기준선 run 44486452235 (us-east-2 미러) | — | 21.9MB in 2분 59초 | **4분 17초** | — | 2분 13초 (포함) |
| 1회차 run 44558935498 job 135682554208 | 없음 | 21.9MB in 2s | 45초 | 약 3초 | 2분 5초 (다운로드 0건) |
| 2회차 같은 run 재실행 job 135686298268 | 적중 | **`Need to get 0 B/21.9 MB`** | 50초 | 약 8초 (`stage` 6초 — update 3.4초 포함) | 2분 27초 (다운로드 0건) |

- F1 ✓: `apt-config dump`에 deb를 지우는 설정이 없다(`Post-Invoke-Success`는 타임스탬프·motd·cnf 갱신뿐, `DPkg::Post-Invoke ""`, `AutocleanInterval "0"`). 설치 뒤 9개 회수, 이름 불일치 경고 없음.
- F2 ✓: 적중 실행의 `Need to get 0 B`. Playwright의 `apt-get update` 뒤에도 투입분이 남았다.
- F3·F6 ✓: 로컬 Docker(위 표).
- F4 ✓: 두 실행 모두 `Run browser tests` 로그에 "Downloading Firefox/WebKit"·"missing dependencies" 0건, 시험 실행·통과.
- F5 ✓: 같은 키 저장은 "Failed to save: Unable to reserve cache … another job may be creating"으로 끝났고, 단계와 job은 success.
- 시간 해석: 미러가 빠를 때는 캐시가 이득이 없다(설치 단계 45초 → 50초, 캐시 단계 약 8초 추가 — 앞쪽 `apt-get update`가 한 번 더 돈다). 이득은 느린 미러에서 폰트 다운로드(관측 최대 2분 59초)를 없애는 데 있다. 느린 미러는 일부러 만들 수 없어서, 적중 실행이 미러에서 받는 바이트가 폰트 21.9MB → 0이라는 것을 근거로 삼는다(§5 2차 기준). `apt-get update`(3~5MB, 적중 시 두 번)는 여전히 미러에 묶인다.

### 이슈
- 로컬 Docker 시험에는 우분투 도커 이미지 전용 정리 훅 `/etc/apt/apt.conf.d/docker-clean`이 있어 시험 스크립트가 먼저 지웠다(러너 VM에는 없음 — F1에서 확인).
- 처음 커밋·push·PR 생성을 한 명령으로 묶었을 때는 자동 권한 검사에 막혔다. 나눠서 실행하니 진행됐다.

### 후속
- 없음(후보: Ubuntu 이미지 메이저가 바뀌면 키 접두어가 바뀌어 첫 실행은 캐시 없이 돈다 — 의도된 동작).
