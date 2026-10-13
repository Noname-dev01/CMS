# CLAUDE.md — com.cms.publicweb.inquiry

> **경로 표기 주의(2026-10-13, PR ②)**: 이 문서의 `/notices`·`/boards`·`/pages`·`/faq`·`/inquiry`·`/`(공개 메인) 표기는 이전 경로다 — 공개 경로는 이제 `/{siteKey}/…` 아래(예: `/main/notices/1`)이고 `/`는 기본 공개 사이트로 302한다. 사이트 해석·404 계약·링크 접두어(`${sitePrefix}`)·시험은 `support/CLAUDE.md` 참조. 이미지(`/banners/{id}/image`·`/popups/{id}/image`·`/content-images/{id}`)는 사이트 무관 경로 그대로다.

이 디렉터리(공개 문의 폼) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.
계획서: `adversarial-review/plan/PLAN-inquiry.md`(적대적 리뷰 6라운드 ship, 2026-10-12). 관리자 쪽(문의함·알림·권한)은 `com.cms.admin.inquiry`의 `CLAUDE.md`.

## 무엇인가 — 두 번째 무인증 POST

공개 `/inquiry`(**정확 경로 1개**, GET·HEAD·POST 무인증, 2026-10-12 승인)로 방문자가 이름·이메일·제목·본문과 개인정보 동의로 문의를 남긴다. 첫 무인증 POST는 비밀번호 재설정 API였고, 이 경로는 페이지 폼 POST로는 처음이다. `SecurityConfig`는 `/inquiry`의 나머지 메서드와 `/inquiry/**`를 `denyAll`로 막는다(하위 경로는 404가 아니라 비인증 로그인 302·인증 403). **CSRF는 그대로 활성**이다 — 폼은 `th:action`이라 `_csrf` hidden이 자동 삽입되고, 토큰 없는 POST는 403이다(익명 CSRF 토큰은 세션 기반이라 폼 GET이 방문자마다 세션을 만든다 — 세션이 만료된 뒤 제출하면 403이고 입력은 보존되지 않는다).

`PublicInquiryTemplateConventionTest`가 **`com.cms.publicweb` 아래 쓰기 매핑이 `POST /inquiry` 하나뿐**임을 고정한다 — 새 공개 쓰기 경로는 인가 정책 승인과 이 시험의 일부러 된 수정이 필요하다.

## POST 순서 (고정) — `PublicInquiryController`

닫힘 확인 → 미처리 상한 확인 → `InquiryInputPolicy` 전체(DB 없음) + 캡차 토큰 형식(비었거나 2048자 초과는 로컬 400) → **캡차 서버 검증**(트랜잭션 밖 외부 호출) → 접수(`InquiryService.submit`, 트랜잭션 하나) → 알림(`InquiryNotifier`, 트랜잭션 밖·최선 노력) → **PRG 303** `/inquiry?sent`. 어떤 형식 오류도 외부 호출을 일으키지 않는다(가짜 검증기 호출 0회로 시험).

| 상황 | 응답 | 입력 보존 |
|---|---|---|
| 검증 실패·캡차 실패(fail-closed) | 400 폼 | 보존(`th:value`/`th:text` 자동 이스케이프) |
| 미처리 상한 도달 | 503 폼(POST) / 503 안내(GET) | POST는 보존 |
| 기능 닫힘(`enabled=false`) | 503 안내 | — |
| 접수 중 예기치 않은 예외 | 500 폼 | 보존 |
| 레이트리밋 초과 | 429 | **보존 안 됨**(`RateLimitFilter`가 `sendError`로 끝낸다 — 필터는 CSRF 우회 quota 고갈 공격 방어 때문에 `CsrfFilter` 다음이어야 해서 바꿀 수 없다) |
| CSRF 실패 | 403 | 보존 안 됨 |

**`publicweb` 예외 advice를 거치지 않는다**: `PublicWebExceptionAdvice`는 모든 예외를 HTML 500으로 바꾸고 **원인 체인까지 로그에 남긴다** — 접수 중 저장·커밋 예외에 입력이 섞이면 이름·이메일·본문이 로그로 복제된다. 그래서 컨트롤러가 검증을 예외 없이 직접 처리(`BindingResult`·`@Valid` 미사용)하고, 접수 중 예외는 이 컨트롤러가 직접 잡아 **예외 클래스명만** ERROR로 남긴다(코딩 컨벤션 "try-catch 남발 금지"의 의도된 예외 — 이 경계 한 곳). `PublicInquiryIntegrationTest.unexpectedFailure_…`가 응답·로그 원인 체인 어디에도 표식이 없음을 고정한다.

## 캡차 — Cloudflare Turnstile (외부 서비스)

