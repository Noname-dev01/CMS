# PLAN — HTML 편집기 + sanitizer + 본문 이미지 업로드 (공통 기반, 첫 적용: 공지)

> 로드맵: `project-direction-roadmap.md` "실행 로드맵 — Top 8 (2026-10-07 선정)" ⓪
> 상태: **구현·검증 완료 (2026-10-07, 커밋·PR 전)** — 설계 v7(적대적 리뷰 7라운드 ship), 결과는 문서 끝 "구현·검증 결과"

## Context

사용자가 게시판·공지·정적 페이지·FAQ·팝업 본문을 모두 HTML 편집기로 쓰기로 확정했다(2026-10-07). 그 공통 기반을 먼저 만들고, 첫 적용 대상으로 **기존 공지**를 전환한다.

지금 공지 본문은 평문이다 — 관리 API가 `@Size(max=10000)` 평문을 받고, 공개 상세는 `th:text`(이스케이프) + CSS `pre-wrap`, 관리 상세 모달은 `textContent`로만 출력한다. `PublicNoticeTemplateConventionTest`가 공개 공지 템플릿의 `th:utext`를 금지한다. 이 작업은 그 XSS 방어 전제를 의도적으로 바꾸므로, **"HTML을 받아들이되 허용 목록 sanitizer를 통과한 것만 저장·출력한다"**는 새 계약을 세우는 것이 핵심이다.

로드맵 확정 결정(2026-10-07):
- 서식: HTML 편집기 + 서버 측 sanitizer
- 본문 이미지: 편집기 도입 때 업로드까지 포함
- 공개 접근: 공개 상태 콘텐츠가 참조하는 이미지만 공개(미사용·비공개 콘텐츠 이미지는 404)
- 이미지: png·jpeg·gif, 파일당 5MB, 전체 저장 용량 상한 있음
- 새 라이브러리는 사전 제안 후 승인

## 정찰 결과 (코드 확인 사실)

| 대상 | 사실 |
|---|---|
| `Notice.content` | `@Lob` + `columnDefinition = "TEXT"`(V8), NOT NULL, 65,535바이트 상한 |
| `NoticeCreateRequest`/`UpdateRequest` | `content`에 `@Size(max = 10000)` — 문자 수 기준 |
| `NoticeService` | `requireNonBlank`(trim 후 공백 거부) 후 그대로 저장. 부분 수정(null=유지) |
| `NoticeResponse.from` / `PublicNoticeDetail.from` | 엔티티 `content`를 그대로 담는다 — 본문 출력 경로는 이 둘뿐(`NoticeSummaryResponse`·`PublicNoticeSummary`는 본문 제외, 통합 검색·공개 검색은 제목만) |
| `public/notice/detail.html` + `notice.css` | `th:text="${notice.content}"` + 본문 `white-space: pre-wrap`(연속 공백·탭·줄바꿈 보존) |
| `admin/notice/manage.html` | `textarea#formContent`(maxlength 10000), 상세는 `viewContent.textContent`, `fillForm()`은 `.value` 교체. 상세 조회·저장 응답에 이미 **세대 토큰·AbortController로 늦은 응답 폐기**(L319·L857 부근). jQuery 3.6 + Bootstrap 4.6(SB Admin 2), 정적 라이브러리는 `/vendor/**` 로컬 |
| CSP | 앱·문서 어디에도 `Content-Security-Policy` 없음 |
| `FileStorage` | 네임스페이스 없는 `store/load/open/delete`(공지 첨부가 사용, `open()`은 스트리밍), 네임스페이스 오버로드는 `store/load/delete`만(`open` 없음) |
| `ProfileImageValidator` | ImageIO 헤더 검사 + 전체 디코드, 상한 2000px·200만 픽셀·`getNumImages(true)==1` — 1920×1080도 거부, **APNG는 PNG reader가 1프레임으로 보고해 통과**(리뷰 R1-6) |
| 업로드 패턴 | `NoticeAttachmentService`(락 → 검증 → `store` → 롤백 시 파일 정리 → 행 저장), `FileStorageTransactionSupport`(공용 유틸) |
| 공개 다운로드 패턴 | `PublicNoticeController`의 첨부 스트리밍(조회 트랜잭션과 파일 열기 분리, `no-store`, `nosniff`, 404 흡수, ID는 문자열로 받아 직접 파싱) |
| 인가 | `/admin/api/notices/**`는 `NOTICE` READ URL 게이트 + 핸들러별 `@RequirePermission`(단일 기능·단일 동작). `AdminEndpointAuthorizationConventionTest`가 모든 API에 인가 선언 1개를 강제 |
| 공개 경로 | `SecurityConfig` 기본 거부 — 새 공개 경로는 명시 `permitAll` 필요(인가 정책 변경) |
| 마이그레이션 관례 | DDL과 DML을 섞지 않는다, DDL 마이그레이션마다 실패 복구 절차를 `docs/migration-guide.md`에 남긴다(V13·V17~V21 선례), 업그레이드 경로 테스트는 별도 스키마에 `target("N")` 적용 후 데이터 삽입 → 나머지 적용(`AdminActionLogTargetLabelMigrationTest`) |
| 멀티파트 | `max-file-size: 10MB`, `max-request-size: 15MB`(5MB 이미지 수용) |

## 핵심 쟁점과 결정

### 쟁점 1. sanitizer 라이브러리 — **jsoup 1.23.2** (사전 제안 대상)

| 선택지 | 장점 | 단점 |
|---|---|---|
| **jsoup `Safelist` + `Cleaner`** | 활발히 유지(1.23.2, 2026-08), MIT, 파서가 HTML 표준 정렬, 결과를 DOM으로 다시 다룰 수 있어 이미지 src 후처리·텍스트 길이 계산을 같은 라이브러리로 처리 | 허용 목록을 직접 정의해야 함(기본 `relaxed()`는 너무 넓음) |
| OWASP Java HTML Sanitizer | 보안 특화 정책 빌더 | 릴리스가 드묾, DOM 후처리·텍스트 추출은 별도 수단 필요 |
| 직접 구현(정규식) | 의존성 없음 | 파서 차이 공격에 취약 — 기각 |

**결정**: jsoup. 이유 — sanitize와 "이미지 src 제한·보이는 텍스트 길이 계산"을 한 파서로 처리해 파서 간 해석 차이를 없앤다.

### 쟁점 2. 편집기 — **Quill 2.0.3** (BSD-3-Clause, 사전 제안 대상)

| 선택지 | 장점 | 단점 |
|---|---|---|
| **Quill 2** | 의존성 없음, 출력 마크업이 단순하고 예측 가능, `formats` 옵션으로 편집기 자체의 허용 서식을 제한 가능, 이미지 핸들러·클립보드 매처 API 공식 제공 | 표(table) 미지원(코어 기준) |
| Summernote | jQuery·Bootstrap 4 스택과 맞음, MIT | 붙여넣기 HTML을 거의 그대로 보존해 출력 마크업이 넓고 불규칙(sanitizer가 많이 깎아 "보이는 것 ≠ 저장되는 것" 괴리), 유지보수 정체 |
| TinyMCE·CKEditor 5 | 기능 최다 | GPL 또는 상용 라이선스, 번들 무거움 |

**결정**: Quill 2.0.3. 정적 파일(`quill.js`·`quill.snow.css`·LICENSE)을 **`/vendor/quill/`에 로컬 보관**한다(기존 vendor 관례, CDN 미사용). 저장은 `quill.getSemanticHTML()` 출력으로 한다.

**로드맵 대비 축소**: 로드맵의 "표" 서식은 Quill 코어가 지원하지 않아 이번 범위에서 제외한다(설계 제약).

### 쟁점 3. 허용 서식 정책 — 편집기 `formats` = 툴바 = sanitizer 허용 목록

세 곳을 1:1로 맞춘다. Quill은 기본적으로 툴바에 없는 서식(색·정렬·다른 제목 단계 등)도 붙여넣기로 받아들이므로(리뷰 R1-4), **편집기 생성 옵션 `formats`로 허용 서식을 명시 제한**해 편집기 화면에서부터 허용 밖 서식이 생기지 않게 한다.

