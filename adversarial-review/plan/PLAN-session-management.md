# PLAN — 세션 관리 화면 (접속 중 관리자 조회·강제 만료) (로드맵 Top 8 ③)

> 상태: v4 — 적대적 리뷰 4라운드 ship(1·3·4라운드 codex gpt-6.1-sol, 2라운드는 codex 한도로 자체 리뷰), 사용자 승인 후 **구현·검증·코드 리뷰 완료(2026-10-11, PR 진행 중)**. 결과는 문서 끝 "구현·검증 결과".

## 개정 이력

- 4라운드: codex gpt-6.1-sol(로그 헤더 `model: gpt-6.1-sol` 확인) **ship — 새 지적 없음**. v4의 세 변경(현재 세션 항상 제외, GET 실패 재조회 금지, 기존 마이그레이션 시험 고정)이 새 결함을 만들지 않음을 확인했다. v4 문서 변경 없음.
- v4 변경(3라운드 반영 — codex gpt-6.1-sol(로그 헤더 `model: gpt-6.1-sol` 확인), P2×2·P3×1 전부 수용, 결정 필요 0건):
  - R3-1(P2 V35가 `BannerMigrationTest`를 깨뜨림): 영향 범위·작업 단계 변경 — `BannerMigrationTest.upgradeFromV32()`가 `flyway(null)`로 최신 버전까지 적용한 뒤 "최상위 메뉴 +1, 배너 메뉴가 맨 끝"을 단언한다(코드 확인: 100~110행). V35가 세션 메뉴를 추가하면 증가량 2·맨 끝은 세션 메뉴가 되어 실패한다. → 그 시험의 메뉴 시드 단언을 `flyway("34")`로 고정하고 설명을 고치며, V35는 `SessionMenuMigrationTest`가 추가·순서·멱등을 따로 검증한다. `flyway(null)`을 쓰는 다른 마이그레이션 시험(`BoardMigrationTest`·`NoticeAbsorbMigrationTest` 등)에 최신 버전 의존 단언이 있는지 구현 1단계에서 전수 점검한다.
  - R3-2(P2 목록 GET 실패 시 재조회 루프): 쟁점 9 변경 — 재조회는 **DELETE 완료 후 한 번**으로 한정, 목록 GET 실패는 오류 표시 후 종료하고 수동 [새로고침]을 기다린다(자동 반복 없음). Playwright에 "지속적인 GET 500에서 요청이 반복되지 않음" 검증 추가.
  - R3-3(P3 시험 편의 처리가 현재 세션 보호를 해제): 쟁점 3 변경 — R2-1을 철회. 회원 단위 만료는 호출자 회원 ID와 **무관하게 현재 세션 ID를 항상 제외**한다(현재 세션 ID는 별도로 확보됨). 호출자 ID 의존과 "ID 없음" 분기가 사라진다. 제외한 세션이 있을 때만 라벨에 `(현재 세션 제외)`, 화면 문구는 본인 행에서만 "내 다른 세션 모두 만료". 시험은 실제 `CustomUserDetails` principal로 구성한다.

- v3 변경(2라운드 — **codex 사용량 한도로 호출 실패(1회 재시도 후 동일), 자체 적대적 리뷰로 폴백**. 코드 사실 검증: `ConcurrentSessionFilter`의 `refreshLastRequest`·`doLogout`·`onExpiredSessionDetected`, `SessionRegistryImpl`의 `ConcurrentHashMap`·`CopyOnWriteArraySet`·`sessionIdChanged`, `InvalidRequestException`=400 `INVALID_REQUEST`·`ConflictException`=409 `RESOURCE_CONFLICT`·`ResourceNotFoundException`=404 `RESOURCE_NOT_FOUND` 확인. P1·P2 없음, P3×3 수용):
  - R2-1(P3 호출자 회원 ID 출처 불명): 쟁점 3 보강 — 컨트롤러가 `@AuthenticationPrincipal`로 호출자를 받고, `CustomUserDetails`가 아니면(슬라이스 시험의 `@WithMockUser`) "호출자 ID 없음"으로 취급해 본인 제외 없이 진행(NPE 없음).
  - R2-2(P3 만료된 세션의 목록 소멸 시점): 쟁점 4 보강 — 만료 즉시 활성 조회에서 빠지고, 레지스트리 항목은 대상의 다음 요청에서 `doLogout`이 세션을 무효화할 때(또는 유휴 타임아웃)에 소멸한다.
  - R2-3(P3 Playwright `confirm()` 멈춤): 구현 메모 — 7단계에서 dialog 핸들러를 등록한다.
  - 참고: codex 한도 복구 시각은 같은 날 오후 10:23. 복구 후 codex로 한 라운드 더 돌리고 싶으면 이 문서를 그대로 넘기면 된다.
- v1: 최초 작성(정찰 → 쟁점 10개 결정).
- v2 변경(1라운드 반영 — codex gpt-6.1-sol, P2×9·P3×1, 결정 필요 0건):
  - R1-1(P2 현재 세션 보호가 세션 ID 변경과 경합하면 우회): 쟁점 3 변경 — "경합 없음" 주장을 철회하고 보호 기준을 **"요청 진입 시점의 세션 ID"**로 명시. 같은 브라우저의 재로그인(ID 변경)이 본인 전체 만료와 겹치면 방금 로그인한 세션이 끊길 수 있으나 재로그인으로 복구되고 데이터 손실이 없어 수용. **기각: latch로 ID 변경 경합을 재현하는 시험** — 로그인 경로까지 동기화해야 막을 수 있는데 비용 대비 효과가 없다.
  - R1-2(P2 컬렉션 안전성을 원자적 만료로 확대 해석): 쟁점 8 변경 — 컬렉션 안전성(ConcurrentHashMap/COWArraySet)과 `expired`·`lastRequest`의 가시성·원자성을 구분하고, 조회는 원자 스냅샷이 아니며 만료는 best-effort(대체로 다음 요청에서 차단, 엄격·즉시 보장 아님)임을 명시. 락 추가 기각 — 필터·이벤트 경로까지 동기화되지 않는다.
  - R1-3(P2 재시도 응답·`expiredCount` 의미 충돌): 쟁점 1·2·8 변경 — 대상은 조회 시점의 활성 세션만, 단일 만료는 못 찾으면 404(이미 만료·소멸 포함)·찾아서 `expireNow()`하면 204(동시에 둘 다 찾으면 둘 다 204 — 멱등), `expiredCount`는 "이번 요청이 조회 시점의 활성 세션에 `expireNow()`를 호출한 수"(참고용, 엄밀한 신규 만료 수 아님). 중복·소멸 시험 추가.
  - R1-4(P2 감사 완료 기준을 기존 Aspect가 충족하지 못함): 쟁점 6 변경 — Aspect는 실패 행에 `targetId`·`targetLabel`을 저장하지 않고(코드 확인) 서비스 진입 전 400은 기록하지 못하며 `request_uri`에는 쿼리가 없다. 감사 범위를 **서비스 진입 이후**로 한정하고 실패 행은 대상 정보가 없음을 명시, 완료 기준 정정. Aspect 변경은 범위 밖.
  - R1-5(P2 `memberId` 누락·직접 검증의 400 처리 없음): 쟁점 2 변경 — 전역 핸들러에 `MissingServletRequestParameterException` 처리가 없어 500이 되는 것을 확인. 전역 변경은 범위 밖이므로 `memberId`를 `required=false`로 받아 컨트롤러가 `InvalidRequestException`(400)로 직접 검증하고 핸들도 `String`+정규식으로 같은 방식 검증(`@Pattern`·메서드 검증 미사용). 누락·빈 값·문자열·0 이하·핸들 형식 시험 추가.
  - R1-6(P2 "응답·로그 어디에도 원문 ID 없음"은 과대): 쟁점 1·완료 기준 변경 — 보장 범위를 **새 기능이 생성하는 정상 응답·감사 라벨·예외 메시지**로 한정. 호출자가 잘못 보낸 값이 오류 `path`에 반사되는 것은 기존 공통 동작이며 호출자가 이미 가진 값이라 새 노출이 아니다. 프레임워크 DEBUG 로그는 범위 밖(운영 로그 수준 정책).
  - R1-7(P2 401 전역 처리 부재): 쟁점 9 변경 — 새 화면이 목록 조회·두 DELETE 모두 401이면 `/admin/login`으로 이동. Playwright에 "fetch를 첫 요청으로 한 만료" 시나리오 추가.
  - R1-8(P2 "계정 수가 세션 수 상한"은 사실과 다름): 쟁점 4 변경 — 전제 정정. `maximumSessions(-1)`이라 세션 수는 무제한이며, 로그인 정책을 바꾸지 않으므로 이를 **수용된 전제**로 명시(세션은 타임아웃 30분·로그아웃으로 소멸, 접속자는 관리자·매니저뿐). 응답 상한·부분 조회는 과설계라 기각.
  - R1-9(P2 시험 누락): 시험 추가 — 두 DELETE의 CSRF 누락/오류 403 + 대상 세션 유지, 유효 CSRF의 MANAGER 거부, 실제 AOP·DB 감사(SUCCESS/FAIL·라벨·감사 저장 실패 후에도 만료 유지), 같은 회원의 여러 principal 묶음·빈 목록·동일 시각 정렬, 레지스트리 제거(`removeSessionInformation`)·현재 세션 없음 처리. **기각: 실제 서블릿 컨테이너의 세션 ID 변경·파괴 이벤트 시험** — Mock 기반 통합 시험의 한계를 계획에 명시하고 실제 서버 동작은 7단계 dev Docker 실기(로그아웃 후 목록 제거)로 대신한다.
  - R1-10(P3 컨벤션 시험이 영구 ADMIN 전용을 잠그지 않음): `AdminFeatureTest`에 `SESSION.getKind() == ADMIN_ONLY` 직접 단언 + 유효 CSRF MANAGER 차단 시험 추가.