- **서버 검증**(`TurnstileCaptchaVerifier`): `POST https://challenges.cloudflare.com/turnstile/v0/siteverify`(`secret`·`response`·`remoteip`), JDK `java.net.http.HttpClient`(새 의존성 없음). 응답 JSON은 `success`만 신뢰한다. **fail-closed** — 오류·타임아웃·잘못된 응답·HTTP 200이 아닌 상태 모두 `false`(Cloudflare 장애 시 문의가 일시 중단됨을 수용). 로그에는 예외 클래스명만(토큰·시크릿·응답 본문 없음).
- **호출 전체 5초 벽시계 제한**: OpenJDK 21의 `HttpRequest.timeout`은 응답 헤더 수신 뒤 타이머가 취소돼 본문 수신 지연을 막지 못한다 → `sendAsync` 결과를 `get(5초)`로 기다리고 초과·오류 시 `cancel(true)`. 헤더만 보내고 본문을 멈추는 로컬 서버 시험(`TurnstileCaptchaVerifierTest`)이 고정한다. 서블릿 스레드를 최대 5초 점유한다(레이트리밋이 빈도를 제한). **DB 트랜잭션 밖에서 호출**한다 — 외부 호출 중 커넥션을 쥐지 않는다.
- **설정** `cms.inquiry.turnstile.{enabled, site-key, secret-key, verify-url}`: **`enabled` 기본 `false` = "문의 기능 닫힘"** — GET·POST `/inquiry`가 모두 503이고 캡차 없이 제출을 받는 상태는 어떤 조합으로도 만들 수 없다. CI `prod-smoke`는 더미 환경으로 `false` 그대로 기동한다. dev는 `application-dev.yml`에서 `enabled=true` + Cloudflare 공개 **테스트 키**(항상 통과)다. 기동 검증(`InquiryTurnstileConfigValidator`, Bean으로 등록돼야 실행됨): `enabled=true`인데 키가 비면 기동 실패, **prod 프로파일에서 Cloudflare 공개 테스트 키(`1x0…`·`2x0…`·`3x0…`)면 기동 실패**(복사해 넣으면 캡차가 무력화되는 운영 실수 방지, 실제 키는 `0x4…`). `verify-url`은 시험이 로컬 서버를 가리키게 하는 설정이다.
- **prod 배선**: `docker-compose.prod.yml`이 환경변수를 명시 매핑하고 호스트 셸 변수 가드 목록(`scripts/_prod-env-guard.sh`)이 따로 있어, `CMS_INQUIRY_TURNSTILE_ENABLED`(`:-false`)·`_SITE_KEY`·`_SECRET_KEY` 세 이름을 둘 다에 등록했다(`.env.example`에 키 이름). **시크릿은 코드·git에 두지 않는다.**
- `CaptchaVerifier` 인터페이스는 시험이 네트워크 없이 대체하기 위한 최소 이음매이며 다른 용도로 확장하지 않는다.
- **프라이버시**: 방문자 IP와 토큰이 Cloudflare로 전송된다 — 개인정보처리방침에 고지가 필요하다(`docs/deployment.md` "문의 폼").

## 레이트리밋

`cms.rate-limit.rules`의 `inquiry-submit`(POST `/inquiry`, 용량 10 / 3600초)·`public-inquiry`(GET·HEAD, 120/60초). 토큰 버킷이라 **정확한 "시간당 10회"가 아니다** — 연속 10회 소진 뒤 약 6분마다 1회 재시도할 수 있고 첫 60분에 최대 19회까지 가능하다(폼 안내는 "짧은 시간에 여러 번 제출하면 잠시 제한됩니다"). 입력 오류 재시도도 quota를 쓰고, CSRF 실패 요청은 quota를 쓰지 않는다(필터가 `CsrfFilter` 다음). 키는 `getRemoteAddr()`라 공유 IP에서는 서로 차단될 수 있다. **`build.gradle`이 테스트 JVM 전체에 `CMS_RATE_LIMIT_ENABLED=false`를 주입하므로** 429를 보는 시험은 `@TestPropertySource(properties = "cms.rate-limit.enabled=true")`로 따로 켠다(`PublicInquiryRateLimitIntegrationTest`).

## 템플릿

`templates/public/inquiry/form.html`: 방문자 입력은 전부 `th:value`/`th:text`(자동 이스케이프), `th:utext` 없음. 스크립트는 **Turnstile 외부 스크립트 하나뿐**이고 자체 인라인 스크립트·이벤트 핸들러가 없다(이중 제출은 PRG와 레이트리밋으로 방어, 버튼 비활성화 JS 없음). CSP 헤더가 없어 차단 설정 변경은 없다. 동의 문구는 폼에 직접 싣는다(수집 항목·목적·보관) — 처리방침 페이지 링크는 하드코딩하지 않는다(없는 슬러그 방지). FAQ 하단에 "문의하기" 링크가 있다(기능이 닫혀 있으면 안내 페이지로 간다).

## 시험

`PublicInquiryIntegrationTest`(접수 왕복·400 입력 보존·캡차 비호출·토큰 형식·소프트 상한 503·로그 비유출·알림 실패 격리·병합), `PublicInquiryRateLimitIntegrationTest`(429), `PublicInquiryClosedIntegrationTest`(`enabled=false`), `PublicInquirySmallPoolIntegrationTest`(요청당 커넥션 1개), `TurnstileCaptchaVerifierTest`, `InquiryTurnstileConfigValidatorTest`(배선 포함), `PublicInquiryTemplateConventionTest`, `SecurityConfigTest`의 공개 `/inquiry` 절.