| Quill format | 툴바 | sanitizer 허용 태그 | 허용 속성 |
|---|---|---|---|
| `header`(2·3단계만) | 제목 | `h2`, `h3` | 없음 |
| `bold`·`italic`·`underline`·`strike` | 굵게 등 | `strong`, `em`, `u`, `s` | 없음 |
| `list` | 목록 | `ol`, `ul`, `li` | 없음 |
| `blockquote` | 인용 | `blockquote` | 없음 |
| `link` | 링크 | `a` | `href`(프로토콜 `http`·`https`·`mailto`만), 강제 `rel="noopener noreferrer nofollow"`, 강제 `target="_blank"` |
| `image` | 이미지 | `img` | `src`(쟁점 6 규칙만), `alt` |
| (문단) | — | `p`, `br` | 없음 |

- **제목 단계 제한(리뷰 R2-5)**: `formats`는 서식 *이름*만 제한하므로 `header`를 허용하면 H1~H6이 모두 들어올 수 있다. 편집기는 clipboard matcher로 붙여넣은 `H1`→2단계, `H4`~`H6`→3단계로 바꾸고, **서버 sanitizer도 cleaning 전에 같은 매핑(`h1`→`h2`, `h4`~`h6`→`h3`)**을 적용해 편집기·서버 결과를 일치시킨다. Quill 키보드 단축 입력이 H1을 만드는지 스파이크에서 확인해, 만들면 해당 바인딩을 끈다.
- **`style`·`class`·`on*`·`data-*` 전부 금지.** 정렬·들여쓰기·글자색은 `formats`에서 뺀다.
- **코드 블록(`code-block`/`pre`) 제외(리뷰 R4-1)**: Quill은 코드 블록을 `<pre>\n…</pre>`로 내보내는데, HTML 파서가 `<pre>` 직후 LF 하나를 버리는 규칙 때문에 저장·출력 이중 sanitize를 거치면 선행 빈 줄이 하나씩 사라진다(jsoup 1.23.2에서는 보존 수정 미포함). 보정 직렬화 규칙을 두는 대신 **코드 블록 서식 자체를 이번 범위에서 뺀다** — 로드맵 확정 서식(제목·목록·굵게·링크·이미지)에 원래 없었고 공지 용도에서 수요가 낮다. 붙여넣은 `<pre>`는 편집기 `formats`에서 막히고, 서버는 태그만 벗겨 내용을 텍스트로 남긴다. 이로써 공백 정규화·길이 계산의 `pre` 예외도 사라진다.
- 금지 태그는 **내용을 보존하고 태그만 벗긴다**(jsoup `Cleaner` 기본 동작) — `script`·`style` 등은 내용째 버려진다.
- 출력 설정: `prettyPrint(false)`, HTML 구문.

### 쟁점 3-1. 공백 저장 형식 — **보존해야 할 공백은 `&nbsp;`로 표현** (리뷰 R2-1)

`pre-wrap` CSS는 *표시*만 보존한다. Quill 2.0.3은 HTML을 Delta로 읽어 들일 때(`clipboard.convert`) 텍스트의 탭을 공백으로 바꾸고 연속 공백을 하나로 접으며 문단 양끝 공백을 지운다 — 저장 HTML에 일반 공백으로 남겨 두면 **편집기를 여는 순간 공백이 사라지고 재저장 시 유실**된다. Quill은 U+00A0(`&nbsp;`)은 접지 않는다.

**결정(저장 형식 규칙)**: 텍스트에서 ① 연속 공백은 첫 칸만 일반 공백, 나머지는 `&nbsp;` ② 문단(블록) 앞뒤 공백은 `&nbsp;` ③ 탭은 **`&nbsp;` 1개**(글자 수 1:1 유지 — 4칸 확장은 기존 10,000자 본문을 길이·바이트 상한 밖으로 밀어내 재저장을 막으므로 기각, 리뷰 R3-2. 탭 표시 폭이 1칸으로 줄어드는 시각 변화는 수용). 이 규칙을 **세 곳이 공유**한다: V25 변환 SQL, 서버 sanitizer의 정규화 단계(멱등), 그리고 Quill `getSemanticHTML()` 출력이 이미 이 형태인지 확인.

**검증 게이트(구현 1단계 스파이크)**: Quill 2.0.3에서 ⑴ 편집기 입력 `A␠␠␠B`·앞 공백·탭 → `getSemanticHTML()` 결과 ⑵ 그 결과를 `clipboard.convert`로 다시 읽은 Delta ⑶ 서버 sanitize 후 재로딩을 실측한다. `getSemanticHTML()`이 모든 공백을 `&nbsp;`로 바꾸는 알려진 동작(줄바꿈 위치를 해침)이 확인되면 저장 직전 클라이언트에서 위 규칙대로 정규화하는 대신 **서버 정규화로 일원화**한다(서버가 단일 원본). 스파이크 결과가 이 규칙으로 왕복 보존을 못 하면 구현을 멈추고 보고한다.

### 쟁점 4. sanitize 시점 — **저장 시 + 출력 시 둘 다**

| 선택지 | 장점 | 단점 |
|---|---|---|
| 저장 시만 | 출력 비용 0 | 정책을 강화해도 기존 행은 옛 정책 그대로, 변환 마이그레이션·직접 DB 수정 등 저장 경로 밖 데이터 무방비 |
| 출력 시만 | 정책 변경이 즉시 전체 적용 | DB에 위험한 원문이 그대로 남음 |
| **둘 다** | 저장 데이터는 항상 정규화, 출력은 방어 심층화 | 출력마다 파싱 비용(상세 1건당 — 목록은 본문을 안 씀) |

**결정**: 둘 다. 출력 경로는 `NoticeResponse.from`·`PublicNoticeDetail.from` 두 곳뿐이라 이 둘이 sanitizer를 거치게 한다. **sanitizer는 멱등**이어야 한다(`clean(clean(x)) == clean(x)`) — 테스트로 고정.

### 쟁점 5. 본문 길이 상한 — **`notice.content`를 MEDIUMTEXT로 확장 + 보이는 텍스트 10,000자 + 저장 HTML 200,000바이트**

v1은 "스키마 변경 없이 65,535바이트 상한"이었으나 리뷰 R1-9의 반례(기존 검증을 통과한 10,000자 평문 — 줄바꿈 9,998개 — 가 변환 후 109,983바이트)로 **변환 마이그레이션이 TEXT 상한에 걸려 배포가 실패할 수 있음**이 확인돼 철회한다.

| 선택지 | 장점 | 단점 |
|---|---|---|
| TEXT 유지 + 변환 실패 시 운영자 보정·`repair` | 스키마 무변경 | 정상 데이터로 배포가 막힐 수 있고, 실패 이력 정리 절차가 필요 |
| TEXT 유지 + 넘치는 행은 변환 생략 | 배포는 성공 | 변환 안 된 평문이 HTML로 해석됨(정합성 깨짐) |
| **MEDIUMTEXT(16MB)로 확장** | 변환 결과가 상한을 넘을 수 없음 — 실패 원인 자체 제거 | DDL 1건(되돌릴 필요 없음 — 확장 방향이라 구버전 앱도 그대로 동작) |

**결정**: MEDIUMTEXT. 엔티티 `columnDefinition = "MEDIUMTEXT"`(`ddl-auto: validate` 정합은 전체 컨텍스트 테스트가 확인). 검증 규칙:
- 요청 DTO: `@Size(max=10000)` 제거, **요청 원문 UTF-8 200,000바이트 이하**(`@MaxUtf8Bytes`, 기존 검증기 재사용 — sanitize 전 파싱 비용 상한)
- 서비스(sanitize 후): ① 결과 UTF-8 바이트 ≤ 200,000 ② 보이는 텍스트 길이 ≤ 10,000(기존 "본문 10,000자" 의미 유지) ③ "텍스트가 공백(`&nbsp;` 포함)이고 이미지도 없음"이면 400(기존 공백 거부 유지 — 이미지만 있는 본문은 허용)
- **보이는 텍스트 길이 계산 규칙(리뷰 R2-7)**: jsoup `Element.text()`는 공백을 정규화하므로 쓰지 않는다. DOM을 순회해 ⑴ 텍스트 노드의 문자를 정규화 없이 코드 포인트 단위로 세고(공백·`&nbsp;` 포함) ⑵ 블록(`p`·`li`·`h2`·`h3`·`blockquote`)이 끝날 때마다 1을 더한다(기존 평문의 줄바꿈 1자와 같은 의미) ⑶ `br`은 **블록의 마지막 자식이 아닐 때만** 1을 더한다 — 블록 끝의 `br`(Quill의 빈 줄 `<p><br></p>`·줄 끝 표시)은 ⑵의 블록 종료와 같은 줄바꿈이므로 중복으로 세지 않는다(리뷰 R3-1: 이중 계산 시 줄바꿈 9,998개 반례가 19,998자로 거부됨). 이 규칙으로 기존 평문 N자는 V25 변환 후 N+1자 이하가 되므로(마지막 블록 종료 1자) 상한 비교는 `≤ 10,001`로 둔다. 반례(줄바꿈 9,998개·탭 9,998개·공백 9,998개)를 **수정 API 재저장**까지 테스트한다.
- **요청 형식 표식(리뷰 R2-2)**: 생성·수정 요청에 `contentFormat`을 둔다. `content`가 있는 요청은 `contentFormat == "HTML"`이 **필수**이고, 없거나 다른 값이면 400 "편집 화면이 오래되었습니다. 새로고침 후 다시 저장해 주세요."를 낸다 — 배포 전에 열어 둔 평문 textarea 화면이 평문을 보내 HTML로 해석되는 경로(정상 배포에서도 발생)를 차단한다. `content` 없이 `useYn`만 바꾸는 PATCH는 표식이 필요 없다(본문 해석 문제가 없음).

