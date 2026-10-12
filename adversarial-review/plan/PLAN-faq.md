# PLAN — FAQ 관리 (로드맵 Top 8 ⑥)

> 상태: v6 — 적대적 리뷰 6라운드 **ship**, 구현·시험·실기 검증 완료(코드 리뷰·커밋·PR 대기). codex `gpt-6.1-sol`; 1라운드 P2×3·P3×1, 2~4라운드 각 P2×2 전부 수용, 5라운드 P2×1 기각 + 안내 문구 부분 수용. 스키마(V41·V42)와 인가 정책 변경(`/faq` GET·HEAD 무인증)은 2026-10-12 사용자 승인.

## 개정 이력

- 6라운드: **ship — 새 실질 결함 없음**(P1·P2 없음. codex `gpt-6.1-sol`이 저장소의 가드 잠금 패턴·감사 Aspect·sanitizer·공개 예외 처리·권한 카탈로그·최신 마이그레이션(V40)을 대조).

- v6 변경(5라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×1, **기각 + 안내 문구 부분 수용**, 결정 필요 0건):
  - R5-1(P2 쓰기 응답 유실 뒤 [다시 불러오기]가 성공해도 원래 쓰기가 가드 대기·커밋 전이면 낡은 트리를 정상으로 돌려줘 화면이 동기화됐다고 오판 — 뒤늦게 커밋되면 누락·중복): **기각 — 멱등 키·완료 결과 조회 계약은 과설계**. 이유: ① 이 프로젝트의 어떤 관리 CRUD(배너·팝업·정적 페이지·게시글)에도 없는 프로토콜이고 응답 유실 뒤 늦은 커밋은 모든 관리 화면에 공통이라 FAQ만 따로 풀 문제가 아니다("요청받지 않은 추상화 금지") ② 전제가 좁다 — `fetch`에 기본 타임아웃이 없어 "응답 유실"은 사실상 네트워크 단절·서버 재시작 때뿐 ③ 최악이 "중복 FAQ 1건"이고 관리 화면 목록에서 바로 삭제할 수 있어 데이터 손상이 없다. **부분 수용**: 결과 불명 상태(`syncFailed` 중 쓰기 응답이 유실된 경우)의 안내를 "저장 여부를 확인할 수 없습니다. 목록을 다시 불러온 뒤 **반영되었는지 직접 확인**하세요"로 쓰고(재조회 성공을 반영 확인으로 오해하게 하지 않는다), 폼 내용은 보존하며 자동 재전송하지 않는다(R4-1 그대로). 수용한 한계에 기록.

- v5 변경(4라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×2, 전부 수용, 결정 필요 0건):
  - R4-1(P2 쓰기 201 뒤 목록 재조회만 실패하면 복구 상태가 없음 — 성공까지 `busy`면 새로고침도 잠기고, `finally`로 풀면 낡은 트리·version으로 재개하며, 등록을 재시도하면 중복 생성): 쟁점 13 변경 — **쓰기 결과와 목록 동기화 결과를 분리**한다. 쓰기가 성공(2xx)한 뒤 재조회가 실패하면 "저장은 완료됐지만 목록을 불러오지 못했습니다" 상태(`syncFailed`)로 들어가 **[다시 불러오기]만 활성**(쓰기·드래그·▲▼는 낡은 트리라 잠금 유지)이고 쓰기를 **자동 재전송하지 않는다**. 쓰기 응답 자체가 유실(네트워크 오류·타임아웃)되면 결과를 알 수 없으므로 같은 `syncFailed` 상태로 보내 **재조회로 확인하게 하고 폼의 재전송을 허용하지 않는다**(중복 생성 방지). 시험: `POST 201 → GET 실패 → GET 재시도`에서 생성 1건·화면 복구(브라우저 시험 1건 + API 시험은 서버 계약이라 해당 없음).
  - R4-2(P2 `busy`가 작업 버튼만 잠가 폼 입력·Quill 편집기의 변경을 보호하지 않음 — 저장 중 추가한 입력이 성공 처리의 폼 초기화로 사라지거나 늦은 상세 응답이 덮음): 쟁점 13 변경 — **저장 중·상세 로딩 중에는 폼 전체(질문·카테고리 선택·노출·이름)와 편집기를 통째로 잠근다**(`editor.quill.enable(false)`, 일반 입력 `disabled` — 팝업 화면 선례). 따라서 응답이 도착해 폼을 초기화·채우는 동안 사용자 입력이 존재할 수 없다. 시험: 저장 응답·상세 응답을 각각 지연시켜 대기 중 입력이 막혀 있고 응답 처리 뒤 유실이 없음(`FaqAdminOrderBrowserTest`와 같은 브라우저 시험 클래스에 포함).

- v4 변경(3라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×2, 전부 수용, 결정 필요 0건):
  - R3-1(P2 "초안 중 다른 작업 잠금"만으로는 반대 방향 경합을 못 막음 — [반영] 응답 대기 중 다시 드래그하면 성공 응답의 트리 교체가 새 초안을 지우고, 늦은 목록 GET이 초안을 덮음. 배너 화면은 `saving` 중 이동 잠금 + 요청 세대 증가로 처리): 쟁점 13 변경 — 쓰기 요청(반영 포함)을 보낸 시점부터 **후속 목록 갱신 완료까지** 드래그·▲▼·[반영]·[취소]·다른 저장을 잠그고(`busy`) **이벤트 핸들러 안에서도** 같은 플래그를 검사한다(버튼 `disabled`만 믿지 않음 — `dragstart`도 `busy`면 취소). 목록 GET은 요청 세대(`loadSeq`)를 갖고, 초안이 시작될 때(첫 이동) 세대를 올려 진행 중인 이전 응답을 폐기한다. 응답을 의도적으로 지연시킨 브라우저 시험 2건(반영 응답 대기 중 드래그 시도 → 무시, 목록 GET 지연 중 드래그 → 늦은 응답이 초안을 덮지 않음)을 `FaqAdminOrderBrowserTest`에 추가한다.
  - R3-2(P2 감사 Aspect는 서비스 반환 객체의 최상위 숫자 getter만 호출 — `{version, categories}` 트리만 반환하면 `targetId`가 조용히 `null`, `201 Location`도 구성 불가): 쟁점 5·12 변경 — **등록 API는 트리가 아니라 생성된 항목 한 건을 반환**한다(`FaqResponse`/`FaqCategoryResponse`, 최상위 `getId()`) — 화면은 등록 뒤 `GET /admin/api/faqs`로 트리를 다시 읽는다(응답 크기·서비스 책임이 줄고 감사·`Location`이 자연스럽다). 순서 반영만 새 트리를 반환한다(`targetId` 없는 감사). 앞서 v2의 "등록 응답의 새 트리도 같은 쓰기 트랜잭션에서 만든다"는 문장은 철회한다. 시험: **생성 ID = `Location`의 ID = SUCCESS 감사 행 `targetId`** 단언(FAQ·카테고리 각각, 실패 행은 `targetId` 없음).