## Context

지금 세션 강제 만료는 **자동 경로뿐**이다 — 타 관리자 수정으로 상태·역할이 실변경되면 `AdminSessionRevokeEvent` → `AdminSessionRevokeListener`(AFTER_COMMIT)가 `AdminSessionService.expireSessionsFor(memberId)`로 대상자 세션을 만료시킨다. ADMIN이 "지금 누가 접속해 있는지" 보거나, 상태·역할 변경 없이 특정 세션을 끊을 **수동 수단이 없다**. 이 계획은 ADMIN 전용 세션 관리 화면과 API를 추가한다.

로드맵 확정 결정(2026-10-07): ③의 두 후보 중 **세션 관리 화면을 먼저**(스키마 변경·공개 POST 개방 없음), ADMIN은 **본인의 다른 세션은 만료할 수 있고 지금 쓰는 세션은 막는다.** 문의하기는 후속.

### 정찰 요약 (읽은 근거)

| 사실 | 근거 |
|---|---|
| 세션 추적은 이미 켜져 있다 — `maximumSessions(-1)` + `SessionRegistryImpl`(메모리) + `HttpSessionEventPublisher`. 동시 로그인 제한은 없고 추적만 한다 | `SecurityConfig` 162~167·201~211행 |
| 레지스트리의 principal은 로그인마다 **서로 다른 `CustomUserDetails` 인스턴스**다(`equals` 미구현) — 같은 회원의 세션도 principal 키가 여러 개라 회원 단위 묶음은 **member id로 직접 해야 한다** | `AdminSessionService` 주석, `CustomUserDetails` |
| 만료는 `SessionInformation.expireNow()`뿐이고, 만료된 세션의 **다음 요청**을 `ConcurrentSessionFilter`가 가로채 `AdminSessionExpiredStrategy`가 API는 JSON 401, 페이지는 `/admin/login` 리다이렉트로 응답한다. 즉시 차단이 아니라 best-effort | `AdminSessionExpiredStrategy`, 루트 `CLAUDE.md` 보안 규칙 |
| `SessionInformation`은 `sessionId`(= `JSESSIONID` 쿠키 값)·`principal`·`lastRequest`·`expired`를 가진다. IP·User-Agent는 **저장하지 않는다** | Spring Security API |
| 만료된 세션은 세션이 실제로 소멸(타임아웃·로그아웃)할 때까지 레지스트리에 남는다 → 목록은 `getAllSessions(principal, false)`(만료 제외)로 구한다 | 기존 `expireSessionsFor` 구현 |
| ADMIN 전용 엔드포인트의 인가 컨벤션: 핸들러마다 `@PreAuthorize("hasRole('ADMIN')")`, 페이지는 `@AdminPage`(메서드 보안 없음), URL은 카탈로그 게이트에 없으면 `/admin/**` ADMIN 캐치올. `AdminEndpointAuthorizationConventionTest`가 선언 누락을 CI에서 잡는다 | `AdminActionLogController`, 권한 `CLAUDE.md` |
| `/admin/api/members/me/**`는 `MY_INFO` 상시 허용 게이트라 MANAGER도 통과한다 → `/admin/api/members/{id}/…` 아래에 ADMIN 전용 API를 두면 `{id}=me`에서 인가 전에 400이 난다(권한관리 API가 이 함정을 시험으로 고정) | 권한 `CLAUDE.md` "권한관리 API·화면" |
| 감사: `@AdminActionLogged`는 서비스 메서드에 붙고 반환 객체의 getter로 `targetId`·`targetLabel`을 뽑는다(없으면 null로 저장). 새 액션 타입은 `AdminActionTypes.ALL`과 `admin/log/manage.html`의 `ACTION_TYPE_LABELS`에 함께 등록해야 한다(`AdminActionTypeLabelSyncTest`가 잠금). `admin_action_log.request_uri`에는 요청 URI가 그대로 저장된다 | `AdminActionTypes`, `AdminActionLogAspect`, 라벨 동기화 테스트 |
| 최신 마이그레이션 V34. 메뉴 시드는 멱등 DML(`WHERE NOT EXISTS`, 최상위 맨 끝 `ord`) | `V34__seed_banner_lock_and_menu.sql` |
| 기능 카탈로그에 `ADMIN_ONLY` 항목을 두는 이유: 권한관리 매트릭스에 "위임 불가"로 보이게 하고 메뉴 URL을 기능에 매핑하기 위해서(판정은 캐치올과 같다). `ADMIN_ONLY`는 동작·게이트 패턴이 비어야 한다(`AdminFeatureTest`) | `AdminFeature`, `AdminFeatureTest` |
| 기존 세션 시험: `AdminSessionServiceTest`(실제 `SessionRegistryImpl` 단위), `AdminSessionRevocationIntegrationTest`(실제 로그인 → 만료 → 다음 요청 거부, Testcontainers), `AdminSessionExpiredStrategyTest` | `src/test/java/com/cms/config/**` |

## 쟁점과 결정

### 1. 세션 식별자 비노출 — 원문 `sessionId` 대신 해시 핸들 (결정)

**쟁점**: 만료할 세션을 클라이언트가 지목해야 하는데, 레지스트리의 `sessionId`는 `JSESSIONID` 쿠키 값 자체다. 응답·URL·감사 로그(`request_uri`)·브라우저 기록·프록시 로그에 원문이 남으면 **세션 탈취 재료**가 된다(ADMIN 전용 화면이어도 로그·기록은 ADMIN 권한 밖으로 퍼진다).

