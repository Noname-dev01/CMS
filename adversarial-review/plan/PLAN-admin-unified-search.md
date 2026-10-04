# PLAN — 관리자 상단바 통합 검색 (드롭다운 미리보기)

> 상태: v2 (2026-10-05) — 사용자 결정 반영, **적대적 리뷰 전**
>
> **개정 이력**
> - v6 변경(3라운드 no-ship, codex `gpt-6.1-sol`, 신규 지적 1건 — **기각**):
>   - R-13 **기각**: 공지 화면에서 같은 공지를 연속 저장하면(저장 응답 대기 중 취소→수정→재저장) 이전 저장 응답이 최신 수정 화면을 덮는다는 지적은 사실로 인정한다(기존 결함, Node 모의 실행 재현). 그러나 **쓰기(저장) 경로의 결함**이라 검색·`?id=` 진입과 무관하고 진입이 발생 가능성을 높이지도 않는다. R-1·R-10~12는 `?id=`가 호출하는 **읽기 경로**(상세·첨부 조회)라 이 PR이 책임지지만, 저장 경로까지 고치면 범위가 공지 수정 기능 전체로 커진다(작업 방식 2번 — 무관한 변경 섞기 금지). **후속**: 별도 이슈/PR로 다루고 PR 본문 "범위 밖 후속"에 기록한다(R-2의 메뉴 URL 저장 검증과 함께)
> - v5 변경(2라운드 no-ship, codex `gpt-6.1-sol`, 신규 지적 3건 전부 수용 — 모두 `?id=`가 재사용하는 기존 화면의 비동기 결함):
>   - R-10 수용: 회원 화면 자동 진입이 `/members/me` 대기 중 사용자의 수동 선택·닫기를 덮어 31번을 다시 연다 → 자동 진입은 **예약 시점의 세대를 캡처**하고 수동 선택·닫기가 세대를 올리면 폐기(§5-D). `/members/me` 지연 중 수동 선택·닫기 시험 추가(§7)
>   - R-11 수용: 공지 상세의 오류 본문 처리(`response.json()` catch)가 `AbortError`를 일반 오류 문구로 바꾸고 상세 catch에 컨트롤러 일치 검사가 없어 31번의 오류가 32번 모달에 표시된다(Node 모의 응답으로 재현) → 오류 처리에도 현재 컨트롤러 검사, `AbortError`는 그대로 전파해 무시(§5-D)
>   - R-12 수용: 공지 첨부 목록이 세대 확인 **뒤에** `await response.json()`하고 이후 재검사가 없어 늦은 31번 첨부가 32번 모달을 덮는다(`currentDetail.id=32`인데 `31.txt` 표시 재현) → 본문 해석 뒤 세대·공지 ID 재검사, 닫기·상세 전환에서 첨부 세대 무효화(§5-D)
> - v4 변경(사용자 결정, 2026-10-05): R-8 확정 — **최소 검색어 2자**(코드포인트 기준, trim 후). 서버는 2자 미만이면 쿼리 없이 빈 결과(200), 화면은 요청 자체를 보내지 않고 "2자 이상 입력하세요" 안내(§5-B, §5-E). 레이트리밋은 **기각**(인증된 관리자 API이고 현행 레이트리밋은 무인증 공개 경로 전용이라 정책 범위가 커짐). 드롭다운 "더 보기" 없음 확정. 계획서는 구현 PR과 함께 커밋
> - v3 변경(1라운드 no-ship, codex `gpt-6.1-sol`, 지적 9건 — 7건 수용·2건 부분 수용·**R-8 결정 대기**):
>   - R-1 수용: 회원 상세 `loadAdminDetail`에 취소·세대·대상 ID 검사가 없어 응답 순서가 뒤집히면 `currentDetail`이 다른 회원으로 바뀌고 `saveEdit`이 그 전역 상태로 PATCH 대상을 정함(원본 함수를 Node로 실행해 31→32 선택이 31로 끝나는 것 재현). 기존 결함이지만 `?id=` 자동 진입이 그대로 쓰므로 이 PR이 고친다(§5-D)
>   - R-2 수용: 메뉴 URL은 저장 시 길이만 검사(`MenuCreateRequest`), ADMIN 가시성은 URL 무관 true → 저장된 `javascript:` URL을 결과 이동에 쓰면 실행됨. 결과 항목의 이동 대상은 **같은 출처 경로**(`/`로 시작, `//`·`\`·제어문자·공백 없음)만 허용하고 나머지 메뉴는 결과에서 제외(§5-C). 메뉴 저장 검증·기존 사이드바 `href`의 같은 위험은 **범위 밖 후속**으로 PR 본문에 남긴다
>   - R-3 수용: 입력 변경 즉시 활성 선택 해제, 닫기(Esc·바깥 클릭)에서도 타이머·요청 무효화, 오류·401·로딩 종료에도 세대 검사(§5-E)
>   - R-4 수용: 회원 화면은 `loadCurrentAdminId()`가 끝난 뒤 자동 진입하고, 본인 ID 도착 후 `configureEditAccess`를 재판정(§5-D)
>   - R-5 수용: 한글 IME 조합 중(`isComposing`·`compositionstart/end`)에는 Enter·방향키를 소비하지 않고 조합 종료 후 검색 예약(§5-E, §7)
>   - R-6 부분 수용: F8 사실 정정(컨벤션 테스트는 세 선언의 OR로 "선언 있음"만 검사, 개수는 세지 않음). **기각**: 테스트를 개수 검사로 바꾸는 것은 범위 밖 — 새 API는 선언 한 개임을 구현 시 확인
>   - R-7 수용: F3·F10 문구 정정 — "같은 요청 시점의 가시성 일치"이며 서로 다른 요청 사이 권한 변경은 보장하지 않는다. 권한 회수 후 `?id=` 링크는 페이지 게이트에서 403 페이지, 페이지를 받은 뒤 회수되면 상세 API 403이 모달에 표시 — 두 경우를 시험으로 구분(§7)
>   - R-8 **결정 대기**: 디바운스·abort는 서버 부하 제한이 아니다(공지 검색은 5건 + 전체 COUNT, `%kw%` LIKE는 인덱스 불가, abort가 JDBC 취소로 이어지지 않음). 서버 측 제한 방식은 사용자 결정 후 반영
>   - R-9 부분 수용: `id` 정규식을 `^[1-9]\d{0,15}$`(16자리 이하 = JS 정밀도 안전 범위)로 한정(§5-D). **기각**: ID 문자열화 — 자동 증가 ID가 2^53에 닿을 현실적 가능성이 없고 기존 API 계약 변경이 크다
> - v2: 사용자 결정 반영 — 결과는 **상단바 드롭다운 미리보기**(JSON API 필요), 결과 클릭 시 **상세를 바로 연다**(공지·회원 관리 화면에 `?id=` 진입 처리), 공지는 제목만, 관리자는 아이디+이름. v1의 서버 렌더링 결과 페이지안은 폐기
> - v1: 초안(결과 페이지 서버 렌더링안)
>
> 근거: **정적 정찰 기준**(코드를 열어 대조, 빌드·테스트 미실행). 기준 커밋 = `feat/my-settings` HEAD `7557bc9`(#88·#89 머지 후 master + 내 설정 PR #90). 구현은 #90 머지 후 master에서 새 브랜치 `feat/topbar-search`로 시작한다
> 유형: feat · **인가 정책 변경**(새 경로 `/admin/api/search-results`를 ADMIN·MANAGER 상시 허용) · 스키마 변경 없음 · 신규 의존성 없음 · `SecurityConfig` 코드 변경 없음(카탈로그 게이트 추가로 처리)

## 0. 요약

#88에서 제거한 상단바 검색창을 실제 기능으로 되살린다. 검색어를 입력하면 상단바 아래 드롭다운에 **메뉴·공지사항·관리자 계정** 결과가 섹션별로 미리 보이고, 항목을 누르면 메뉴는 그 화면으로, 공지·관리자는 **해당 관리 화면에서 상세 모달이 바로 열린다**. 결과는 **현재 사용자가 원래 볼 수 있는 것만** 나온다 — 검색이 권한 우회 경로가 되지 않는 것이 이 계획의 핵심 불변식이다.

| 도메인 | ADMIN | MANAGER | 클릭 시 |
|---|---|---|---|
| 메뉴 | 사이드바에 보이는 메뉴 | 사이드바에 보이는 메뉴(그 회원 권한으로 가지치기된 결과) | 메뉴 URL |
| 공지사항(제목) | 항상 | 그 회원에게 `NOTICE:READ`가 있을 때만 | `/admin/notice/manage?id={id}` → 상세 모달 |
| 관리자 계정(아이디·이름) | 항상 | **항상 제외**(회원 관리는 `ADMIN_ONLY`) | `/admin/member/manage?id={id}` → 상세 모달 |

## 1. 확정된 사용자 결정

| # | 쟁점 | 결정 |
|---|---|---|
| D1 | 검색 범위 | **통합 검색**(메뉴·공지·관리자) — 2026-10-04 |
| D2 | 접근 대상 | ADMIN·MANAGER 모두 검색창 사용, 결과는 권한으로 필터 |
| D3 | 결과 화면 | **상단바 드롭다운 미리보기** — 2026-10-05 |
| D4 | 결과 클릭 | **상세를 바로 연다** — 2026-10-05 |
| D5 | 공지 검색 대상 | **제목만**(관리 화면 검색과 같은 범위) — 2026-10-05 |
| D6 | 관리자 검색 필드 | **아이디 + 이름** — 2026-10-05 |

## 2. 정찰 사실 표

| # | 사실 | 근거 | 이 계획의 처리 |
|---|---|---|---|
| F1 | 공지 검색 쿼리가 있다 — `deleted=false` 고정, `title contains keyword`, `useYn` 선택 필터, id 보조 정렬 | `NoticeRepositoryImpl.searchNotices` :30-63 | 그대로 재사용(`useYn` 미지정 = 사용·미사용 모두 — 관리 화면 목록과 같은 범위) |
| F2 | 회원 검색 쿼리는 `ROLE_ADMIN/MANAGER`만, `DELETED` 제외, `userId contains` **AND** `userName contains` | `MemberRepositoryImpl` :35-60 | 검색어 하나로 아이디 **또는** 이름을 찾아야 하므로 `MemberRepositoryCustom.searchByKeyword(keyword, pageable)`(같은 기본 조건 + OR) 추가 |
| F3 | 사이드바는 `MenuService.getSidebarMenus(urlVisible)`가 판정기에서 도출한 URL 가시성으로 가지치기한 트리다(활성·깊이 3 이내) | `AdminSidebarAdvice` :45-57, `menu/CLAUDE.md` "노출 계산" | 메뉴 검색은 **이 결과 트리를 평탄화해 이름으로 필터** — 별도 권한 로직이 없어 **같은 요청 시점에서** 사이드바와 검색 결과가 어긋나지 않는다(서로 다른 요청 사이의 권한 변경은 보장하지 않음 — v3 R-7) |
| F4 | `AdminSidebarAdvice`는 `@AdminPage` 컨트롤러에만 적용된다(REST 요청엔 메뉴 조회 없음 — 의도) | 같은 파일 Javadoc | 검색 API는 서비스 안에서 `getSidebarMenus`를 직접 호출한다(검색 요청당 메뉴 조회 1회 — 의도된 비용) |
| F5 | 판정기 `AdminPermissionEvaluator`가 유일한 판정 함수. ADMIN은 DB 미조회 true, MANAGER 위임 기능은 회원 본인 허용 행, 회원 식별 불가 주체는 위임 기능 fail-closed | `permission/CLAUDE.md` "판정" | 공지 섹션 = `allows(snapshot, NOTICE, READ)`, 관리자 섹션 = `ROLE_ADMIN` 권한 |
| F6 | URL 게이트는 카탈로그(`AdminFeature.gatePatterns`)로 만들어진다. `ALWAYS`는 ADMIN·MANAGER, 카탈로그 밖 `/admin/**`는 ADMIN 캐치올 | `SecurityConfig` :84-100 | 새 상시 허용 기능 `SEARCH` 추가(§5-A) |
| F7 | 권한관리 화면·API는 `AdminFeature.values()` 전체를 행으로 보여 준다 | `MemberPermissionService` :220, `permission/manage.html` :304-309 | 권한관리 화면에 "통합 검색 — 모든 관리자 상시 허용" 행이 생긴다(편집 불가). 기능 목록을 세는 시험 후보 `AdminFeatureTest`·`MemberPermissionServiceTest`·`AdminPermissionEvaluatorTest`·`DashboardServiceTest` 확인·조정 |
| F8 | `AdminEndpointAuthorizationConventionTest`: 읽기 전용 GET 페이지만 면제, API는 인가 선언이 **있어야** 한다(`@RequirePermission` \| `hasRole('ADMIN')` \| **ALWAYS 경로 한정** `hasAnyRole('ADMIN','MANAGER')` — 테스트는 세 플래그를 OR로 합쳐 "선언 없음"만 잡고 중복은 세지 않는다, v3 R-6) | `permission/CLAUDE.md` "컨벤션 테스트", `AdminEndpointAuthorizationConventionTest` `declared()`·`violations()` | 검색 API에 `@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")`. "ALWAYS 경로" 판정이 `SEARCH`의 게이트 패턴을 인식하는지 구현 시 확인(§4 1단계) |
| F9 | 공지 관리 화면은 `openDetailModal(id)`(모달 열기 + 상세 조회), 회원 관리 화면은 `$('#adminDetailModal').modal('show')` + `loadAdminDetail(id)`로 상세를 연다. 두 화면 모두 URL 쿼리를 읽지 않고 진입 시 목록 첫 페이지만 조회한다 | `notice/manage.html` :717-735,770,1042 / `member/admin-manage.html` :666-711,754-755 | 진입 시 `?id=` 처리 추가(§5-D). 기존 함수를 그대로 호출하므로 상세 조회 API·권한은 바뀌지 않는다 |
| F10 | 상세 API: 공지 `GET /admin/api/notices/{id}`(`@RequirePermission NOTICE READ`), 회원 `GET /admin/api/members/{id}`(`hasRole('ADMIN')`) | `NoticeController` :46-48, `AdminMemberController` :70-72 | 링크로 들어온 id도 기존 API가 판정한다. **두 경우를 구분**: ① 링크를 연 시점에 권한이 없으면 페이지 게이트(`SecurityConfig` 카탈로그 게이트)가 403 페이지로 막아 모달 스크립트에 도달하지 않음(페이지 게이트는 유지) ② 페이지를 받은 뒤 권한이 회수되면 상세 API 403이 기존 모달 오류 영역에 표시. 없는 id는 404 모달 오류 |
| F11 | 검색 DTO 상한: 공지 `keyword` 200, 회원 `userId` 50·`userName` 100 | 각 `*SearchRequest` | 통합 검색어 `@Size(max=100)`(초과는 공통 400 `VALIDATION_ERROR`) |
| F12 | QueryDSL `contains`는 `like ... escape '!'`로 렌더링되고 `%`·`_`를 이스케이프한다(기존 검색이 사용 중) | 기존 사용처 — **구현 전 context7로 재확인** | 리터럴 처리를 시험으로 고정 |
| F13 | 상단바는 모든 관리자 페이지가 포함하는 프래그먼트이고, 페이지마다 하단에서 jQuery·Bootstrap을 로드한다 | `fragments/topbar.html`, 각 페이지 | 검색 스크립트는 프레임워크 비의존(vanilla) 정적 파일 `static/js/admin/topbar-search.js`를 프래그먼트에서 `defer`로 로드 |

## 3. 범위

**포함**
- 검색 API `GET /admin/api/search-results?keyword=`
- 서비스 `AdminSearchService`(권한 필터를 서비스 한 곳에서 수행) + 회원 키워드(아이디 OR 이름) 쿼리
- 상단바 검색 입력 + 드롭다운(데스크톱), 모바일(xs) 검색 아이콘 → 같은 입력을 펼침
- 공지·회원 관리 화면의 `?id=` 진입 시 상세 모달 열기
- 카탈로그 `SEARCH`(ALWAYS) 추가, 문서(`config/CLAUDE.md` 표, `permission/CLAUDE.md` 기능 표)

**제외**
- 별도 결과 페이지(드롭다운의 "더 보기" 대상 없음 — §5-C 참고), 공지 본문 검색, 활동 로그·권한·메뉴 관리 데이터 검색, 검색 이력·하이라이트
- 서버 측 레이트리밋(인증된 관리자 API이고 클라이언트 디바운스로 충분하다는 가정 — 기존 레이트리밋은 무인증 공개 경로 전용)
- 전문 검색 인덱스 — 관리자 데이터 규모에서 LIKE로 충분하다는 가정

## 4. 단계 (각 단계 끝에 `./gradlew test` 통과)

1. 카탈로그 `SEARCH` 추가 + 기능 목록 시험 조정 + `SecurityConfigTest`(게이트) + 컨벤션 테스트가 새 API 선언을 받아들이는지 확인
2. `MemberRepositoryCustom.searchByKeyword` + 리포지토리 시험
3. `AdminSearchService` + 단위 시험(역할·권한 조합)
4. `AdminSearchController`(API) + 슬라이스 시험
5. 상단바 입력·드롭다운 + `topbar-search.js`
6. 공지·회원 관리 화면 `?id=` 진입 처리
7. 통합 시험(실제 SecurityConfig·판정기·MariaDB)
8. playwright 실화면 검증(ADMIN·MANAGER 각각, 키보드 조작 포함)

## 5. 설계

### 5-A. 인가

- `AdminFeature.SEARCH(ALWAYS, "통합 검색", EnumSet.of(READ), List.of(), List.of("/admin/api/search-results"))`
  - `menuUrls`는 비운다(사이드바 메뉴가 아니다). 게이트 패턴은 **정확히 이 경로 하나**(하위 경로를 미리 열지 않는다)
- API 메서드: `@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")`
- **데이터 노출 판정은 게이트가 아니라 서비스 내부 권한 필터가 한다** — 게이트는 "검색창을 쓸 수 있는가"만 판정
- 대안(기각): `MY_INFO`·`DASHBOARD` 게이트에 경로 추가 — 기능 의미가 섞이고 권한관리 화면에서 구분되지 않는다

### 5-B. API

`GET /admin/api/search-results?keyword={kw}` (URI는 `api-conventions`의 명사·복수형 규칙)

- 요청 DTO `AdminSearchRequest`: `keyword` `@Size(max = 100)`. trim 후 **2코드포인트 미만이면 쿼리 없이 빈 결과(200, 섹션 키 없음)** — 와일드카드·1글자 검색으로 전체 스캔을 유발하지 않기 위한 서버 측 비용 제한(v4 R-8)
- 응답(섹션이 권한상 없으면 **키 자체를 생략** — 빈 배열과 구분, `@JsonInclude(NON_NULL)`):

```json
{
  "keyword": "공지",
  "menus":   { "total": 1, "items": [ { "name": "공지사항 관리", "path": "공지사항 관리", "url": "/admin/notice/manage", "icon": "fas fa-fw fa-bullhorn" } ] },
  "notices": { "total": 12, "items": [ { "id": 31, "title": "...", "useYn": true, "createDate": "2026-10-01T10:00:00" } ] },
  "members": { "total": 0, "items": [] }
}
```

- 섹션당 최대 5건, `total`은 그 사용자가 볼 수 있는 전체 건수
- 관리자 항목은 `id`·`userId`·`userName`·`userType`·`status`만(이메일·프로필 제외)
- 오류: 100자 초과 400 `VALIDATION_ERROR`, 미인증 JSON 401(`ApiAuthenticationEntryPoint`), ROLE_USER 403

### 5-C. 서비스 `AdminSearchService` (`com.cms.admin.search` 신규 패키지)

- `@Transactional(readOnly = true)`
- MANAGER일 때만 `snapshot()`을 **한 번** 받아 메뉴 가시성·공지 판정에 같이 쓴다(`AdminSidebarAdvice`의 지연 공급자 패턴)
- **메뉴**: `getSidebarMenus(menuUrlVisibility(snapshotOnce, authentication))` → 깊이 우선 평탄화 → `menuName`에 검색어 포함(대소문자 무시) **그리고 URL이 있는** 노드만(자식 있는 그룹은 사이드바가 URL을 그리지 않으므로 제외 — `menu/CLAUDE.md` 가지치기 규칙). `path`는 "상위 > 하위". **이동 대상 URL 검증(v3 R-2)**: `^/(?![/\\])[^\s\x00-\x1f\x7f]*$`에 맞는 같은 출처 경로만 결과에 넣고, 그 밖의 값(`javascript:`·`data:`·`//host`·`http(s)://…`·`\`·공백·제어문자 포함)은 **결과에서 제외**한다. 화면은 이 값을 `href`/`location`에 쓰기 전에 같은 검사를 한 번 더 한다(서버 검사를 신뢰하지 않는 방어 심층)
- **공지**: 판정 false면 `notices` 생략. true면 `searchNotices(keyword, useYn=null, PageRequest.of(0, 5, createDate desc))`
- **관리자**: `ROLE_ADMIN`이 아니면 `members` 생략. ADMIN이면 `searchByKeyword(keyword, PageRequest.of(0, 5, id desc))`
- 드롭다운에 "더 보기"는 두지 않는다 — `total`이 5를 넘으면 "전체 N건 중 5건 — 검색어를 구체적으로 입력하세요" 안내(별도 결과 페이지는 범위 밖)

### 5-D. 상세 바로 열기 (`?id=`)

- 공지 관리 화면: 첫 `loadNotices(0)` 호출 뒤, `new URLSearchParams(location.search).get('id')`가 `/^[1-9]\d{0,15}$/`(16자리 이하 — JS 숫자 정밀도 안전 범위, v3 R-9)이면 `openDetailModal(id)`. 공지 상세는 이미 `AbortController`로 이전 요청을 취소하므로 그 동작을 시험으로 확인만 한다
- 회원 관리 화면: 같은 조건이면 **`loadCurrentAdminId()`가 끝난 뒤** `$('#adminDetailModal').modal('show'); loadAdminDetail(id)`(v3 R-4). 본인 ID가 상세보다 늦게 도착하는 경우를 막기 위해 상세 응답을 그린 뒤 `configureEditAccess`가 본인 ID가 확정된 상태에서 호출되도록 한다(미확정이면 수정 버튼을 숨긴 채 ID 도착 후 재판정)
- **회원 상세 응답 경쟁 수정(v3 R-1, 기존 결함)**: `loadAdminDetail`·`saveEdit`에 모달 세대(`detailSeq`) + 요청 대상 ID를 둔다 — 응답 도착 시 `seq`가 현재와 같고 응답 `id`가 요청 `id`와 같을 때만 `currentDetail`·화면을 갱신하고, 모달을 닫을 때·다른 회원을 열 때 세대를 올리고 진행 중 요청을 `AbortController`로 취소한다. 저장 응답도 같은 검사를 하고 `saveEdit`의 PATCH 대상은 화면에 표시된 확정 대상 ID에서만 가져온다
- **자동 진입 폐기(v5 R-10)**: 회원 화면은 `/members/me` 대기를 시작할 때의 `detailSeq`를 캡처하고, 대기가 끝났을 때 세대가 그대로일 때만 자동 진입한다 — 대기 중 수동 선택·닫기가 세대를 올렸으면 자동 진입을 버린다
- **공지 화면 비동기 보강(v5 R-11·R-12, 기존 결함)**: ① 상세 오류 경로 — 오류 본문 해석에서 `AbortError`를 일반 오류 문구로 바꾸지 않고 그대로 던지며, 상세 catch는 "현재 컨트롤러와 같을 때만" 오류를 표시하고 `AbortError`는 무시한다 ② 첨부 목록 — `await response.json()` **뒤에** 세대와 현재 공지 ID를 다시 검사하고, 모달을 닫을 때·다른 공지로 전환할 때 첨부 세대를 올려 진행 중 요청을 무효화한다. 수정은 두 함수 내부로 한정(새 화면 로직 없음)
- 모달을 연 직후 `history.replaceState`로 `id` 파라미터를 지운다 — 새로고침·뒤로 가기에서 모달이 다시 열리지 않게
- 형식이 아니면 무시(목록만 표시). 없는 id는 상세 API 404가 기존 모달 오류 영역에 표시된다. 권한 문제는 F10의 두 경우로 나뉜다

### 5-E. 상단바 드롭다운 (`topbar-search.js`)

- 마크업: 입력 `role="combobox"`·`aria-expanded`·`aria-controls`, 결과 목록 `role="listbox"`, 항목 `role="option"`. `maxlength="100"`. `form`은 제출을 막는다(Enter는 활성 항목 이동)
- 요청: 입력 후 **300ms 디바운스**, trim 후 **2코드포인트 미만이면 요청하지 않고** 빈 값은 닫기, 1글자면 "2자 이상 입력하세요" 안내(v4 R-8). 새 요청 전 이전 요청 `AbortController.abort()` + 요청 세대 번호로 늦은 응답 무시(응답의 `keyword`가 현재 입력과 같을 때만 반영)
- **세대 무효화 규칙(v3 R-3)**: ① 입력이 바뀌는 즉시 활성 항목 선택을 해제하고(이전 결과로 Enter 이동 불가) ② Esc·바깥 클릭·닫기 시 디바운스 타이머와 진행 중 요청을 취소하고 세대를 올려 늦은 응답이 드롭다운을 다시 열지 못하게 하며 ③ 성공뿐 아니라 오류·401·로딩 종료 처리도 모두 "요청 세대 == 현재 세대"일 때만 화면을 바꾼다
- **IME(v3 R-5)**: `compositionstart`~`compositionend` 사이와 `event.isComposing`/`keyCode 229`인 키 입력에서는 Enter·↑↓를 소비하지 않는다(한글 확정 Enter가 이동으로 처리되지 않게). `compositionend` 시점에 디바운스를 예약한다
- 렌더링: **`textContent`·`createElement`만 사용**(`innerHTML`에 데이터 삽입 금지). 섹션 제목, 항목(메뉴는 아이콘 클래스 — 화이트리스트 형식 `^[a-z0-9 -]+$`만 적용), 공지는 "미사용" 배지
- 키보드: ↑↓ 이동, Enter 이동, Esc 닫기(입력값 유지), 바깥 클릭 닫기, 포커스 복귀
- 상태 문구: 로딩, 결과 없음, 오류("검색 중 오류가 발생했습니다"), **401 → "세션이 만료되었습니다" + 로그인 링크**
- 모바일(xs): 검색 아이콘 → 같은 입력·드롭다운을 펼침(입력 요소 1개를 공유 — 스크립트가 한 인스턴스만 다룸)
- CSRF: GET이라 불필요

## 6. 위험·불변식

| # | 위험 | 대응 |
|---|---|---|
| R1 | 검색이 권한 우회 경로(MANAGER가 공지 권한 없이 제목 열람, 회원 목록 열람) | 섹션 노출을 서비스에서 판정기로 결정 + 섹션 키 생략, 통합 시험으로 고정(§7 ⑥) |
| R2 | 메뉴 결과와 사이드바 불일치 | 같은 `getSidebarMenus` + 같은 판정 함수(F3) |
| R3 | `?id=` 링크로 권한 없는 상세 조회 | 페이지 게이트 + 기존 상세 API의 인가가 그대로 판정(F10 두 경우). 화면은 표시만 |
| R11 | 메뉴 URL이 `javascript:` 등으로 저장돼 결과 이동에서 실행(v3 R-2) | §5-C 같은 출처 경로 검증(서버·화면 이중), 위반 메뉴는 결과 제외. 메뉴 저장 검증·사이드바 `href`는 범위 밖 후속(PR 본문에 기록) |
| R12 | 회원 상세 응답 경쟁으로 다른 회원이 수정됨(v3 R-1, 기존 결함) | §5-D 모달 세대·대상 ID 검사, 시험으로 응답 순서 역전 재현 |
| R4 | 늦은 응답이 최신 입력의 결과를 덮음 | abort + 세대 번호 + `keyword` 일치 확인(§5-E) |
| R5 | XSS(공지 제목·회원 이름·메뉴 아이콘 클래스) | `textContent`만, 아이콘 클래스 형식 검사, 시험에서 `<img onerror>` 제목이 문자 그대로 보이는지 확인 |
| R6 | 와일드카드(`%`·`_`) | F12 + 시험. 권한 범위 안의 열거는 원래 목록에서도 가능해 보안 경계 문제는 아니다(정확성 문제) |
| R7 | 키 입력마다 요청 폭주 | 300ms 디바운스 + abort. 요청당 쿼리: 메뉴 1 + (공지 2) + (회원 2) + MANAGER 스냅샷(캐시) |
| R8 | 권한관리 화면에 새 행 | 의도된 표시(편집 불가 상시 허용), PR 본문 명시 |
| R9 | 세션 만료 중 입력 | 401 문구 + 로그인 링크(§5-E). 로그인 리다이렉트 HTML이 아니라 JSON 401이 오는 것은 `/admin/api/**` 기존 계약 |
| R10 | 비활성 공지 노출 | 관리 화면 목록과 같은 범위로 의도. "미사용" 배지 |

## 7. 시험

1. `AdminFeatureTest` 등: `SEARCH`가 ALWAYS·게이트 정확히 `/admin/api/search-results`·menuUrls 비어 있음
2. `SecurityConfigTest`: MANAGER 통과, 비로그인 JSON 401, ROLE_USER 403, `/admin/api/search-results/x`는 ADMIN 캐치올(MANAGER 403)
3. `AdminEndpointAuthorizationConventionTest`: 새 API가 통과(선언 1개)
4. `AdminSearchServiceTest`(단위): ADMIN → 3섹션, MANAGER + NOTICE READ → 메뉴·공지, MANAGER 권한 없음 → 메뉴만, MANAGER는 관리자 섹션 없음, 공백 검색어 → 쿼리 없음, 그룹 메뉴 제외, 최대 5건·total
5. 리포지토리(Testcontainers): 아이디 일치·이름 일치(OR), DELETED·ROLE_USER 제외, `%`·`_` 리터럴
6. 컨트롤러 슬라이스 + 통합(`MariaDbContainerSupport`·`TestMembers`): 실제 MANAGER로 권한 부여 전/후 `notices` 키 유무, `members` 키 항상 없음, 101자 400, 응답에 이메일 없음
7. 화면 로직 시험(가능한 범위): 회원 상세 응답 순서 역전(31→32) 시 최종 상태가 32, 메뉴 URL 검증(`javascript:`·`//x`·`\`·공백 → 제외, `/admin/x` → 통과), 권한 회수 후 `?id=` 진입 구분(페이지 게이트 403 / 상세 API 403), `/members/me` 지연 중 수동 선택·닫기 시 자동 진입 폐기(R-10), 공지 상세 404 본문 읽는 중 다른 공지로 전환 시 새 모달 보존(R-11), 늦은 첨부 응답이 새 공지 모달을 덮지 않음(R-12)
8. playwright: ADMIN·MANAGER 각각 — 입력 → 드롭다운 → 키보드 이동·Enter → 메뉴 이동 / 공지·관리자 상세 모달 열림 → URL에서 `id` 제거, 권한 없는 MANAGER에게 공지 섹션 없음, 특수문자 제목 표시

**주체 규칙**: 공지 노출을 다루는 MANAGER 시험은 `@WithManager`(슬라이스)·`TestMembers.asMember`(통합)를 쓴다(`src/test/java/CLAUDE.md`).

## 8. 문서

- `config/CLAUDE.md` 접근 제어 표: `/admin/api/search-results` ADMIN·MANAGER(상시 허용 `SEARCH`)
- `permission/CLAUDE.md` 기능 표: `SEARCH` 추가
- `notice/CLAUDE.md`·`member/CLAUDE.md`: 관리 화면 `?id=` 진입 동작 한 줄
- 루트 `CLAUDE.md`: 변경 없음

## 9. 미결 사항

- 없음(Q1~Q4는 D3~D6으로 결정). "더 보기" 없음(§5-C)은 계획상 기본값 — 리뷰·사용자 확인 대상

## 구현 메모 (2026-10-05)

- 구현 완료(브랜치 `feat/topbar-search`, 커밋·PR 전). `./gradlew test` 1042건 통과, playwright로 ADMIN·MANAGER(권한 부여 전/후) 검증.
- 계획과 달라진 점: ① 메뉴 URL 정규식 초안이 경로 **중간**의 `\`(`/admin\evil`)를 통과시켜 단위 시험이 잡았다 — 문자 클래스에 `\`를 넣어 수정(서버·화면 동일). ② 카탈로그에 `SEARCH`가 들어가면서 권한관리 매트릭스의 행 위치가 밀려 `MemberPermissionApiIntegrationTest`의 고정 인덱스 단언(`features[2]`)이 깨졌다 — 기능 이름으로 찾도록 시험을 고쳤다.
- 범위 밖 후속(PR 본문에 기록): 메뉴 저장 시 URL 형식 검증·사이드바 `href`, 공지 화면 연속 저장 응답 경쟁(R-13).
- 미확인: 실제 한글 IME 입력(Enter 확정) 동작 — 코드 경로(`compositionstart/end`·`isComposing`)만 구현, 브라우저 자동화로 재현하지 못했다.