- v3 변경(2라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×2, 전부 수용, 결정 필요 0건):
  - R2-1(P2 삭제·이동 뒤 `ord` 연속성 규칙 없음 — 배너는 삭제 후 압축하지 않아 `[0,2]`가 남고, 화면이 그대로 재반영하면 서버가 `[0,1]`로 고치며 버전을 올려 "같은 순서 재저장 = 쓰기·버전 없음"과 충돌): 쟁점 1·4 변경 — **삭제·카테고리 이동 트랜잭션이 영향받는 부모 목록을 즉시 `0..n-1`로 재번호**(값이 다른 행만 쓴다)하고, **이동한 FAQ는 대상 카테고리 목록의 맨 끝에 넣는다**(등록과 같은 규칙). 순서 반영은 이 불변식("각 부모의 `ord`는 항상 정확히 0..n-1")을 전제로 값 비교를 한다. 배너를 따르지 않는 이유를 문서에 남긴다. 시험: 중간 삭제·이동 직후 연속 순번, 그대로 재반영 시 버전 불변.
  - R2-2(P2 검색 입력만 NFC로 정규화하고 `textContent`를 쓰면 분해형 한글 답변이 안 맞고, `<p>배송</p><p>안내</p>`가 `배송안내`가 되어 `배송 안내` 검색에서 누락 — sanitizer가 `prettyPrint(false)`라 직렬화 줄바꿈에도 기댈 수 없음): 쟁점 9 변경 — 검색 대상 텍스트를 **로드 시 한 번 추출하는 규칙**을 정한다: DOM을 순회해 텍스트 노드는 그대로 잇고, **블록 경계(`p`·`li`·`ul`·`ol`·`h2`·`h3`·`blockquote`)의 시작·끝과 `br`에서 공백 하나를 넣은 뒤**, 질문·답변 모두 NFC 정규화·소문자화·연속 공백(`\s+`) 한 칸 압축을 적용한다(인라인 요소 `strong`·`em`·`u`·`s`·`a`는 구분자를 넣지 않는다 — `<strong>배송</strong>안내`는 `배송안내`). 입력도 같은 정규화(NFC·소문자·공백 압축·trim). 시험: 분해형 한글(NFD) 답변, 문단·`br` 경계, 인라인 요소 경계.

- v2 변경(1라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×3·P3×1, 전부 수용, 결정 필요 0건):
  - R1-1(P2 `<p></p>`는 sanitizer가 `<p><br></p>`로 보충해 문자열 `isBlank`로는 빈 답변을 못 잡음 — 팝업 선례 `PublicPopup.from`의 `body.isBlank()` 한계): 쟁점 7·8 변경 — 공개 서비스가 출력 시 재정리 결과를 `SanitizedHtml.blank()`로 판정해 항목을 건너뛰고, **건너뛴 뒤** 항목이 0개가 된 카테고리를 제거한다. 시험 추가(빈 문단 답변·마지막 항목이 빈 답변인 카테고리).
  - R1-2(P2 쓰기만 가드로 직렬화되고 관리자 `GET`은 트리와 `version`을 따로 읽어 옛 순서 + 새 version이 나올 수 있음 — 반영 요청이 409를 우회): 쟁점 3 변경 — `GET /admin/api/faqs`는 **단일 `@Transactional(readOnly = true)`(REPEATABLE READ 스냅샷)에서 `faq_lock.version`을 포함해 한 번에 읽는다**(권한 매트릭스 조회와 같은 방식, 잠금 없는 일반 SELECT). 순서 반영 응답의 새 트리는 같은 쓰기 트랜잭션 안에서 만든다(등록 응답은 트리가 아니라 생성된 항목 — v4 R3-2). 쟁점 15에 조회 도중 순서 반영이 커밋되는 경합 시험 추가.
  - R1-3(P2 `/faq/**` denyAll이면 `/faq/x`는 MVC에 닿지 않아 404가 아니라 무인증 302·인증 403): 쟁점 15 변경 — 기대값을 정정(무인증 302 로그인, 인증된 관리자 403).
  - R1-4(P3 no-op이 버전을 올리지 않는 쟁점 4와 "같은 version 두 요청 중 정확히 하나 성공"이 모순): 쟁점 15 변경 — 하나 성공·하나 409 단언은 **두 요청 모두 최초 상태를 실제로 바꾸는 경우**로 한정하고, no-op끼리(둘 다 200, 버전 불변)와 no-op·변경 경합은 별도 기대값으로 시험한다.
- v1: 최초 작성(정찰 → 쟁점 15개 결정). 로드맵 "⑥ FAQ 관리"의 확정 결정(2026-10-07)을 그대로 따른다 — 답변은 ⓪ HTML 편집기, 카테고리는 별도 테이블(화면에서 추가·정렬·비노출), 공개 검색은 서버 검색 없이 화면 내 즉시 필터(JS), 정렬은 드래그 + [반영], 동시 편집은 비관적 락 + 버전 검사로 409, 메뉴 구조 반영 코드는 공유하지 않는다.

## Context

