# CLAUDE.md — com.cms.config

이 디렉터리(설정·시큐리티 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.

`SecurityConfig`에 정의된 접근 제어:

| 경로 | 접근 |
|------|------|
| `/admin/login`, `/admin/login-error` | 공개 |
| `/admin/password-reset`, `/admin/password-reset/confirm` | 공개 (비밀번호 재설정 페이지, 2026-07-13 승인) |
| `/admin/api/password-reset-requests`, `/admin/api/password-resets` | 공개 (비밀번호 재설정 API — CSRF 토큰은 필요) |
| `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**` | `ROLE_ADMIN` 필수 |
| `/admin`, `/admin/member/info`, `/admin/api/members/me`, `/admin/api/members/me/**` | `ROLE_ADMIN`·`ROLE_MANAGER` (상시 허용 기능 `DASHBOARD`·`MY_INFO` — 이전과 동일. 이제 `AdminFeature` 카탈로그(`com.cms.admin.permission`)에서 만든 게이트다) |
| `/admin/notice/**`, `/admin/api/notices`, `/admin/api/notices/**` | **`ROLE_ADMIN` 또는 공지 조회(READ) 권한이 있는 `ROLE_MANAGER`** (위임 가능 기능 `NOTICE` — 기능 단위 READ 게이트, 동작별 판정은 핸들러의 `@RequirePermission`. 공지사항 관리 MANAGER 허용은 2026-07-20 승인, **2026-10-02 DB 허용 행(`role_permission`)으로 이관** — V14 시드가 오늘의 범위(조회·생성·수정·삭제)를 그대로 옮겨 배포 직후 동작은 같고 ADMIN이 권한관리 화면/API로 바꿀 수 있다 — 2026-10-02 PR 3/4). 상세는 `com.cms.admin.permission`의 `CLAUDE.md` 참조 |
| `/admin/**` | `ROLE_ADMIN` 필수 (카탈로그에 없는 모든 `/admin/**` 캐치올 — 권한관리 `GET /admin/permission/manage`·`/admin/api/roles/**`도 여기에 걸려 ADMIN 전용이며 `SecurityConfig` 변경 없이 보호된다, 위임 불가 `PERMISSION`) |
| `/notices`, `/notices/**` | GET·HEAD만 공개 (`permitAll`), 그 외 메서드는 `denyAll`로 명시 차단 (공개 공지 페이지, 2026-07-28 승인). `/notices/**`가 하위 세그먼트 전체를 포괄해 `/notices/{id}/attachments/{attachmentId}`(2026-08-03 추가)도 별도 규칙 없이 이 매처가 적용됨 |
| `/actuator/health` | 공개 (`permitAll`, 로드밸런서 헬스체크용) |
| `/actuator/**`(health 제외) | `denyAll` 명시 차단 (2026-07-29 승인 — env/beans/metrics 등 노출 설정이 넓어져도 뚫리지 않도록 이중 방어) |
| 컨테이너 ERROR 디스패치(`sendError` → `/error`) | 공개 (`dispatcherTypeMatchers(ERROR).permitAll()`, **규칙 맨 앞** — 없으면 404·429·403 오류 페이지가 로그인 302로 뒤바뀜). `/error` URL 직접 요청(REQUEST 디스패치)은 기본 거부 (2026-09-29 승인) |
| `/css/**`, `/js/**`, `/img/**`, `/vendor/**`, `/favicon.ico` | GET·HEAD만 공개 (정적 리소스, 2026-09-29 승인). 앞 4개 접두사는 **정적 전용 예약 경로** — 컨트롤러 매핑 금지(`DefaultDenyErrorDispatchIntegrationTest`가 강제). `static/`에 새 최상위 디렉터리를 추가하면 `SecurityConfig.STATIC_PUBLIC_PATHS`에도 추가해야 한다(`SecurityConfigTest`가 감지). `/favicon.ico`는 파일이 없어도 열어 기존 404 유지 |
| 그 외 모든 경로 | **기본 거부** (`anyRequest().denyAll()`, 2026-09-29 승인, 감사 M-08) — 비인증은 `/admin/login` 302, 인증(ADMIN·MANAGER)은 403. **새 엔드포인트는 반드시 위 표에 접근 규칙을 추가해야 한다**(누락 시 즉시 차단). `/admin` 아래 핸들러는 추가로 **인가 선언 컨벤션**(읽기 전용 GET/HEAD 페이지만 면제, 그 밖의 모든 핸들러와 모든 API는 `@RequirePermission`·`hasRole('ADMIN')` 중 하나 — `AdminEndpointAuthorizationConventionTest`, 상세는 `com.cms.admin.permission`의 `CLAUDE.md`)도 지켜야 하며, 어기면 CI가 실패한다. 이 보장은 명시 허용 규칙에 매칭되지 않는 경로에 한정 — 정적 예약 접두사 하위는 위 컨트롤러 금지 규칙이 지킨다. 설계 근거·검증 이력은 `adversarial-review/plan/PLAN-default-deny-authorization.md` |