### 쟁점 6. 본문 이미지 저장·공개 판정 — **이미지 테이블 + 참조 테이블**

이미지 `src`는 **같은 사이트의 `/content-images/{id}`만** 허용한다(정규식 `^/content-images/[1-9][0-9]{0,18}$` **+ `Long.parseLong` 성공** — 19자리 중 `Long` 범위 밖 값은 정규식만으로 걸러지지 않으므로 파싱 실패를 잡아 그 `img`를 제거한다, 리뷰 R1-10). 외부 이미지 URL·`data:` URI는 sanitizer가 `img`째 제거한다 — 외부 추적 픽셀·혼합 콘텐츠·Base64 비대화 방지.

| 선택지(공개 판정) | 장점 | 단점 |
|---|---|---|
| 다운로드 시 `notice.content LIKE` 역검색 | 테이블 1개 | 전체 스캔, `/content-images/12`가 `/content-images/123`에 걸리는 부분 일치 버그 위험 |
| 이미지 행에 소유자 1개 | 단순 | 작성 중(공지 ID 없음) 표현 불가, 여러 글이 한 이미지 공유 불가 |
| **참조 테이블 `content_image_ref(owner_type, owner_id, image_id)`** | 정확한 판정, ⑧ 미디어 라이브러리의 사용처 추적으로 확장 가능 | 저장 시 참조 갱신 로직 필요 |

**결정**: 참조 테이블. 공지 저장(생성·본문 수정) 트랜잭션 안에서 sanitize된 HTML의 이미지 ID 집합으로 `(NOTICE, noticeId)` 참조를 **교체**한다(삭제 후 삽입, 존재하지 않는 이미지 ID는 참조에서 제외 — 깨진 이미지로 남지만 무해). 공지 소프트 삭제 시 참조는 남긴다(공개 판정에서 `deleted=false`로 걸러짐).

**공개 다운로드 `GET/HEAD /content-images/{id}`** 판정:
1. ID는 문자열로 받아 직접 파싱(비숫자·범위 밖 → 404, 공개 공지 컨트롤러와 같은 방식)
2. 공개 조건: `content_image_ref`에 `owner_type='NOTICE'`이고 그 공지가 `use_yn=true AND deleted=false`인 행이 하나라도 있으면 허용
3. 관리자 미리보기: 요청자가 **`NOTICE` READ 권한 보유자**(`AdminPermissionEvaluator.allows`, ADMIN 포함)면 공개 여부와 무관하게 허용 — 작성 중·비노출 공지를 편집기에서 볼 수 있어야 하므로. 세션 인증은 `/content-images` 경로에서도 `SecurityContext`에 실린다(구현 시 테스트로 확인).
4. 그 외(없는 ID·비공개만 참조·미참조) → 동일 404(존재 여부 비노출)

응답: 저장된 `content_type`, `nosniff`, `Cache-Control: no-store`, `Content-Disposition` 없음(인라인). 파일은 기존 `FileStorage.open()` 스트리밍(조회 트랜잭션과 파일 열기 분리).

### 쟁점 7. 저장 위치 — **네임스페이스 없는 루트(공지 첨부와 같은 영역)**

네임스페이스로 분리하면 스트리밍용 `open(key, namespace)`가 없어 `FileStorage`·`LocalDiskFileStorage`를 함께 고쳐야 한다. storageKey는 서버가 UUID로 만들고 DB 행으로만 접근하므로 물리 분리가 보안상 필요하지 않다. **결정**: 루트에 저장하고 기존 `open()`으로 스트리밍한다(스토리지 코드 무변경).

### 쟁점 8. 이미지 검증 — **공용 검증기로 일반화, APNG 차단, 본문 이미지는 헤더 검사만**

| 선택지 | 장점 | 단점 |
|---|---|---|
| `ProfileImageValidator` 그대로 | 코드 재사용 | 2000px·200만 픽셀 상한이라 일반 스크린샷도 거부 |
| 본문 전용 검증기 복제 | 프로필 코드 무변경 | ImageIO 헤더 검사 로직 중복 |
| **공용 `ImageFileValidator`(상한·전체 디코드 여부를 인자로) + `ProfileImageValidator`는 기존 상한으로 위임** | 중복 없음 | 프로필 코드 일부 이동(기존 테스트로 회귀 확인) |

**결정**: 세 번째. 본문 이미지 상한 — 한 변 4,096px, 1,680만 픽셀, 단일 프레임, 선언 MIME과 실제 포맷 일치. **전체 픽셀 디코드는 하지 않는다**(1,680만 픽셀 디코드는 요청당 힙 ~64MB). 손상된 이미지는 저장돼도 `nosniff` + 정확한 `image/*` 타입으로만 응답돼 무해하다. 프로필은 지금처럼 전체 디코드를 유지한다.

**GIF 논리 화면 크기(리뷰 R3-3)**: `getWidth(0)`·`getHeight(0)`은 GIF의 첫 프레임 크기만 돌려주고, 브라우저가 실제로 그리는 캔버스 크기인 **논리 화면(Logical Screen)**은 별개다. GIF일 때 스트림 메타데이터(`javax_imageio_gif_stream_1.0`의 `LogicalScreenDescriptor`)의 화면 크기에도 같은 크기·픽셀 상한을 적용하고, 이미지 메타데이터(`ImageDescriptor`)의 프레임 위치+크기가 화면 범위 안인지 검사한다(벗어나면 거부). 프로필에도 적용된다(결함 보정). fixture: 논리 화면 5000×5000·프레임 1×1 GIF, 화면 밖 프레임 GIF.

**APNG 차단(리뷰 R1-6)**: JDK PNG reader는 APNG를 1프레임으로 보고하므로, PNG일 때 **IDAT 이전 청크에 `acTL`이 있으면 애니메이션으로 거부**한다(청크 길이·타입을 순서대로 읽는 단순 스캔, 바이트 경계 검사 포함). 공용 검증기에 넣으므로 **프로필 이미지에도 적용**된다 — 프로필의 기존 정책("애니메이션 이미지는 지원하지 않습니다")의 구멍을 메우는 것이라 정책 변경이 아니라 결함 보정으로 기록한다.

### 쟁점 9. 전체 저장 용량·개수 상한 — **카운터 행 비관적 락으로 정확히 집행**

v1의 "소프트 상한"은 리뷰 R1-7·8에 따라 철회한다(초과량이 동시 업로드 수에 비례해 상한이 무의미해질 수 있고, `SUM` 비용·파일 개수 증가를 막지 못함).

| 선택지 | 장점 | 단점 |
|---|---|---|
| `SUM(file_size)` 소프트 상한 | 테이블 추가 없음 | 동시성 초과 무제한, 업로드마다 전체 합계 |
| MariaDB `GET_LOCK` 이름 잠금 | 테이블 추가 없음 | 트랜잭션과 무관한 커넥션 단위 잠금 — 풀 커넥션 반환 시 누수·해제 누락 위험 |
| **카운터 행 1개(`content_image_usage`) `SELECT … FOR UPDATE`** | 정확(직렬화), O(1), 기존 비관적 락 패턴과 동일 | 업로드가 직렬화됨(관리자 기능이라 빈도 낮음 — 수용) |