관리자가 FAQ 카테고리와 질문/답변을 등록하고 드래그로 순서를 바꾼다. 방문자는 공개 `/faq`에서 카테고리별 아코디언으로 보고, 입력한 단어로 목록을 바로 걸러 본다. 선행 ⓪(HTML 편집기·sanitizer)은 끝났다.

### 정찰 요약 (읽은 근거)

| 읽은 곳 | 알게 된 것 |
|---|---|
| `admin/banner/*` + `CLAUDE.md` | 기능 단위 위임(`DELEGABLE`) 선례. 가드 행(`banner_lock`)을 `FOR UPDATE`로 **첫 DB 조회**로 잠가 개수 상한·`ord` 계산을 직렬화, 비잠금 읽기는 가드 **뒤**에. 순서 저장은 `PUT /order`가 전체 ID를 받아 집합이 다르면 409, 값이 같은 행은 쓰지 않음. 화면은 ▲▼ + [순서 저장]. |
| `admin/menu` (`applyStructure`) | 드래그 초안 + [반영]. 행별 base 값 비교로 낙관적 검사. **FAQ는 이 코드를 공유하지 않는다**(로드맵 명시). 메뉴 화면은 jstree(CDN)이며 트리 위젯이라 FAQ에 맞지 않는다. |
| `admin/page/service/SitePageService` | `version`(모든 변경 +1)과 잠금 아래 수동 비교 → 낡은 화면 409. 본문 이미지 불허(`sanitizePage`) + 출력 시 재정리 선례. |
| `admin/popup/*` | 가장 최근 `DELEGABLE` 파급 목록(`AdminSidebarAdviceSnapshotTest`·`AdminPermissionEvaluatorTest`·`MenuExposureSidebarIntegrationTest`), 감사 `safeErrorMessage`, 롤백 후 권한 부활 문서(`docs/deployment.md`), `ContentBodyPolicy` 전용 상한 패턴. |
| `admin/permission/AdminFeature` + `CLAUDE.md` | 새 기능은 `AdminFeature` 한 항목 추가로 `SecurityConfig`의 `DELEGABLE` 루프가 게이트를 만든다(게이트 쪽 코드 변경 없음). 컨벤션 테스트가 핸들러마다 인가 선언 1개를 강제. |
| `config/SecurityConfig` | 공개 경로는 정확 경로별 GET/HEAD permit + 나머지 denyAll, 끝은 `anyRequest().denyAll()`. `/faq`는 아직 규칙이 없어 **지금은 기본 거부**다. |
| `application.yml` `cms.rate-limit.rules` | 공개 경로마다 규칙 1개(`public-page` 120/60초 등). |
| `publicweb/page/*` + `templates/public/page/detail.html` | 공개 컨트롤러는 예외를 던지지 않고 404를 흡수, 서비스가 공개 불변식을 쿼리에 고정, 본문 `th:utext`는 한 곳만 허용하며 `PublicPageTemplateConventionTest`가 잠근다. |
| `db/migration` | 최신 V40. DDL(V33·V37·V39)과 시드 DML(V34·V38·V40)을 분리하는 관례. 메뉴 시드는 `WHERE NOT EXISTS` 멱등. |
| `logs/AdminActionTypes` + `admin/log/manage.html` | 상수와 화면 라벨을 동기화 시험이 고정. |

## 핵심 쟁점과 결정

### 쟁점 1 — 도메인 구조: 카테고리 + FAQ 두 테이블

- 선택지: (a) 카테고리 컬럼만 둔 단일 테이블 (b) 카테고리·FAQ 두 테이블 (c) 게시판 재사용.
- **결정 (b)**. 로드맵이 "카테고리는 별도 테이블(화면에서 추가·정렬·비노출)"로 확정했다. (c)는 게시판이 카테고리·수동 정렬·아코디언을 지원하지 않아 로드맵이 전용 도메인을 택한 이유 그대로 기각.
- `faq_category(id, name, ord, use_yn, create_date, update_date)`, `faq(id, category_id FK RESTRICT, question, answer_html MEDIUMTEXT, ord, use_yn, create_date, update_date)`. **하드 삭제**(참조하는 곳이 없다). 날짜 컬럼 `datetime(6)`, 시각은 주입된 KST `Clock`.
- `ord`는 같은 부모(카테고리는 전체, FAQ는 카테고리) 안의 0..n-1. 공개·관리 조회 정렬은 `(ord, id)`. **불변식: 각 부모의 `ord`는 항상 정확히 0..n-1이다**(R2-1) — 등록은 맨 끝(n), **삭제·카테고리 이동은 영향받는 부모 목록을 같은 트랜잭션에서 즉시 재번호**(값이 다른 행만 쓴다), 이동한 FAQ는 대상 목록의 맨 끝에 들어간다. 배너는 삭제 후 압축하지 않지만(순서 저장이 한 번에 `0..n-1`로 고친다) FAQ는 "같은 순서 재저장 = 쓰기·버전 없음"(쟁점 4)이 값 비교에 기대므로 연속성을 항상 유지한다.

### 쟁점 2 — 권한: 새 `DELEGABLE` 기능 `FAQ` 하나

- 선택지: (a) 카테고리와 FAQ를 별도 기능 둘 (b) 기능 하나 (c) ADMIN 전용.
- **결정 (b)**. 같은 화면·같은 위임 주체라 권한을 쪼갤 이유가 없다(팝업·배너도 화면당 기능 1개). 동작 매핑: 목록·상세 = READ, 카테고리·FAQ 등록 = CREATE, 수정·**순서 반영** = UPDATE, 삭제 = DELETE. 쓰기는 READ 의존(기존 판정기 그대로).
- 게이트: `/admin/faq/manage`, `/admin/api/faqs`, `/admin/api/faqs/**`, `/admin/api/faq-categories`, `/admin/api/faq-categories/**`(컬렉션과 하위를 함께 적는 기존 관례). `menuUrls`는 `/admin/faq/manage`.
- 파급(팝업 때 확인된 목록): `AdminSidebarAdviceSnapshotTest`·`AdminPermissionEvaluatorTest`(ADMIN 전 동작 키)·`MenuExposureSidebarIntegrationTest`(시드는 카탈로그에서 생성). **롤백 후 권한 부활** 문서 항목 추가(`docs/deployment.md`).