| 선택지 | 장점 | 단점 |
|---|---|---|
| A. 원문 `sessionId`를 그대로 사용 | 구현 최소 | 탈취 재료가 응답·URL·감사 로그에 남는다 — **기각** |
| B. 서버가 요청마다 임시 번호 맵을 보관(1,2,3…) | 짧음 | 상태 보관·재시작/동시 요청 시 번호 어긋남(다른 세션을 끊을 위험) |
| C. 목록 순번(index)으로 지목 | 상태 없음 | 목록과 만료 사이에 순서가 바뀌면 **엉뚱한 세션을 끊는다** — **기각** |
| D. `SHA-256(sessionId)` 앞 32 hex(128비트)를 핸들로 사용, 서버가 레지스트리를 순회해 해시가 같은 세션을 찾는다 | 상태 없음, 결정적(목록·만료 사이 안정), 원문 복원 불가(세션 ID는 128비트 이상 난수라 역상·사전 공격이 성립하지 않는다) | 만료 시 순회 O(세션 수) — 관리자 세션 수는 작다 |
| E. 요청마다 새 난수 키를 쓴 HMAC | 핸들이 재시작마다 바뀜 | 키 관리가 늘 뿐 D 대비 이득이 없다(세션은 재시작 때 어차피 사라진다) |

**결정: D.** 핸들은 `[0-9a-f]{32}`. 형식이 다르면 400, 형식이 맞는데 **조회 시점에 활성인** 대응 세션이 없으면(이미 만료·소멸 포함) 404. 핸들은 URL에 들어가 감사 `request_uri`에 남지만 **원문이 아니라 해시**라 재사용·탈취 불가다.

**보장 범위(v2 정정)**: 새 기능이 생성하는 **정상 응답 본문·감사 `targetLabel`·예외 메시지**에 원문 `sessionId`를 넣지 않는다 — 테스트가 이 범위를 단언한다. 범위 밖: ① 호출자가 `{handle}` 자리에 잘못 보낸 값이 오류 응답의 `path`·감사 `request_uri`에 반사되는 것(기존 공통 동작이며 호출자가 이미 가진 값이라 새 노출이 아니다), ② Spring Security 자체가 세션 등록·만료 때 DEBUG 로그에 원문 ID를 찍는 것(운영 로그 수준 정책 — `prod`는 DEBUG를 켜지 않는다). "어떤 로그에도 없음"이라는 절대 보장은 하지 않는다.

### 2. API 경로 — `/admin/api/sessions` 한 곳에 모은다 (결정)

| 선택지 | 비고 |
|---|---|
| A. `DELETE /admin/api/members/{memberId}/sessions` | 자연스럽지만 `{memberId}=me`가 `MY_INFO` 게이트(`/admin/api/members/me/**`)를 통과해 MANAGER가 인가 전에 400을 받는 경로 혼선(권한관리 API가 시험으로 고정해 둔 함정) |
| B. `/admin/api/sessions` 아래에 모두 둔다 | 새 경로는 어떤 카탈로그 게이트에도 걸리지 않아 `/admin/**` ADMIN 캐치올이 막는다. **`SecurityConfig` 변경 없음** |

**결정: B.**

| 메서드 | 경로 | 동작 | 성공 | 실패 |
|---|---|---|---|---|
| `GET` | `/admin/api/sessions` | 활성(만료 안 된) 세션을 회원별로 묶어 조회 | 200 | — |
| `DELETE` | `/admin/api/sessions/{handle}` | 세션 하나 만료 | 204 | 400(핸들 형식), 404(없음), 409(**현재 세션**) |
| `DELETE` | `/admin/api/sessions?memberId={id}` | 그 회원의 활성 세션 전부 만료. **현재 세션은 항상 제외** | 200 `{expiredCount}` | 400(`memberId` 누락·형식) |

회원 단위 만료는 컬렉션 `DELETE` + 필수 쿼리 파라미터다(동사를 경로에 넣지 않는 규칙). 대상 회원이 존재하는지는 확인하지 않는다 — 레지스트리에 세션이 없으면 `expiredCount: 0`이고 이는 정상 응답이다(회원 존재 여부 노출 없음, 이미 로그아웃한 경우와 구분하지 않는다). `expiredCount`는 **"이번 요청이 조회 시점에 활성이던 세션에 `expireNow()`를 호출한 수"**다(`expireNow()`가 최초 만료 여부를 알려 주지 않아 엄밀한 "신규 만료 수"는 아니다 — 참고용 수치).

**입력 검증(v2, R1-5)**: 전역 `GlobalApiExceptionHandler`에는 `MissingServletRequestParameterException` 처리가 없어 필수 `@RequestParam`이 누락되면 500이 된다. 전역 핸들러 변경은 범위 밖이므로 `memberId`는 `@RequestParam(required = false) Long`으로 받아 컨트롤러가 `InvalidRequestException`(400, 고정 문구)로 직접 검증한다(누락·0 이하). 타입 불일치(`abc`)는 기존 `MethodArgumentTypeMismatchException` 핸들러가 400으로 처리한다. `{handle}`은 `String`으로 받아 `^[0-9a-f]{32}$`를 같은 방식으로 검증한다(`@Pattern`·`@Validated` 메서드 검증은 쓰지 않는다 — 처리 핸들러가 없다).

### 3. 현재 세션 보호 — 서버가 판정, 화면은 보조 (결정)

로드맵 확정: **본인의 다른 세션은 만료할 수 있고 지금 쓰는 세션은 막는다.**

- 현재 세션 = **요청 진입 시점**의 `HttpSession`(`request.getSession(false)`) ID. 컨트롤러가 이를 서비스에 넘기고, 서비스는 핸들로 비교한다(서비스가 `HttpServletRequest`를 모른다). 회원 단위 만료는 **호출자 회원 ID와 무관하게 현재 세션 ID를 항상 제외**한다(v4 — 현재 세션 ID는 별도로 확보되므로 principal 종류에 의존하지 않는다. 대상 회원이 호출자가 아니면 현재 세션이 대상 집합에 없으니 결과는 같다). 호출자 회원 ID는 판정에 쓰지 않는다.
- 단일 만료(`{handle}`)가 현재 세션이면 **409 `RESOURCE_CONFLICT`**(상태 충돌 — "현재 사용 중인 세션은 만료할 수 없습니다. 로그아웃을 사용하세요"). 400이 아닌 이유: 요청 형식은 맞고 서버 상태(이 세션이 호출자의 것)와 충돌하기 때문이다.
- **보호 계약의 한계(v2, R1-1)**: 보호는 "요청 진입 시점의 세션 ID"를 기준으로 한다. 세션 ID는 로그인(세션 고정 방어)에서만 바뀌므로, 같은 브라우저의 재로그인이 본인 대상 회원 단위 만료와 정확히 겹치면 방금 로그인한 새 세션이 끊길 수 있다 — 재로그인으로 복구되고 데이터 손실이 없는 경합이라 수용하며, 이를 막기 위한 로그인 경로 동기화나 latch 재현 시험은 두지 않는다.
- 회원 단위 만료는 대상 집합에서 현재 세션(있으면)을 **건너뛰고** 나머지를 만료한다(요청 전체를 거부하지 않는다 — 본인 대상의 "내 다른 세션 모두 끊기"가 의도된 사용이다). 다른 회원 대상이면 현재 세션이 집합에 없으니 전부 만료된다. 현재 세션 ID를 얻을 수 없으면(이론상 불가 — 인증된 요청) 제외 없이 진행한다. 감사 라벨의 `(현재 세션 제외)`는 실제로 제외한 세션이 있을 때만 붙는다.
- 목록 응답은 세션마다 `current: true/false`를 낸다. 화면은 현재 세션 행의 [만료]를 비활성화하지만 **인가·보호는 서버가 한다**(화면 우회 직접 호출은 409).
- 다른 ADMIN의 세션은 만료할 수 있다(전원 ADMIN 신뢰, 계정 잠금과 같은 수준의 권한). 마지막 ADMIN lockout 위험은 없다 — 세션 만료는 계정을 막지 않고 재로그인하면 된다.

