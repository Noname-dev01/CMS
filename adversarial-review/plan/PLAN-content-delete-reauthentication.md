# PLAN — 쿠키 세션의 복구 불가 콘텐츠 삭제에 재인증 확대

> 상태: v3 (2026-11-12) — 적대적 리뷰 3라운드 ship → 승인(2026-11-12) → 구현·검증 완료(PR 전)
> 선행: `PLAN-sensitive-reauthentication.md` v10(#228 머지) — 표지·인터셉터·재인증 API·`reauth.js` 모달을 그대로 재사용한다.
> 출처: 로드맵 69차 머리말 "남은 후속 후보 — 쿠키 세션의 콘텐츠 삭제류 재확인 확대 여부".
> **위험 변경: 인가(인증 경로)** — 쿠키 자동 로그인 세션이 할 수 있는 작업을 줄인다. URL 접근 규칙(`SecurityConfig`)·스키마·의존성·동시성 변경 없음.

## 1. 목표와 범위

쿠키 자동 로그인(remember-me)으로만 인증된 세션은 **화면에서 되살릴 수 없는 콘텐츠 삭제**를 비밀번호 재확인 없이 실행할 수 없게 한다.
기존 민감 작업 7종과 같은 흐름이다. 403 `REAUTHENTICATION_REQUIRED`가 오면 그 자리에서 비밀번호 모달을 띄우고, 재인증한 뒤 같은 요청을 1회 다시 보낸다.

### 대상 (사용자 결정 2026-11-12: "복구 불가 삭제 11종")

| # | 핸들러 | API | 삭제 방식(코드 근거) |
|---|---|---|---|
| 1 | `BannerController#deleteBanner` | `DELETE /admin/api/banners/{id}` | 행 삭제 + 이미지 분리(`BannerService:212-215`) |
| 2 | `PopupController#deletePopup` | `DELETE /admin/api/popups/{id}` | 행 삭제(`PopupService:240`) |
| 3 | `BoardController#deleteBoard` | `DELETE /admin/api/boards/{id}?version=` | 소프트 삭제지만 **게시판 복원 기능 없음** + 게시판 권한 행 삭제(`BoardService:288-289`) |
| 4 | `PostAttachmentController#delete` | `DELETE …/posts/{postId}/attachments/{id}` | 행 삭제 + 커밋 뒤 파일 삭제(`PostAttachmentService:155-157`) |
| 5 | `FaqController#deleteFaq` | `DELETE /admin/api/faqs/{id}` | 행 삭제 |
| 6 | `FaqCategoryController#deleteCategory` | `DELETE /admin/api/faq-categories/{id}` | 행 삭제 |
| 7 | `InquiryController#deleteInquiry` | `DELETE /admin/api/inquiries/{id}` | 행 삭제 — 방문자가 입력한 원본이라 다시 만들 수 없음 |
| 8 | `MediaController#delete` | `DELETE /admin/api/media/{id}` | 행·썸네일 삭제 + 커밋 뒤 파일 삭제(`MediaImageRemover`) |
| 9 | `MenuController#deleteMenu` | `DELETE /admin/api/menus/{id}` | 영구 삭제(`MenuService:196`) |
| 10 | `SitePageController#deletePage` | `DELETE /admin/api/site-pages/{id}?version=` | 행 + 개정 이력 삭제(`SitePageService:150-151`) |
| 11 | `SiteController#deleteSite` | `DELETE /admin/api/sites/{id}` | 행 삭제 |

### 제외(사유)

- `PostController#deletePost`: 소프트 삭제이고 휴지통에서 복원할 수 있다(#193). 가장 자주 쓰는 삭제라 매번 확인하면 불편이 크다.
- `AdminMessageController#delete`: 본인 보관함의 쪽지라 다른 사람의 데이터나 공개 콘텐츠와 관계가 없다. 상단바 공용 스크립트(`message-box.js`)라 모든 화면에 `reauth.js`가 필요해진다.
- `AdminMemberController#resetMyProfileImage`: 기존 면제를 유지한다(본인 프로필 이미지).
- **수정(PATCH/PUT)은 대상이 아니다**. 쿠키를 탈취한 사람은 여전히 본문 덮어쓰기·비노출 전환으로 콘텐츠를 훼손할 수 있다. 이번 작업은 "한 번에, 되돌릴 수 없게 지우는 것"만 막는다(수용한 한계로 기록).
- 구조·순서 반영 API(`PUT /menus/structure`, `PUT /banners/order`, `PUT /faqs/order`)는 행을 지우지 않는다(정찰: 서비스의 `delete` 호출은 위 삭제 메서드뿐).

## 2. 정찰 요약 (재사용·파급)

- 서버: `@RequireFullAuthentication`(메서드 전용) + `FullAuthenticationInterceptor`(모든 경로에 등록, 표지로 판정). 인터셉터는 보안 필터 뒤·핸들러 호출 앞에서 동작한다. 그래서 역할·권한이 부족하면 기존 `ACCESS_DENIED`가 먼저이고, 쿠키 세션은 서비스가 돌기 전에 거부된다. 존재하지 않는 ID여도 404가 아니라 403이다.
  - 콘텐츠 컨트롤러의 인가는 `@RequirePermission`(MANAGER 위임)과 `@PreAuthorize`(ADMIN 전용)이다. 인터셉터는 메서드 보안보다 **먼저** 돈다. → 권한이 없는 MANAGER의 쿠키 세션이 `ACCESS_DENIED` 대신 `REAUTHENTICATION_REQUIRED`를 받을 수 있다(아래 쟁점 4).
- `FullAuthenticationConventionTest`: ① 계정 컨트롤러 4종의 쓰기 핸들러를 전수 검사(표지 또는 사유 있는 면제) ② 표지가 붙은 핸들러 집합 = `APPROVED`.
- 화면: `reauth.js`가 `CmsReauth.fetch`를 제공한다. 진행 중 재인증 공유, 원 요청별 재시도 1회, 다른 모달 위에 떴다 닫히면 `modal-open`을 복원한다(겹친 모달 대응이 이미 있음). jQuery·Bootstrap 4를 요구하는데, 대상 화면은 모두 이미 불러온다.
- 대상 화면 10곳(삭제 호출 위치):
  `banner/manage.html:664`, `popup/manage.html:828`, `board/manage.html:683`, `board/posts.html:1618`(첨부), `faq/manage.html:882·1007`(공용 `write()` 경유), `inquiry/manage.html:377`, `media/manage.html:406`, `menu/fragments/menu-tab.html:1447`, `page/fragments/page-tab.html:596`, `site/fragments/site-tab.html:465`.
- 403 문구 처리: `banner`·`faq`·`inquiry`·`media`·`board/posts`의 오류 문구 함수는 403이면 **항상 고정 문구**("권한이 없습니다")를 쓴다. 모달을 취소하면 실제와 다른 안내가 된다. 나머지 5곳(`board/manage`·`popup`·`menu-tab`·`page-tab`·`site-tab`)은 서버 `message`를 그대로 보인다. 선례: `session/manage`·`permission/manage`는 `code === "REAUTHENTICATION_REQUIRED"`일 때만 서버 문구를 쓴다.
- 거부 문구 `ReauthenticationRequiredException.MESSAGE` = "…다시 **저장**하고 비밀번호를 입력해 주세요." — 삭제에는 어색하다. 시험·템플릿이 이 문자열을 직접 단언하지 않음을 확인했다(`grep "다시 저장하고"` 0건).

## 3. 설계 결정

### 쟁점 1 — 서버 적용 방식
- (a) **각 핸들러에 `@RequireFullAuthentication`** ← 채택
- (b) 인터셉터가 "HTTP DELETE면 전부"로 판정
- (c) 경로 패턴 등록
- **왜 (a)**: 기존 규약(표지 = 승인 목록, 컨벤션 시험이 고정)을 그대로 따른다. (b)는 휴지통으로 복원할 수 있는 게시글 삭제와 본인 쪽지까지 막아 결정(제외 3종)과 어긋나고, 예외 목록을 인터셉터에 두면 판정이 두 곳으로 갈라진다. (c)는 경로가 바뀌면 조용히 빠진다.

### 쟁점 2 — 재발 방지(새 DELETE API가 표지 없이 추가되는 것)
- (a) **컨벤션 시험에 "모든 컨트롤러의 DELETE 핸들러는 표지 또는 사유 있는 면제" 규칙 추가** ← 채택
- (b) 콘텐츠 컨트롤러 이름 목록을 `ACCOUNT_CONTROLLERS`처럼 관리
- **왜 (a)**: 이번 기준이 "삭제"이므로 HTTP 메서드로 전수 검사하면 새 도메인 컨트롤러도 자동으로 들어온다. (b)는 새 컨트롤러를 목록에 넣는 것을 잊으면 빠진다(기존 시험이 스스로 밝힌 한계와 같은 문제). 메서드 제한이 없는 매핑도 DELETE를 받으므로 DELETE로 본다.
  - 면제 목록 `DELETE_EXEMPT`(사유 포함): `PostController#deletePost`(휴지통 복원), `AdminMessageController#delete`(본인 보관함). `AdminMemberController#resetMyProfileImage`는 기존 `EXEMPT`에 있으므로 DELETE 규칙도 두 면제 목록의 합집합으로 판정한다. 낡은 면제 검사는 목록별로 한다.
  - `APPROVED`는 18개(기존 7 + 11)가 된다. 세션 만료 2종은 이미 표지가 있어 DELETE 규칙도 통과한다.

### 쟁점 3 — 화면 적용
- (a) **삭제 호출 10곳만 `CmsReauth.fetch`로 바꾸고 각 화면에 `reauth.js`를 불러온다** ← 채택
- (b) 공용 fetch 래퍼를 모든 화면에 전역으로 덮어씌운다
- **왜 (a)**: 기존 7곳과 같은 방식이고 영향 범위가 호출 단위로 좁다. (b)는 전 화면의 요청 경로를 바꾸는 큰 변경이라 과설계다.
  - FAQ는 공용 `write(url, request, …)`를 거친다. `write`에 보낼 함수 인자를 두지 않고, `request.method === "DELETE"`일 때만 `CmsReauth.fetch`를 쓴다. 저장·순서 반영 같은 다른 쓰기는 영향받지 않는다.
  - 재시도는 같은 `init`을 다시 쓴다. 대상 삭제 요청은 모두 본문이 없어 `reauth.js`의 "본문은 문자열 또는 없음" 계약을 만족한다.
  - **안내 배너(`reauth-notice`)는 넣지 않는다**. 콘텐츠 화면은 늘 쓰는 화면이라 쿠키 세션 내내 배너가 떠 있으면 소음이 된다. 기존 7종은 그 화면의 주 작업이 민감 작업이라 미리 알렸지만, 여기서는 삭제가 부수 작업이다. 모달 본문이 이유를 설명한다("로그인 상태 유지로 접속 중이라, 이 작업 전에 비밀번호를 한 번 더 확인합니다").
  - **403 문구**: 고정 문구를 쓰는 5곳(`banner`·`faq`·`inquiry`·`media`·`board/posts`)의 오류 문구 함수에 `session/manage`와 같은 분기를 넣는다(`code === "REAUTHENTICATION_REQUIRED"`면 서버 문구). 공용 도우미로 묶지 않는다. 각 화면이 자기 함수를 갖는 현재 구조를 따르며, 묶으면 무관한 리팩터링이 된다.

### 쟁점 4 — 권한 없는 MANAGER의 응답 순서
- 사실(`SecurityConfig:119-138` 확인): 인터셉터는 URL 게이트 **뒤**, 메서드 보안(`@RequirePermission`·`@RequireBoardPermission`·`@PreAuthorize`) **앞**이다. 대상별로 나누면 다음과 같다.
  - ADMIN 전용 3종(게시판 정의·사이트·메뉴): 위임 게이트 밖이라 `/admin/**` ADMIN 캐치올(URL)이 먼저 → MANAGER는 기존처럼 403 `ACCESS_DENIED`. 변화 없음.
  - 위임 기능 7종(배너·팝업·FAQ 2·문의·미디어·정적 페이지): URL 게이트는 **기능 단위 READ**만 본다. READ가 없는 MANAGER는 여전히 URL에서 `ACCESS_DENIED`. **READ는 있고 DELETE가 없는 MANAGER**의 쿠키 세션만 403 `REAUTHENTICATION_REQUIRED` → 모달 → 재인증 → 재시도 → 403 `ACCESS_DENIED` 순서가 된다.
  - 첨부 삭제(게시판 단위): 게이트는 "어느 게시판이든 READ", 핸들러 판정은 그 게시판의 **`UPDATE`**다(`PostAttachmentController:87` — 첨부 삭제는 게시글 수정). 그래서 그 게시판 READ+UPDATE면 재인증 뒤 삭제되고, READ+DELETE(UPDATE 없음)면 재인증 뒤 `ACCESS_DENIED`다. 화면의 첨부 삭제 버튼도 `UPDATE`로 표시한다(`posts.html:1176`).
  - 기존 문서의 "역할이 부족하면 기존 `ACCESS_DENIED`가 먼저"는 URL 규칙에서 막히는 경우에만 맞다 → 문서를 정정한다.
- 선택지: (a) **그대로 둔다** ← 채택 / (b) 인터셉터에서 권한 판정기를 먼저 호출
- **왜 (a)**: 위임 권한이 없는 MANAGER에게는 삭제 버튼이 보이지 않는다(화면이 권한으로 숨김). 이 경우는 직접 호출하거나 화면을 연 사이 권한이 회수된 경우뿐이다. 비밀번호를 한 번 더 묻는 것은 정보 노출이 아니고(대상 존재 여부도 드러나지 않음 — ID와 무관하게 403), 재인증 뒤에는 정확한 `ACCESS_DENIED`를 받는다. (b)는 인터셉터가 권한 카탈로그를 알게 되어 "누가/어떻게"의 직교 원칙을 깬다.

### 쟁점 5 — 거부 문구
- **"다시 저장하고" → "다시 시도하고"로 바꾼다**. 삭제 화면에서 취소하면 이 문구가 보인다. 기존 7종(저장)에도 "다시 시도"는 맞는 말이다. 단언하는 시험은 없고, 화면은 서버 문구를 그대로 보인다.

### 쟁점 6 — 겹친 모달의 포커스 복원 (v2, 리뷰 1라운드)
- 사실: Bootstrap 4는 모달마다 문서에 `focusin.bs.modal` 하나를 건다(`_enforceFocus`가 기존 것을 `off` 후 등록). 재인증 모달이 뜨면 아래 모달의 포커스 제약이 재인증 모달의 것으로 바뀌고, 재인증 모달이 닫히면 `hide()`가 그것마저 해제한다(`bootstrap.bundle.js:4816·4957`). `reauth.js`는 닫힌 뒤 `modal-open`만 복원한다(`reauth.js:108`). 이번 대상 중 문의·미디어 상세와 메뉴 영구삭제는 실패·취소 뒤에도 아래 모달이 남는다.
- 선택지: (a) **`reauth.js`의 `hidden.bs.modal`에서 남은 최상위 모달의 포커스 제약을 다시 걸고, 모달을 열기 전 포커스 요소(아래 모달 안에 있으면)로 포커스를 되돌린다** ← 채택 / (b) Bootstrap 내부 `_enforceFocus()` 호출 / (c) 그대로 둔다
- **왜 (a)**: (b)는 비공개 API(밑줄)라 벤더 갱신 때 조용히 깨질 수 있다. 같은 이벤트 이름(`focusin.bs.modal`)으로 같은 규칙을 등록하면, 아래 모달이 나중에 닫힐 때 Bootstrap의 `off`가 그대로 해제한다. 그래서 해제 시점이 Bootstrap과 같고 정리 누락이 없다. (c)는 키보드 사용자가 열린 모달 뒤의 화면을 조작하게 된다. 공용 스크립트라 기존 7종 흐름(겹친 모달이 있는 화면)도 함께 고쳐진다.
- 남은 최상위 모달은 `.modal.show` 중 마지막 요소(DOM 순서)로 고른다. 재인증 모달 자신은 이미 `show`가 빠진 뒤다.
- **복귀 대상(v3)**: 모달을 열 때 `document.activeElement`를 기억한다. `hidden` 시점에 그 요소가 문서에 남아 있고, 아래 모달 안에 있고, 포커스 가능(비활성 아님)하면 그 요소로 돌아간다. 아니면 아래 모달 루트(`tabindex="-1"` — Bootstrap이 모달을 보일 때 포커스하는 곳과 같음)로 돌아간다. 문의·미디어처럼 요청 전에 삭제 버튼을 비활성화하는 화면은 저장 시점에 버튼이 포커스를 잃었을 수 있다(리뷰 2라운드). 그래서 "삭제 버튼으로 복귀"는 보장하지 않고 **"포커스가 열린 모달 안에 머문다"**를 계약으로 한다. 복귀 대상을 `CmsReauth.fetch` 인자로 넘기는 방식은 10곳 호출 계약을 넓히는 과설계라 채택하지 않는다.

## 4. 성공 기준 (검증 가능)

1. 쿠키 복원 ADMIN 세션의 11종 삭제가 전부 403 `REAUTHENTICATION_REQUIRED`이고, 실제 행이 남는다(대표 실데이터 2종: FAQ 분류·문의).
2. 같은 회원이 폼 로그인(완전 인증)한 세션에서는 11종이 표지 때문에 막히지 않는다(존재하지 않는 ID → 404, 실데이터 → 204).
3. 쿠키 세션의 게시글 삭제(제외)는 204이고 휴지통으로 간다.
4. 첨부: 그 게시판 READ+UPDATE MANAGER의 쿠키 세션 → 403 REAUTH, 재인증(완전 인증 세션) 뒤 204. READ+DELETE(UPDATE 없음) → 403 REAUTH, 재인증 뒤 `ACCESS_DENIED`. `BANNER` READ+DELETE를 가진 MANAGER의 쿠키 세션 → 403 REAUTH. `BANNER` READ만 가진 MANAGER의 쿠키 세션 → 403 REAUTH, 재인증 뒤 → 403 `ACCESS_DENIED`. 권한이 전혀 없는 MANAGER의 쿠키 세션 → 바로 `ACCESS_DENIED`(URL 게이트). ADMIN 전용(사이트 삭제)에서 MANAGER 쿠키 세션 → 바로 `ACCESS_DENIED`(쟁점 4 순서 고정).
5. 컨벤션 시험: `APPROVED` 18개 일치, DELETE 규칙 위반 0, 반례(표지 없는 새 DELETE·메서드 제한 없는 매핑·낡은 DELETE 면제) 검출.
6. 브라우저: 쿠키 복원 세션에서 배너 삭제 → 확인창 → 모달 → 정답 → 삭제 1회·목록 갱신. 취소 → 서버 문구(고정 "권한 없음" 아님)·삭제 0회. 상세 모달 안의 삭제(문의) → 겹친 모달에서 정답 → 삭제 1회·상세 모달 닫힘. 문의 상세 모달에서 재인증을 **취소**하면 포커스가 상세 모달 안(요소 또는 모달 루트)에 있고, Tab을 여러 번 눌러도 상세 모달 밖으로 나가지 않는다(전환을 켠 상태 — `restoreModalTransitions`).
7. 실기(Playwright MCP): 10개 화면 모두 쿠키 세션 삭제에서 모달이 뜨고, 정답 뒤 삭제된다. 폼 로그인 세션에서는 모달 없이 기존과 같다.

## 5. 놓치기 쉬운 입력·실패 모드 → 고정 시험

| ID | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 쿠키 세션이 대상 삭제를 그대로 실행한다(표지 누락·오부착) | `ContentDeleteReauthenticationIntegrationTest` — 11종 × 쿠키 세션 403 REAUTH + 실데이터 2종 행 유지 / `FullAuthenticationConventionTest` `APPROVED` 일치 |
| F2 | 나중에 추가한 DELETE API가 표지 없이 들어온다 | `FullAuthenticationConventionTest` DELETE 규칙 + 반례 |
| F3 | 표지가 완전 인증 세션이나 제외 대상(게시글 삭제)까지 막아 평소 사용이 깨진다 | 통합 시험: 폼 로그인 세션 11종 404/204, 쿠키 세션 게시글 삭제 204 |
| F4 | 모달 취소 뒤 화면이 "권한이 없습니다"를 보여 원인을 오해하게 한다 | `ReauthModalBrowserTest` 배너 취소 케이스(서버 문구 표시·DELETE 1회) |
| F5 | 상세 모달 위에 뜬 재인증 모달이 입력을 못 받거나, 재시도가 중복 삭제를 보내거나, **재인증 모달이 닫힌 뒤 아래 모달의 포커스 제약이 사라져** Tab이 배경 화면으로 빠진다(Bootstrap 4 `hide()`가 문서의 `focusin.bs.modal`을 전부 해제 — `bootstrap.bundle.js:4816`) | `ReauthModalBrowserTest` 문의 상세 모달 케이스(입력 가능·원 DELETE 2회 = 거부 1 + 재시도 1·행 삭제) + 취소 뒤 `activeElement`가 상세 모달 안·Tab 10회 동안 밖으로 나가지 않음(전환 켬) |

## 6. 작업 단계

1. 브랜치 `security/content-delete-reauthentication`.
2. 시험 먼저(TDD): 컨벤션 시험 확장(DELETE 규칙·`APPROVED` 18) → 실패 확인. 통합 시험 작성 → 실패 확인.
3. 서버: 11개 핸들러에 표지를 단다. `ReauthenticationRequiredException.MESSAGE` 문구를 바꾼다. `./gradlew compileJava`.
4. 화면: `reauth.js` 겹친 모달 포커스 복원(쟁점 6 — 브라우저 시험을 먼저 써서 실패 확인) → 10곳 호출 + `reauth.js` 로드 + 5곳 403 분기(템플릿은 TDD 제외). 브라우저 시험 3건을 추가한다(배너 취소·정답, 문의 상세 모달 정답, 문의 상세 모달 취소 뒤 포커스).
5. 영향 범위 시험: `--tests 'com.cms.config.auth.*' --tests '*ConventionTest'`와 대상 도메인의 삭제 관련 기존 시험(완전 인증 세션이나 `@WithMockUser`로 동작하는지 — 표지가 기존 시험을 깨는지 확인).
6. 실기(Playwright MCP) → 문서(`com.cms.config` CLAUDE.md "민감 작업 재인증" 대상·순서 정정·한계, 루트 CLAUDE.md 보안 규칙 줄, 이 계획 결과, `plan/README.md` 인덱스).

## 7. 리스크

- **기존 시험 파급**: `@WithMockUser`는 `UsernamePasswordAuthenticationToken`이라 완전 인증으로 판정되어 영향이 없을 것으로 본다. `with(user(...))`·`TestingAuthenticationToken`을 쓰는 시험이 있으면 `isFullyAuthenticated`가 참인지 확인한다(`AuthenticationTrustResolverImpl`은 익명·remember-me 타입만 거짓). 단계 5에서 실제로 확인한다.
- **브라우저 시험 시간**: `BrowserTestSupport` 케이스가 2건 늘어난다(필수 체크 `browser-test`).
- **수용한 한계**: 수정(PATCH/PUT)을 통한 훼손, 게시글 삭제(휴지통으로 복원 가능), 권한 없는 MANAGER의 쿠키 세션은 모달을 한 번 거친 뒤 `ACCESS_DENIED`.

## 구현·검증 결과 (2026-11-12)

### Context
로드맵 69차가 남긴 후속 후보 "쿠키 세션의 콘텐츠 삭제류 재확인 확대". 범위는 정찰 뒤 사용자가 결정했다(복구할 수 없는 삭제 11종). 계획 v3은 적대적 리뷰 3라운드에서 ship 판정을 받았고 구현이 승인됐다(인증 경로 변경).

### 핵심 확정 사항
- 서버: 핸들러 11개에 `@RequireFullAuthentication`. 거부 문구 "다시 저장하고" → "다시 시도하고". URL 규칙·스키마·의존성 무변경.
- 재발 방지: `FullAuthenticationConventionTest`에 "모든 `com.cms` 컨트롤러의 DELETE 핸들러는 표지 또는 사유 있는 면제" 규칙 추가. `APPROVED` 18개.
- 화면: 삭제 호출 10곳만 `CmsReauth.fetch`. 403 고정 문구 5곳은 `REAUTHENTICATION_REQUIRED`일 때만 서버 문구. `reauth.js` 겹친 모달 포커스 복원(쟁점 6).
- **계획과 달라진 점(구현 중 보고)**: ① 컨벤션 시험이 `CustomErrorController#handleError`(메서드 제한 없는 `/error` 매핑)도 DELETE로 잡았다. 상태를 바꾸지 않는 오류 화면이라 `DELETE_EXEMPT`에 사유와 함께 넣었다. ② 브라우저 시험은 계획의 "배너 취소"를 문의 화면으로 옮겼다. 문의도 403 고정 문구 화면이고 상세 모달 안의 삭제라 F4·F5를 한 화면에서 고정한다. 배너는 이미지 픽스처가 무거워 실기에서 확인했다. ③ Tab 순환 단언은 "항상 모달 안"이 아니라 "모달 안 또는 문서 밖(`body`), 페이지의 다른 요소로는 가지 않고 문서 밖에서 모달로 돌아옴"이다. 진단 결과 재인증 없이 상세 모달만 연 **기준선**에서도 마지막 버튼 다음 Tab은 `body`(브라우저 UI)로 나갔다가 다음 Tab에 모달로 돌아왔다. Bootstrap 4의 동작이다.

### 구현 파일
- 서버: `BannerController`·`PopupController`·`BoardController`·`PostAttachmentController`·`FaqController`·`FaqCategoryController`·`InquiryController`·`MediaController`·`MenuController`·`SitePageController`·`SiteController`(표지 1줄씩), `ReauthenticationRequiredException`(문구).
- 화면: `static/js/admin/reauth.js`(`restoreUnderlyingFocus`·`returnFocus`), 템플릿 `banner/manage`·`popup/manage`·`board/manage`·`board/posts`·`faq/manage`·`inquiry/manage`·`media/manage`·`menu/manage`+`menu-tab`·`page/manage`+`page-tab`·`site/manage`+`site-tab`.
- 시험: `ContentDeleteReauthenticationIntegrationTest`(신규 7건), `ContentDeleteReauthBrowserTest`(신규 3건), `FullAuthenticationConventionTest`(규칙 1·반례 1 추가).
- 문서: `com.cms.config` CLAUDE.md "민감 작업 재인증"(대상·DELETE 규칙·순서 정정·화면·겹친 모달·시험)·"로그인 상태 유지" 한계, 루트 CLAUDE.md 보안 규칙, `plan/README.md` #59.

### 검증 결과
- TDD: 컨벤션 시험은 표지 전에 2건 실패했다(11종 + 오류 화면 매핑 검출). 통합 시험은 표지 전에 5건 실패했다(403 기대에 404·204, REAUTH 기대에 ACCESS_DENIED). 표지 뒤에는 통합 7/7·컨벤션 5/5 통과. 브라우저 시험은 화면 변경 전 3건 모두 "재인증 모달 없음"에서 실패했다. 문의 화면을 바꾼 뒤에는 포커스 시험만 "모달 안으로 복귀"에서 실패했고, `reauth.js` 수정 뒤 3/3 통과.
- 변이: 포커스 제약 재등록(`focusin.bs.modal`)을 무력화하면 포커스 시험이 7번째 Tab에서 사이드바 링크로 빠져 실패했다. 원복 확인.
- 영향 범위 시험: 비브라우저 1,536건(바꾼 도메인 9패키지 + `permission`·`config`·`*ConventionTest`) 통과. 브라우저 108건(같은 패키지 + `member`·`session`, 기존 `ReauthModalBrowserTest` 포함) 통과. 전체 `./gradlew test`(브라우저 포함, `code-review-loop` 마무리): **3,189건 통과**(실패 0·건너뜀 12 — 기존 3,177 + 이번 12). 첫 시도는 Docker Desktop이 꺼져 있어 Testcontainers 초기화 실패(`MariaDbContainerSupport` 클래스 초기화 연쇄 194건)였고, 엔진을 기동한 뒤 다시 돌려 통과했다(환경 문제).
- 코드 리뷰(`code-review-loop`): `review` 1라운드 지적 0건 → 위험 변경이라 실패 모드 F1~F5와 초점을 붙인 `adversarial-review` 1라운드 approve(지적 0건).
- 실기(bootRun dev + Playwright, 쿠키 세션 = 폼 로그인 "로그인 상태 유지" 뒤 세션 쿠키만 삭제):
  - 10개 화면 11종 모두 쿠키 세션 삭제 → 재인증 모달 → 정답 → 삭제 성공. DELETE는 각각 거부 1 + 재시도 1. 배너·팝업·미디어·문의·FAQ·FAQ 분류·첨부·메뉴·정적 페이지·사이트·게시판 정의.
  - 취소: 배너·미디어·문의·첨부·메뉴에서 서버 문구("…다시 시도하고 비밀번호를 입력해 주세요")가 보였다. 고정 "권한 없음" 문구는 나오지 않았다.
  - 겹친 모달: 미디어·문의 상세 모달과 메뉴 영구삭제 확인 모달에서 취소 뒤 포커스가 아래 모달 안에 있었다. 문의 Tab 8회는 `MMMMMBMM`(페이지 요소로 빠짐 없음).
  - 제외: 쿠키 세션의 게시글 삭제는 모달 없이 204.
  - 회귀: 폼 로그인(완전 인증) 세션의 FAQ 분류 삭제는 모달 없이 DELETE 1회로 삭제됐다.
  - 스크린샷: scratchpad `shots/reauth-delete-*.png` 13장.
  - MANAGER 경계(위임·ADMIN 전용·첨부 권한 조합)는 통합 시험으로 고정했고 브라우저 실기는 하지 않았다.

### 이슈
- **실기 중 remember-me 탈취 오탐(검증 하네스 원인, 제품 무관)**: 미디어 삭제 성공 75ms 뒤 서버가 "remember-me 쿠키 탈취 의심"으로 그 회원의 토큰·세션을 모두 지웠다. 서버 로그와 요청 계측으로 확인한 원인은 다음과 같다. 하네스가 다음 화면으로 가려고 `clearCookies()`를 불렀을 때, 목록을 다시 그리던 썸네일 요청(`/admin/api/media/108/file` — 그 뒤 401)이 아직 진행 중이었다. 그 요청은 세션 쿠키 없이 자동 로그인을 시도했고, `goto`도 같은 토큰으로 동시에 자동 로그인해 두 복원이 겹쳤다. 이는 이미 수용된 한계("여러 탭 동시 복원의 오탐 탈취")와 같은 현상이다. 같은 화면 흐름을 하네스 개입 없이 두 번(정답만 / 취소→Tab→정답) 계측 재현했는데 두 번 모두 오탐이 없었다. 재인증 응답이 새 `JSESSIONID`를 내려주고 재시도·재조회가 모두 새 세션으로 나갔다. 하네스는 쿠키를 바꾸기 전에 `networkidle`을 기다리도록 고쳤다.

### 후속
- 콘텐츠 **수정**(PATCH/PUT)의 재확인 여부. 이번에는 범위 밖으로 수용했다. 덮어쓰기 훼손은 여전히 가능하다.
- 게시글 휴지통의 영구 삭제가 생기면 그 API는 이 규칙상 표지가 필요하다(컨벤션 시험이 강제한다).

## 개정 이력

- v1 (2026-11-12): 최초 작성.
- v2 변경 (리뷰 1라운드, codex): ① [중간·수용] 겹친 모달에서 재인증 모달이 닫히면 아래 모달의 포커스 제약이 사라진다 — 쟁점 6 추가(`reauth.js` `hidden`에서 포커스 제약 재등록·포커스 복귀), F5·성공 기준 6에 취소 뒤 포커스·Tab 순환 시험(전환 켬) 추가. ② [낮음·수용] 첨부 삭제는 `UPDATE` 판정이라 쟁점 4의 순서 서술이 맞지 않음 — 첨부를 따로 서술하고 READ+UPDATE(재인증 뒤 204)·READ+DELETE(재인증 뒤 `ACCESS_DENIED`) 조합을 성공 기준 4에 추가.
- v3 변경 (리뷰 2라운드, codex): [중간·일부 수용] 저장 시점에 삭제 버튼이 이미 비활성(문의·미디어)이라 "삭제 버튼으로 복귀"를 보장할 수 없음 — 복귀 대상을 "저장 요소가 아래 모달 안에서 포커스 가능하면 그것, 아니면 아래 모달 루트"로 명시하고, 성공 기준·F5를 "포커스가 상세 모달 안에 머문다"로 정정. 기각: 복귀 대상을 `CmsReauth.fetch`에 전달·화면별 재활성화 순서 조정 — 10곳 호출 계약을 넓히는 과설계이며, 핵심 결함(포커스가 열린 모달 밖으로 빠짐)은 모달 내부 보장으로 해소된다.