### 쟁점 3 — 동시성: 가드 행 `faq_lock` 하나가 모든 쓰기를 직렬화 (+ `version`)

- 선택지: (a) 메뉴식 전 행 `FOR UPDATE` + 행별 base 비교 (b) 배너식 가드 행 + 집합 비교 (c) 가드 행에 `version` 두고 모든 쓰기를 가드로 직렬화.
- **결정 (c)**. (a)는 요청 모양이 커지고 두 테이블 전 행 잠금은 과하다. (b)의 집합 비교는 같은 집합의 **순서만** 바꾼 다른 관리자 저장을 못 잡는다(마지막 저장이 조용히 이긴다) — 로드맵의 "버전 검사 409"를 충족하지 못한다. (c)는 `faq_lock(id=1, version bigint)`을 **모든 쓰기 서비스 메서드의 첫 DB 조회**로 `FOR UPDATE` 한 뒤(비잠금 읽기는 그 뒤) 동작한다. **조회(`GET /admin/api/faqs`)는 단일 `readOnly` 트랜잭션(REPEATABLE READ 스냅샷)에서 `version`과 트리를 함께 읽는다**(R1-2) — 쓰기만 직렬화해서는 옛 순서 + 새 version 조합을 막지 못한다. 규칙이 하나(가드 → 끝)라 교착 추론이 필요 없다. 관리자 소수의 저빈도 쓰기라 직렬화 비용은 무시할 수 있다.
- **`version`은 구조가 바뀔 때만 +1**: 카테고리·FAQ 등록·삭제, 순서 반영, FAQ의 카테고리 이동. 이름·질문·답변·노출 여부만 바뀌는 수정은 올리지 않는다(진행 중인 순서 초안을 불필요하게 무효화하지 않기 위해).
- 가드 행이 없으면 `IllegalStateException`(조용히 잠금 없이 진행하지 않는다). V42가 시드한다.
- 단건 수정은 가드를 잡으므로 행 락·`@Version`이 필요 없다(가드 아래에서는 서로 직렬화). 그러나 낡은 폼이 방금 바뀐 노출 여부를 덮는 stale-form 한계는 배너·메뉴와 같이 수용한다.

### 쟁점 4 — 순서 반영 API: `PUT /admin/api/faqs/order`

- 요청 `{version, categories:[{id, faqIds:[…]}…]}` — 카테고리 **전체 순서**와 각 카테고리 안의 **FAQ 전체 순서**를 한 번에 보낸다. 응답은 새 트리(새 `version` 포함).
- 검사 순서: 형식(null·음수·중복 → 400) → 가드 잠금 → `version` 불일치 **409**(낡은 화면 — 재조회) → 요청 집합이 현재와 다르면 **400**(version이 같은데 집합이 다르면 클라이언트 오류. 카테고리 소속 변경도 400) → 값이 다른 행만 `ord = index`로 갱신, **실제로 바뀐 것이 있을 때만** `version` +1(같은 순서 재저장은 쓰기·버전 없음).
- **드래그는 같은 카테고리 안 FAQ 순서와 카테고리 순서만** 다룬다. 다른 카테고리로 옮기기는 FAQ 수정 폼의 카테고리 선택으로 한다(드래그 UI·서버 규칙이 모두 단순해진다). `PUT /order`와 `PUT /{id}`가 같은 컨트롤러에 있으므로 배너 선례대로 리터럴이 우선함을 시험으로 고정한다.

### 쟁점 5 — API 경로 (api-conventions)

명사·복수·하이픈: `GET /admin/api/faqs`(트리 + version, **답변 본문 제외**), `GET /admin/api/faqs/{id}`(답변 포함), `POST /admin/api/faqs`(**생성된 FAQ 한 건 반환**, 201 + `Location`), `PUT /admin/api/faqs/{id}`(전체 교체), `DELETE /admin/api/faqs/{id}`, `PUT /admin/api/faqs/order`, `POST /admin/api/faq-categories`, `PUT /admin/api/faq-categories/{id}`(전체 교체: 이름·노출), `DELETE /admin/api/faq-categories/{id}`. 상태 코드: 등록 201, 삭제 204, 검증 400, 없음 404, 상한·낡은 화면·자식 있는 카테고리 삭제 409. 호출하는 화면은 새 `admin/faq/manage.html` 하나뿐이라 기존 JS 영향 없음.

### 쟁점 6 — 상한

- 카테고리 **30**, FAQ **200**(초과 409). 가드로 직렬화되므로 **정확한 상한**이다(팝업의 소프트 상한과 다르다). 근거: 공개 `/faq`가 노출 중 전부를 한 페이지에 그리고(JS 필터가 DOM 전체를 걸러야 한다) 서버 검색·페이지네이션이 없으므로 총량을 묶는다.
- 입력 길이: 카테고리 이름 ≤ 50자, 질문 ≤ 300자(`@Size` — 코드포인트가 아니라 UTF-16 길이 기준이면 이모지 경계는 기존 DTO 관례를 따른다).

### 쟁점 7 — 답변 본문: HTML, 이미지 불허, 전용 상한, 저장·출력 양쪽 정리

- 선택지: (a) 게시글 정책(이미지 허용) 재사용 (b) 정적 페이지·팝업처럼 이미지 불허 + 전용 상한.
- **결정 (b)**. 이미지를 허용하면 본문 이미지 공개 판정(`com.cms.admin.contentimage`)이 "공개 게시글이 참조"하는 참조 추적에 FAQ를 추가해야 하고 업로드 진입점·권한·회수 경로가 늘어난다 — 정적 페이지·팝업이 같은 이유로 불허했다. 로드맵의 FAQ 요구에 이미지가 없다.
- `ContentBodyPolicy.sanitizeFaq`/`sanitizeForFaqOutput` 추가: 보이는 글자 **5,000자**(+문단 종료 1자), 정리 후 **30,000바이트**, 요청 원문 **60,000바이트**(`@MaxUtf8Bytes`로 파싱 전에 거름). 공개 최악 크기 ≈ 200 × 30KB = 6MB(수용 — 실제 FAQ는 이보다 훨씬 작고 상한은 방어용). 편집기는 `NoticeEditor.create(…, {allowImages:false})`, 툴바·`formats`·sanitizer 허용 목록 1:1 유지.
- 출력 시 재정리 결과가 `SanitizedHtml.blank()`이면 그 FAQ는 공개 화면에서 건너뛴다(R1-1 — 문자열 `isBlank`는 `<p><br></p>`를 못 잡는다, 팝업 선례의 한계). 질문은 평문이라 `th:text`.