**보장 범위(리뷰 R6-1)**: 카운터는 **DB에 등록된 이미지(`content_image` 행)의 바이트·개수**만 집계한다. 파일 저장 후 커밋 전에 프로세스가 강제 종료되거나 롤백 시 파일 삭제가 실패하면 "행 없는 파일"이 디스크에 남고 카운터에는 잡히지 않는다(공지 첨부도 같은 성질). 이 잔존 파일은 쟁점 14의 수동 회수 ⑥단계(파일·DB 대조)로 회수한다.

**결정**: 카운터 행. 업로드 트랜잭션에서 카운터를 잠그고 `total_bytes + size ≤ max-total-bytes`(기본 1GB)·`total_count + 1 ≤ max-count`(기본 10,000)를 검사한 뒤 증가시킨다. 초과 시 409. 설정 키 `cms.content-image.max-total-bytes`·`cms.content-image.max-count`. 다중 인스턴스에서도 DB 락이라 정확하다. 파일 저장은 락 보유 중에 하되 실패·롤백 시 파일 정리(`FileStorageTransactionSupport` 패턴) — 카운터 증가도 함께 롤백된다.

### 쟁점 10. 업로드 권한 — **`NOTICE` CREATE 또는 UPDATE**

| 선택지 | 장점 | 단점 |
|---|---|---|
| `@RequirePermission(NOTICE, UPDATE)`만 | 선언 1개 | CREATE만 가진 MANAGER가 새 공지에 이미지를 못 넣음 |
| `@PreAuthorize("... or ...")` 직접 작성 | 정확 | 인가 선언 컨벤션 테스트가 허용하는 형태가 아님 |
| **`@RequirePermission(NOTICE, READ)` + 서비스에서 CREATE∨UPDATE 재판정(403)** | 컨벤션 준수, 정확 | 판정이 두 곳에 나뉨(주석·테스트로 고정) |

**결정**: 세 번째. 경로는 **`POST /admin/api/notices/content-images`**(기존 `NOTICE` URL 게이트 안 — `SecurityConfig` 변경 없음). 다른 도메인이 편집기를 쓰게 되면 그때 일반화한다. 감사 로그 `CONTENT_IMAGE_UPLOAD`.

### 쟁점 11. 기존 평문 본문 변환 — **Flyway SQL(DML 단독), MEDIUMTEXT 확장 뒤에 실행**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **Flyway SQL** | 프로젝트 관례, DML만이라 실패 시 롤백, 업그레이드 경로 테스트 선례 | 이스케이프를 SQL `REPLACE`로 표현 |
| Flyway Java 마이그레이션 | Java 이스케이프 재사용 | 프로젝트 첫 Java 마이그레이션(관례 신설) |
| 기동 시 러너 | 행 단위 실패 흡수 | 변환 전 행과 후 행이 섞이는 기간 발생 |

**결정**: Flyway SQL. 마이그레이션 구성(DDL·DML 분리 관례):
- `V23__expand_notice_content.sql` — DDL 1문: `ALTER TABLE notice MODIFY content MEDIUMTEXT NOT NULL`
- `V24__create_content_image.sql` — DDL 3문: `content_image`, `content_image_ref`, `content_image_usage`
- `V25__convert_notice_content_to_html.sql` — DML: 카운터 행 `(1, 0, 0)` 멱등 삽입 + 공지 본문 변환

변환 규칙(순서 중요):
1. `\r\n`→`\n`, `\r`→`\n`
2. `&`→`&amp;`(반드시 먼저), `<`→`&lt;`, `>`→`&gt;`, `"`→`&quot;`
3. 줄마다 `<p>…</p>`: `CONCAT('<p>', REPLACE(x, '\n', '</p><p>'), '</p>')`
4. 빈 문단 `<p></p>`→`<p><br></p>`(Quill의 빈 줄 표현)
5. 공백 저장 형식(쟁점 3-1): 탭→`&nbsp;`×1, 연속 공백의 두 번째 칸부터 `&nbsp;`, 문단 앞뒤 공백→`&nbsp;`. SQL `REPLACE`만으로 "첫 칸만 일반 공백"을 정확히 표현하기 어려우면 MariaDB `REGEXP_REPLACE`(10.0.5+)를 쓰고, 그래도 어려우면 **V25에서는 1~4단계만 하고 공백 정규화는 서버 sanitizer의 출력 정규화가 담당**하게 한다(출력 시 sanitize가 같은 규칙을 적용하므로 표시·편집기 로딩 결과는 같다). 어느 쪽인지는 구현 시 업그레이드 테스트로 확정한다.
- MEDIUMTEXT 확장 후라 결과가 컬럼 상한을 넘을 수 없다. 기존 평문(≤10,000자)의 변환 결과 최악값은 줄바꿈 반례 109,983바이트(`&lt;` 10,000개는 ~40KB, 공백·탭 10,000개는 ~60KB)로 **저장 상한 200,000바이트 안**이라 변환본 재저장이 바이트 상한에 걸리지 않는다 — 반례 3종을 테스트에 넣는다.
- V23·V24의 DDL 실패 복구 절차(이력·잔여 객체 확인 → 빈 잔여 객체만 정리 → `flyway repair` → 재기동)를 `docs/migration-guide.md`에 기존 V20·V21 형식으로 추가한다.

### 쟁점 12. 출력 방식 — 공개 `th:utext`(sanitize된 필드 한정), 관리 상세 `innerHTML`, 표시 CSS는 편집기와 동일

- 공개 `detail.html`: `th:utext="${notice.content}"`. `PublicNoticeDetail.from`이 반드시 sanitize한 값을 담는다. `PublicNoticeTemplateConventionTest`는 **"`detail.html`의 `th:utext="${notice.content}"` 정확히 1곳만 허용, 그 외 전부 금지"**로 바꾼다.
- 관리 상세 모달: `viewContent.innerHTML = data.content`(서버가 출력 시 sanitize한 값). 목록 표의 `escapeHtml` 규칙은 그대로.
- **표시 CSS(리뷰 R1-5)**: Quill 편집 영역(`.ql-editor`)은 `white-space: pre-wrap`에 문단 여백 0으로 렌더링한다. 공개 본문·관리 상세 보기도 **`pre-wrap` 유지 + `p` 여백 0 + 목록·인용·이미지(`max-width:100%`) 최소 스타일**로 맞춰, 편집기 화면 = 공개 화면 = 기존 평문 표시(연속 공백·탭·빈 줄 보존)가 되게 한다.

### 쟁점 13. 편집기 상태 관리 — 교체·초기화·업로드 경합 (리뷰 R1-2·3·4)

**내용 교체**: `fillForm`에서 `dangerouslyPasteHTML(0, …)`(삽입)이 아니라 `quill.setContents(quill.clipboard.convert({ html: html + '<p><br></p>', text: '\n' }), 'silent')`로 **교체**하고(리뷰 R5-1: `convert()`는 서식 없는 마지막 개행 하나를 버려 끝의 빈 문단이 재편집마다 하나씩 사라진다 — Quill 자신의 초기 HTML 로딩과 같은 "빈 문단 1개 덧붙여 변환" 보정을 쓴다), 열기·신규 작성·취소·다른 공지 전환 때마다 `quill.history.clear()`로 undo 이력을 비운다. 신규 작성은 빈 내용으로 교체.

**이미지 입력 경로 3개 모두 업로드 API를 거친다**:
- 툴바 이미지 버튼 → 파일 선택 → 업로드 → 삽입
- 이미지 **파일** 붙여넣기·끌어놓기 → Quill uploader 모듈 handler 교체 → 업로드 → 삽입(Quill 기본값은 `data:` URI 삽입이라 sanitizer가 지워 "보였는데 저장하면 사라짐" 괴리가 생김)
- **HTML 안의 `<img>`** 붙여넣기(다른 웹페이지 복사 등) → clipboard matcher(`'IMG'`)가 `src`가 `/content-images/{id}` 형식이 아니면 이미지를 버린다(외부 URL·`data:`는 편집기에서부터 들어오지 않음)
- 정확한 Quill API(uploader handler 시그니처, matcher 반환 Delta)는 구현 전 context7로 확인한다.