### 4. 목록 구성 — 레지스트리 스냅샷, DB 조회 없음, 회원별 묶음 (결정)

- `AdminSessionService.listActiveSessions()`: `getAllPrincipals()` → `CustomUserDetails`만 → `getAllSessions(principal, false)`(만료 제외) → 세션 목록(핸들·회원 id·아이디·이름·역할·마지막 요청). principal 스냅샷 값(로그인 시점)을 쓴다 — **DB를 조회하지 않는다**. 이유: 이름 변경은 세션을 만료시키지 않아 이름이 낡을 수 있으나 표시용이고, 역할·상태가 바뀌면 이미 세션이 만료되므로 권한 판단에 쓰이지 않는다. DB 조회를 넣으면 실패 모드(조회 실패 시 화면 전체 실패)·N+1/IN 쿼리가 생기는데 얻는 것은 이름의 최신성뿐이다. 화면에 "표시 정보는 로그인 시점 기준"을 한 줄 안내한다.
- 응답: 회원 단위 `{memberId, userId, userName, role, sessionCount, lastRequestAt, sessions:[{handle, lastRequestAt, current}]}`. 회원은 `lastRequestAt` 내림차순, 세션도 내림차순(같으면 `memberId`·`handle` 오름차순으로 순서를 고정). 최상위에 `totalSessions`. 페이징 없음.
- **규모 전제(v2 정정, R1-8)**: `maximumSessions(-1)`이라 세션 수에 상한이 없고 한 계정도 로그인마다 별도 principal로 등록된다 — "계정 수가 상한"이라는 v1의 근거는 틀렸다. 로그인 정책을 바꾸지 않으므로 **세션 수 무제한을 수용된 전제**로 둔다. 세션은 유휴 타임아웃(기본 30분)·로그아웃으로 소멸하고 로그인할 수 있는 주체는 관리자·매니저뿐이라 현실 규모는 작다. 목록·정렬·직렬화·핸들 순회 비용은 세션 수에 선형이다. 응답 상한·부분 조회는 과설계라 두지 않는다.
- `lastRequestAt`: `SessionInformation.getLastRequest()`(`Date`)를 `LocalDateTime.ofInstant(date.toInstant(), clock.getZone())`로 변환한다(KST `Clock` 규약 — `now()` 직접 호출 없음, `ClockUsageConventionTest` 통과). **목록을 부르는 요청 자신이 호출자 세션의 `lastRequest`를 갱신한다**(`ConcurrentSessionFilter`) — 자기 행은 항상 "방금"으로 보인다. 정상 동작이며 화면에 설명하지 않는다.
- 만료된 세션의 소멸 시점(v3): `expireNow()` 직후부터 활성 조회에서 빠져 목록·핸들 조회에 나타나지 않는다. 레지스트리 항목 자체는 대상의 다음 요청에서 `ConcurrentSessionFilter`가 `doLogout`으로 세션을 무효화할 때(또는 유휴 타임아웃 시)에 사라진다 — 활성만 조회하므로 화면에는 영향이 없다.
- 노출하지 않는 것: 원문 세션 ID, IP·UA(레지스트리가 저장하지 않는다 — 저장하려면 필터를 추가해야 해 범위 밖), 비밀번호 해시 등 principal의 다른 필드.
- 응답은 `Cache-Control`을 따로 건드리지 않는다(Spring Security 기본 `no-store`가 `/admin/api/**`에 적용됨).

### 5. 인가 — 영구 ADMIN 전용, `SecurityConfig` 변경 없음 (결정, **인가 정책 변경 없음**)

- 모든 핸들러에 `@PreAuthorize("hasRole('ADMIN')")`(컨벤션 테스트 충족), 페이지는 `@AdminPage` + `GET /admin/session/manage`(메서드 보안 없음 — 차단은 URL 캐치올이 HTML 403으로).
- 새 경로(`/admin/api/sessions…`, `/admin/session/manage`)는 어떤 카탈로그 `gatePatterns`에도 없으므로 **기존 `/admin/**` ADMIN 캐치올이 막는다** → URL 인가 규칙 추가·변경 없음. 위임 가능 기능으로 만들지 않는다 — 세션 만료는 다른 관리자의 작업을 끊는 권한이라 위임 대상이 아니다(`MEMBER`·`ACTION_LOG`와 같은 등급).
- `AdminFeature.SESSION(ADMIN_ONLY, "세션 관리", noneOf, menuUrls=["/admin/session/manage"], gate=[])`을 추가한다. 목적은 권한관리 매트릭스의 "위임 불가" 표시와 메뉴 URL→기능 분류(`forMenuUrl`)다. `AdminFeatureTest`의 ADMIN_ONLY 불변식(동작·게이트 없음)을 만족한다.

### 6. 감사 — `SESSION_EXPIRE` 하나, 대상은 세션 소유 회원 (결정)

- 서비스 메서드(`AdminSessionManageService.expireSession`·`expireMemberSessions`)에 `@AdminActionLogged(actionType = SESSION_EXPIRE, targetType = "MEMBER", targetIdExpression = "memberId", targetLabelExpression = "auditLabel")`.
- 결과 객체 `SessionExpireResult`가 `Long getMemberId()`·`String getAuditLabel()`을 노출한다(없으면 `targetId=null`로 저장되는 Aspect 계약 — 권한관리와 같음). 라벨은 코드 상수·숫자만: 단일은 `세션 1개 만료`, 회원 단위는 `세션 n개 만료`(현재 세션을 실제로 제외했으면 `세션 n개 만료(현재 세션 제외)`). **세션 핸들·ID는 라벨에 넣지 않는다.**
- **감사 범위(v2 정정, R1-4)**: 코드 확인 결과 `AdminActionLogAspect`의 실패 처리는 `targetId`·`targetLabel`을 **저장하지 않는다**(둘 다 null — getter 추출은 성공 처리뿐). 따라서 서비스 진입 이후의 실패(404·409)는 FAIL 감사 1건이 남되 **대상 회원·라벨은 없다**(예: 현재 세션 거부 409도 소유 회원을 알지만 남기지 않는다). 서비스 진입 전에 컨트롤러가 거르는 400(핸들 형식·`memberId` 누락 등)은 서비스의 `@AdminActionLogged`가 닿지 못해 **감사되지 않는다**(다른 API와 같은 정책). `request_uri`는 `getRequestURI()`라 쿼리 문자열(`?memberId=`)이 없다 — 회원 단위 만료의 대상 회원은 **성공 행의 `targetId`**로만 추적된다. 이 한계를 받아들이고 Aspect·감사 모델은 바꾸지 않는다(범위 밖). 회원 단위 만료가 0건이어도 성공(200)이므로 `세션 0개 만료`가 SUCCESS로 남는다 — "누가 끊으려 했는가"의 기록이 목적이다.
- `AdminActionTypes.SESSION_EXPIRE`를 상수·`ALL`에 추가하고 `admin/log/manage.html`의 `ACTION_TYPE_LABELS`에 `SESSION_EXPIRE: "세션 강제 만료"`를 등록한다(동기화 시험이 잠금).
- 서비스는 레지스트리 조작뿐이라 DB 트랜잭션이 필요 없다(`@Transactional` 없음). 감사 저장은 기존 계약대로 독립 트랜잭션·예외 격리다.

### 7. 만료 계약 — best-effort를 UI 문구로 정직하게 (결정)