### 쟁점 8 — 공개 `/faq`: 서버 렌더링, 노출 불변식은 쿼리에 고정

- `FAQ`는 노출 중인 **카테고리 ∧ FAQ**만 보인다. 카테고리를 숨기면 그 안의 FAQ가 모두 숨는다. 노출 FAQ가 하나도 없는 카테고리는 그리지 않는다 — 판정은 **본문이 빈 항목을 건너뛴 뒤**에 한다(R1-1). 불변식은 `PublicFaqService`가 호출하는 리포지토리 쿼리(`c.useYn = true AND f.useYn = true`, `(c.ord, c.id, f.ord, f.id)` 정렬)에 고정한다 — 관리 서비스와 별도 클래스(공개 불변식이 선택적 필터가 되지 않게).
- 컨트롤러: `@Controller`·`@AdminPage` 없음·`GET /faq`만. 조회 실패(예외)는 공개 메인 푸터와 달리 **fail-soft가 아니라 500**(페이지 자체가 본문이므로) — 단 공개 예외 advice가 이미 HTML 오류 페이지로 처리한다. `publicweb/faq/`에 컨트롤러·서비스·뷰 DTO(`PublicFaqCategory`·`PublicFaqItem`). `@Transactional(readOnly = true)`(OSIV 꺼짐 — 전송 중 커넥션 점유 없음).
- 템플릿 `public/faq/list.html`: 카테고리마다 `<section>` + 항목마다 `<details><summary th:text>질문</summary><div th:utext>답변</div></details>`. **스크립트가 없어도 모든 항목이 열고 닫히고 보인다**(점진적 향상). 이스케이프 없는 출력은 답변 한 곳만 — `PublicFaqTemplateConventionTest`가 고정(`utext` 정확히 1개, 인라인 `<script>`·`on*=` 없음, 외부 스크립트는 `th:src` 한 개).

### 쟁점 9 — 즉시 필터(JS)

- `static/js/public/faq.js`(외부 파일, 인라인 금지 — 공개 메인 팝업 스크립트와 같은 관례). 검색 입력은 서버가 `hidden`으로 내보내고 스크립트가 실행되면 보인다(스크립트가 꺼지면 동작하지 않는 입력란이 보이지 않는다).
- **검색용 텍스트 추출(R2-2)**: 로드 시 항목마다 한 번 만든다. DOM을 순회해 텍스트 노드를 잇되 **블록 경계(`p`·`li`·`ul`·`ol`·`h2`·`h3`·`blockquote`)의 시작·끝과 `br`에서 공백 하나**를 넣고(인라인 `strong`·`em`·`u`·`s`·`a`는 구분자 없음 — `<strong>배송</strong>안내` = `배송안내`), 질문·답변 모두 NFC 정규화·소문자화·`\s+` 한 칸 압축. 입력도 같은 정규화(NFC·소문자·공백 압축·trim)를 한다.
- 동작: 정규화한 입력이 **질문 또는 답변의 검색용 텍스트에 부분 문자열로 포함**되는 항목만 보이고, 보이는 항목이 없는 카테고리는 숨기며, 아무것도 없으면 "일치하는 질문이 없습니다" 문구를 보인다. 빈 입력 = 전부 표시. `input` 이벤트(IME 조합 중 포함)로 즉시 반영. 항목 텍스트는 로드 시 한 번 캐시한다. 일치 항목을 자동으로 펼치지는 않는다(`details`의 사용자 상태를 건드리지 않는다).
- 서버는 검색 파라미터를 받지 않는다(`?q=`는 무시 — 로드맵 확정).

### 쟁점 10 — 인가 정책 변경 (사전 승인 필요)

`SecurityConfig`에 `/faq` **정확 경로 1개**의 GET/HEAD 무인증 공개 + `/faq`, `/faq/**` 그 외 메서드·하위 경로 `denyAll` 추가(공개 정적 페이지와 같은 모양). 하위 경로는 라우트가 없다. `SecurityConfigTest`에 `/faq` 판을 추가한다. 이는 CLAUDE.md "인가 정책은 사전 협의 없이 변경하지 않는다"의 대상이라 **구현 전 별도 승인**을 받는다. 관리 쪽 `SecurityConfig` 변경은 없다(`DELEGABLE` 루프).

### 쟁점 11 — 레이트리밋

`application.yml`에 규칙 `public-faq`(`/faq`, GET·HEAD, 120/60초, 공개 정적 페이지와 같은 값 — 본문 외 자원을 내려주지 않는다) 추가. 규칙 평가 순서는 경로가 겹치지 않아 무관. 시험은 `PublicSitePageRateLimitIntegrationTest` 선례.

### 쟁점 12 — 감사

`FAQ_CREATE`·`FAQ_UPDATE`·`FAQ_DELETE`(`targetType=FAQ`, `targetId`=FAQ ID), `FAQ_CATEGORY_CREATE`·`FAQ_CATEGORY_UPDATE`·`FAQ_CATEGORY_DELETE`(`targetType=FAQ_CATEGORY`), `FAQ_ORDER`(`targetId` 없음). `targetId`는 Aspect가 **서비스 반환 객체의 최상위 `getId()`**로 뽑으므로 등록·수정·삭제 서비스는 최상위 `Long getId()`를 가진 결과를 돌려준다(삭제는 삭제 전에 만든 스냅샷 — 메뉴·공지 선례). 질문·이름·답변은 사용자 입력이라 `targetLabel` 없음, 실패 행 `errorMessage`는 고정 문구(`safeErrorMessage`, 팝업 선례). `AdminActionTypes`와 `admin/log/manage.html` 라벨 동기화.

