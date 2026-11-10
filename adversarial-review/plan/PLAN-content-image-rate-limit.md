# PLAN — 본문 이미지 한도(`content-image`) 초과 시 깨진 이미지 대응

> 출처: `adversarial-review/project-direction-roadmap.md` "후속 과제 — 게시판 정의 고도화 완료 시 기록 (2026-10-30)" ③.
> 선행: `PLAN-board-definition.md` PR 5·6과 #189(썸네일 실패 자리표시) — 모두 머지됨.
> 상태: **구현·검증 완료(2026-11-02, 브랜치 `feat/content-image-rate-limit-fallback`, 미커밋)** — 적대적 리뷰 2라운드 ship, 사용자 승인 후 구현. 결과는 문서 끝 "구현·검증 결과".

## 개정 이력

- v1(2026-11-02): 최초 작성. 사용자 결정 2건 반영(U1 로그인 세션 면제, U2 한도 수치 유지).
- v2 변경(리뷰 R1, needs-attention 1건): ① **수용** — 교체 상자가 본문 `img`의 `alt`를 버리면 깨진 이미지일 때 브라우저가 보여주던 대체 텍스트가 사라져 후퇴한다. D1을 "고정 안내 문구 + 비어 있지 않은 `alt`를 별도 텍스트 노드(`textContent`)로 보존"으로 바꾸고, 브라우저 시험에 `alt` 보존·빈 `alt`·HTML처럼 생긴 `alt`의 문자 그대로 출력을 추가했다. 기각 없음.

## Context

`/content-images/{id}`(원본·`?size=thumb`)는 레이트리밋 규칙 `content-image`(240/60초, 키 = `getRemoteAddr()`) 하나를 쓴다. 한도가 차면 `RateLimitFilter.reject`가 `sendError(429)` → HTML 오류 페이지라 `<img>`는 깨진 이미지가 된다. 2026-10-29 실측: 연속 요청 250건까지 200, 251번째부터 429(`Retry-After: 1`), 50칸 갤러리를 약 1초 간격으로 끝까지 스크롤하며 넘기면 7회차부터 43~46장 실패, 15초 뒤 회복.

#189가 **공개 목록 썸네일**은 실패 시 자리표시로 바꿨다. 남은 곳은 둘이다.

1. **공개 게시글 상세의 본문 이미지** — 깨진 이미지 아이콘.
2. **관리 화면(게시글 관리 `posts.html`)의 이미지** — Quill 편집기 본문, 보기 모드 본문, 대표 선택지(원본), 대표 미리보기(썸네일). 로그인한 관리자도 IP 버킷을 같이 쓰므로, 같은 IP(사내망)의 방문자나 본인의 공개 화면 열람이 버킷을 비우면 편집기 이미지가 깨진다.

## 정찰 결과(코드에서 확인한 사실)