`expireNow()`는 **다음 요청부터** 거부시킨다. 진행 중인 요청은 끝까지 처리되고, 대상이 편집 중이던 입력은 사라질 수 있다. 화면의 확인창에 "대상은 다음 요청부터 로그인이 필요하며 입력 중인 내용을 잃을 수 있습니다"를 적는다. 레지스트리는 **단일 인스턴스 메모리**라 다중 인스턴스에서는 이 화면이 해당 인스턴스의 세션만 본다(기존 레이트리밋·권한 캐시와 같은 전제 — `CLAUDE.md`에 명시). 서버 재시작은 모든 세션을 소멸시킨다(목록이 빈다).

### 8. 동시성 — 락을 두지 않고 best-effort 계약을 유지한다 (결정, v2 정정)

`SessionRegistryImpl`(Spring Security 7.0.7)은 기본 맵과 principal별 집합이 `ConcurrentHashMap`·`CopyOnWriteArraySet`이라 **컬렉션 자체는** 동시 접근에 안전하다. 그러나 이를 원자적 만료 보장으로 넓혀 읽지 않는다: ① `SessionInformation.expired`·`lastRequest`는 **일반 필드**이고 `expireNow()`는 동기화 없이 `expired = true`만 한다(다른 스레드의 가시성이 엄격하지 않다), ② `getAllPrincipals()`와 각 `getAllSessions()`는 **하나의 원자적 스냅샷이 아니다**(목록 도중 세션이 생기거나 사라질 수 있다), ③ 반환되는 `SessionInformation`은 변경 가능한 참조다. 그래서 계약은 기존과 같은 **best-effort**("대체로 만료된 세션의 다음 요청에서 거부", 즉시·엄격 보장 아님 — 루트 `CLAUDE.md` 보안 규칙)이며 관리 서비스에 락을 추가하지 않는다(락은 Security 필터·세션 이벤트 경로까지 동기화하지 못해 보장을 만들지 못한다).

**경합 처리 규칙**:
- 목록과 만료 사이에 세션이 로그아웃·타임아웃으로 사라지면 → 단일 만료는 못 찾아 404(`RESOURCE_NOT_FOUND`), 회원 단위는 그 세션을 세지 않는다. 조회 후 소멸한 객체에 `expireNow()`를 호출하는 경우는 무해하며 건수에 포함될 수 있다(`expiredCount`는 참고용).
- 같은 세션을 두 관리자가 거의 동시에 만료하면: 둘 다 조회에서 찾으면 둘 다 204(`expireNow()` 멱등), 한쪽이 먼저 만료시킨 뒤 늦은 쪽이 조회하면 활성 목록에 없으므로 404. 어느 쪽이든 결과 상태는 "만료됨"이다.
- 이미 만료됐지만 레지스트리에 남아 있는 세션은 활성 조회(`getAllSessions(principal, false)`)에서 빠지므로 목록에도, 핸들 조회에도 없다(404). 응답이 두 종류로 갈리지 않는다.
- 만료 직후 같은 회원의 신규 로그인은 새 세션이라 영향 없다(회원 단위 만료는 "그 시점의 활성 세션"만 대상).

### 9. 화면 (결정, 과설계 경계)

- `templates/admin/session/manage.html` — 다른 관리 화면과 같은 쉘(`admin-manage-shell`·`admin-panel`)·CSRF 메타·`textContent`만 사용(이름·아이디 삽입 시 XSS 방지). 스크립트는 로그·배너 화면과 같이 템플릿 인라인.
- 구성: 상단 [새로고침] + 마지막 갱신 시각 + 총 세션 수, 회원별 행(아이디·이름·역할·세션 수·마지막 요청) 아래에 세션 행(마지막 요청·"현재 세션" 배지·[만료]), 회원 행에 [이 회원의 세션 모두 만료](본인 행은 문구 "내 다른 세션 모두 만료", 다른 세션이 없으면 비활성).
- **자동 갱신(폴링)·웹소켓은 두지 않는다**(수동 [새로고침] — 요청 자체가 호출자 세션의 `lastRequest`를 갱신하므로 폴링은 의미를 흐린다). 만료 성공·실패 후에는 목록을 다시 불러온다.
- **401 처리(v2, R1-7)**: 공통 스크립트(`sb-admin-2.js`)에는 fetch의 401을 다루는 전역 처리가 **없다**(배너·쪽지·검색 화면도 각자 처리) — v1의 "기존 전역 처리에 맡긴다"는 사실과 달랐다. 이 화면은 **목록 조회와 두 DELETE의 응답이 401이면 화면의 목록을 비우고 `/admin/login`으로 이동**한다(다른 ADMIN이 내 세션을 끊은 경우 오래된 목록을 계속 보지 않게). 그 밖의 오류(403·404·409·5xx)는 화면 상단 알림에 서버 메시지를 표시한다. **재조회는 DELETE 완료(성공·실패 모두) 뒤 한 번으로 한정**한다 — 목록 GET이 실패하면 오류만 표시하고 멈추며 수동 [새로고침]을 기다린다(v4, R3-2: 지속적인 403·5xx에서 `조회 실패 → 재조회` 루프가 장애 중 부하를 증폭하지 않게 한다. 재조회 자체가 실패해도 다시 재조회하지 않는다).
- 만료는 `window.confirm()`으로 한 번 확인한다(되돌릴 수 없는 동작).

### 10. 메뉴 시드 V35 — 최상위 맨 끝 (결정)

`V35__seed_session_menu.sql`: V34의 메뉴 INSERT와 같은 멱등 DML(`WHERE NOT EXISTS (menu_url = '/admin/session/manage')`, `ord` = 최상위 `MAX+1`(INT 상한 `LEAST`), 날짜 NULL, 아이콘 `fas fa-fw fa-user-clock`, 이름 "세션 관리"). **DDL 없음**(순수 DML — 기존 시드 규칙). 노출은 카탈로그가 정한다 — `SESSION`은 ADMIN_ONLY라 ADMIN에게만 보인다. 이전 버전 앱으로 롤백하면 이 메뉴가 남아 클릭 시 404이므로 `docs/deployment.md`의 롤백 메모에 한 줄 추가한다(배너 메뉴와 같은 취급).

## 영향 범위 (파일)

