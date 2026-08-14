# CLAUDE.md — com.cms.config

이 디렉터리(설정·시큐리티 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.

`SecurityConfig`에 정의된 접근 제어:

| 경로 | 접근 |
|------|------|
| `/admin/login`, `/admin/login-error` | 공개 |
| `/admin/password-reset`, `/admin/password-reset/confirm` | 공개 (비밀번호 재설정 페이지, 2026-07-13 승인) |
| `/admin/api/password-reset-requests`, `/admin/api/password-resets` | 공개 (비밀번호 재설정 API — CSRF 토큰은 필요) |
| `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**` | `ROLE_ADMIN` 필수 |
| `/admin/notice/**`, `/admin/api/notices`, `/admin/api/notices/**` | `ROLE_ADMIN`·`ROLE_MANAGER` (공지사항 관리, 2026-07-20 승인) |
| `/admin/**` | `ROLE_ADMIN` 필수 |
| `/notices`, `/notices/**` | GET·HEAD만 공개 (`permitAll`), 그 외 메서드는 `denyAll`로 명시 차단 (공개 공지 페이지, 2026-07-28 승인). `/notices/**`가 하위 세그먼트 전체를 포괄해 `/notices/{id}/attachments/{attachmentId}`(2026-08-03 추가)도 별도 규칙 없이 이 매처가 적용됨 |
| `/actuator/health` | 공개 (`permitAll`, 로드밸런서 헬스체크용) |
| `/actuator/**`(health 제외) | `denyAll` 명시 차단 (2026-07-29 승인 — env/beans/metrics 등 노출 설정이 넓어져도 뚫리지 않도록 이중 방어) |
| 그 외 모든 경로 | 공개 (`anyRequest().permitAll()`) |
