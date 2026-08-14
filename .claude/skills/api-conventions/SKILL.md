---
name: api-conventions
description: 이 프로젝트의 RESTful API 설계 규칙(URI 명명, 상태 코드 계약, 목록 조회 파라미터)을 안내한다. /admin/api/** 또는 공개 경로에 엔드포인트를 추가·변경하거나, API 응답 상태 코드·에러 포맷을 결정할 때 사용한다.
---

## RESTful API 설계 규칙

이 프로젝트의 API는 RESTful 컨벤션을 따른다. 신규 엔드포인트는 아래 규칙을 기준으로 설계한다.

### URI 규칙

- **자원은 명사, 복수형, 소문자**로 표현한다. 동사를 URI에 넣지 않는다. (`/createMember` ✕ → `POST /members` ○)
- 다중 단어는 하이픈(`-`)으로 연결한다. (`/profile-image`)
- 컬렉션과 단일 자원을 구분한다.
    - 컬렉션: `/admin/api/members`
    - 단일 자원: `/admin/api/members/{id}`
- 현재 로그인 사용자(본인) 리소스는 `me` 별칭을 사용한다. (`/admin/api/members/me`)
- 하위 관계는 중첩 경로로 표현한다. (`/admin/api/members/me/profile-image`)

### 상태 코드 규칙

- `400` 검증 실패(`VALIDATION_ERROR`), JSON 파싱 오류(`JSON_PARSE_ERROR`), `401` 미인증, `403` 권한 없음, `404` 자원 없음, `409` 상태 충돌·중복(`DUPLICATE_RESOURCE`), `500` 서버 오류.
- DB 유니크 제약 위반(`DataIntegrityViolationException`)도 409 `DUPLICATE_RESOURCE`로 처리된다. `uk_member_user_id`·`uk_member_email` 위반 시 각각 사람이 읽을 수 있는 메시지로 응답한다.
- 컨트롤러까지 도달한 API 예외는 `GlobalApiExceptionHandler`를 통해 `common` 패키지의 공통 응답 포맷으로 반환한다.
- `@PreAuthorize` 위반으로 발생하는 `AccessDeniedException`은 `GlobalApiExceptionHandler.handleAccessDenied()`가 잡아 **JSON 403** (`ACCESS_DENIED`)으로 반환한다. 단, 이는 컨트롤러까지 도달한 요청에만 해당한다.
- `/admin/api/**` 경로는 Security Filter Chain 레벨에서 전용 핸들러가 처리한다. 미인증은 `ApiAuthenticationEntryPoint`(JSON 401 `UNAUTHORIZED`), 권한 부족은 `ApiAccessDeniedHandler`(JSON 403 `ACCESS_DENIED`)가 응답한다. HTML 리다이렉트나 기본 오류 페이지는 반환되지 않는다.
- `/admin/api/**` 이외의 경로(Thymeleaf 페이지 등)에서 발생하는 **401(미인증)**은 로그인 페이지로 리다이렉트된다.
- **핸들러가 아예 등록되지 않은 경로(정적 리소스 미존재 포함)도 404다** (2026-08-06 해결). `GlobalApiExceptionHandler`가 `NoResourceFoundException`·`NoHandlerFoundException`을 `Exception` catch-all보다 먼저 잡아 `/admin/api/**`는 JSON 404(`RESOURCE_NOT_FOUND`), 그 외는 기존 `error/404.html`(`/admin/**` 하위는 `error/admin/404.html`)로 응답한다. 이전에는 이 catch-all이 500 `INTERNAL_ERROR`로 바꿔버리던 기존 결함이었다(`docs/troubleshooting.md` 참조).

### 목록 조회 파라미터

- 페이징·정렬·검색은 쿼리 파라미터로 전달한다. (`?page=0&size=20&sort=createdAt,desc&keyword=...`)
- 페이징 응답은 일관된 구조(콘텐츠 + 페이지 메타)를 유지한다.