- **기존 시험 수정(v4, R3-1)**: `admin/banner/BannerMigrationTest`의 메뉴 시드 단언(`upgradeFromV32()`의 "최상위 +1·맨 끝")을 `flyway("34")`로 고정. `flyway(null)`을 쓰는 다른 마이그레이션 시험(`BoardMigrationTest`·`NoticeAbsorbMigrationTest` 등)은 구현 1단계에서 grep으로 최신 버전 의존 단언(메뉴 개수·맨 끝)을 전수 점검해 같은 방식으로 고정한다.
- 수정: `config/auth/AdminSessionService.java`(목록 조회·핸들 도출·핸들 만료·제외 세션 인자 추가 — 기존 `expireSessionsFor(Long)`은 시그니처 유지), `admin/permission/AdminFeature.java`(`SESSION`), `admin/log/constant/AdminActionTypes.java`(+`ALL`), `templates/admin/log/manage.html`(`ACTION_TYPE_LABELS`)
- 신규: `admin/session/controller/AdminSessionController.java`·`AdminSessionPageController.java`, `admin/session/service/AdminSessionManageService.java`, `admin/session/dto/response/{SessionListResponse, SessionMemberResponse, SessionItemResponse, SessionExpireResult, SessionExpireResponse}.java`, `admin/session/CLAUDE.md`, `templates/admin/session/manage.html`, `V35__seed_session_menu.sql`
- 문서: 루트 `CLAUDE.md`(지침 파일 지도·보안 규칙 세션 항목), `com.cms.config`·`com.cms.admin.member`의 `CLAUDE.md` 중 세션 설명 부분, `docs/deployment.md`(롤백 메모), 로드맵(③ 완료는 `/updateRoadmap`)
- **변경 없음(확인 대상)**: `SecurityConfig`(캐치올이 막음), `AdminSessionExpiredStrategy`·`AdminSessionRevokeListener`(기존 자동 만료 경로 무변경), 엔티티·테이블(스키마 변경 없음)
- 시험: 신규 `AdminSessionServiceTest` 확장, `AdminSessionControllerTest`(슬라이스), `AdminSessionManageServiceTest`, `AdminSessionManagementIntegrationTest`(Testcontainers, 실제 로그인), `SessionMenuMigrationTest`, `AdminFeatureTest`(+`SESSION` ADMIN_ONLY 직접 단언)·`AdminActionTypeLabelSyncTest`·`AdminEndpointAuthorizationConventionTest`·`AdminPageAnnotationConventionTest`는 기존 시험이 자동으로 신규 항목을 검사
- **시험 항목(v2 추가)**:
  - 서비스: 같은 회원의 여러 principal 묶음, 빈 목록, 같은 시각 정렬 고정, 레지스트리에서 `removeSessionInformation`으로 소멸시킨 세션이 목록·핸들 조회에서 사라짐(404), 이미 만료된 세션 핸들은 404, 조회 후 소멸·중복 만료 시 `expiredCount` 정의대로, 현재 세션 ID가 null인 경우(제외 없음).
  - 컨트롤러/MVC: 두 DELETE 모두 **CSRF 누락·잘못된 토큰 403 + 서비스 미호출 + 대상 세션 유지**, **유효한 CSRF를 가진 MANAGER도 403**(CSRF 거부를 인가 거부로 오인하지 않게), 미인증 JSON 401, `memberId` 누락·빈 값·`abc`·0·음수·범위 초과 400, 핸들 형식 오류 400(실제 MVC 요청).
  - 감사(실제 AOP·DB): 성공은 `targetId`=소유 회원·라벨 숫자·상수, 404·409는 FAIL 1건(대상·라벨 없음), 서비스 진입 전 400은 감사 행이 생기지 않음, 감사 저장이 실패해도 만료 결과는 유지.
  - 정책 고정: `AdminFeatureTest`에 `SESSION.getKind() == ADMIN_ONLY` 단언.
  - **시험 한계(명시)**: 통합 시험은 `MockMvc`·`MockHttpSession` 기반이라 실제 서블릿 컨테이너의 세션 ID 변경·파괴 이벤트는 검증하지 못한다. 그 부분(로그아웃 후 목록에서 사라짐)은 7단계 dev Docker 실기로 확인한다.

## 작업 단계 (구현 순서)

1. **레지스트리 파사드**: `AdminSessionService`에 핸들 도출·목록·핸들 만료·제외 세션 인자 + `AdminSessionServiceTest` 확장(핸들이 원문을 포함하지 않음·결정적임, 만료 제외, 제외 세션 보호).
2. **카탈로그·감사 상수**: `AdminFeature.SESSION`, `AdminActionTypes.SESSION_EXPIRE`, 로그 화면 라벨.
3. **관리 서비스·DTO**: `AdminSessionManageService`(현재 세션 보호·감사 어노테이션) + 단위 시험.
4. **컨트롤러**: API 3개 + 페이지, 슬라이스 시험(ADMIN 200/204/409, MANAGER 403 JSON, 미인증 401, 핸들 형식 400, 컨벤션 시험 통과).
5. **마이그레이션**: V35 + `SessionMenuMigrationTest`(추가·최상위 맨 끝·멱등) + 기존 `flyway(null)` 시험의 최신 버전 의존 단언 점검·고정(`BannerMigrationTest` 포함).
6. **화면**: `manage.html`.
7. **통합 시험**(Testcontainers): 세션 2개 로그인 → 목록 → 단일 만료 → 대상의 다음 API 401·페이지 302 → 현재 세션 만료 409 → 본인 다른 세션 만료 가능 → 회원 단위 만료 → 응답에 원문 ID 없음 → MANAGER 403.
8. **전체 테스트 → dev Docker 실기(Playwright)** → 문서 갱신.

## 완료 기준 (로드맵 반영)

- [ ] `./gradlew test` 통과, CI 통과, MANAGER 접근(페이지 403·API JSON 403)·미인증(API 401·페이지 로그인 리다이렉트) 보안 테스트
- [ ] 강제 만료 후 대상 세션의 다음 요청이 API는 JSON 401, 페이지는 `/admin/login` 리다이렉트(`AdminSessionExpiredStrategy` 계약)
- [ ] 요청자의 현재 세션 단일 만료는 409로 거부되고 세션은 유지, 본인의 다른 세션은 만료됨, 회원 단위 만료(본인 대상)는 현재 세션을 남기고 나머지만 만료
- [ ] 새 기능이 생성하는 정상 응답 본문·감사 라벨·예외 메시지에 원문 세션 ID가 없음(테스트로 단언 — 보장 범위는 쟁점 1)
- [ ] 감사 `SESSION_EXPIRE`: 성공은 `targetId`=소유 회원 ID·라벨 숫자·상수만, 서비스 진입 이후 실패(404·409)는 FAIL 1건(대상·라벨 없음), 서비스 진입 전 400은 감사 행 없음, 감사 저장 실패에도 만료 결과 유지
- [ ] 두 DELETE의 CSRF 누락/오류 403(서비스 미호출·세션 유지), 유효 CSRF의 MANAGER 403, `AdminFeatureTest`의 `SESSION` ADMIN_ONLY 단언
- [ ] `memberId` 누락·0 이하·문자열, 핸들 형식 오류가 500이 아니라 400
- [ ] Playwright: 두 브라우저 컨텍스트(ADMIN 2명 또는 한 계정 2세션) → 목록에 둘 다 표시 → 하나 만료 → 만료된 쪽 다음 동작이 로그인으로 → 현재 세션 [만료] 비활성 → **만료된 세션이 fetch(목록 새로고침)를 첫 요청으로 보내도 로그인 화면으로 이동** → 로그아웃한 세션이 목록에서 사라짐 → **목록 GET이 지속적으로 500/403이어도 요청이 자동 반복되지 않음**(요청 수 관측)

## 리스크

| 리스크 | 완화 |
|---|---|
| 원문 세션 ID가 응답·로그·감사로 샌다 | 핸들=해시 설계(쟁점 1), 응답/감사/예외 어디에도 원문을 쓰지 않고 테스트로 단언 |
| 목록과 만료 사이 순서 변화로 엉뚱한 세션을 끊는다 | 순번이 아니라 세션 ID에서 결정적으로 도출한 핸들로 지목 |
| ADMIN이 자기 세션을 끊어 작업이 끊긴다 | 서버가 현재 세션 만료를 409로 막고, 회원 단위는 현재 세션 제외(요청 진입 시점 ID 기준 — 재로그인 경합은 수용, 쟁점 3) |
| 세션 수 무제한(`maximumSessions(-1)`)이라 목록이 커진다 | 수용된 전제로 명시(쟁점 4) — 타임아웃·로그아웃으로 소멸, 현실 규모 작음 |
| 감사 실패 행에 대상이 없고 서비스 진입 전 400은 기록되지 않는다 | 한계로 명시(쟁점 6) — Aspect는 바꾸지 않는다 |
| 만료가 즉시 차단이라는 오해 | UI 문구·`CLAUDE.md`에 best-effort(다음 요청부터)와 입력 손실 명시 |
| 다중 인스턴스에서 일부 세션만 보인다 | 단일 인스턴스 전제를 `CLAUDE.md`에 명시(기존 한계와 동일) |
| 표시 이름이 낡을 수 있다 | 로그인 시점 기준임을 화면에 안내(권한 판단에는 쓰지 않음) |
| 메뉴 롤백 시 죽은 링크 | `docs/deployment.md` 롤백 메모 |