### 쟁점 13 — 관리 화면

- `templates/admin/faq/manage.html` + `@AdminPage` 컨트롤러 `FaqPageController`(`/admin/faq/manage`, GET). 사이드바 메뉴 시드 V42(최상위 맨 끝, 아이콘 `fas fa-fw fa-question-circle`).
- 구성: 왼쪽/위 카테고리 카드 목록(카드 안에 그 카테고리의 FAQ 행), [카테고리 추가]·[FAQ 추가], 카드 머리에 이름 수정·노출 토글·삭제, 행에 수정·삭제. 카테고리 삭제는 FAQ가 있으면 서버 409(메시지로 안내). 수정은 상세 API를 먼저 불러온다(요청 세대로 늦은 응답 폐기). 모든 사용자 입력 문자열은 `textContent`로만 삽입.
- **드래그**: 새 라이브러리 없이 **HTML5 네이티브 드래그앤드롭**(카테고리 카드끼리, 같은 카테고리의 FAQ 행끼리 — 드래그 가능 범위를 `dragstart`에서 종류(`data-kind`)·부모로 제한하고 `dragover`가 같은 종류·같은 부모일 때만 `preventDefault`). 선택지 중 jstree(트리 위젯·스타일 대량 오버라이드)와 SortableJS(새 의존성 — "검증되지 않은 새 라이브러리는 먼저 제안" 규칙의 대상)는 기각. 한계: 네이티브 DnD는 터치 기기에서 동작하지 않는다 — 관리 화면은 데스크톱 전제이며(메뉴 구조 편집도 마찬가지) 배너처럼 **▲▼ 버튼도 함께 둔다**(키보드 접근 + 같은 초안 로직을 쓰는 몇 줄이라 비용이 작고 DnD가 막힌 환경의 대안이 된다 — 승인 단계에서 제거 가능한 선택으로 표시).
- **초안 + [반영]**: 드래그·▲▼는 서버를 호출하지 않고 화면 초안만 바꾼다. [반영]이 `PUT /order`를 보낸다, [취소]는 서버 상태로 되돌린다. 초안이 있는 동안 다른 저장·삭제·추가·새로고침은 잠기고 `beforeunload`가 경고. **상호 배제(R3-1)**: 쓰기 요청 시작부터 후속 목록 갱신 완료까지 `busy` — 이 동안 드래그·▲▼·[반영]·[취소]·저장을 모두 잠그고 핸들러 안에서도 `busy`를 검사한다. 목록 GET은 요청 세대를 갖고 첫 이동으로 초안이 시작되면 세대를 올려 진행 중인 이전 응답을 폐기한다. **저장·상세 로딩 중에는 폼 전체와 Quill 편집기를 통째로 잠근다**(R4-2). **쓰기 성공 후 재조회 실패·쓰기 응답 유실은 `syncFailed` 상태**로 보내 [다시 불러오기]만 허용하고 쓰기를 자동 재전송하지 않는다(R4-1 — 중복 생성 방지). 409(낡은 화면)이면 서버 메시지 + 초안 폐기 + 최신 재조회, 400이면 초안 유지. 권한이 없으면 버튼을 숨기고(`myPermissions` `"FAQ:CREATE"` 등) 서버 403이 최종이며 고정 한국어 문구를 쓴다(메서드 보안 403 메시지가 영문).
- 화면 안내: 비노출 카테고리 아래의 FAQ는 공개되지 않음을 표시한다(배지).

### 쟁점 14 — 마이그레이션 (DDL / DML 분리)

- `V41__create_faq.sql`: `faq_category`·`faq`·`faq_lock` 3 DDL(문마다 암묵 커밋 — 실패 복구 문서 `docs/migration-guide.md`에 V41~V42 항목). 인덱스 `idx_faq_category_use_ord (use_yn, ord, id)`, `idx_faq_category_ord (category_id, ord, id)`. FK `fk_faq_category` RESTRICT.
- `V42__seed_faq_menu.sql`: `faq_lock` 가드 행(id=1, version=0) 멱등 + 메뉴 시드 멱등(`WHERE NOT EXISTS`, `LEAST`로 INT 상한 방어) — DDL 금지.
- 머지된 마이그레이션 수정 없음. `MigrationTest`는 V40 → V42 적용, 재실행 중복 없음, 가드 행·메뉴 1건.

### 쟁점 15 — 테스트 계획

- 서비스 단위 `FaqServiceTest`(검증 순서·가드 먼저·상한·`ord` 부여·카테고리 이동(대상 끝 삽입, 원래 목록 재번호)·`version` 증가 규칙·같은 순서 저장 = 쓰기 없음·삭제 규칙·**중간 삭제·이동 직후 `ord`가 0..n-1이고 그대로 재반영해도 버전 불변** — R2-1)
- 공개 필터 검색 규칙은 JS라 브라우저 시험(`FaqPublicFilterBrowserTest`)으로: NFD 답변, 문단·`br` 경계(`배송 안내`가 `<p>배송</p><p>안내</p>`에 일치), 인라인 경계(`배송안내`는 `<strong>배송</strong>안내`에 일치), 빈 결과 문구(R2-2)
- 리포지토리 `FaqRepositoryDataJpaTest`(공개 쿼리 불변식 — 숨긴 카테고리·숨긴 FAQ 제외, 정렬)
- `FaqApiIntegrationTest`(생명주기·검증 400·상한 409·감사·고정 오류 문구·**낡은 version 409**·집합 불일치 400·`PUT /order` vs `PUT /{id}` 라우팅)
- `FaqPermissionMatrixIntegrationTest`(핸들러 × 권한 7조합, 신규 MANAGER, 교차 회원)
- `FaqConcurrencyIntegrationTest`(`LockWaitObserver`로 가드 락 대기 관측, 빈 테이블 N개 동시 등록 = 정확히 상한/`ord` 중복 없음, 두 관리자가 같은 version으로 순서를 **실제로 바꾸는** 반영 → 정확히 하나 성공·하나 409(no-op끼리는 둘 다 200·버전 불변, no-op·변경 경합은 별도 기대값 — R1-4), 조회 도중 순서 반영이 커밋돼도 응답의 version·순서가 같은 스냅샷(R1-2), 순서 반영 ↔ 등록 직렬화; **변이 실험**: 가드 잠금 제거 시 실패)
- `FaqMigrationTest`, `PublicFaqIntegrationTest`(노출 불변식, 숨긴 카테고리 → FAQ 전부 숨김, 빈 카테고리 미표시, 본문 XSS 벡터가 저장 시·출력 시 제거, HEAD, 비-GET/HEAD 거부, `/faq/x`는 무인증 302·인증 403(R1-3 — `denyAll`이라 MVC에 닿지 않음), 정렬, 빈 문단 답변·마지막 항목이 빈 답변인 카테고리 제외), `PublicFaqTemplateConventionTest`, `PublicFaqRateLimitIntegrationTest`, `SecurityConfigTest` `/faq` 판
- 파급 시험 갱신: `AdminSidebarAdviceSnapshotTest`·`AdminPermissionEvaluatorTest`·`AdminActionTypes` 동기화 시험·`AdminEndpointAuthorizationConventionTest`(자동 검사 통과 확인)
- **브라우저 시험** `FaqAdminOrderBrowserTest`(`BrowserTestSupport`): 네이티브 드래그가 초안을 만들고 [반영] 후 서버 순서가 바뀜, 취소, 409 시 초안 폐기 — 화면 JS만 필요한 최소 1~2건.
- 실기(Playwright, dev): 로드맵 완료 기준 그대로 — 등록 → 드래그 → [반영] → `/faq` 같은 순서 → 필터. 숨긴 카테고리·스크립트 차단 상태의 공개 화면 포함.