**업로드 경합(기존 세대 토큰 패턴 재사용)**:
- 모달 열기·전환·닫기마다 편집 세대 값을 올린다. 업로드는 시작 시점 세대와 **삽입 위치(selection index)**를 기억한다.
- **위치 추적(리뷰 R2-6)**: 대기 중인 업로드의 위치는 Quill `text-change` 이벤트마다 `delta.transformPosition(index)`로 갱신한다(업로드 중 앞쪽에 글을 넣거나 지워도 원래 자리에 삽입). 한 번에 고른·붙여넣은 여러 파일은 **순차 업로드·순차 삽입**(앞 파일 삽입 후 다음 파일 위치 = 직전 위치 + 1)해 응답 순서와 무관하게 선택 순서를 지킨다.
- 응답 도착 시 세대가 바뀌었으면 **삽입하지 않고 버린다**(공지 A의 이미지가 B에 들어가는 경로 차단). 업로드된 파일은 미참조 이미지로 남는다(쟁점 14).
- 업로드 진행 중(대기 건수 > 0)에는 **저장 버튼을 비활성화**하고 "이미지 업로드 중" 표시 — 업로드 완료 전 저장으로 이미지가 빠지는 경로 차단.
- 업로드 실패는 편집기 위 경고로 표시, 대기 건수 감소.

### 쟁점 14. 미참조(고아) 이미지 정리 — **자동 정리는 범위 밖, 수동 회수 절차 문서화**

편집기에 올리고 저장하지 않은 이미지, 본문에서 지운 이미지, 세대가 바뀌어 버려진 업로드는 파일·행이 남는다. 자동 정리(스케줄러)는 "올린 직후 저장 전" 이미지를 지울 경합을 새로 만든다. **결정**: 자동 정리는 ⑧ 미디어 라이브러리로 미룬다. 대신 상한(쟁점 9)에 도달했을 때 운영자가 복구할 수 있도록 **수동 회수 절차**를 운영 문서에 남긴다(리뷰 R2-3·R2-4 반영):
- **전제: 앱을 내린 유지보수 구간에서만 수행**한다 — 앱이 떠 있으면 대상 조회 직후 관리자가 그 이미지를 본문에 다시 넣어 저장할 수 있어, 사용 중인 파일을 지우게 된다. 회수는 드문 비상 절차라 잠금 프로토콜을 새로 설계하지 않는다.
- **회수 대상**: 참조가 없거나, **모든 참조가 소프트 삭제된 공지(`deleted=true`)인** 이미지(살아 있는 공지가 하나라도 참조하면 제외)
- **순서**: ① 앱 정지 + 백업(`make prod-backup`) ② 대상 이미지 ID·storageKey 목록 확정(파일로 저장) ③ 한 트랜잭션에서 대상의 참조 행 삭제 → 이미지 행 삭제 → 카운터 재계산(`UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size),0) FROM content_image), total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1`) → 커밋 ④ 커밋 후 ②의 목록으로 파일 삭제 ⑤ **행 없는 파일 정리(리뷰 R6-1)**: 저장 루트의 파일 목록(최상위 `profile/` 네임스페이스 디렉터리 제외)을 `content_image.storage_key`와 `notice_attachment.storage_key` 합집합과 대조해, 어느 행에도 없는 파일을 삭제한다(업로드 중 강제 종료·롤백 시 삭제 실패로 남은 파일 — 공지 첨부의 기존 잔존 파일도 함께 회수됨). 앱이 정지돼 있어 진행 중 업로드와 경합하지 않는다 ⑥ 앱 기동. 파일을 DB보다 나중에 지우므로 중간 실패 시 남는 것은 "행 없는 파일"뿐이고, 다음 회수의 ⑤단계가 정리한다.
- 이 절차의 대조 쿼리·명령은 `docs/deployment.md`에 그대로 실행 가능한 형태로 적고, 구현 단계에서 dev 환경에 한 번 실제로 수행해 검증한다.
- 삭제된 공지의 이미지는 공지 복원 기능이 없으므로(소프트 삭제는 API로 되돌릴 수 없음) 회수해도 사용자 기능 손실이 없다.

### 쟁점 15. 앱 롤백 — **V25 이후 구버전 롤백은 공지 쓰기 동결이 조건** (리뷰 R1-1)

구버전 앱은 본문을 평문으로 저장하고 참조를 갱신하지 않는다. 롤백 중 쓰인 평문은 재배포 때 다시 변환되지 않아 HTML로 재해석되고(예: 본문의 `<h2>` 글자가 제목이 됨 — sanitizer가 막는 건 XSS뿐 형식 혼동은 못 막음), 지운 이미지의 참조가 남아 계속 공개될 수 있다.

| 선택지 | 장점 | 단점 |
|---|---|---|
| 형식 컬럼(`content_format`) 추가로 평문·HTML 구분 | 구버전이 새로 만든 행은 기본값 'TEXT'로 식별 | 구버전이 **기존 HTML 행을 수정**하는 경우는 여전히 구분 불가 — 복잡도 대비 불완전 |
| **롤백 중 공지 쓰기 동결 + 재배포 전 점검(운영 절차)** | 단순, 롤백 자체가 드문 비상 절차 | 절차 준수에 의존 |

**결정**: 운영 절차. `docs/migration-guide.md`(롤백 절)와 `docs/deployment.md`에 "V25 적용 후 구버전으로 롤백하면 공지 생성·수정 동결, 동결이 깨졌다면 재배포 전 `update_date > 롤백 시각`인 공지를 확인해 본문 재변환·재저장" 절차를 기록한다. 데이터 손실은 없다(MEDIUMTEXT 확장은 구버전과 호환).

## 설계 제약 (프로젝트·UI)

- 관리 화면은 jQuery 3.6 + Bootstrap 4.6 SB Admin 2, 정적 라이브러리는 `/vendor/**` 로컬. 빌드 도구(npm) 없음 → Quill은 배포본 파일을 그대로 넣는다.
- CSP 헤더가 없으므로 편집기 동작에 CSP 조정이 필요 없다(CSP 도입은 별도 범위).
- 공개 페이지는 `PublicWebExceptionAdvice`(HTML 500)·404 흡수 정책, 관리 API는 `GlobalApiExceptionHandler` JSON 규약.
- 시각은 주입된 `Clock`, 대소문자 변환은 `Locale.ROOT`.

## 변경 범위

### 의존성 (사전 승인 필요)
- `build.gradle`: `implementation 'org.jsoup:jsoup:1.23.2'`
- `src/main/resources/static/vendor/quill/`: `quill.js`(2.0.3 배포본), `quill.snow.css`, `LICENSE`

### 스키마 (Flyway, 사전 고지 대상)
- `V23__expand_notice_content.sql`(DDL): `notice.content` TEXT → MEDIUMTEXT
- `V24__create_content_image.sql`(DDL): `content_image(id, storage_key UNIQUE, content_type, file_size, uploader_id, create_date)`, `content_image_ref(owner_type varchar(30), owner_id bigint, image_id bigint, PK(owner_type, owner_id, image_id), KEY(image_id), FK image_id → content_image RESTRICT)`, `content_image_usage(id PK, total_bytes bigint, total_count bigint)`
- `V25__convert_notice_content_to_html.sql`(DML): 카운터 행 멱등 삽입 + 본문 변환

### 인가 정책 (사전 승인 필요)
- `SecurityConfig`: `GET`·`HEAD /content-images/*` `permitAll`, 같은 경로 그 외 메서드 `denyAll`
- 업로드 API는 기존 `/admin/api/notices/**` 게이트 안 — 경로 규칙 추가 없음