## 후속(이 계획 밖)

- 세션의 IP·User-Agent·로그인 시각 표시(레지스트리에 없음 → 로그인 성공 핸들러·필터 추가 필요)
- 다중 인스턴스용 외부 세션 저장소
- 만료 대상에게 알림(E1~E4 알림 체계 연계)
- 문의하기(로드맵 ③ 후속)

## 구현·검증 결과 (2026-10-10)

> 상태: **구현·검증·코드 리뷰 완료, PR 진행 중**(브랜치 `feat/session-management`). 코드 리뷰 루프는 1라운드에서 approve(지적 0건)로 끝났다. CI 결과와 머지는 PR에서 확인한다.

### Context

로드맵 Top 8 ③ 앞쪽 — ADMIN 전용 세션 관리 화면. 계획 v4(적대적 리뷰 4라운드 ship: 1·3·4라운드 codex `gpt-6.1-sol`, 2라운드는 codex 사용량 한도로 자체 리뷰)를 그대로 구현했다. 스키마 변경 없음, 인가 정책 변경 없음(`SecurityConfig` 무변경).

### 핵심 확정 사항 (계획 v4 그대로 + 구현 중 달라진 점)

- 계획과 달라진 결정은 **없다**. 세부 구현 선택: ① 회원 단위 만료 결과를 `MemberExpiration(expiredCount, currentExcluded)` record로 돌려 감사 라벨의 `(현재 세션 제외)`를 "실제로 제외했을 때만" 붙인다, ② 기존 자동 만료 `expireSessionsFor(Long)`는 시그니처를 유지하고 새 오버로드에 위임한다, ③ `SessionExpireResult`는 `getMemberId()`·`getAuditLabel()` getter가 필요해 record가 아니라 Lombok getter 클래스다(Aspect 리플렉션 계약).
- 2라운드 자체 리뷰의 R2-1(호출자 ID 없음 분기)은 3라운드에서 철회돼 구현에 없다 — 회원 단위 만료는 현재 세션 ID를 호출자 회원과 무관하게 항상 제외한다.

### 구현 파일

- 수정: `config/auth/AdminSessionService`(핸들·목록·핸들 만료·제외 세션 인자), `admin/permission/AdminFeature`(`SESSION`), `admin/log/constant/AdminActionTypes`(`SESSION_EXPIRE`+`ALL`), `templates/admin/log/manage.html`(라벨)
- 신규: `admin/session/{controller/AdminSessionController, AdminSessionPageController, service/AdminSessionManageService, dto/response/SessionItemResponse·SessionMemberResponse·SessionListResponse·SessionExpireResponse·SessionExpireResult, CLAUDE.md}`, `templates/admin/session/manage.html`, `db/migration/V35__seed_session_menu.sql`
- 시험: 신규 `AdminSessionManageServiceTest`·`AdminSessionControllerTest`·`AdminSessionManagementIntegrationTest`·`SessionMenuMigrationTest`, 확장 `AdminSessionServiceTest`·`AdminFeatureTest`, 수정 `BannerMigrationTest`(메뉴 시드 단언을 `flyway("34")`로 고정 — R3-1)
- 문서: 루트 `CLAUDE.md`(지침 파일 지도·세션 보안 항목), `com.cms.config`·`com.cms.admin.permission`의 `CLAUDE.md`, `docs/deployment.md`(V35 롤백·재시작 메모), 이 인덱스

### 검증 결과

- **전체 `./gradlew cleanTest test`**: 1788건 중 실패 0·오류 0·건너뜀 12(9분 37초, BUILD SUCCESSFUL, 2026-10-10 로컬 Windows·Docker Desktop). 건너뜀 12건은 전부 `LocalDiskFileStorageTest`의 "이 환경은 심볼릭 링크 생성을 지원하지 않아 건너뜀"(Windows 권한 — 앱 코드와 무관, 직전 작업의 12건과 같은 수)이다.
- **UTC 재실행(2026-10-11)**: `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test` → 1788건 실패 0·오류 0·건너뜀 12(8분 42초, BUILD SUCCESSFUL). Gradle 출력에 `Picked up JAVA_TOOL_OPTIONS: -Duser.timezone=UTC`가 세 번 찍혀 옵션이 JVM에 실제로 전달됐음을 확인했다(배너 작업 때는 이 확인이 없었다).
- **변이 실험(2026-10-11, 변이마다 체크섬으로 적용·원복 확인 — 8건 전부 시험이 잡았다)**:

| # | 변이 | 실패한 시험 |
|---|---|---|
| M1 | 회원 단위 만료의 현재 세션 제외 제거 | `AdminSessionServiceTest`·`AdminSessionManageServiceTest`(제외 세션·라벨) |
| M2 | 핸들을 원문 세션 ID로 반환 | `AdminSessionServiceTest` 2건(핸들 형식·원문 비포함·목록)·`AdminSessionManageServiceTest`(원문 비노출) |
| M3 | 목록에 만료된 세션 포함(`getAllSessions(principal, true)`) | `AdminSessionServiceTest` 2건(활성만 나열·정렬) |
| M4 | 회원 단위 DELETE의 `@PreAuthorize` 제거 | `AdminEndpointAuthorizationConventionTest`(선언 누락)·`AdminSessionControllerTest`(MANAGER 403) — URL 캐치올이 없는 슬라이스에서도 잡힘 |
| M5 | `memberId`를 필수 `@RequestParam`으로 | `AdminSessionControllerTest` — **`Status expected:<400> but was:<500>`로 누락 시 500을 직접 확인** |
| M6 | 단일 만료의 현재 세션 409 가드 제거 | `AdminSessionManageServiceTest` |
| M7 | `@AdminActionLogged` 두 곳 제거(감사 누락) | `AdminSessionManagementIntegrationTest` 6건(감사 행·라벨·FAIL 행) |
| M8 | 현재 세션 제외 제거(통합) | `AdminSessionManagementIntegrationTest`(본인 대상 만료가 현재 세션 유지) |
- **새 시험이 고정한 계약**: 핸들(32 hex·결정적·원문 비포함)·만료 제외 목록·정렬 고정(같은 시각 포함)·핸들 만료(404 근거: 없는 핸들·이미 만료·레지스트리에서 사라짐)·회원 단위 만료의 현재 세션 제외·두 DELETE의 CSRF 누락/오류 403(서비스 미호출)·유효 CSRF를 가진 MANAGER 403(세 엔드포인트+페이지, 실제 로그인 세션)·`memberId` 누락/0/음수/문자열/범위 초과와 핸들 형식 오류가 500이 아니라 400·강제 만료 후 대상의 API JSON 401·페이지 `/admin/login` 리다이렉트·현재 세션 409·본인의 다른 세션 만료·감사 SUCCESS(대상 회원·라벨)와 FAIL(대상·라벨 없음)과 서비스 진입 전 400의 감사 행 없음·감사 저장 실패에도 만료 유지·V35 추가·최상위 맨 끝·멱등.
- **dev 실기(Playwright + DB 직접 조회, 기존 `cms-db-dev` 컨테이너 안의 일회용 스키마 `cms_verify_session`에서 `bootRun`, 검증 뒤 앱·스키마·임시 파일 삭제)**: V35 자동 적용 확인(`flyway_schema_history` 35 success, 메뉴 "세션 관리" 최상위 맨 끝), 스크린샷 `build/verify-session-01-list.png`·`build/verify-session-02-actionlog.png`(`build/`는 git 추적 대상이 아니라 커밋되지 않는다).
  - 목록: ADMIN 두 세션·MANAGER 한 세션이 회원별 카드로 묶이고 "현재 세션" 배지와 비활성 [만료], 역할 배지, 세션 수·마지막 요청 시각(KST).
  - 만료: UI에서 매니저 세션 [만료] → `confirm()` 문구 확인 → "세션을 만료했습니다." → 카드 사라짐·총 세션 감소 → **대상 컨텍스트의 다음 API 요청이 JSON 401 `UNAUTHORIZED`**, 다른 ADMIN 세션 둘은 200 유지.
  - 본인 대상: 두 번째 ADMIN 컨텍스트에서 "내 다른 세션 모두 만료" → "1개 세션을 만료했습니다." → 현재 세션 유지(세션 1개).
  - **fetch가 첫 요청인 만료**: 만료된 쪽이 [새로고침](`GET /admin/api/sessions`)을 누르면 요청 한 번 뒤 `/admin/login`으로 이동(목록 비움).
  - **실제 서블릿 컨테이너 로그아웃**: 세 번째 컨텍스트 로그인 → 목록에 세션 2개 → 그 컨텍스트 로그아웃(`POST /admin/logout`) → 새로고침 시 1개(Mock 시험이 못 보던 부분).
  - **목록 GET 지속 500**(Playwright `route`로 주입): 클릭 1회에 요청 1건, 4초 대기 후에도 1건, 두 번째 클릭 후 2건 — 자동 재조회 루프 없음, 서버 메시지가 알림에 표시.
  - MANAGER: API 403·페이지 403·사이드바에 "세션 관리" 없음.
  - 감사: DB에서 `SESSION_EXPIRE` 두 건 — 다른 회원 대상 `targetId=2`·`세션 1개 만료`·`request_uri`에 해시 핸들만, 본인 대상 `targetId=1`·`세션 1개 만료(현재 세션 제외)`. 활동 로그 화면에 "세션 강제 만료"·`MEMBER #n · 라벨` 표시.
  - 회귀: `/admin`·회원·메뉴·게시판 정의·게시글·배너·권한관리 화면과 공개 `/notices`·`/`가 모두 200.
