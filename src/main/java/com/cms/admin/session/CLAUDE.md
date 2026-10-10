# CLAUDE.md — com.cms.admin.session

이 디렉터리(세션 관리 — 접속 중 관리자 조회·수동 강제 만료) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.
계획서: `adversarial-review/plan/PLAN-session-management.md`(적대적 리뷰 4라운드 ship, 2026-10-10 — 로드맵 Top 8 ③).
자동 만료(역할·상태 변경 → `AdminSessionRevokeListener`)는 `com.cms.config.auth`에 그대로 있고 이 패키지가 건드리지 않는다.

## 구성

| 구성요소 | 역할 |
|---|---|
| `config/auth/AdminSessionService` | **세션 레지스트리 파사드**(자동 만료와 이 화면이 공유). `handleOf`·`listActiveSessions`·`expireByHandle`·`expireSessionsFor(memberId, exceptSessionId)`. 원문 세션 ID는 이 클래스 밖으로 나가지 않는다 |
| `AdminSessionManageService` | 조회(회원별 묶음)·단일 만료·회원 단위 만료. **현재 세션 보호와 감사(`@AdminActionLogged`)** 가 여기 있다. DB 트랜잭션 없음 |
| `AdminSessionController` | `GET /admin/api/sessions`, `DELETE /admin/api/sessions/{handle}`(204), `DELETE /admin/api/sessions?memberId=`(200 `{expiredCount}`). 입력 검증을 컨트롤러가 직접 한다 |
| `AdminSessionPageController` | `GET /admin/session/manage`(`@AdminPage`). 화면 `templates/admin/session/manage.html` |

## 계약 (코드만 봐서는 알기 어려운 것)