### 코드
- 신규 `com.cms.common.html.HtmlContentSanitizer`(정책·`sanitize`·이미지 ID 추출·보이는 텍스트 길이)
- 신규 `com.cms.common.image.ImageFileValidator`(공용, APNG 검사 포함), 수정 `ProfileImageValidator`(위임)
- 신규 `com.cms.admin.contentimage`(`domain`·`repository`·`service/ContentImageService`(업로드·상한·참조 교체·공개 판정)·`controller`(업로드 API)·`dto`)
- 신규 `com.cms.publicweb.contentimage`(공개 다운로드 컨트롤러·서비스)
- 수정 `Notice`(MEDIUMTEXT), `NoticeService`(sanitize·길이 규칙·참조 교체), `NoticeCreateRequest`/`UpdateRequest`(검증 교체), `NoticeResponse`·`PublicNoticeDetail`(출력 sanitize), `AdminActionTypes`(`CONTENT_IMAGE_UPLOAD`)
- 수정 `application.yml`: `cms.content-image.*` 상한 2종, 레이트리밋 규칙 `content-image`(`/content-images/*`, GET·HEAD, 240/60초)
- 수정 템플릿·CSS: `admin/notice/manage.html`(Quill·업로드 경합 처리·상세 innerHTML), `public/notice/detail.html`(`th:utext`), `static/css/public/notice.css`, 관리 상세 보기 스타일
- 수정 테스트: `PublicNoticeTemplateConventionTest`(허용 1곳), 기존 공지 테스트 중 평문 본문 단정이 있는 것
- 문서: `docs/migration-guide.md`(V23·V24 실패 복구, V25 롤백 주의), `docs/deployment.md`(롤백 시 쓰기 동결·고아 이미지 수동 회수)

## 작업 단계

1. 의존성·vendor 추가(승인 후) → `./gradlew compileJava`
1-1. **Quill 스파이크**(정적 HTML 1장, Playwright로 실측): 공백·탭·앞뒤 공백의 `getSemanticHTML`↔`clipboard.convert` 왕복, 끝 빈 문단 보존(R5-1 보정 포함), 제목 단축 입력, uploader handler·IMG matcher API, `getSemanticHTML`의 목록·링크 출력 형태 → 쟁점 3·3-1 규칙 확정(어긋나면 보고)
2. `HtmlContentSanitizer` + XSS·멱등·이미지 src(범위 밖 ID 포함)·제목 매핑·공백 정규화·텍스트 길이 단위 테스트 **먼저**
3. `ImageFileValidator` 추출(+APNG) + `ProfileImageValidator` 위임 → 기존 프로필 테스트 통과 확인
4. V23·V24 → 엔티티·리포지토리 → `ContentImageService`(업로드·카운터 상한·참조 교체·공개 판정) → 업로드 API → 공개 다운로드 → `SecurityConfig`·레이트리밋
5. `NoticeService`·DTO 연결(sanitize·길이·참조) → V25 변환 + 업그레이드 경로 테스트
6. 템플릿(Quill·상태 관리·출력 전환·CSS) → 컨벤션 테스트 갱신
7. `./gradlew test` 전체 → Playwright 실기 검증
8. 운영 문서(마이그레이션 실패 복구·롤백·수동 회수)

## 완료 기준

- [ ] `./gradlew test` 통과, CI 통과
- [ ] XSS 벡터(`<script>`, `<img src=x onerror>`, `javascript:`·`JaVaScRiPt:`·엔티티 인코딩 `javascript&colon;` 링크, `<svg onload>`, `<iframe>`, `style`·`class`·`data-*` 속성, `data:` 이미지, 외부 이미지 URL, 범위 밖 이미지 ID)가 저장·출력 양쪽에서 제거됨, sanitize 멱등
- [ ] 변환 업그레이드 경로(Testcontainers, V22까지 적용 → 평문 삽입 → 최신): `&`·`<`·`"` 포함 텍스트, 연속 공백·탭, 연속 빈 줄, `\r\n`, **줄바꿈 9,998개짜리 10,000자 반례**가 모두 성공 변환되고, 공개 출력에서 같은 텍스트로 보이며 원래 `<`·`&`가 마크업으로 해석되지 않음
- [ ] 본문 이미지: 허용 외 형식·5MB 초과·4096px 초과·애니메이션 GIF·**APNG** 400, 바이트·개수 상한 초과 409(동시 업로드에서도 상한 정확 — 동시성 테스트), CREATE·UPDATE 둘 다 없는 MANAGER 403, 업로드 롤백 시 파일·카운터 원복
- [ ] 프로필 이미지 기존 테스트 통과(+APNG·큰 논리 화면 GIF·화면 밖 프레임 GIF 거부 추가)
- [ ] `/content-images/{id}`: 공개 공지 참조 200, 비공개·삭제 공지만 참조·미참조·없는 ID·비숫자·범위 밖 ID 404(익명), `NOTICE` READ 보유자는 비공개 공지 이미지 200, 비-GET/HEAD 거부
- [ ] 본문 길이: 보이는 텍스트(쟁점 5 규칙) 상한 초과 — **공백 10,001개 사이에 낀 문단 포함** — ·결과 200,000바이트 초과 400, 이미지만 있는 본문 허용, 빈 본문(`&nbsp;`만 포함) 400, 기존 10,000자 공지의 변환 후 재저장 허용
- [ ] `contentFormat` 누락·다른 값 + `content` 있는 요청 400(새로고침 안내), `useYn`만 바꾸는 PATCH는 표식 없이 허용
- [ ] 공백 왕복: 연속 공백·탭·문단 앞뒤 공백이 편집기 열기→저장→재열기 후 같고, 기존 평문(변환본)도 같다
- [ ] 끝 빈 문단: 끝에 빈 문단 1개·3개가 있는 본문을 3회 반복 열기→저장해도 빈 문단 수가 같다
- [ ] 제목: H1·H4~H6 붙여넣기가 편집기·서버 모두에서 2·3단계로 바뀌어 저장·재열기 결과가 같다
- [ ] Playwright: 편집기로 서식(제목·굵게·목록·인용·링크)·이미지 포함 공지 작성 → 공개 화면 표시 일치 → `<img src=x onerror=alert(1)>`·외부 이미지 HTML 붙여넣기 미반영 → 업로드 중 저장 비활성 → 공지 A에서 업로드 중 B로 전환 시 B에 삽입 안 됨 → 업로드 중 앞쪽 글 입력·삭제 후에도 원래 자리에 삽입, 여러 파일은 선택 순서대로 → 다른 공지 열기 시 내용 교체(누적 안 됨) → 기존 공지(연속 공백·빈 줄) 표시·편집 후 재저장 회귀

## 리스크

| 리스크 | 대응 |
|---|---|
| sanitizer 정책 누락으로 저장형 XSS | 허용 목록(기본 거부) + 저장·출력 이중 sanitize + XSS 벡터 테스트 |
| 편집기·sanitizer 허용 서식 불일치로 서식이 저장 시 사라짐 | `formats`=툴바=허용 목록 1:1, Playwright 서식별 왕복 |
| 업로드 경합으로 이미지 누락·다른 공지에 삽입 | 세대 토큰·업로드 중 저장 차단·늦은 응답 폐기 |
| DDL 마이그레이션 중단 | 실패 복구 절차 문서화(V20·V21 형식) |
| 앱 롤백 시 형식 혼동·참조 불일치 | 쓰기 동결 + 재배포 전 점검 절차(쟁점 15) |
| 고아 이미지 누적 | 정확한 바이트·개수 상한 + 수동 회수 절차, ⑧에서 자동화 |
| 미리보기 권한 우회(`NOTICE` READ 없는 MANAGER) | 공개 판정 + READ 판정 둘 다 실패 시 404 |

## 개정 이력

- v1 (2026-10-07): 최초 작성
- v2 (2026-10-07) 변경 — 적대적 리뷰 1라운드(codex) 10건 전부 수용:
  - R1-1 롤백 영향: 쟁점 15 신설 — 롤백 중 공지 쓰기 동결 + 재배포 전 점검 절차(형식 컬럼안은 구버전의 기존 HTML 행 수정을 구분 못 해 기각)
  - R1-2 업로드 경합: 쟁점 13에 세대 토큰·삽입 위치·늦은 응답 폐기·업로드 중 저장 차단
  - R1-3 `dangerouslyPasteHTML(0,…)`는 삽입: `setContents(clipboard.convert(...), 'silent')` 교체 + `history.clear()`
  - R1-4 붙여넣기 정책: 편집기 `formats` 제한(제목 2·3단계만), IMG clipboard matcher
  - R1-5 공백 보존: 공개·관리 보기 `pre-wrap` 유지 + 문단 여백 0(편집기와 동일), 회귀 기준 추가
  - R1-6 APNG: `acTL` 청크 검사 추가(프로필에도 적용 — 기존 정책 구멍 보정)
  - R1-7·8 소프트 상한: 카운터 행 비관적 락으로 바이트·개수 상한 정확 집행, 수동 회수 절차 문서화
  - R1-9 변환 실패: `notice.content` MEDIUMTEXT 확장(V23)으로 실패 원인 제거, 반례 테스트 추가, 쟁점 5의 "스키마 무변경" 철회, 마이그레이션을 V23(DDL)·V24(DDL)·V25(DML)로 분리
  - R1-10 ID 범위: `Long` 파싱 실패 시 img 제거·다운로드 404