- **dev 실기 보강(2026-10-11, 두 번째 일회용 스키마 `cms_verify_session2`에서 `bootRun`, 검증 뒤 앱·스키마·임시 파일 삭제)**: ① **별개의 ADMIN 두 계정**(`admin`·`admin2`)과 MANAGER 한 명으로 세 컨텍스트 로그인 → 목록에 세 회원이 각각 카드로 표시(`build/verify-session-03-two-admins.png`) → UI로 `admin2` 세션 [만료] → `admin2`의 다음 API가 401, 같은 핸들을 다시 만료하면 404. ② **실제 서버의 실패 응답**(CSRF 헤더를 붙인 fetch): 현재 세션 409 `RESOURCE_CONFLICT`·없는 핸들 404 `RESOURCE_NOT_FOUND`·형식 오류(숫자+대문자 혼합·대문자 32자) 400 `INVALID_REQUEST`·`memberId` 누락/`abc`/`0` 400 `INVALID_REQUEST`·**CSRF 헤더 없는 두 DELETE 403 `ACCESS_DENIED`**, 이 모든 호출 뒤에도 현재 세션 200. ③ **실패 감사 행**(DB): 현재 세션 409·없는 핸들 404·재만료 404가 FAIL로 남고 `target_id`·`target_label`은 NULL, `error_message`는 서비스의 고정 문구(입력값 반사 없음), 성공은 `targetId`=대상 회원·`세션 1개 만료`. **400과 CSRF 403은 감사 행이 생기지 않음**(서비스 진입 전 — 계획 쟁점 6의 한계 그대로). ④ MANAGER: API 403·페이지 403(`build/verify-session-04-manager-403.png`).
- **하지 않았거나 못 한 검증(완료를 과장하지 않는다)**: ① **감사 저장 실패에도 만료 유지**는 통합 시험(`@MockitoSpyBean`으로 저장 실패 주입)으로만 확인했고 dev 실기에서는 재현하지 않았다. ② CI(`test`·`prod-smoke`)는 아직 돌지 않았다(push·PR 전).

### 이슈

- **Docker Desktop 엔진 정지(환경)**: 구현 중 `docker ps`가 `500 Internal Server Error`를 반환해 `MariaDbContainerSupport` 초기화가 실패했고 DB 의존 시험 전체(원래 통과하던 `BannerMigrationTest` 포함)가 `Could not find a valid Docker environment`로 떨어졌다 — 코드 문제가 아니라 환경 문제였고 Docker 재시작 뒤 같은 시험이 통과했다. 이 세션에서 확인한 것은 증상(Docker Desktop 프로세스는 떠 있으나 Linux 엔진이 500)과 재시작 후 정상화까지이고 근본 원인(WSL 쪽 정지로 보임)은 추정이다.
- 슬라이스 시험에서 `MockHttpSession`의 기본 ID가 짧은 숫자(`20`)라 "응답에 세션 ID가 없다" 단언이 날짜 문자열과 우연히 겹쳐 실패했다 — 식별 가능한 세션 ID(`raw-session-id-for-test-9f3a`)로 바꿨다(시험 결함이지 구현 결함이 아님).
- Playwright MCP: `confirm()`이 모달 상태로 남고(핸들러 등록 필요), `globalThis`는 호출 사이에 유지되지 않아 컨텍스트는 `browser.contexts()`로 다시 찾아야 했다(검증 도구 사용상의 메모).
- 비밀번호 정책(15자 이상)을 모르고 짧은 비밀번호로 MANAGER를 만들려다 400을 받았다(검증 환경 준비 단계, 앱 결함 아님).

### 후속

- 전역 `GlobalApiExceptionHandler`에 `MissingServletRequestParameterException` 처리가 없어 **필수 `@RequestParam` 누락이 500이 된다**(리뷰에서 코드로 확인, 변이 M5로 `expected:<400> but was:<500>` 실측 — 이 계획은 컨트롤러 직접 검증으로 우회). **기존 엔드포인트 영향은 없다**: `src/main/java`의 `@RequestParam`은 전부 `required=false` 또는 `defaultValue`라 누락이 500이 되는 곳이 지금은 없다(2026-10-11 전수 확인). 앞으로 필수 `@RequestParam`을 쓰는 신규 엔드포인트가 이 함정에 빠지지 않게, 필요해지면 전역 핸들러에 400 처리를 추가하는 별도 작업을 한다.
- **MANAGER가 ADMIN 전용 페이지를 열면 403 오류 화면이 `status: null error: null message: null …`로 표시된다** — 이번 변경과 무관한 **기존 동작**이다(`/admin/log/manage`·`/admin/permission/manage`도 같은 화면, 2026-10-11 dev 실기에서 비교). 상태 코드는 403으로 맞지만 오류 템플릿이 `status`·`message` 등을 채우지 못한다. 이번 범위가 아니라 고치지 않았고, **별도 이슈 #117로 등록했다**(원인: `CustomErrorController`가 404·429에서만 뷰·모델을 채워 그 밖의 상태는 모델이 빈 기본 `error.html`로 떨어진다).
- 세션의 IP·User-Agent·로그인 시각 표시(레지스트리에 없음 → 로그인 성공 핸들러·필터 추가 필요), 다중 인스턴스용 외부 세션 저장소, 만료 대상에게 알림, 문의하기(로드맵 ③ 후속).
- 이 인덱스의 37·38행은 이 작업 이전부터 "커밋·PR 전"으로 남아 있다(#113·#115는 머지됨) — 이번 범위가 아니라 고치지 않았다.