## 수정해야 할 파일

- 신규: `admin/faq/`(`domain/FaqCategory`·`Faq`·`FaqLock`, `repository/*`, `service/FaqService`, `controller/FaqController`·`FaqCategoryController`·`FaqPageController`, `dto/request·response`, `CLAUDE.md`), `publicweb/faq/`(컨트롤러·서비스·DTO), `common/html/ContentBodyPolicy`(FAQ 규칙 추가), `V41`·`V42`, `templates/admin/faq/manage.html`, `templates/public/faq/list.html`, `static/css/public/faq.css`, `static/js/public/faq.js`, 위 테스트들
- 수정: `AdminFeature`(FAQ), `SecurityConfig`(`/faq`), `application.yml`(레이트리밋), `AdminActionTypes` + `admin/log/manage.html`, `docs/deployment.md`(롤백 후 권한 부활·`/faq`)·`docs/migration-guide.md`(V41~V42 실패 복구), 프로젝트 `CLAUDE.md`(지도 표·`DELEGABLE` 기능·`SecurityConfig` 승인 이력), `com.cms.admin.permission`·`com.cms.config`의 `CLAUDE.md`, `src/test/java/CLAUDE.md`(필요 시)

## 수용한 한계

- 단건 수정의 stale-form(낡은 폼이 방금 바뀐 노출 여부를 덮음) — 배너·메뉴와 같다.
- 네이티브 DnD의 터치 미지원 — 관리 화면은 데스크톱 전제, ▲▼ 대안 제공.
- 공개 페이지가 FAQ 전체를 한 번에 내려준다(상한 200·답변 30KB로 묶음 — 최악 ≈ 6MB).
- 모든 FAQ 쓰기가 한 가드로 직렬화된다(관리자 소수·저빈도 전제).
- **쓰기 응답이 유실된 뒤의 늦은 커밋**(R5-1): 네트워크 단절 등으로 응답을 잃으면 결과를 알 수 없고, 재조회가 성공해도 원래 요청이 뒤늦게 커밋돼 항목이 하나 늦게 나타나거나 사용자가 다시 등록해 중복이 생길 수 있다. 멱등 키·결과 조회 계약은 두지 않는다(다른 관리 CRUD와 같은 한계) — 화면은 "직접 확인" 안내만 한다.
- 가드 아래에서만 동시성이 보장된다 — DB를 직접 수정하면 `version`이 올라가지 않아 낡은 초안이 409 없이 저장될 수 있다(순서만 영향).

## 후속(이번 범위 밖)

- 공개 메인·푸터에서 `/faq`로 가는 링크 — 로드맵에 없다(공개 메인 푸터는 정적 페이지 전용). 필요하면 별도 작업.
- FAQ 답변 이미지 — ⑧ 미디어 라이브러리 이후 재평가.

## 구현·검증 결과 (2026-10-12)

### Context

FAQ 관리(로드맵 Top 8 ⑥)를 계획 v6 그대로 구현했다. 관리자가 카테고리·FAQ를 등록하고 **네이티브 드래그 또는 ▲▼**로 순서를 바꿔 [순서 반영]으로 저장하며, 공개 `/faq`는 노출 중인 카테고리·FAQ만 `<details>` 아코디언으로 보여 주고 JS 즉시 필터를 제공한다. 사용자가 구현 전에 **스키마(V41·V42)와 `/faq` 무인증 공개(인가 정책 변경), ▲▼ 포함**을 승인했다(2026-10-12).

### 핵심 확정 사항 (v6 그대로 구현)

- 새 `DELEGABLE` 기능 `FAQ`(카테고리·FAQ 한 기능), 모든 쓰기의 첫 조회가 가드 행 `faq_lock` `FOR UPDATE`, 구조가 바뀔 때만 `version` +1, 관리자 조회는 version과 트리를 한 스냅샷에서, `ord` 항상 0..n-1(삭제·이동 즉시 재번호), 순서 반영 낡은 version 409·집합 불일치 400·같은 순서 = 쓰기·버전 없음, 답변 HTML 이미지 불허, 카테고리 30·FAQ 200 정확한 상한, 등록 응답은 생성된 항목 한 건.
- 공개 `/faq`는 정확 경로 1개 GET·HEAD, 하위 경로는 404가 아니라 302/403, 빈 답변은 `SanitizedHtml.blank()`로 건너뛴 뒤 빈 카테고리 제거, 검색용 텍스트는 블록 경계 공백·인라인 무구분·NFC.

