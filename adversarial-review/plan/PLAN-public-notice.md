# PLAN — 공개 공지 페이지 (첫 비관리자 화면)

> 작성일: 2026-07-28
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 Top 5 (2026-07-20 재선정) — ③"
> 선행 완료: ① 공지사항(notice) 관리 CRUD (`6c5ca4c` #16), ② 파일 스토리지 + 첨부파일 (`174e925` #18)

## 개정 이력
- v1 (2026-07-28): 최초 작성(plan 모드 정찰·설계 결과). 사용자 확정: URL `/notices`·`/notices/{id}`, 첨부 공개 제외, 검색 제외. `/plan-review-loop` 리뷰 대상으로 제출.
- v2 (2026-07-28, codex 리뷰 1차 반영):
  - **수용(차단1)**: `GlobalApiExceptionHandler`가 전역 `@RestControllerAdvice`라 페이지 컨트롤러의 예외(타입 변환 오류·DB 장애 등)도 JSON 500으로 가로챈다는 지적을 실측(`templates/error.html`·`CustomErrorController` 대조)으로 확인. 결정 3을 확장 — `id`·`page`는 Spring 바인딩에 맡기지 않고 String으로 받아 수동 파싱(파싱 실패 시 404/0 보정으로 흡수) + `com.cms.publicweb.support.PublicWebExceptionAdvice`(범위 한정 advice, `@Order(HIGHEST_PRECEDENCE)`) 신설로 나머지 예외를 HTML 500으로 전환. `GlobalApiExceptionHandler` 자체는 손대지 않아 기존 admin API·페이지 동작에 영향 없음.
  - **수용(높음2)**: `requestMatchers("/notices", "/notices/**").permitAll()`이 GET 외 모든 HTTP 메서드까지 인증을 면제한다는 지적. `HttpMethod.GET`으로 명시 축소 — 기존에 승인받은 공개 정책을 더 좁히는 방향이라 재승인 불필요로 판단.
  - **부분 반박(높음3)**: 인덱스 없는 대량 OFFSET 스캔 우려. 복합 인덱스 추가(Flyway 마이그레이션 신규)는 이번 계획이 명시한 "스키마 변경 없음" 범위를 벗어나는 확장이자, 소규모 개인 포트폴리오 CMS 데이터량 기준 과설계로 판단해 이번 범위에서는 추가하지 않는다. 대신 malformed page 방어(차단1 수정)로 비정상 입력에 의한 낭비는 최소화하고, 실제 데이터량 증가 시 재검토할 후속 과제로 리스크 표에 명시.
  - **수용(중간4)**: tie-breaker(`createDate desc, id desc`)가 "동시 변경 중 페이지 누락·중복까지 막는다"는 서술이 과장이었음을 인정 — 동일 쿼리 스냅샷 내 동률 정렬만 보장하며, 동시 생성·전환·삭제로 인한 페이지 경계 이동(eventual consistency)은 허용 범위임을 명시하도록 결정 2 문구 수정.
  - **부분 반박(중간5)**: 범위 초과 페이지에서 "등록된 공지사항이 없습니다"로 표시하는 동작은 일반적인 UX 패턴이라 유지(진짜 빈 게시판과 구분하지 않음). 다만 지적된 경계값 테스트 케이스(0건/10건/11건/마지막 페이지/`page==totalPages`/음수/비숫자/정수범위초과/size 무시)는 전부 테스트 계획에 반영.
  - **수용(중간6)**: 테스트 계획에 malformed id/page, HTML 500 경로, GET 외 메서드 차단, tie-breaker Repository 테스트, authorId 필드명이 아닌 실제 값 부재 검증을 추가.
  - **수용(낮음7)**: 재사용하는 `templates/error/404.html`의 "홈으로 돌아가기" 링크가 `/`(매핑 없음)를 가리켜 다시 404가 되는 문제. `/`가 여전히 매핑이 없는 상태에서 `/notices`가 사실상 유일한 공개 진입점이 되므로, 공유 템플릿의 링크만 `/notices`로 교체(최소 변경 — 다른 내용은 그대로).
  - **반박**: `th:text`만으로 XSS 방어가 불충분하다는 우려 — 텍스트 컨텍스트에만 값을 쓰는 현재 설계에서는 충분하다는 리뷰 자체의 결론과 동일하게 유지. 다만 테스트 payload를 `<script>` 외 속성 주입까지 확장.
- v3 (2026-07-28, codex 리뷰 2차 반영 — needs-attention, 4개 지적 전부 실측으로 확인되어 전부 수용):
  - **수용(높음1)**: `HttpMethod.GET` 매처만 추가하면 v2가 주장한 "향후 방어"가 **현재 설정에서 이미 성립하지 않는다** — `SecurityConfig.java:58`의 `anyRequest().permitAll()`이 GET이 아닌 요청도 그대로 통과시킨다(매처는 첫 일치 규칙 하나만 적용되며, POST는 GET 매처를 건너뛰고 catch-all에 걸림). 직접 코드로 재확인해 사실로 확인. 결정 수정 — GET(+HEAD) `permitAll` 다음 줄에 `requestMatchers("/notices", "/notices/**").denyAll()`을 명시 추가해, catch-all에 기대지 않고 **지금 당장** 비-GET 메서드를 차단한다. 계획했던 "405 또는 401" 테스트는 무엇도 증명하지 못하므로 폐기 — 비인증 POST(+CSRF) → `denyAll` 경합 시 Spring Security가 익명 사용자를 `AuthenticationEntryPoint`로 보내는 기존 분기(`ExceptionTranslationFilter`)를 타므로 `/admin/login`으로 3xx 리다이렉트되는 것을 검증하는 테스트로 교체.
  - **수용(높음2)**: `PublicWebExceptionAdvice`가 "템플릿 렌더링 오류"까지 처리한다는 서술이 부정확함을 인정 — Spring MVC `DispatcherServlet.doDispatch()`는 핸들러 실행(`ha.handle()`)만 try/catch로 감싸 `processHandlerException`(= `@ExceptionHandler` 대상)으로 넘기고, 그 다음 단계인 `render()`(뷰 렌더링)는 별도 catch 없이 호출된다 — 즉 Thymeleaf 렌더링 예외는 이 advice가 잡지 못하고 컨테이너 오류 처리(`/error` → `CustomErrorController`)로 전파된다. advice의 보장 범위를 "컨트롤러·Service 실행 중 예외"로 명시적으로 좁히고, 렌더링 실패는 이 기능이 새로 만든 공백이 아니라 앱 전체에 이미 있던 기존 한계임을 명시(별도로 닫지 않음 — 대신 신규 템플릿이 항상 유효한 모델로만 렌더링되도록 테스트로 보증해 이 경로 자체가 실제로 트리거되지 않게 한다).
  - **수용(중간3)**: 인덱스 연기를 "완전 반박"이 아니라 "명시적 위험 수용"으로 재정리 — malformed page 보정은 `page=2147483647`류의 문법적으로 유효한 거대 OFFSET은 막지 못한다는 지적이 맞다. `NoticeService.MAX_PAGE_SIZE=100` 캡 선례를 따라 `PublicNoticeService`에 `MAX_PAGE` 상한(예: 1000)을 신설해 그 이상은 0으로 재보정 — COUNT 쿼리 비용 자체는 줄이지 못하지만 단일 요청의 OFFSET 스캔 비용 상한은 확실히 막는다. 가정("공지 수 수백 건 이하 소규모")과 재검토 트리거를 명시적으로 문구화.
  - **수용(중간4)**: v1 잔여 문구가 v2 결정과 모순됨을 확인 — 결정 3 본문에 남아있던 "`error/404.html`을 그대로 재사용(수정 없음)", "새 `@ControllerAdvice`를 만들지 않는 이유" 문장을 제거(3-1·3-2가 대체). 또한 `page` 파싱 책임 계층이 Controller(3-1)·Service(결정 8)·테스트 계획 세 곳에서 서로 다르게 서술된 모순을 해소 — **Controller는 문자열 파싱 안전성만 책임**(예외를 던지지 않고 파싱 가능한 int로 변환, 실패 시 0 — 파싱에 성공한 값이 음수여도 그대로 Service에 전달), **Service는 그 위에 비즈니스 규칙(고정 크기·정렬·`MAX_PAGE` 상한·음수 보정)만 책임**한다. `size` 무시는 바인딩되지 않는 파라미터라는 자명한 사실이라 Service 테스트에서 제거하고 Controller 테스트로 이동.
- v4 (2026-07-28, codex 리뷰 3차 반영 — needs-attention, 2개 지적 전부 수용 후 ship 조건 충족):
  - **수용(중간)**: `denyAll()` 설계 자체는 실측 검증(익명 POST+CSRF → `authenticationEntryPoint` → `/admin/login` 302, 익명 POST+CSRF 없음 → `CsrfFilter` → 403, ADMIN POST+CSRF → 커스텀 `accessDeniedHandler` 비-API 분기 → `AccessDeniedHandlerImpl` → 403)로 맞다고 확인됐으나, 계획의 "ADMIN도 동일하게 거부됨" 테스트 서술이 모호해 405 같은 엉뚱한 결과로도 거짓 통과할 수 있다는 지적 — **정확히 403**을 기대하도록 테스트 계획 명시. 또한 v3에서 새로 허용한 `HEAD`에 대한 회귀 테스트가 없었다는 지적을 수용해 비인증 `HEAD /notices`(및 가능하면 `/notices/{id}`) 200 테스트를 추가.
  - **수용(낮음)**: 개정 이력(구 v2 항목)의 "non-negative int로 변환"이라는 표현이 결정 3-1 본문("파싱 성공값은 음수여도 그대로 Service에 전달")·결정 8("Service가 음수를 0으로 보정")과 모순된다는 지적 — 개정 이력 문구를 "파싱 가능한 int로 변환(음수 판단은 Service 책임)"으로 정정해 세 서술을 일치시킴(위 항목에 함께 반영).
  - **4차 확인 리뷰(2026-07-28) 결과: ship.** 위 2개 지적 반영이 충분히 확인됐고(비인증 POST 302, ADMIN+CSRF POST 정확히 403, HEAD 회귀 테스트, 파싱 책임 서술 일치) v4 반영 과정에서 새로 발생한 문제는 없음 — `plan-review-loop` 4라운드 종료, 승인 단계로 진행.

## Context

로드맵 `adversarial-review/project-direction-roadmap.md`의 "실행 로드맵 Top 5 (2026-07-20 재선정)" ③번 항목.

현재 이 프로젝트는 `/admin/**` 관리 화면만 존재한다 — 공지사항(notice) 도메인(①, `6c5ca4c` #16)과 첨부파일(②, `174e925` #18)로 "관리할 대상"은 생겼지만, **그 콘텐츠를 밖으로 내보내는 경로가 없다.** 관리자가 작성한 공지를 비로그인 사용자가 볼 수 없으므로 "CMS"의 절반(콘텐츠 배포)이 비어 있는 상태다.

이 작업은 노출(`useYn=true`) 상태이면서 소프트 삭제되지 않은(`deleted=false`) 공지의 목록·상세를 비로그인 사용자에게 서빙하는 공개 Thymeleaf 페이지를 추가한다. 로드맵의 "공개 프론트 vs headless" 갈림길에서 **공개 프론트(Thymeleaf)** 를 실행하는 것이며, 완료되면 2단계(정체성 확보)가 마감되고 ⑤(prod 부활·실배포)의 "배포할 대상"이 확보된다.

**사용자 확정 결정 (2026-07-28)**
- 공개 URL: `/notices`(목록) · `/notices/{id}`(상세). 루트 `/`는 현재대로 매핑 없음(404) 유지.
- 첨부파일 공개: **이번 범위 제외** (본문만 공개)
- 목록 검색: **제외** — 목록 + 페이징 + 상세만

## 스키마 · 인가 정책 영향 (승인 필요 항목)

- **스키마 변경: 없음.** notice 테이블을 읽기 전용으로 재사용한다. Flyway 마이그레이션 파일 추가 없음.
- **인가 정책 변경: 있음.** `SecurityConfig`에 아래 두 줄을 순서대로 추가한다(`anyRequest().permitAll()`보다 앞에 위치해야 함):
  ```java
  .requestMatchers(HttpMethod.GET, "/notices", "/notices/**").permitAll()
  .requestMatchers(HttpMethod.HEAD, "/notices", "/notices/**").permitAll()
  .requestMatchers("/notices", "/notices/**").denyAll()
  ```
  - 현재 `anyRequest().permitAll()`이라 GET 허용 자체는 이 줄이 없어도 이미 접근 가능하다 — GET 한정으로는 **런타임 동작이 변하지 않는다.**
  - **(v3 수정) `denyAll()` 명시 추가.** codex 리뷰 2차(높음1)에서 실측으로 확인된 문제 — `HttpMethod.GET` 매처만 추가하면 그 매처에 걸리지 않는 POST 등은 뒤에 남은 `anyRequest().permitAll()`에 그대로 걸려 **여전히 공개된다.** "향후 쓰기 엔드포인트가 추가돼도 막힌다"는 방어를 실제로 성립시키려면 GET(+HEAD) 허용 다음에 나머지 메서드를 `denyAll()`로 명시 차단해야 한다 — 이렇게 하면 catch-all에 기대지 않고 지금 당장 비-GET 메서드가 막힌다. `HEAD`도 명시 허용하는 이유는 `@GetMapping`이 기본적으로 HEAD를 지원하는 것과 일치시키기 위함(HEAD로 접근하는 모니터링·크롤러 도구가 조용히 막히지 않도록).
  - `denyAll()`은 인증 여부·역할과 무관하게 전부 거부한다 — `/notices`에는 어떤 사용자(익명·ADMIN 포함)도 GET/HEAD 외 메서드로 접근할 합법적 이유가 없으므로 의도한 동작이다.

## 핵심 설계 결정

### 1. 공개 조회는 별도 Service로 격리 (`PublicNoticeService`)

`NoticeService`에 공개용 메서드를 추가하지 않고 `com.cms.publicweb.notice.service.PublicNoticeService`를 신설한다.

**왜**: 공개 조회의 불변식은 "`deleted=false` **AND** `useYn=true`"인데, admin용 Service는 `useYn`을 **선택적 필터**로 다룬다(`NoticeSearchRequest.useYn`이 null이면 전체). 같은 클래스에 두면 파라미터 하나를 잘못 넘겨 비노출 공지가 공개로 새는 사고가 리뷰에서 눈에 띄지 않는다. 클래스를 나누면 "이 클래스가 반환하는 것은 언제나 공개 가능한 것"이 타입·클래스 단위로 보장된다.

Repository는 재사용한다(`NoticeRepository`) — 데이터 접근을 이중화하지 않는다.

### 2. Repository는 파생 쿼리 2개 추가 (QueryDSL 재사용 안 함)

`NoticeRepository`에 추가:
```java
Page<Notice> findByDeletedFalseAndUseYnTrue(Pageable pageable);
Optional<Notice> findByIdAndDeletedFalseAndUseYnTrue(Long id);
```

**왜**: 기존 `NoticeRepositoryImpl.searchNotices()`는 keyword·useYn 동적 조건 + 정렬 화이트리스트를 다루는 admin 검색 전용이다. 공개 목록은 조건이 고정(노출·미삭제)이고 정렬도 고정이라 동적 쿼리가 필요 없다. 메서드명 자체가 조건을 강제하므로 "필터를 빠뜨릴 수 없는" 형태가 된다 — 위 결정 1의 불변식을 DB 접근 계층에서 한 번 더 잠근다.

정렬은 Service가 고정: `Sort.by(desc("createDate"), desc("id"))`. `id` tie-breaker는 `NoticeRepositoryImpl`이 이미 쓰는 패턴 — **동일 쿼리 스냅샷 안에서** 동률 행(같은 `createDate`) 순서를 결정적으로 만든다. (v2 정정) 이것이 동시 생성·노출 전환·삭제로 인한 페이지 경계 이동까지 막아주지는 않는다 — 사용자가 목록을 보는 사이 다른 공지가 추가·전환되면 다음 페이지에서 항목이 한 번 더 보이거나 건너뛸 수 있다. 공개 조회 전용의 읽기 전용 목록에서는 이 정도의 eventual consistency를 허용 범위로 둔다(keyset pagination 등 엄격한 일관성 보장은 이번 범위에서 과설계로 판단해 도입하지 않음).

### 3. 404는 예외가 아니라 `Optional` + 뷰 직접 반환

**제약(정찰에서 실측)**: `GlobalApiExceptionHandler`는 `@RestControllerAdvice`라 **페이지 컨트롤러에도 적용된다.** 공개 페이지에서 `ResourceNotFoundException`을 던지면 브라우저에 JSON(`{"code":"RESOURCE_NOT_FOUND",...}`)이 그대로 뿌려진다. `ResponseStatusException`도 마찬가지로 `@ExceptionHandler(Exception.class)` 폴백에 걸려 **500 JSON**이 된다.

**결정**: 공개 컨트롤러는 예외를 던지지 않는다. (구체적인 `id` 파싱 방식은 3-1, 남는 예외 처리는 3-2 참조 — Spring 바인딩에 타입 변환을 맡기지 않는 형태로 최종 확정)
- `response.setStatus()`는 `sendError()`와 달리 컨테이너 error-page dispatch를 유발하지 않으므로 지정한 뷰가 그대로 렌더링된다.
- `templates/error/404.html`을 재사용한다(홈 링크만 v2에서 `/notices`로 교체 — 그 외 구조는 그대로).

**미노출·소프트 삭제·존재하지 않는 ID는 모두 동일한 404**로 응답한다(존재 여부 열거 방지).

**(v2 추가) 3-1. Spring 바인딩에 맡기지 않고 `id`·`page`를 직접 파싱한다 — 남아있는 예외 경로를 원천 차단**

codex 리뷰 1차(차단1)에서 지적된 대로, `@PathVariable Long id`로 선언하면 `/notices/abc` 같은 요청이 Spring 바인딩 단계에서 `MethodArgumentTypeMismatchException`을 던지고, 이는 컨트롤러 진입 전이라 `Optional` 분기로 흡수되지 않는다. **실측 확인**: `GlobalApiExceptionHandler`는 전역 `@RestControllerAdvice`이므로 이 예외(및 그 외 모든 미처리 `Exception`)를 잡아 **JSON**으로 응답한다 — 공개 HTML 페이지에 JSON이 그대로 노출되는 진짜 결함이다.

해결책:
```java
@GetMapping("/{id}")
public String detail(@PathVariable String id, Model model, HttpServletResponse response) {
    Long noticeId = parseId(id); // 실패 시 null
    if (noticeId == null) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return "error/404";
    }
    return publicNoticeService.findPublishedNotice(noticeId)
            .map(notice -> { model.addAttribute("notice", notice); return "public/notice/detail"; })
            .orElseGet(() -> { response.setStatus(HttpServletResponse.SC_NOT_FOUND); return "error/404"; });
}
```
`page`도 동일하게 `@RequestParam(defaultValue = "0") String page`로 받는다. **(v3 정리) 계층 책임 분리** — 지적(중간4)에서 파싱 책임이 Controller·Service·테스트 계획에 흩어져 서로 모순됐던 점을 확인해 다음과 같이 확정한다:
- **Controller(이 결정 3-1)의 책임은 "예외를 던지지 않는 것"뿐이다.** 문자열을 파싱해 실패(비숫자·정수 범위 초과)하면 **0**을, 성공하면 그 값을 그대로 Service에 넘긴다(음수 여부 등 비즈니스 판단은 하지 않는다) — Spring이 타입 변환 예외를 던질 여지 자체를 없애는 것이 유일한 목적이다.
- **Service(결정 8)의 책임은 비즈니스 규칙이다.** 음수 보정, `MAX_PAGE` 상한(아래 추가), 고정 페이지 크기, 고정 정렬. Controller가 이미 안전한 값을 넘기더라도 Service가 음수를 다시 한번 방어적으로 0 보정한다(계약이 지켜지지 않는 다른 호출부가 생기더라도 안전).
- `size` 쿼리 파라미터는 애초에 Controller 메서드 시그니처에 없어 자동으로 무시된다 — Service가 다룰 사항이 아니므로 Service 테스트가 아니라 Controller 테스트에서 확인한다.

**(v2 추가) 3-2. 남은 예외(DB 장애 등)를 위한 범위 한정 advice**

`id`·`page` 파싱 실패를 흡수해도 Repository/DB 장애 등 진짜 예상 못 한 예외는 여전히 발생할 수 있다. 전역 `GlobalApiExceptionHandler`를 수정하면 admin API·페이지 전체의 예외 처리가 흔들릴 위험이 있으므로 건드리지 않는다. 대신 `publicweb` 패키지에만 적용되는 별도 advice를 신설한다:

```java
@ControllerAdvice(basePackages = "com.cms.publicweb")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicWebExceptionAdvice {
    @ExceptionHandler(Exception.class)
    public String handleUnexpected(Exception e, HttpServletResponse response) {
        log.error("공개 페이지 처리 중 오류", e);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return "public/notice/error";
    }
}
```
`@ControllerAdvice`의 advice 빈 선택은 대상 컨트롤러에 **적용 가능한(selector가 일치하는) 빈들** 중에서 `@Order`로 우선순위를 가린다 — `basePackages`가 다르므로 admin/API 요청에는 이 advice가 애초에 적용 후보조차 되지 않아, 기존 `GlobalApiExceptionHandler` 동작에는 영향이 없다. `publicweb` 요청에서는 `HIGHEST_PRECEDENCE`로 지정해 전역 advice보다 먼저 매칭되도록 한다(codex 리뷰 2차 검증 — Spring 공식 계약과 일치함을 확인). 신규 최소 뷰 `templates/public/notice/error.html`("일시적인 오류가 발생했습니다" — 예외 상세는 노출하지 않음)을 함께 추가한다.

**(v3 정정) 이 advice의 보장 범위는 "컨트롤러·Service 실행 중 예외"로 한정된다 — 템플릿 렌더링 예외는 포함하지 않는다.** codex 리뷰 2차(높음2)에서 지적된 대로, `DispatcherServlet.doDispatch()`는 핸들러 실행(`ha.handle()`)만 try/catch로 감싸 `@ExceptionHandler` 처리 경로(`processHandlerException`)로 넘기고, 그 다음 단계인 뷰 렌더링(`render()`)은 별도로 감싸지 않는다 — 즉 Thymeleaf 렌더링 중 예외(예: 모델에 없는 속성 참조)는 이 advice가 잡지 못하고 서블릿 컨테이너의 기본 오류 처리(`/error` → `CustomErrorController`)로 그대로 전파된다. 이 공백은 이번 기능이 새로 만든 것이 아니라 앱 전체(페이지를 반환하는 모든 컨트롤러)에 이미 있던 기존 한계이므로 이번 범위에서 별도로 닫지 않는다 — 대신 신규 템플릿(`list.html`·`detail.html`·`error.html`)이 항상 유효한 모델 데이터로만 렌더링되도록 테스트로 보증해, 이 경로 자체가 정상 흐름에서 실제로 트리거되지 않게 한다.

### 4. 공개 DTO에서 `authorId`를 제외한다

`authorId`는 작성 시점 로그인 **관리자 계정의 userId 문자열**이다(`admin` 등). 공개 페이지에 노출하면 유효한 로그인 아이디가 익명 사용자에게 새어 무차별 대입의 출발점이 된다 — 로그인 실패 잠금(5회)과 계정 열거 방지를 이미 구현해둔 정책과 정면으로 어긋난다.

- `PublicNoticeSummary`: `id`, `title`, `createDate`
- `PublicNoticeDetail`: `id`, `title`, `content`, `createDate`, `updateDate`

admin DTO(`NoticeSummaryResponse`/`NoticeResponse`)를 재사용하지 않는 이유가 이것이다 — 재사용하면 `authorId`·`useYn`이 딸려 들어온다.

### 5. XSS: `th:text` 전용, `th:utext` 금지

본문은 관리 화면에서 `<textarea>`로 입력받는 **plain text**다(HTML 에디터 없음). 따라서:
- 제목·본문 모두 `th:text`로 출력한다 → Thymeleaf가 자동 이스케이프한다.
- 줄바꿈은 CSS `white-space: pre-wrap`으로 살린다 — admin 상세 모달(`.notice-content-view`)이 쓰는 것과 동일한 방식.
- **`th:utext`는 어떤 필드에도 쓰지 않는다.** 이 금지를 코드 주석과 테스트로 함께 고정한다.

### 6. 서버 사이드 렌더링 (공개 REST API를 만들지 않는다)

목록·페이징·상세 모두 컨트롤러가 Model에 담아 Thymeleaf가 렌더링한다. 페이징은 `<a href="/notices?page=N">` 링크.

**왜**: 로드맵이 headless가 아니라 공개 프론트를 택했다. GET만 있으므로 CSRF 토큰·`fetch`·JS 상태 관리가 전부 불필요해지고(관리 화면 JS의 복잡도가 여기로 번지지 않는다), 검색엔진·비JS 환경에서도 동작한다. 공개용 JS 파일은 만들지 않는다.

### 7. 레이아웃은 공개 전용 최소 CSS (admin 프래그먼트·SB Admin 2 재사용 안 함)

- `admin/fragments/*`는 사이드바·topbar가 `AdminViewAdvice`/`AdminSidebarAdvice`가 주입하는 모델 속성(`currentAdminName`, `sidebarMenus`)에 의존한다. 이 advice들은 `basePackages="com.cms.admin"` / `annotations=@AdminPage`로 범위가 제한돼 `publicweb`에는 적용되지 않으므로 **재사용 시 렌더링이 깨진다.**
- `sb-admin-2.min.css`는 bootstrap을 통째로 번들한 관리자 테마다(vendor에 컴파일된 bootstrap CSS는 없고 scss만 존재 — 실측). 공개 목록/상세 두 화면에 쓰기엔 과하고 톤도 맞지 않는다.
- **결정**: `static/css/public/notice.css` 신규 최소 CSS. `static/css/error.css`가 이미 같은 선례(공개용 독립 스타일)다.

### 8. 페이지 크기 고정 10, `page` 파라미터만 수용

`size`를 쿼리 파라미터로 받지 않는다 — 비인증 사용자가 `size=100000`으로 전체를 한 번에 긁는 경로를 만들지 않기 위해서다(admin은 `MAX_PAGE_SIZE=100` clamp로 방어하지만, 공개는 애초에 노출하지 않는 편이 단순하다). `page`는 (Controller가 이미 파싱 실패를 0으로 흡수한 뒤) Service에서 **음수는 0으로, `MAX_PAGE`(v3 신규 — 아래 참조) 초과는 0으로** 보정한다. 유효한 범위의 정수이지만 총 페이지 수를 넘으면(예: `page=999`) Spring Data `Pageable`이 예외 없이 빈 콘텐츠를 반환하므로, 이 경우는 그대로 빈 목록 화면("등록된 공지사항이 없습니다")을 보여준다 — URL 오타로 인한 접근과 실제로 공지가 없는 상태를 구분하지 않는다(v2, codex 지적5 — 흔한 UX 패턴으로 판단해 구분 로직을 추가하지 않기로 결정. 대신 경계값 테스트로 이 동작을 고정한다).

**(v3 수정) 인덱스 연기를 "명시적 위험 수용"으로 재정리 + `MAX_PAGE` 상한 신설**: codex 리뷰 2차(중간3)에서 지적된 대로, malformed page(비숫자 등) 보정만으로는 `page=2147483647`처럼 **문법적으로 유효한** 거대 정수가 만드는 대형 OFFSET을 막지 못한다는 지적이 정확하다. `notice` 테이블은 `id` PK 외 인덱스가 없어(`V8__create_notice.sql`) `WHERE deleted=false AND use_yn=true` 목록·카운트 쿼리가 현재도(admin 검색 포함) 풀스캔이며, COUNT 쿼리 비용은 `page` 값과 무관하게 항상 발생한다 — 인덱스 부재 자체는 이번 범위(스키마 변경 없음)에서 해소하지 않는다. 대신 `PublicNoticeService`에 `NoticeService.MAX_PAGE_SIZE=100` 캡 선례를 따르는 `MAX_PAGE = 1000` 상한을 신설해, 이를 초과하는 `page`는 0으로 재보정한다 — **단일 요청의 OFFSET 스캔 비용 상한**은 확실히 막는다(COUNT 비용 자체를 줄이지는 못함, 별개 문제로 인식). **명시적 가정**: 이번 범위는 실사용 공지 수가 수백 건 이하인 소규모 운영을 전제하며, 이 가정이 깨지는 시점(공지 수 급증·실제 공개 트래픽 발생)에는 복합 인덱스(`deleted, use_yn, create_date`) 추가를 별도 작업으로 반드시 재검토한다.

### 9. 감사 로그·방문 로그는 기록하지 않는다

`@AdminActionLogged`를 붙이지 않는다(조회이고, 행위 주체가 관리자가 아니다). `VisitLog`도 건드리지 않는다 — "ADMIN·MANAGER 로그인 성공 1회 = 방문 1건"이라는 기존 정의를 공개 트래픽이 오염시키면 대시보드 지표의 의미가 바뀐다.

### 10. `@AdminPage` 부착 금지

`publicweb` 패키지이므로 `AdminPageAnnotationConventionTest`(스캔 범위 `com.cms.admin`)의 대상이 아니다. 붙이면 `AdminSidebarAdvice`가 공개 요청마다 메뉴 DB 조회를 날린다.

## 작업 단계

의존 방향 안쪽부터. 각 단계 후 `./gradlew compileJava`로 컴파일 확인.

1. **브랜치**: `feat/public-notice-page`
2. **Repository**: `NoticeRepository`에 파생 쿼리 2개 추가 (결정 2)
3. **DTO**: `com.cms.publicweb.notice.dto`의 `PublicNoticeSummary`·`PublicNoticeDetail` (결정 4). 기존 DTO 패턴대로 `@Getter @Builder` + 정적 `from(Notice)` 팩터리
4. **Service**: `PublicNoticeService` — `@Transactional(readOnly = true)`, 페이지 크기 10 고정, 음수·`MAX_PAGE`(1000) 초과 0 보정(v3), 정렬 고정 (page 문자열 파싱 자체는 Controller 책임 — 결정 3-1)
5. **예외 처리(v2 추가)**: `com.cms.publicweb.support.PublicWebExceptionAdvice` (결정 3-2, 보장 범위는 컨트롤러·Service 실행 중 예외로 한정 — v3) + `templates/public/notice/error.html`
6. **Controller**: `PublicNoticeController` (`@Controller`, `@RequestMapping("/notices")`, `@AdminPage` 없음) — `id`·`page`는 String으로 받아 파싱 실패를 흡수만 하고(결정 3-1), 나머지 판단은 Service에 위임. 목록 / 상세(Optional→404)
7. **SecurityConfig**: `requestMatchers(HttpMethod.GET/HEAD, "/notices", "/notices/**").permitAll()` + `requestMatchers("/notices", "/notices/**").denyAll()`(v3 추가) 명시 (인가 정책 변경 — 승인 항목)
8. **템플릿**: `templates/public/notice/list.html`, `detail.html` + `static/css/public/notice.css`. `templates/error/404.html`의 홈 링크를 `/notices`로 교체(결정 7)
9. **테스트** (아래 별도 섹션)
10. **문서**: CLAUDE.md 현행화 + `adversarial-review/plan/PLAN-public-notice.md` 구현·검증 결과 기록

### 신규 파일

```
src/main/java/com/cms/publicweb/notice/controller/PublicNoticeController.java
src/main/java/com/cms/publicweb/notice/service/PublicNoticeService.java
src/main/java/com/cms/publicweb/notice/dto/PublicNoticeSummary.java
src/main/java/com/cms/publicweb/notice/dto/PublicNoticeDetail.java
src/main/java/com/cms/publicweb/support/PublicWebExceptionAdvice.java
src/main/resources/templates/public/notice/list.html
src/main/resources/templates/public/notice/detail.html
src/main/resources/templates/public/notice/error.html
src/main/resources/static/css/public/notice.css
src/test/java/com/cms/publicweb/notice/controller/PublicNoticeControllerTest.java
src/test/java/com/cms/publicweb/notice/service/PublicNoticeServiceTest.java
```

### 수정 파일

```
src/main/java/com/cms/admin/notice/repository/NoticeRepository.java   # 파생 쿼리 2개
src/main/java/com/cms/config/SecurityConfig.java                      # GET/HEAD permitAll + 나머지 메서드 denyAll 명시
src/main/resources/templates/error/404.html                           # 홈 링크 / → /notices
src/test/java/com/cms/config/SecurityConfigTest.java                  # 공개 경로 회귀 테스트 + 스텁(GET/HEAD만 허용, POST denyAll 검증 포함)
src/test/java/com/cms/admin/notice/repository/NoticeRepositoryDataJpaTest.java  # 파생 쿼리 검증
CLAUDE.md                                                              # 보안 표·패키지 구조·엔드포인트
```

## 테스트 계획

- **`PublicNoticeServiceTest`** (단위, mock repository): 노출 공지만 반환 / 미노출·삭제 공지는 `Optional.empty()` / page 음수 → 0 보정 / **`MAX_PAGE`(1000) 초과 → 0 보정(v3)** / 페이지 크기 10 고정 / 정렬 `createDate desc, id desc` 인자 검증
- **`PublicNoticeControllerTest`** (`@WebMvcTest`): **비인증** 목록 200 + 뷰 이름 / 비인증 상세 200 / 미노출·삭제·없는 ID → **404 + `error/404` 뷰** / **`/notices/abc`(비숫자 ID) → 404, JSON 아님(결정 3-1 회귀 고정)** / **`?page=abc`, `page=99999999999999`(정수 범위 초과 문자열) → 파싱 실패가 0으로 흡수되어 200(v3, Controller 책임만 검증)** / **`?size=999`가 있어도 무시됨(v3, Controller 시그니처에 바인딩 대상이 없다는 사실 확인)** / 응답 본문에 `authorId`가 없음(필드명뿐 아니라 실제 작성자 값도 부재 확인) / **`<script>`·따옴표·이벤트 속성 포함 payload가 이스케이프**되어 나옴(결정 5, payload 확장) / 목록·상세 템플릿에 `th:utext` 미사용 정적 확인
- **`PublicWebExceptionAdviceTest` 또는 `PublicNoticeControllerTest` 내 케이스**: Service가 예외를 던지면 HTML 500 + `public/notice/error` 뷰로 응답(JSON 아님) 확인. **(v3 명시)** 이 테스트는 Service/Controller 실행 중 예외만 검증한다 — 템플릿 렌더링 단계 예외는 이 advice의 보장 범위 밖(결정 3-2 v3 정정)이라 별도로 테스트하지 않는다.
- **`SecurityConfigTest`**: 비인증 `/notices`·`/notices/{id}` 200(로그인 리다이렉트 아님) — 스텁 컨트롤러 방식은 기존 파일 패턴 그대로 / **(v3 수정)** 비인증 **POST**(+CSRF 토큰 포함) `/notices` → `denyAll` 경합으로 익명 사용자는 `authenticationEntryPoint`를 타 `/admin/login` **302** 리다이렉트(JSON 아님) — "405/401"이 아니라 실제 인가 거부임을 검증 / **(v4 정정)** `@WithMockUser(roles="ADMIN")` POST `/notices`(+CSRF) → 인증된 사용자는 커스텀 `accessDeniedHandler`의 비-API 분기(`AccessDeniedHandlerImpl`)를 타 정확히 **403**(익명과 다른 경로로 도달하지만 결과는 거부 — "동일하게 거부"가 아니라 "각각 302/403으로 정확히 거부"임을 명시해 405 등 엉뚱한 결과로 오통과하지 않도록 고정) / **(v4 신규)** 비인증 **HEAD** `/notices`(및 `/notices/{id}`) → 200(로그인 리다이렉트 아님) — v3에서 추가한 HEAD 허용 규칙의 회귀 테스트
- **`NoticeRepositoryDataJpaTest`**: 파생 쿼리 2개가 `deleted=true`/`useYn=false` 행을 제외하는지 (Testcontainers — `MariaDbContainerSupport`) / 동률 `createDate`에서 `id desc` tie-breaker 실제 정렬 순서 검증
- **경계값**: 공지 0건에서 `page=0` / 정확히 10건·11건 / 마지막 유효 페이지 / `page == totalPages` / `totalPages=0`일 때 페이징 링크가 깨지지 않음
- **회귀**: `./gradlew test` 전체 통과

## 검증 (실기)

1. `./gradlew test` 전체 통과
2. `./gradlew bootRun` (dev) 기동 — Flyway `validate` 통과(스키마 무변경 확인)
3. **Playwright**
   - ADMIN 로그인 → `/admin/notice/manage`에서 노출 공지 1건 + 비노출 공지 1건 준비
   - **비로그인 컨텍스트**(새 브라우저 컨텍스트)로 `/notices` → 목록에 노출 공지만 보임, 비노출 공지 미표시
   - 상세 클릭 → 본문 렌더링(줄바꿈 유지) 확인, `authorId` 미표시 확인
   - **비노출 공지 ID로 `/notices/{id}` 직접 접근 → 404 페이지** (숨긴 URL 직접 접근 차단)
   - 소프트 삭제한 공지 ID로 직접 접근 → 404
   - **(v2 추가)** `/notices/abc`(비숫자 ID) 직접 접근 → JSON이 아닌 404 HTML 페이지 확인
   - **(v2 추가)** 404 페이지의 "홈으로 돌아가기" 링크 클릭 → `/notices`로 정상 이동(다시 404로 순환하지 않음)
   - `<script>alert(1)</script>` 제목 공지 작성 → 공개 목록/상세에서 스크립트가 실행되지 않고 텍스트로 표시되는지 확인
   - 관리 화면 회귀: `/admin`, `/admin/notice/manage`, `/admin/member/manage` 정상 렌더링 스크린샷
4. 스크린샷 보관. Playwright를 쓸 수 없는 상황이면 그 사실을 명시하고 완료를 주장하지 않는다.

## 리스크

| 리스크 | 대응 |
|---|---|
| `GlobalApiExceptionHandler`(`@RestControllerAdvice`)가 공개 페이지 예외를 JSON으로 바꿔버림 | 결정 3 — 공개 컨트롤러는 예외를 던지지 않고 `Optional` + 뷰 반환. `id`·`page`는 수동 파싱으로 타입 변환 예외 자체를 없애고, 남은 예외는 범위 한정 `PublicWebExceptionAdvice`(결정 3-2)가 HTML 500으로 흡수. 컨트롤러 테스트로 `error/404`·`public/notice/error` 뷰 이름과 JSON이 아님을 고정 |
| **(v3)** `PublicWebExceptionAdvice`가 못 잡는 예외(템플릿 렌더링 단계) | 결정 3-2 — 보장 범위를 "컨트롤러·Service 실행 중"으로 명시 한정. 렌더링 실패는 앱 전체에 이미 있던 기존 공백이라 이번 범위에서 닫지 않되, 신규 템플릿이 항상 유효한 모델로만 렌더링되도록 테스트로 실질적 발생을 차단 |
| 비노출·삭제 공지가 공개로 새어나감 | 결정 1·2 — Service 분리 + 메서드명이 조건을 강제하는 파생 쿼리. Service·Controller·Repository 3계층 모두 테스트 |
| `authorId`(관리자 로그인 ID) 노출 | 결정 4 — 공개 전용 DTO에서 필드 자체를 제거. 컨트롤러 테스트에서 응답 본문에 없음을 검증 |
| 향후 `anyRequest()`를 조일 때 공개 페이지가 조용히 막힘 | 단계 7 — `permitAll` 명시 + `SecurityConfigTest` 회귀 테스트 |
| **(v3)** `permitAll`이 GET 외 메서드까지 인증 면제 | v2에서 `HttpMethod.GET` 매처만 추가했으나, 뒤에 남은 `anyRequest().permitAll()`이 여전히 비-GET 요청을 통과시켜 **실질적 방어가 안 됨을 실측으로 확인**(codex 2차 지적) — GET/HEAD `permitAll` 다음 줄에 `denyAll()`을 명시 추가해 지금 당장 차단하도록 수정 |
| 공개 목록이 `content`(TEXT)까지 DB에서 읽음 + `notice` 테이블에 `deleted`/`use_yn` 인덱스 없음(비인증 트래픽 반복 노출 시 풀스캔 비용) | admin 목록과 동일한 기존 특성. 복합 인덱스 추가는 "스키마 변경 없음" 범위를 벗어나 이번엔 도입하지 않음(명시적 위험 수용 — 소규모 데이터량 가정을 문서화, 실제 공지 수 증가 시 별도 작업으로 재검토). **(v3)** malformed page 방어만으로는 문법적으로 유효한 거대 OFFSET(`page=2147483647`)까지는 못 막는다는 지적을 수용해 `MAX_PAGE=1000` 상한을 Service에 추가 — 단일 요청의 OFFSET 스캔 비용 상한은 확보(COUNT 비용 자체는 별개 문제로 인덱스 도입 전까지 해소 안 됨) |
| 페이지네이션이 동시 쓰기 중 완전한 일관성을 보장한다는 오해 | 결정 2 문구 정정 — tie-breaker는 동일 쿼리 스냅샷 내 동률 정렬만 보장. 동시 변경으로 인한 페이지 경계 이동은 허용 범위로 명시 |
| 첨부가 달린 공지가 공개 상세에서 첨부 없이 보임 | 사용자 확정 범위(첨부 제외). 화면에 첨부 영역 자체를 두지 않아 "깨진 링크"가 아니라 "없는 기능"으로 보이게 함. 후속 작업으로 스트리밍 다운로드 + rate limit과 함께 검토 |
| 공유 템플릿 `error/404.html`의 홈 링크가 매핑 없는 `/`를 가리켜 재-404 순환 | 링크만 `/notices`로 교체(결정 7) — `/notices`가 사실상 유일한 공개 진입점이므로 다른 내용은 그대로 유지 |

## 승인 후 이어갈 워크플로우

이 계획은 8단계 워크플로우의 1(정찰)·2(설계)에 해당한다. 승인 시:
3. `adversarial-review/plan/PLAN-public-notice.md`로 저장 → `plan-review-loop` 스킬로 적대적 리뷰 라운드 반복(ship 판정까지)
4. 리뷰 반영 결과를 다시 보고 → 승인
5~8. 구현 → 테스트 → Playwright 실기 검증 → CLAUDE.md·계획서 기록

커밋/PR은 사용자 확인 후 `/code-review-loop` → `/commitPR`로 처리한다.

## 구현·검증 결과 (2026-07-28)

### 핵심 확정 사항

계획서 v4(ship) 그대로 구현했다. 구현 중 계획과 달라진 판단 1건:

- **컨트롤러 테스트의 "비인증 200" 검증 방식 변경**: 계획 초안은 `PublicNoticeControllerTest`에서 비인증 요청으로 직접 200을 검증하는 것을 전제했으나, 이 프로젝트의 기존 `@WebMvcTest` 컨벤션(`NoticeControllerTest`·`AdminMainControllerTest`)은 슬라이스 기본 보안(미인증 시 401)을 그대로 두고 `@WithMockUser`로 기능을 검증하며, **실제 `SecurityConfig`(permitAll/denyAll) 회귀는 전담 `SecurityConfigTest`가 검증**하는 구조임을 구현 중 확인했다. 계획과 달리 `PublicNoticeControllerTest`는 `@WithMockUser`로 컨트롤러 로직(뷰·모델·404·XSS·예외 advice 우선순위)만 검증하고, 실제 `permitAll`/`denyAll` 동작은 `SecurityConfigTest`(실제 `SecurityConfig` import)가 전담하도록 재배치했다 — 기존 프로젝트 컨벤션과 일관성을 유지하기 위한 조정이며 커버리지 손실은 없다(오히려 `SecurityConfigTest`가 실제 시큐리티 체인으로 더 정확하게 검증).

### 구현 파일

**신규**
- `src/main/java/com/cms/publicweb/notice/controller/PublicNoticeController.java`
- `src/main/java/com/cms/publicweb/notice/service/PublicNoticeService.java`
- `src/main/java/com/cms/publicweb/notice/dto/PublicNoticeSummary.java`, `PublicNoticeDetail.java`
- `src/main/java/com/cms/publicweb/support/PublicWebExceptionAdvice.java`
- `src/main/resources/templates/public/notice/list.html`, `detail.html`, `error.html`
- `src/main/resources/static/css/public/notice.css`
- `src/test/java/com/cms/publicweb/notice/controller/PublicNoticeControllerTest.java`
- `src/test/java/com/cms/publicweb/notice/service/PublicNoticeServiceTest.java`
- `src/test/java/com/cms/publicweb/notice/PublicNoticeTemplateConventionTest.java`(계획에 없던 추가 — `th:utext` 미사용 정적 검증용 경량 테스트)

**수정**
- `src/main/java/com/cms/admin/notice/repository/NoticeRepository.java` — 파생 쿼리 2개
- `src/main/java/com/cms/config/SecurityConfig.java` — GET/HEAD `permitAll` + 나머지 `denyAll`
- `src/main/resources/templates/error/404.html` — 홈 링크 `/` → `/notices`
- `src/test/java/com/cms/config/SecurityConfigTest.java` — 공개 경로 회귀 테스트 6건 + `PublicNoticeStubController`
- `src/test/java/com/cms/admin/notice/repository/NoticeRepositoryDataJpaTest.java` — 파생 쿼리·tie-breaker 테스트 5건
- `CLAUDE.md` — 패키지 구조·핵심 도메인 모델(Notice)·보안 표·엔드포인트 목록
- `docs/troubleshooting.md` — "애플리케이션/런타임" 카테고리에 `@RestControllerAdvice` 전역 적용 범위 이슈 기록
- 스키마 변경 없음(Flyway 최대 버전 V10 그대로, `bootRun` 기동 시 Flyway `validate` 통과로 확인)

### 검증 결과

- `./gradlew test` 전체 통과 (Docker 미기동 상태였다가 기동 후 재확인 — Testcontainers 기반 `NoticeRepositoryDataJpaTest` 포함 전체 통과)
- `./gradlew bootRun`(dev) 기동 성공, Flyway `Schema cms is up to date`(V10) 확인 — 스키마 무변경 재확인
- **Playwright 실기 검증** (ADMIN 로그인 → 노출 공지 2건(스크립트 payload 포함) + 비노출 공지 1건 준비 → 쿠키 삭제로 비로그인 전환):
  - `/notices` 목록에 노출 공지만 표시, 비노출 공지 미표시 확인
  - 상세(`/notices/{id}`) 진입 시 본문 줄바꿈(`white-space: pre-wrap`) 정상 렌더링, 작성자 정보 미표시 확인(스크린샷: `public-notice-detail.png`)
  - 비노출 공지 ID 직접 접근 → 404 페이지 확인
  - 비숫자 ID(`/notices/abc`) 직접 접근 → JSON 아닌 404 HTML 페이지 확인(결정 3-1 검증)
  - `page=abc`, `page=99999999999999` → 200(0으로 보정) 확인
  - 404 페이지 "홈으로 돌아가기" 링크가 `/notices`로 이동, 재-404 순환 없음 확인
  - `<script>`·`onerror` payload 공지 → 목록·상세 모두 텍스트로만 렌더링, alert 미실행 확인(스크린샷: `public-notice-xss-detail.png`)
  - 관리 화면 회귀 없음: 대시보드·공지사항 관리·관리자 조회 정상 렌더링 확인(스크린샷 3장)
  - 검증에 사용한 테스트 공지 3건은 검증 후 관리 화면에서 삭제해 dev DB를 원복함
- Playwright MCP 도구는 정상 동작해 실기 검증을 전부 수행함(제약 없음)

### 이슈

- **컨트롤러 테스트 초안의 XSS 어서션 오류**: `not(containsString("onerror="))`로 작성했으나, 이스케이프된 텍스트(`&lt;img ... onerror=...&gt;`)에는 `onerror=`라는 글자 자체가 여전히 남는다(escaping은 `<`,`>`,`"`,`'`만 치환하고 단어 자체는 치환하지 않음) — 실제 보안 속성인 "raw `<img` 태그 부재"를 검증하도록 어서션을 `not(containsString("<img"))` + `containsString("&lt;img")`로 정정했다. 테스트 코드 내 실수였고 구현(템플릿)에는 문제가 없었다.
- **GET `/admin/logout` → 500(JSON)**: Playwright로 비로그인 전환을 시도하며 발견. 이 기능(공개 공지 페이지)과 무관한 기존 동작이라 이번 PR 범위에서 다루지 않았다(로그아웃은 POST 전용 설계로 보이며, GET 접근 시 처리가 매끄럽지 않음 — 별도 확인 필요). 이번 실기 검증은 쿠키 삭제로 비로그인 상태를 만들어 우회했다.
- **`GET /favicon.ico` → 500**: 공개 페이지 방문 시 브라우저가 자동 요청하며 발견. 이 기능과 무관한 기존 동작(정적 리소스 404 처리 미흡으로 추정), 이번 범위에서 다루지 않음.

### 후속

- `notice` 테이블 인덱스 부재(비인증 트래픽 노출에 따른 위험) — 실제 공지 수 증가 시 복합 인덱스(`deleted, use_yn, create_date`) 추가 재검토(결정 8 참조)
- 위 "이슈"의 `/admin/logout` GET 500, `/favicon.ico` 500은 이 기능과 무관한 기존 결함으로 별도 조사·수정 필요(이번 PR 범위 아님 — 발견 사실만 기록)
- 로드맵 ③ 완료 → 다음은 ⑤ prod 프로파일 부활(로드맵 참조)