- **원문 세션 ID는 `JSESSIONID` 쿠키 값이다** → 응답·URL·감사에는 `SHA-256(sessionId)` 앞 32 hex **핸들**만 쓴다(`AdminSessionService.handleOf`). 핸들은 세션 ID에서 결정적이라 목록과 만료 사이에 순서가 바뀌어도 같은 세션을 가리킨다(순번·임시 번호 방식은 엉뚱한 세션을 끊을 수 있어 기각). **보장 범위는 새 기능이 만드는 정상 응답 본문·감사 라벨·예외 메시지**다 — 호출자가 `{handle}` 자리에 잘못 보낸 값이 오류 응답 `path`·감사 `request_uri`에 반사되는 것과 Spring Security 자체의 DEBUG 로그(세션 등록·만료 때 원문 ID 출력)는 범위 밖이다.
- **영구 ADMIN 전용**: 핸들러마다 `@PreAuthorize("hasRole('ADMIN')")`, 페이지·API 경로는 어떤 카탈로그 게이트에도 없어 `/admin/**` ADMIN 캐치올이 막는다(**`SecurityConfig` 변경 없음**). `AdminFeature.SESSION`(`ADMIN_ONLY`)은 권한관리 매트릭스의 "위임 불가" 표시와 메뉴 URL 분류용이며 `AdminFeatureTest`가 종류를 직접 고정한다. 위임 가능 기능으로 바꾸지 않는다 — 다른 관리자의 작업을 끊는 권한이다.
- **현재 세션 보호는 서버가 판정한다**(화면의 비활성화는 보조). 기준은 **요청 진입 시점의 세션 ID**: 단일 만료가 현재 세션이면 **409**(`RESOURCE_CONFLICT`), 회원 단위 만료는 호출자와 무관하게 **현재 세션을 항상 건너뛴다**(대상이 다른 회원이면 현재 세션이 집합에 없어 전부 만료). 같은 브라우저의 재로그인(세션 ID 변경)이 본인 대상 회원 단위 만료와 정확히 겹치면 방금 로그인한 세션이 끊길 수 있으나 재로그인으로 복구되는 경합이라 수용했다(로그인 경로 동기화·latch 시험 없음).
- **만료는 best-effort다**(`SessionInformation.expired`·`lastRequest`는 일반 필드, `getAllPrincipals()`+`getAllSessions()`는 원자적 스냅샷이 아니다). 대체로 만료된 세션의 **다음 요청**에서 `AdminSessionExpiredStrategy`가 거부(API JSON 401·페이지 `/admin/login`)하며 즉시·엄격 보장이 아니다. 락은 두지 않는다(필터·이벤트 경로까지 동기화하지 못한다). 화면 문구도 이를 정직하게 적는다.
- **경합 규칙**: 대상은 **조회 시점에 활성인** 세션뿐이다. 단일 만료는 못 찾으면(없는 핸들·이미 만료·소멸) 404, 찾아서 `expireNow()`하면 204(동시에 둘 다 찾으면 둘 다 204 — 멱등). `expiredCount`는 "이번 요청이 조회 시점의 활성 세션에 만료를 호출한 수"로 **참고용**이다(엄밀한 신규 만료 수 아님). 회원 단위 만료는 대상 세션이 없어도 200(`expiredCount` 0 — 회원 존재 여부를 드러내지 않는다).
- **입력 검증**: 전역 `GlobalApiExceptionHandler`에는 `MissingServletRequestParameterException` 처리가 없어 필수 `@RequestParam` 누락이 500이 된다 → `memberId`는 `required=false`로 받아 컨트롤러가 `InvalidRequestException`(400 `INVALID_REQUEST`, 고정 문구)으로 직접 검증하고, 핸들은 `String`+`^[0-9a-f]{32}$`로 같은 방식 검증한다(`@Pattern`·메서드 검증은 처리 핸들러가 없어 쓰지 않는다). 타입 불일치(`abc`·Long 초과)는 기존 핸들러가 400. 오류 문구에 입력값을 싣지 않는다.
- **감사 `SESSION_EXPIRE`**(`targetType=MEMBER`): 성공 행은 `targetId`=세션 소유 회원 ID·라벨 `세션 n개 만료`(현재 세션을 실제로 건너뛰었으면 `(현재 세션 제외)`). `SessionExpireResult`가 `getMemberId()`·`getAuditLabel()` getter를 노출해야 Aspect가 뽑는다(record 불가). **한계(수용)**: ① `AdminActionLogAspect`의 실패 처리는 `targetId`·`targetLabel`을 저장하지 않아 서비스 진입 이후 실패(404·409)는 대상 없는 FAIL 한 건이다, ② 서비스 진입 전에 컨트롤러가 거르는 400은 감사되지 않는다(다른 API와 같은 정책), ③ `request_uri`는 쿼리 문자열이 없어 회원 단위 만료의 대상은 성공 행의 `targetId`로만 추적된다. 세션 ID·핸들은 라벨에 넣지 않는다. 새 액션 타입이라 `AdminActionTypes.ALL`과 활동 로그 화면 `ACTION_TYPE_LABELS`에 함께 등록돼 있다(`AdminActionTypeLabelSyncTest`).
- **목록**: 레지스트리 스냅샷 + **DB 조회 없음**. 이름·아이디·역할은 principal의 로그인 시점 값이다(이름 변경은 세션을 만료시키지 않아 낡을 수 있으나 표시용 — 권한 판단에 쓰지 않는다. 역할·상태가 바뀌면 이미 세션이 만료된다). 회원은 `memberId`로 묶는다(로그인마다 principal 인스턴스가 달라 레지스트리 키로는 묶이지 않는다). 정렬은 마지막 요청 내림차순 → 회원 ID → 핸들 오름차순으로 고정. 마지막 요청 시각은 주입된 `Clock`의 시간대로 변환한다. **세션 수는 `maximumSessions(-1)`이라 상한이 없다**(계정 수가 상한이라는 초기 근거는 틀렸다) — 유휴 타임아웃 30분·로그아웃으로 소멸하고 로그인 주체가 관리자·매니저뿐이라 현실 규모가 작다는 점을 수용한 전제로 두고 응답 상한·페이징은 두지 않았다. 단일 인스턴스 메모리 레지스트리라 다중 인스턴스에서는 해당 인스턴스의 세션만 보인다(레이트리밋·권한 캐시와 같은 전제).
- **화면**(`manage.html`): 수동 [새로고침], 폴링 없음(목록 요청이 호출자 세션의 `lastRequest`를 갱신해 의미를 흐린다). 만료 요청 뒤 목록 재조회는 **한 번뿐**이고 목록 GET이 실패하면 오류만 표시하고 멈춘다(장애 중 요청 루프 방지). 공통 스크립트에는 fetch 401 처리가 없어 이 화면이 목록·두 DELETE의 **401이면 목록을 비우고 `/admin/login`으로 이동**한다. 이름·아이디는 `textContent`로만 삽입한다. 만료는 `confirm()`으로 한 번 확인한다.
- **메뉴 V35**: 최상위 맨 끝 멱등 DML(스키마 변경 없음). 이전 버전 앱으로 롤백하면 메뉴 행이 남아 클릭 시 404다(`docs/deployment.md`). **새 마이그레이션이 최상위 메뉴를 추가하면 `flyway(null)`로 "맨 끝"을 단언하는 옛 마이그레이션 시험이 깨진다** — `BannerMigrationTest.upgradeFromV32`가 V34에 고정된 이유다. 메뉴를 시드하는 새 마이그레이션을 쓸 때는 앞선 시험이 최신 버전에 의존하는지 확인한다.

## 시험

`AdminSessionServiceTest`(실제 `SessionRegistryImpl` 단위 — 핸들·활성만 나열·정렬 고정·핸들 만료·제외 세션)·`AdminSessionManageServiceTest`(현재 세션 409·404·라벨·KST 변환)·`AdminSessionControllerTest`(슬라이스 — 유효 CSRF의 MANAGER 403·CSRF 누락 403·서비스 미호출·입력 검증 400·상태 매핑)·`AdminSessionManagementIntegrationTest`(실제 로그인·SecurityConfig·MariaDB·감사 AOP — 강제 만료 후 대상의 API 401·페이지 리다이렉트, 현재 세션 보호, 본인의 다른 세션 만료, 회원 단위, 감사 SUCCESS/FAIL, 감사 저장 실패에도 만료 유지)·`SessionMenuMigrationTest`·`AdminFeatureTest`. **통합 시험은 `MockMvc`·`MockHttpSession` 기반이라 실제 서블릿 컨테이너의 세션 파괴·ID 변경 이벤트는 검증하지 못한다** — 로그아웃한 세션이 목록에서 사라지는 것은 dev 실기로 확인했다. 컨텍스트가 캐시되면 다른 시험이 남긴 세션이 레지스트리에 있으므로 목록 단언은 자기 회원 행만 본다.