| 대상 | 사실 |
|---|---|
| `RateLimitFilter` | `limiter.rules()`를 선언 순서로 돌며 **메서드·경로가 맞는 첫 규칙 하나**만 적용 → `tryConsume(remoteAddr, rule)`. 규칙이 없으면 통과. 429는 `/admin/api/**`면 JSON, 그 외 `sendError(429)`. |
| 필터 위치 | `SecurityConfig`가 `addFilterAfter(rateLimitFilter, CsrfFilter.class)`. `SecurityContextHolderFilter`가 앞에 있어 **세션의 인증 정보를 이 시점에 읽을 수 있다**. `AnonymousAuthenticationFilter`는 뒤에 있어 비로그인 요청의 인증은 `null`이다. `ConcurrentSessionFilter`(강제 만료 세션 처리)도 Spring Security 기본 순서상 이 필터 **뒤**에 있다(구현 시 실제 체인으로 확인). |
| `RateLimitRule`/`RuleConfig` | 레코드 `(id, matcher, methods, capacity, refillPeriod)`. `TokenBucketRateLimiter.toRule`이 유일한 생성 지점(main·test 모두 grep으로 확인). 설정 검증은 `RateLimitConfigValidator`(id 중복·메서드). |
| `/content-images` 사용처 | 공개: `public/board/list.html` 썸네일(#189 자리표시), `public/board/detail.html` 본문(`th:utext` 한 곳). 정적 페이지·FAQ·팝업 HTML은 본문 이미지를 허용하지 않고 배너·팝업 이미지는 별도 경로(`/banners/*/image`·`/popups/*/image`, 각자 규칙). 관리: `posts.html`(편집기·보기·대표 선택지·대표 미리보기). 미디어 라이브러리는 `/admin/api/media/{id}/file`이라 한도 대상 아님. |
| 본문 `<img>` | `HtmlContentSanitizer` 허용 속성은 `src`·`alt`뿐(`loading` 없음) → 상세의 본문 이미지는 즉시 로딩. |
| 공개 게시판 스크립트 규칙 | `PublicBoardTemplateConventionTest.scriptsAreExternalFallbackOnly` — 스크립트는 `list.html`의 외부 파일 `board-thumb-fallback.js` 하나, `detail.html`은 0개, 인라인 스크립트·`on…=` 속성 금지. |
| 시험 | 필터 단위 `RateLimitFilterTest`(`@WebMvcTest` + 테스트용 규칙 속성), 실설정 `SitePublicRateLimitConventionTest`(`@SpringBootTest` + `cms.rate-limit.enabled=true`, `MariaDbContainerSupport`), 브라우저 `BoardThumbFallbackBrowserTest`(익명 컨텍스트, 파일 없는 이미지 행으로 404를 결정적으로 만듦). `/content-images`의 429를 직접 단언하는 시험은 없다. 시험 전역은 레이트리밋 꺼짐(`build.gradle` `CMS_RATE_LIMIT_ENABLED=false`). |

## 사용자 결정(2026-11-02)

- **U1 관리 화면**: 로그인 세션은 `content-image` 한도에서 **면제**한다(선택지: 면제 / 수용 / 회원별 버킷 → 면제).
- **U2 한도 수치**: `content-image` 240/60초 **유지**(선택지: 유지 / 600 상향 → 유지). 원본이 최대 5MB라 상향은 IP당 최대 전송량(약 1.2GB/분 → 3GB/분)을 함께 키운다.

## 핵심 쟁점과 결정

### D1. 공개 상세 본문 이미지 — **외부 스크립트로 실패한 본문 이미지를 안내 문구 상자로 교체**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 외부 JS로 교체(#189와 같은 방식)** | #189의 검증된 방식(캡처 단계 `error` + `DOMContentLoaded` 정리) 재사용, 서버·경로·인가 무변경 | 공개 상세 템플릿에 스크립트가 처음 생겨 컨벤션 시험 규칙을 바꿔야 한다 |
| B. 자동 재시도(지연 후 `src` 재설정) | 일시적 429면 이미지가 결국 보인다 | 실패 원인을 구분할 수 없어(404도 재시도) 한도를 더 쓰고, 여러 장이 동시에 재시도하면 다시 넘친다. #189가 같은 이유로 기각 |
| C. 한도 상향만 | 코드 변경 없음 | U2에서 기각. 상향해도 넘으면 여전히 깨진다 |

**결정 A**. 교체물은 목록의 회색 사진 아이콘(장식, `aria-hidden`)이 아니라 **문구가 보이는 상자**(`<span class="board-content-image-failed">`)다 — 본문 이미지는 내용의 일부라 "여기 이미지가 있었다"가 읽는 사람(스크린리더 포함)에게 전달돼야 한다. 상자 내용은 ⓐ 고정 안내 문구 "이미지를 불러오지 못했습니다" ⓑ 원래 `img`의 `alt`가 공백이 아니면 그 값을 담은 별도 자식 `<span class="board-content-image-alt">`(v2, 리뷰 R1-1 — 깨진 이미지일 때 브라우저가 보여주던 대체 텍스트를 잃지 않게). 두 값 모두 `textContent`로만 넣는다(`innerHTML` 금지 — HTML처럼 생긴 `alt`도 글자 그대로). 재시도하지 않는다(새로고침하면 회복 — 목록과 같은 계약).

### D2. 스크립트 파일 — **상세 전용 새 파일 `/js/public/board-content-image-fallback.js`**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 상세 전용 새 파일** | 각 템플릿이 자기 몫의 스크립트 하나만 싣는다. 기존 파일·시험·문서 이름 무변경 | 캡처 리스너 골격(약 15줄)이 두 파일에 비슷하게 있다 |
| B. 기존 파일에 선택자 추가 + 이름 변경(`board-image-fallback.js`) | 골격 한 벌 | 이름 변경이 `list.html`·시험·지침 문서까지 번진다. 목록 페이지가 상세 규칙을, 상세가 목록 규칙을 함께 싣는다 |
| C. 기존 파일에 선택자만 추가(이름 유지) | 변경 최소 | "thumb" 파일이 본문 이미지를 처리해 이름이 거짓이 된다 |

**결정 A** — 번들러가 없는 정적 JS라 공용 모듈화는 과설계이고, 중복은 짧고 선택자·교체물이 달라 실제로 같은 코드가 아니다.

`detail.html`의 `<head>`에 `defer` 없이 싣는다(목록과 같은 이유 — 본문 이미지 요청보다 리스너가 먼저 붙는다). 본문에 이미지가 없는 글에도 싣는다(조건 분기 없음 — 정적 파일은 레이트리밋 대상이 아니고 브라우저 캐시가 된다).

### D3. 관리 화면 — **`content-image` 규칙에 "인증된 요청 면제" 설정(U1)**

- `RuleConfig`에 `exempt-authenticated`(boolean, 기본 `false`), `RateLimitRule`에 같은 필드. **`content-image`만 `true`**.
- `RateLimitFilter`: 첫 매칭 규칙이 면제 규칙이고 현재 인증이 "익명이 아닌 인증"이면 토큰을 소비하지 않고 통과. 판정은 `SecurityContextHolder`의 `Authentication`이 `null`이 아니고 `isAuthenticated()`이며 `AnonymousAuthenticationToken`이 아닐 것.
- **첫 매칭 규칙 뒤에 다른 규칙으로 떨어지지 않는다** — 면제는 "이 규칙의 버킷을 쓰지 않음"이지 다음 규칙 평가가 아니다(지금도 규칙은 하나만 적용).
- Quill 편집기 안의 `<img>`를 스크립트로 바꾸는 대안은 **기각** — Quill이 DOM 변경을 문서 변경으로 받아들여 저장 본문에서 이미지가 빠질 수 있다.
- 회원별 버킷 대안은 **기각**(U1) — 키 체계·캐시 크기 계산이 바뀌어 범위가 커지고, 이 앱의 인증 주체는 관리자(ADMIN·MANAGER)뿐이라 상한의 이득이 작다.

**왜 안전한가**
- 이 앱의 로그인 주체는 관리자 계정뿐이다(공개 회원 없음). 면제를 얻으려면 유효한 관리자 세션이 필요하고, 그 세션은 이미 관리 기능 전체를 쓸 수 있다.
- **익명 방문자의 한도·경로·인가 규칙은 그대로**다(`SecurityConfig` 무변경, URL 접근 규칙 무변경).
- 강제 만료된 세션: 면제 판정은 만료 처리(`ConcurrentSessionFilter`)보다 먼저라 그 요청은 버킷을 쓰지 않지만, 곧이어 만료 전략(페이지 경로 → `/admin/login` 리다이렉트)이 응답을 끝내 **이미지 바이트는 나가지 않는다**. 만료 세션 쿠키로 얻는 것은 "값싼 리다이렉트 응답"뿐이라 수용한다(구현 시 실제 동작 확인).
- 면제는 `/content-images/*` GET·HEAD에만 걸린다 — 다른 규칙(공개 게시판 120 등)은 관리자에게도 그대로다.

**정책 변경 고지**: URL 인가 규칙은 바뀌지 않지만 루트 `CLAUDE.md` "보안 규칙 > 무인증 공개 엔드포인트 레이트리밋"의 계약(키는 IP, 모두에게 같은 한도)이 바뀐다 → 승인 기록을 이 문서와 지침 문서에 남긴다(2026-11-02 사용자 승인 U1).

### D4. 한도 수치 — **240/60초 유지(U2)**

갤러리 50칸을 빠르게 넘기면 일부 칸이 자리표시로 보이는 것은 그대로다(깨진 아이콘은 아님). `application.yml`의 규칙 주석에 면제와 함께 이유를 남긴다.

## 설계 제약

- 공개 템플릿: 외부 JS 파일만, 인라인 스크립트·`on…=` 속성 금지(#189 선례), `th:utext`는 상세 본문 한 곳 그대로.
- 정적 JS 경로 `/js/**`는 이미 공개·레이트리밋 비대상이라 `SecurityConfig` 변경 없음.
- 필터는 `CsrfFilter` 다음 위치 유지(쟁점 4 — CSRF 실패 요청의 quota 소비 방지).

## 작업 단계

1. `RuleConfig.exemptAuthenticated` + `RateLimitRule.exemptAuthenticated` + `TokenBucketRateLimiter.toRule` + `RateLimitFilter` 면제 분기.
2. `application.yml` `content-image`에 `exempt-authenticated: true`와 주석.
3. `board-content-image-fallback.js` + `board.css` `.board-content-image-failed` + `detail.html` 스크립트.
4. 시험(아래) → 영향 범위 실행.
5. 실기 검증(아래).
6. 기록: 루트 `CLAUDE.md` 보안 규칙 레이트리밋 항목, `com.cms.publicweb.board`·`com.cms.config` `CLAUDE.md`, 이 문서 결과, 계획 인덱스.

## 시험

| 시험 | 내용 |
|---|---|
| `RateLimitFilterTest`(수정) | 테스트 규칙에 면제 규칙을 하나 추가: ① 익명은 capacity 후 429 ② 같은 IP의 인증 요청(`user()`)은 capacity를 넘겨도 429 아님 ③ **인증 요청이 버킷을 소비하지 않는다**(인증 요청 다수 뒤에도 익명은 capacity만큼 통과) ④ 면제 아닌 규칙은 인증 요청도 429 |
| `ContentImageRateLimitExemptionTest`(신규, `com.cms.config`) | 실설정(`SitePublicRateLimitConventionTest`와 같은 컨텍스트 구성 → 컨텍스트 재사용): ① 실제 규칙 중 면제는 `content-image` 하나뿐 ② 익명 IP로 `content-image` 버킷을 소진하면 429 ③ 같은 IP의 ADMIN·MANAGER 요청은 429가 아니다(없는 id라 404) |
| `TokenBucketRateLimiterTest` | 기본값 `false`가 규칙으로 옮겨지는지 1건 |
| `PublicBoardTemplateConventionTest`(수정) | 템플릿별 허용 스크립트: `list.html` = `board-thumb-fallback.js` 1개, `detail.html` = `board-content-image-fallback.js` 1개, 인라인 금지 유지 |
| `BoardThumbFallbackBrowserTest`(메서드 추가) | 상세: 정상 이미지 + 파일 없는 이미지(404) 3장(`alt` 없음·`alt="접수 마감: 11월 30일"`·`alt="<b>굵게</b>"`) 본문 → 실패한 것만 `.board-content-image-failed`(안내 문구 확인), `alt` 있는 상자는 `.board-content-image-alt`에 원문 그대로(`<b>`가 요소가 아니라 글자), `alt` 없는 상자에는 `.board-content-image-alt` 없음, 정상 이미지는 그대로, 깨진 `<img>` 0개 |

영향 범위 실행: `com.cms.config.*`·`com.cms.publicweb.board.*`·`com.cms.publicweb.contentimage.*`·`*ConventionTest`. 공용 코드(`config/**`)를 바꾸므로 **전체 `./gradlew test`도 돌린다**(CLAUDE.md 검증 범위 규칙).

## 실기 검증

1. 익명: 상세 화면에서 버킷을 소진한 뒤(같은 IP에서 `curl` 연속 요청) 새로고침 → 본문 이미지가 안내 상자로 바뀌고 깨진 아이콘이 없다(스크린샷). 15초 뒤 새로고침하면 정상.
2. 관리자: 같은 IP 버킷이 소진된 상태에서 로그인 세션으로 게시글 관리 화면 편집 모달 → 편집기·대표 선택지 이미지 정상(스크린샷), `curl`로 세션 쿠키 포함 요청은 200·익명은 429.
3. 회귀: 갤러리 목록 썸네일 자리표시 동작 그대로, 레이트리밋 꺼짐/켜짐 기본 공개 화면 정상.
4. 강제 만료 세션 쿠키로 `/content-images/{id}` 요청 → 이미지가 아닌 리다이렉트.

## 리스크

- 관리자 세션은 `/content-images`를 무제한 요청할 수 있다 — 유효한 관리자 세션이 전제라 수용(D3).
- 같은 IP의 익명 방문자는 여전히 240/60초를 나눠 쓴다(기존 레이트리밋 전체의 한계).
- 안내 상자는 이미지 크기를 모른다(본문 `img`에 크기 속성 없음) → 원래 이미지보다 작게 보일 수 있다.

## 구현·검증 결과 (2026-11-02)

**Context / 확정 사항**: 본문 이미지(`/content-images/*`)가 한도(240/60초)에 걸려 429가 되면 깨진 이미지가 보이던 두 곳을 처리했다. ① 공개 게시글 상세의 본문 이미지 — 외부 스크립트가 실패한 이미지를 안내 상자(고정 문구 + 비어 있지 않은 원래 `alt`, `textContent`만)로 바꾼다. ② 관리 화면 이미지 — `content-image` 규칙에만 `exempt-authenticated: true`를 두어 로그인(익명이 아닌) 요청은 버킷을 쓰지 않는다. 사용자 결정 U1(면제)·U2(240/60초 유지) 그대로. 스키마·`SecurityConfig`·URL 인가 규칙·의존성 변경 없음. 보안 규칙(레이트리밋 계약)은 바뀌었고 승인 기록은 루트 `CLAUDE.md`와 `com.cms.config`의 `CLAUDE.md`에 남겼다.

**구현 파일**
| 파일 | 변경 |
|---|---|
| `config/ratelimit/RateLimitProperties.java` (`RuleConfig`) | `exemptAuthenticated`(기본 `false`) |
| `config/ratelimit/RateLimitRule.java`·`TokenBucketRateLimiter.java` | 레코드 필드 추가·`toRule` 전달 |
| `config/ratelimit/RateLimitFilter.java` | 첫 매칭 규칙이 면제 규칙이고 `Authentication`이 null이 아니며 `isAuthenticated()`이고 `AnonymousAuthenticationToken`이 아니면 토큰 소비 없이 통과(다음 규칙으로 넘어가지 않음) |
| `application.yml` | `content-image`에 `exempt-authenticated: true` + 이유 주석 |
| `static/js/public/board-content-image-fallback.js`(신규) | 캡처 단계 `error` + `DOMContentLoaded` 정리, 재시도 없음 |
| `static/css/public/board.css` | `.board-content-image-failed`·`.board-content-image-alt` |
| `templates/public/board/detail.html` | `<head>`에 위 스크립트 1개(defer 없음) |
| 시험 | `RateLimitFilterTest`(+2)·`SitePublicRateLimitConventionTest`(+2)·`TokenBucketRateLimiterTest`(+1)·`PublicBoardTemplateConventionTest`(템플릿별 스크립트 1개로 갱신)·`BoardThumbFallbackBrowserTest`(+1) |
| 문서 | 루트 `CLAUDE.md`·`com.cms.config`·`com.publicweb.board`의 `CLAUDE.md` |

**검증 결과**
- 영향 범위 시험(`config.*`·`publicweb.board.*`·`publicweb.contentimage.*`·`*ConventionTest`) 462건, 실패 0. **전체 `./gradlew test` 2,969건 중 실패 0·오류 0·건너뜀 12**(건너뜀 12건은 이번 변경과의 관련을 따로 확인하지 않았다).
- **변이 실험**: 필터의 면제 분기를 `false`로 바꾸면 `RateLimitFilterTest`·`SitePublicRateLimitConventionTest`에서 정확히 2건 실패(원복 확인).
- **실기(dev `bootRun` 8081, curl·Playwright)**:
  1. 익명 연속 600건 → 77건 200·523건 429, 같은 순간 관리자 세션 600/600 200. 면제가 없는 다른 규칙(공개 게시판 목록)은 관리자도 125건 뒤 429(면제가 `content-image`에 한정).
  2. 공개 상세(익명, 한도 소진 직후 새로고침): 정상 이미지까지 3장 모두 안내 상자로 바뀌고 `alt`("정상 이미지"·"접수 마감: 11월 30일")가 글자 그대로 보이며 깨진 `<img>` 0개(`docs/screenshots/rate-limit/content-image-01-detail-rate-limited.png`). 15초 뒤 새로고침하면 정상 이미지는 복구되고 파일 없는 2장(404)만 상자로 남는다(`docs/screenshots/rate-limit/content-image-02-detail-recovered.png`).
  3. 관리 화면: 익명 버킷 소진 상태(익명 `fetch` 429)에서 관리자 쿠키 이미지는 로드되고, 관리자 요청 뒤에도 익명은 계속 429(관리자가 버킷을 쓰지 않음). 글 상세 모달의 정상 이미지는 로드됨(`docs/screenshots/rate-limit/content-image-03-admin-modal-bucket-exhausted.png`).
  4. 강제 만료된 세션 쿠키: 첫 요청은 `302 → /admin/login`(본문 0바이트)이라 이미지가 나가지 않고, 이후는 세션이 사라져 익명 취급(익명 한도 적용). 현재 유효 세션은 계속 200.
  5. 검증 행(게시판 #20·글 #123·이미지 109~111)과 업로드 파일은 삭제해 원복.

**이슈**
- 첫 영향 범위 시험 22건 실패는 Docker Desktop이 꺼져 있어서였다(코드 문제 아님) — 기동 후 통과.
- 전체 테스트 첫 실행은 시스템 메모리 부족으로 하네스가 중단시켰고, 같은 명령 재실행에서 통과했다.
- curl을 요청마다 새로 띄우면 토큰이 계속 차올라 429가 거의 나오지 않았다 — 한 연결로 `?x=[1-600]` 글로빙을 써서 재현했다.

**후속 / 수용한 한계**: 갤러리 50칸을 빠르게 넘기면 일부 칸이 자리표시로 보이는 것은 그대로(240/60초 유지, U2). 같은 IP 익명 방문자는 한 버킷을 나눠 쓴다. 안내 상자는 원래 이미지 크기를 몰라 이미지보다 작게 보일 수 있다. 관리자 세션은 `/content-images`를 무제한 요청할 수 있다(유효한 관리자 세션이 전제). 로드맵 후속 ③은 `/updateRoadmap`으로 반영한다.