- v3 (2026-10-07) 변경 — 적대적 리뷰 2라운드(codex) 7건 전부 수용:
  - R2-1 Quill이 HTML 로딩 시 공백을 접음: 쟁점 3-1 신설 — 보존할 공백은 `&nbsp;`(탭은 4칸), V25·서버 정규화·Quill 출력이 같은 규칙, 구현 1-1단계 스파이크로 실측 확정(실패 시 보고)
  - R2-2 배포 전 열린 평문 화면: 요청에 `contentFormat: "HTML"` 필수, 누락 시 400 새로고침 안내
  - R2-3 수동 회수 경합: 앱 정지 유지보수 구간 전제, DB 정리 커밋 후 파일 삭제
  - R2-4 삭제 공지 이미지 회수 불가: 회수 대상을 "모든 참조가 삭제 공지"까지 확장, 참조 행 선삭제
  - R2-5 `formats`는 이름만 제한: H1→2·H4~H6→3 매핑을 편집기 matcher와 서버 sanitizer 양쪽에 적용
  - R2-6 같은 세대 내 위치 이동: `transformPosition`으로 대기 위치 갱신, 다중 파일 순차 업로드·삽입
  - R2-7 `text()` 정규화: DOM 순회 길이 규칙(문자 그대로 + 블록·`br`당 1), 경계 1자 여유
- v4 (2026-10-07) 변경 — 적대적 리뷰 3라운드(codex) 3건 전부 수용:
  - R3-1 빈 줄 이중 계산: 블록 마지막 자식 `br`은 세지 않음(블록 종료와 같은 줄바꿈), 반례를 수정 API 재저장까지 테스트
  - R3-2 탭 4칸 확장이 상한 충돌: 탭→`&nbsp;` 1개(글자 수 1:1)로 변경, 최악 변환 크기 109,983바이트 < 200,000 명시, 탭·공백 반례 추가
  - R3-3 GIF 논리 화면 우회: 스트림 메타데이터 화면 크기 상한 + 프레임 범위 검사, fixture 추가(프로필에도 적용)
- v5 (2026-10-07) 변경 — 적대적 리뷰 4라운드(codex) 1건 수용:
  - R4-1 `pre` 선행 LF 유실: 보정 직렬화 대신 **코드 블록 서식을 범위에서 제외**(로드맵 확정 서식에 없음), `pre` 관련 예외 규칙 삭제
- v6 (2026-10-07) 변경 — 적대적 리뷰 5라운드(codex) 1건 수용:
  - R5-1 `clipboard.convert`가 마지막 빈 문단을 버림: 로딩 시 `<p><br></p>` 덧붙여 변환(Quill 초기 로딩과 같은 보정), 반복 재편집 회귀 기준·스파이크 항목 추가
  - 리뷰 루프 5라운드 상한 도달 — 5라운드 지적은 1건이며 반영 완료, 미해결 지적 없음. 라운드별 지적 수 10 → 7 → 3 → 1 → 1로 수렴(후반은 Quill 왕복 세부 — 구현 1-1단계 스파이크 게이트가 같은 계열을 실측으로 잡는다)
- v7 (2026-10-07) 변경 — 사용자 요청 추가 리뷰 6라운드(codex) 1건 수용:
  - R6-1 행 없는 잔존 파일이 카운터·회수 절차에서 빠짐: 카운터 보장 범위를 "DB 등록 이미지"로 명시, 수동 회수에 파일·DB storageKey 대조 단계 추가(`content_image`·`notice_attachment` 합집합 보존, `profile/` 제외), dev 실제 수행 검증
- 7라운드(codex, 사용자 요청 추가 리뷰): **ship 판정** — 새 실질 지적 없음. 총 7라운드, 지적 23건 전부 수용(반박·결정 대기 0건)

## 구현·검증 결과 (2026-10-07)

### Context

계획 v7(적대적 리뷰 7라운드, 지적 23건 전부 수용 → ship)을 사용자 승인(2026-10-07) 후 브랜치 `feat/html-editor`에서 구현했다. 승인 범위: 새 의존성 jsoup 1.23.2·Quill 2.0.3, 스키마 V23~V25(기존 공지 본문 일괄 변환 포함), `GET/HEAD /content-images/*` 무인증 공개, 프로필 이미지 검증 강화.

### 핵심 확정 사항 (구현 중 계획과 달라진 점 포함)

1. **Quill 스파이크 게이트 통과(1-1단계)** — 정적 페이지 + Playwright로 실측. 공백 저장 형식 규칙(쟁점 3-1)은 성립했고, 실측에 따라 세 가지를 확정했다.
   - `getSemanticHTML()`은 **모든** 공백을 `&nbsp;`로 내보낸다(단어 사이 줄바꿈 불가) → 계획의 대안대로 **공백 정규화를 서버 sanitizer로 일원화**(클라이언트 정규화 없음).
   - 빈 줄이 `<p></p>`(높이 0)로 나온다 → sanitizer가 **빈 블록에 `<br>` 보충**(계획에 없던 정규화 1건 추가).
   - V25는 쟁점 11의 대안 경로를 택했다 — **이스케이프·문단 감싸기·빈 문단만 변환**하고 공백·탭 정규화는 저장·출력 시 sanitize가 같은 규칙으로 처리(저장 HTML의 공백은 다음 저장 때 정규화된다).
   - 그 밖에 확인: `formats`로 `pre`·`code`·`style`·`class`가 편집기 단계에서 걸러짐, 링크는 Quill이 `rel="noopener noreferrer" target="_blank"`를 붙임, `#` 제목 단축 입력은 없음(바인딩 조치 불필요), uploader 기본 MIME에 gif가 없음(설정으로 추가), H1·H4·H6 붙여넣기는 그대로 들어옴(매처 필요 — 계획대로).
2. **실기 검증 중 발견·반영 1건**: 붙여넣은 `<script>`·`<style>`의 **내용**이 Quill 기본 매처에서 일반 텍스트로 들어왔다(실행은 안 됨). 서버 sanitizer는 내용째 버리므로 편집기 결과와 달랐다 → 편집기 clipboard 매처에 `SCRIPT`·`STYLE` → 빈 Delta 추가.
3. **저장 진행 표시의 토큰화**: 업로드 busy와 저장 진행을 함께 반영하려고 저장 진행을 불리언이 아니라 요청 identity 토큰(`saveInFlightToken`)으로 관리 — 저장 중 다른 공지로 전환하면 기존 finally가 해제를 건너뛰어 저장 버튼이 영구 비활성화되는 경로를 막는다.
4. **응답 DTO 테스트 계약**: 공개 상세는 `th:utext`이므로 방어선은 `PublicNoticeDetail.from`의 sanitize다. 빌더로 원문을 넣던 기존 XSS 슬라이스 테스트를 `from()` 경유로 바꿨다(빌더 직접 생성은 계약 밖).
5. 나머지는 계획 그대로(엔드포인트·상한·권한 재판정·카운터 행 잠금·참조 교체·공개 판정·이미지 검증).

### 구현 파일