### 구현 파일

- 신규 백엔드: `admin/faq/`(`domain/Faq`·`FaqCategory`·`FaqLock`, `repository/*`·`PublicFaqRow`, `service/FaqService`, `controller/FaqController`·`FaqCategoryController`·`FaqPageController`, `dto/request·response`, `CLAUDE.md`), `publicweb/faq/`(`PublicFaqController`·`PublicFaqService`·DTO·`CLAUDE.md`), `V41__create_faq.sql`·`V42__seed_faq_menu.sql`.
- 신규 화면: `templates/admin/faq/manage.html`, `templates/public/faq/list.html`, `static/css/public/faq.css`, `static/js/public/faq.js`.
- 수정: `AdminFeature`(`FAQ`), `SecurityConfig`(`/faq` 정확 경로), `application.yml`(`public-faq` 레이트리밋), `ContentBodyPolicy`(`sanitizeFaq`·`sanitizeForFaqOutput`·상한), `AdminActionTypes`·`admin/log/manage.html`(감사 7종), `docs/deployment.md`("FAQ")·`docs/migration-guide.md`("V41~V42")·`docs/troubleshooting.md`, 프로젝트 `CLAUDE.md`·`com.cms.config`·`com.cms.admin.permission`의 `CLAUDE.md`.
- 시험: 신규 `FaqApiIntegrationTest`(14)·`FaqPermissionMatrixIntegrationTest`(핸들러 9 × 권한 7조합 등 5)·`FaqConcurrencyIntegrationTest`(6)·`FaqMigrationTest`(4)·`FaqAdminOrderBrowserTest`(8)·`PublicFaqIntegrationTest`(7)·`PublicFaqTemplateConventionTest`(3)·`PublicFaqRateLimitIntegrationTest`(1)·`FaqPublicFilterBrowserTest`(5), 수정 `SecurityConfigTest`(FAQ 판 8건 + 스텁 컨트롤러)·`AdminSidebarAdviceSnapshotTest`·`AdminPermissionEvaluatorTest`(ADMIN 전 동작 키에 `FAQ:*`)·`BrowserTestSupport`(옵션을 받는 `newContext` 오버로드, 스크립트를 끈 컨텍스트용).

### 계획과 달라진 점

- **추가한 관리 화면 결함 수정**: 목록이 조회 중(`busy`)에 그려져 손잡이·▲▼가 잠긴 채 남는 결함을 브라우저 시험이 잡아 `sync()`(잠금 상태 변경 시 목록 재렌더)를 도입했다 — 계획서의 "목록 조작 잠금" 규칙을 구현하다 생긴 구현 결함이고 계획 결정은 바뀌지 않았다(`docs/troubleshooting.md`).
- 계획서의 단위 시험(`FaqServiceTest`·`FaqRepositoryDataJpaTest`)은 **만들지 않고** 같은 항목을 실제 MariaDB 통합 시험이 맡는다 — 서비스의 가치가 가드 락·`ord` 재번호·버전 규칙이라 모킹으로는 검증되지 않는다. 쟁점 15의 나머지 항목은 모두 구현했다.
- 계획서의 `FaqCategoryRequest`는 `@AllArgsConstructor`를 갖는다(시험이 직접 생성) — Jackson이 생성자를 creator로 인식해도 이 DTO에는 세터 기반 사전 처리가 없어 무해하다.

### 검증 결과

- `./gradlew test` **2128건 실패 0·오류 0·건너뜀 12**(건너뜀은 기존 항목, 이번 변경과 무관) — 마지막 전체 실행 BUILD SUCCESSFUL 7분 42초(로컬 KST 기본 시간대).
- **변이 실험**: `FaqService.lockGuard()`를 잠금 없는 `findById`로 바꾸면 `FaqConcurrencyIntegrationTest` 3건(같은 version 동시 순서 반영·빈 테이블 동시 등록·상한 직전 동시 등록)이 실패하고 원복 후 통과한다. 락 대기 시험은 `bumpVersion`의 UPDATE가 같은 행을 잠그므로 변이에서도 통과한다(가드 `@Lock`을 직접 겨냥하는 변이는 아님 — 위 3건이 방어선).
- **실기(dev, Docker 앱 컨테이너 재빌드, Playwright)**: V41·V42가 실제 dev DB에 적용됨 → 카테고리 2개·FAQ 4개를 화면으로 등록(감사·DB 확인) → FAQ를 끌어 놓으면 초안만 바뀌고 DB 불변(version 6) → 카테고리도 끌어 [순서 반영] → DB `ord`·version 7 반영 → 비로그인 `/faq`가 같은 순서(HEAD 200, `/faq/x` 302, POST 403) → 공개 화면에서 질문(`해외`)·답변(`재설정`) 필터·카테고리 숨김·펼치기 → **왕복**: 카테고리를 숨기면 공개에서 사라지고 되돌리면 복귀 → 비로그인 관리 접근 page 302·api 401 → 콘솔 오류는 기존 `favicon.ico` 404뿐. 스크린샷 4장(`adversarial-review/plan/faq-screenshots/`, git 제외)과 dev 테스트 데이터 원복(행 0건·version 0)까지 확인.
- 미확인: 실제 MANAGER 계정 로그인으로 권한 부여·회수를 화면에서 확인하는 실기(권한 매트릭스·세션 반영은 `FaqPermissionMatrixIntegrationTest`로 대체), 가드 행 락 자체를 겨냥한 `@Lock` 제거 변이.

### 이슈

- 위 `sync()` 결함 1건(해결). `docs/troubleshooting.md`에 기록.

### 후속

- 공개 메인·푸터에서 `/faq`로 가는 링크(로드맵에 없음), FAQ 답변 이미지(⑧ 미디어 라이브러리 이후 재평가), 쓰기 응답 유실 뒤 늦은 커밋을 막는 멱등 키(R5-1 기각 — 다른 관리 CRUD와 같은 한계).
- 로드맵 ⑥ 완료 반영은 `/updateRoadmap`이 맡는다(이 작업은 로드맵 파일을 수정하지 않았다).