| 구분 | 파일 |
|---|---|
| 의존성 | `build.gradle`(jsoup 1.23.2), `static/vendor/quill/`(quill.js·quill.snow.css·LICENSE·quill.js.LICENSE.txt — npm 2.0.3 tarball, 레지스트리 sha512 무결성 일치 확인) |
| 마이그레이션 | `V23__expand_notice_content.sql`, `V24__create_content_image.sql`, `V25__convert_notice_content_to_html.sql` |
| 공통 | 신규 `common/html/HtmlContentSanitizer`·`SanitizedHtml`, 신규 `common/image/ImageFileValidator`, 수정 `common/storage/FileStorageTransactionSupport`(루트 영역 `deleteOnRollback` 오버로드) |
| 본문 이미지 | 신규 `admin/contentimage/`(`domain` 4·`repository` 3·`service/ContentImageService`·`controller/NoticeContentImageController`·`dto`·`config` 2), 신규 `publicweb/contentimage/`(컨트롤러·서비스·DTO 2) |
| 공지 | `Notice`(MEDIUMTEXT), `NoticeService`(sanitize·길이·형식 표식·참조 교체), `NoticeCreateRequest`·`NoticeUpdateRequest`(`contentFormat`, `@MaxUtf8Bytes(200_000)`), `NoticeResponse`·`PublicNoticeDetail`(출력 sanitize) |
| 기타 코드 | `ProfileImageValidator`(위임), `AdminActionTypes`(`CONTENT_IMAGE_UPLOAD`), `SecurityConfig`(`/content-images/*`), `application.yml`(`cms.content-image.*`, 레이트리밋 `content-image`) |
| 화면 | 신규 `static/js/admin/notice-editor.js`, `admin/notice/manage.html`(편집기·업로드 경합·상세 innerHTML), `public/notice/detail.html`(`th:utext`), `static/css/public/notice.css`, `admin/log/manage.html`(라벨) |
| 테스트 | 신규 `HtmlContentSanitizerTest`(61), `ImageFileValidatorTest`(8), `ContentImageIntegrationTest`(11), `NoticeContentHtmlMigrationTest`(1, 반례 9종). 수정 `NoticeServiceTest`(+15 — 형식 표식·공백·길이·바이트·이미지만·참조 교체·출력 sanitize), `ProfileImageValidatorTest`(+2), `AdminPermissionMatrixIntegrationTest`(요청 본문), `PublicNoticeTemplateConventionTest`(허용 1곳), `PublicNoticeControllerTest`(XSS 테스트 `from()` 경유) |
| 문서 | 루트·`admin/notice`·`publicweb/notice`·`config`·`admin/member` `CLAUDE.md`, 신규 `admin/contentimage/CLAUDE.md`, `docs/migration-guide.md`(V23~V25), `docs/deployment.md`(환경변수·수동 회수), `docs/troubleshooting.md`(Quill 왕복) |

### 검증 결과

- **`./gradlew test` 전체 통과 — 1,490개, 실패 0**(Testcontainers MariaDB).
- 완료 기준 대조:
  - [x] XSS 벡터 16종(스크립트·`onerror`·`javascript:`/대소문자/엔티티 인코딩/앞 공백·`vbscript:`·`<svg onload>`·`<iframe>`·`<form>`·`<object>`·mXSS 형태·`style`/`class`/`data-*`·`data:`·외부 이미지) 저장·출력 제거, 멱등 10종(`HtmlContentSanitizerTest`)
  - [x] 변환 업그레이드 경로: `&`·`<`·`"`, 연속 공백·탭, 연속 빈 줄, CRLF/CR, 한글·이모지, 줄바꿈·탭·공백 9,998개 반례, `<` 10,000개 — 전부 변환 성공·같은 텍스트·저장 규칙 충족, `update_date` 보존, 카운터 시드(`NoticeContentHtmlMigrationTest`)
  - [x] 이미지: 비이미지·APNG·MIME 불일치·SVG·5MB 초과 400(카운터 불변), 개수·바이트 상한 409, **동시 업로드 2건이 남은 1칸을 다투면 정확히 1건 201·1건 409**, MANAGER는 CREATE 또는 UPDATE 보유 시만 업로드, 롤백 시 파일·행·카운터 원복(`ContentImageIntegrationTest`)
  - [x] 프로필 기존 테스트 통과 + APNG·큰 논리 화면 GIF 거부
  - [x] `/content-images/{id}`: 공개 공지 참조 200(인라인·`nosniff`·`no-store`), HEAD 본문 없음, 비노출·삭제·미참조·없는 ID·비숫자·0·`Long` 범위 밖 404, `NOTICE` READ 보유자 미리보기 200, 권한 없는 MANAGER 404, 본문에서 이미지 제거 시 참조 해제, POST·PUT 거부
  - [x] 본문 길이·형식: 10,001자 허용/공백으로 10,002자 400, 200,000바이트 초과 400, 이미지만 허용, 빈 본문 400, `contentFormat` 누락·`TEXT`·`html` 400, `useYn`만 PATCH는 표식 불필요(`NoticeServiceTest`)
  - [x] 공백 왕복·끝 빈 문단·제목 매핑: 스파이크(3회 반복 열기·저장 시 끝 빈 문단 3개 유지, `&nbsp;` 형식 왕복 보존, H1→2·H4~H6→3) + 실기
- **실기 검증(dev Docker, Playwright, 스크린샷 `.playwright-mcp/html-editor/` — git 무시 경로)**:
  1. dev DB 백업 후 앱 재빌드 → V23~V25 적용(0.246초). 일부러 넣은 레거시 평문(앞 공백·연속 공백·탭·빈 줄·`<b>`·`&`·`"`)이 공개 화면에서 그대로 보이고(01), 관리 상세 innerHTML(02)·편집기 Delta(03)에서도 같은 텍스트(탭은 1칸 — 설계대로).
  2. 레거시 공지 끝에 글을 덧붙여 저장 → DB가 정규화 형식(`&nbsp;` 규칙·`<p><br></p>`)으로 저장.
  3. 새 공지: 제목 2단계·목록 서식 + 툴바로 PNG 2장 다중 업로드 → 선택 순서대로 삽입, 업로드 중 저장 비활성·안내 표시. 위험 HTML 붙여넣기(H1·`onerror`·외부 이미지·인라인 스타일·`<script>`) → H1→2, H5→3, 이미지·스타일 제거, 스크립트 미실행. 저장 후 참조 2건·카운터·감사 로그(`CONTENT_IMAGE_UPLOAD` 2, `NOTICE_CREATE`) 확인, 공개 화면 서식·이미지 표시(05).
  4. 익명 접근: 이미지 200(`image/png`·`nosniff`·`no-store`), 없는 ID·비숫자 404, POST 403, 업로드 401. 공지 비노출 전환 → 이미지 GET·HEAD 404·공지 404, 관리자 미리보기 200.
  5. 경합(업로드 응답 2초 지연 주입): 대기 중 앞쪽에 "XYZ" 입력 → 원래 자리에 삽입("XYZ앞[3][4]뒤"), 2장 순서 유지, busy 해제 후 저장 활성. 업로드 대기 중 모달 닫고 다른 공지 편집 → 늦은 응답 폐기(다른 공지에 삽입 없음, 이미지는 미참조로 남음).
  6. 구 화면 요청(`contentFormat` 없음) → 400 "편집 화면이 오래되었습니다…".
  7. 회귀: 첨부 업로드·목록·공개 다운로드·삭제, 공개 목록, 활동 로그 라벨 정상.
  8. **수동 회수 절차(R6-1) dev 실제 수행**: 테스트 공지 소프트 삭제 + 잔존 파일 1개 생성 → 앱 정지·DB/볼륨 백업 → 대상 확정(삭제 공지만 참조 2·미참조 3 = 5건) → 트랜잭션 정리·카운터 재계산(0/0) → 파일 삭제 → 파일·DB 대조로 잔존 파일 정확히 1건 검출·삭제 → 재기동 후 이미지 404. 절차를 `docs/deployment.md`에 그대로 기록.
  9. 원복: 실기 검증 공지 2건 삭제, 이미지·카운터 0. dev DB는 V25 적용 상태로 남는다(변환 전 백업은 세션 scratchpad `dev-cms-before-v23.sql`).

### 이슈

- 실기 검증 중 콘솔 경고 1건(Bootstrap 4 모달을 스크립트로 닫을 때 포커스가 편집기에 남아 `aria-hidden` 경고) — 테스트 조작으로 생긴 것이고 기존 입력 필드에서도 같은 조건이면 난다. 이번 범위 밖.
- 붙여넣은 블록 서식이 커서가 있던 줄에 입혀지는 것(예: 목록 줄에 H1을 붙이면 그 줄이 제목이 됨)은 Quill 붙여넣기 의미론이며 편집기 화면과 저장 결과가 같다(WYSIWYG 일치).

### 후속

- 다른 콘텐츠 도메인(①-1 게시판·⑤ 정적 페이지·⑥ FAQ·⑦ 팝업)에 편집기를 붙일 때: 업로드 엔드포인트·참조 owner_type·공개 판정 쿼리를 도메인별로 추가하거나 공용화한다(지금은 공지 전용 — 쟁점 10).
- 미참조·삭제 공지 이미지의 자동 정리와 사용처 화면: 로드맵 ⑧ 미디어 라이브러리.
- 로드맵 ⓪ 완료 반영은 PR 머지 후 `/updateRoadmap`.
