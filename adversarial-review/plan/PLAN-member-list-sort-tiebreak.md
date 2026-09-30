# PLAN — 회원 목록 정렬 id 보조 정렬(tie-breaker) 추가 (로드맵 L-01)

> 상태: ✅ 구현·검증 완료 (2026-09-30, 커밋·PR 전) — 계획 v2 (적대적 리뷰 1라운드 ship — codex, 비차단 3건 전부 수용)
> 출처: `adversarial-review/project-direction-roadmap.md` "우선순위에서 밀린 감사 항목" 표의 L-01
> 유형: fix · 스키마 변경 없음 · 인가 정책 변경 없음 · 신규 의존성 없음

## Context

`MemberRepositoryImpl.toOrderSpecifiers(Sort)`는 요청된 정렬 키(허용 필드)만 그대로 `ORDER BY`로 만들고, 유효한 정렬이 하나도 없을 때만 `id desc`를 기본값으로 쓴다. 따라서 `sort=userName,asc`처럼 **동률이 가능한 키만** 지정하면 동률 행 사이 순서가 DB 실행 계획에 맡겨져, 페이지 경계(offset/limit)를 넘나들 때 같은 행이 두 페이지에 나오거나 한 행이 빠질 수 있다.

같은 구조의 `NoticeRepositoryImpl`은 이미 "요청 정렬에 `id`가 없으면 마지막에 `id desc`를 붙인다"(`NoticeRepositoryImpl.java:72-94`), `AdminActionLogRepositoryImpl`은 항상 `id desc`를 붙인다(`:83-105`). 회원 목록만 빠져 있다.

### 정찰에서 확정한 사실

| 사실 | 근거 |
|---|---|
| 변경 대상은 `toOrderSpecifiers` 한 메서드. 호출처는 `searchAdminMembers`의 `orderBy` 한 곳 | `MemberRepositoryImpl.java:65,84` |
| 화면(관리자 회원 목록)은 `sort` 파라미터를 보내지 않는다 → 기본값 `id desc`(유일 키)로 동작 | `src/main/resources` 의 js·html grep 결과 회원 관련 `sort=` 없음, 컨트롤러는 `@PageableDefault(size = 20)`만 지정(`AdminMemberController.java:63`) |
| 즉 결함이 도달 가능한 경로는 **API 직접 호출(Swagger 등)의 `sort=` 지정**뿐이다 | 위 두 항목 |
| 정렬 화이트리스트: `id, userId, userName, email, userType, status, createDate, updateDate`. `userType`·`status`(enum)는 `buildOrderSpecifier`가 null을 반환해 무시됨 | `MemberRepositoryImpl.java:29-31,109-120` |
| `userId`·`email`은 유니크 컬럼이라 동률이 없고, `userName`·`createDate`·`updateDate`는 동률 가능(`enum` 필드는 정렬 미지원) | `V1__init_schema.sql:33-34` (`uk_member_user_id`, `uk_member_email`). 그래도 "id를 요청하지 않은 모든 정렬에 일괄 적용"하는 결정은 유지한다 — 키별 예외 목록을 코드에 두는 것보다 규칙이 하나인 편이 단순하다 |
| 기존 단위 테스트 `MemberRepositoryImplSortTest`는 `hasSize(1)`/`hasSize(2)`로 지정 정렬 결과의 **개수를 단언**한다 → 보조 정렬 추가 시 갱신 필요 | `MemberRepositoryImplSortTest.java:56,91,139` |
| 회원 목록 검색을 실제 DB로 검증하는 테스트는 없다(서비스 테스트는 리포지토리를 목킹) | `searchAdminMembers` grep: `AdminMemberServiceTest`(mock) 한 곳뿐 |
| 슬라이스 테스트 선례: `NoticeRepositoryDataJpaTest`(`@DataJpaTest` + `@Import(QuerydslConfig)` + `MariaDbContainerSupport`, 트랜잭션 롤백으로 데이터 미잔존) | `NoticeRepositoryDataJpaTest.java:31-35` |

## 핵심 쟁점과 결정

### 쟁점 1 — 보조 정렬을 언제 붙일 것인가

| 선택지 | 내용 | 평가 |
|---|---|---|
| A | 항상 `id desc`를 마지막에 추가 (`AdminActionLog` 방식) | 단순하나 `sort=id,asc`처럼 이미 id를 요청해도 `ORDER BY id ASC, id DESC`가 나가 무의미한 절이 생긴다 |
| **B (채택)** | 요청 정렬에 `id`가 없을 때만 마지막에 `id desc` 추가 (`Notice` 방식) | 동률 해소 목적에 정확히 맞고 중복 절이 없다. 유효한 정렬이 0개면 이 한 줄이 기존 기본값(`id desc`)을 그대로 겸한다 |
| C | 정렬 키를 화이트리스트에서 "유일 키(id·userId)"로 제한 | API 계약(허용 정렬 필드)을 줄이는 변경 — 요청 범위 밖 |

**결정: B.** 이유: 가장 가까운 형제 구현(`NoticeRepositoryImpl`)과 동일해 컨벤션이 하나로 모인다. 기존 `if (specifiers.isEmpty()) return {id desc}` 분기는 B의 규칙에 흡수되므로 **삭제**한다(분기가 남으면 규칙이 두 곳에 흩어진다).

### 쟁점 2 — 보조 정렬의 방향

| 선택지 | 평가 |
|---|---|
| 주 정렬 방향을 따라간다(asc면 id asc) | 화면 의미는 자연스러우나 형제 구현과 달라진다 |
| **`id desc` 고정 (채택)** | `Notice`·`AdminActionLog`·회원 기본값과 동일. 방향은 "결정적이기만 하면" 목적을 달성하며, 최신 가입 우선이 기본 UX와 일치 |

**결정: `id desc` 고정.** 이유: 목적은 순서를 **결정적**으로 만드는 것이지 특정 방향이 아니며, 프로젝트 3개 도메인이 같은 방향을 쓴다.

### 쟁점 3 — 테스트 전략

- **단위(`MemberRepositoryImplSortTest`)**: 생성되는 `OrderSpecifier` 목록을 단언한다. 기존 케이스의 개수 단언을 보조 정렬 포함 값으로 갱신하고, 신규 케이스를 추가한다(아래 표). 이 테스트가 회귀의 주 방어선이다(DB 실행 계획에 의존하지 않고 결정적).
- **DB 슬라이스(`MemberRepositoryDataJpaTest` 신규)**: 동률 `userName` 여러 건을 저장하고 `sort=userName,asc`로 페이지를 나눠 조회해 (1) 페이지 간 중복·누락 없음, (2) 동률 내 순서가 `id desc`임을 단언한다. 정렬 변환이 실제 SQL로 이어지는지를 확인하는 유일한 층이다.
  - 주의: MariaDB는 동률에서 삽입 순서(id asc)로 우연히 안정적일 수 있어 "중복·누락 없음"만으로는 결함을 못 잡을 수 있다. 그래서 (2) **동률 내 id desc 순서**를 명시 단언한다. 다만 보조 정렬을 제거한 DB가 우연히 같은 순서를 낼 가능성은 배제할 수 없으므로, **제거 변이를 확실히 잡는 근거는 단위 테스트(`OrderSpecifier` 전체 순서·대상·방향 단언)** 이고 DB 테스트는 실제 SQL 경로를 확인하는 보완 수단이다. 변이 실험은 두 테스트에 대해 각각 실행·기록하되 DB 테스트의 실패 여부를 일반 보장으로 표현하지 않는다.
- **보장 범위(명시)**: 이 변경이 보장하는 것은 "**조회 사이에 데이터가 변하지 않는 동안** 동률 행의 순서가 결정적"이라는 점뿐이다. offset/limit 방식이므로 페이지 요청 사이에 회원 생성·삭제·이름 변경이 끼면 중복·누락은 여전히 가능하다(예: 첫 페이지 `[5,4]` 후 같은 이름의 `6`이 추가되면 다음 페이지가 `[4,3]`). keyset 페이징 전환은 요청 범위 밖이라 하지 않는다.
- 과설계 경계: 멀티 스레드·대량 데이터 시나리오는 만들지 않는다. 동률 5건·페이지 크기 2로 충분하다.

| 단위 테스트 케이스 | 기대 |
|---|---|
| 정렬 미지정 | `[id desc]` (기존과 동일, 개수 1) |
| `id desc` 지정 | `[id desc]` — 보조 정렬 **중복 추가 없음**(개수 1) |
| `id asc` 지정 | `[id asc]` — 보조 정렬 추가 없음(개수 1) |
| `userName asc` | `[userName asc, id desc]` |
| `userName asc` + `createDate desc` | `[userName asc, createDate desc, id desc]` |
| `userName asc` + `id asc` (id가 뒤에 명시) | `[userName asc, id asc]` — id 요청이 있으므로 추가 없음 |
| 미허용 필드만 / enum(`userType`,`status`)만 | `[id desc]` (기존과 동일) |
| 허용+미허용 혼합 | `[허용 필드, id desc]` |

## 변경 범위

| 파일 | 변경 |
|---|---|
| `src/main/java/com/cms/admin/member/repository/MemberRepositoryImpl.java` | `toOrderSpecifiers` 보조 정렬 규칙 적용, Javadoc 갱신 |
| `src/test/java/com/cms/admin/member/repository/MemberRepositoryImplSortTest.java` | 개수 단언 갱신 + 신규 케이스 |
| `src/test/java/com/cms/admin/member/repository/MemberRepositoryDataJpaTest.java` | **신규** — 동률 페이징 결정성 |
| `adversarial-review/project-direction-roadmap.md` · `plan/README.md` | 완료 반영은 `/updateRoadmap`·인덱스 갱신 단계에서 (이 작업이 로드맵 파일을 직접 수정하지 않음) |

**변경하지 않는 것**: `AdminActionLogRepositoryImpl`(항상 추가 방식이라 `sort=id,asc` 시 중복 절이 생기나 무해 — 이번 범위 밖, 무관한 리팩터링 금지), `NoticeRepositoryImpl`, 컨트롤러·서비스·DTO·스키마·Flyway·`SecurityConfig`·화면·JS.

## 영향 범위 보고 (프로젝트 작업 방식 5·6항)

- **스키마/마이그레이션**: 없음. 인덱스 추가도 하지 않는다(관리자 계정 수는 소규모, `ORDER BY` 끝에 PK 한 컬럼이 붙는 것뿐).
- **API·화면**: 경로·파라미터·응답 스키마 불변. 화면은 `sort`를 보내지 않아 동작 불변. API로 `sort=` 지정 시 **동률 행의 상대 순서만** 결정적으로 바뀐다(기존엔 비결정).
- **인가 정책**: 변경 없음.

## 리스크

- 낮음. 쿼리 `ORDER BY` 끝에 PK가 한 컬럼 붙는다. 실행 계획 영향은 관리자 계정 규모에서 무시 가능.
- 기존 테스트가 개수를 단언하므로 갱신을 빠뜨리면 실패로 즉시 드러난다(조용한 회귀 아님).

## 완료 기준

- [ ] `sort`에 `id`가 없는 모든 유효 정렬 뒤에 `id desc`가 붙고, `id`를 요청한 경우 중복 추가가 없다(단위 테스트).
- [ ] (조회 사이 데이터 변경 없음을 전제로) 동률 `userName` 5건을 페이지 크기 2로 조회하면 3개 페이지 합이 정확히 5건·중복 0·누락 0이고 동률 내 순서는 `id desc`다(DB 슬라이스 테스트).
- [ ] 보조 정렬 제거 변이 시 단위 테스트가 실패한다(확정 요건). DB 테스트의 변이 결과는 실행해 있는 그대로 기록한다(실패 여부를 요건으로 삼지 않음).
- [ ] `./gradlew test` 전체 통과, 실서버 실기 검증(API `sort=userName,asc` 페이징 왕복 + 화면 회귀 없음) 기록.

## 개정 이력

- v1 (2026-09-30): 최초 작성.
- v2 변경 (2026-09-30, 1라운드 codex ship): (1) 수용 — 보장 범위를 "조회 사이 데이터 불변 동안의 동률 순서 결정성"으로 명시(offset/limit 특성, keyset 전환은 범위 밖), 완료 기준에 전제 추가. (2) 수용 — `email`은 `uk_member_email` 유니크라 동률 근거 삭제(V1 34행 확인), 일괄 적용 결정은 유지. (3) 수용 — 제거 변이를 확실히 잡는 주 방어선은 단위 테스트로 명시, DB 테스트는 보완으로 격하. 기각 항목 없음.

## 구현·검증 결과 (2026-09-30)

### 핵심 확정 사항
- 계획 v2 그대로 구현했다. 계획과 달라진 결정 없음.
- 규칙: 요청 정렬에 `id`가 없을 때만 마지막에 `id desc`를 추가한다(`Notice`와 동일). 기존 "유효한 정렬 0개면 `id desc`" 분기는 이 규칙에 흡수·삭제했다.

### 구현 파일
| 파일 | 변경 |
|---|---|
| `src/main/java/com/cms/admin/member/repository/MemberRepositoryImpl.java` | `toOrderSpecifiers` 보조 정렬 규칙 적용, Javadoc에 보장 범위 명시 |
| `src/test/java/com/cms/admin/member/repository/MemberRepositoryImplSortTest.java` | 지정 정렬 5건의 개수·보조 정렬 단언 갱신 + 신규 2건(`id asc`, id가 뒤에 명시된 다중 정렬) + `assertIdDescTieBreaker` 헬퍼 |
| `src/test/java/com/cms/admin/member/repository/MemberRepositoryDataJpaTest.java` | **신규** — Testcontainers MariaDB, 동률 `userName` 5건 페이지 크기 2 조회(중복·누락 0, 동률 내 `id desc`) + 정렬 미지정 기본값 유지 |

### 검증 결과
- **단위·슬라이스**: `com.cms.admin.member.repository.*` 16개 통과. 실제 SQL 확인: `order by m1_0.user_name, m1_0.id desc` (정렬 미지정은 `order by m1_0.id desc`).
- **변이 실험**(`specifiers.add(m.id.desc())` 임시 주석 처리 → 원복 확인): 단위 테스트 9개 실패, DB 슬라이스 2개 실패(16개 중 11개 실패). 계획대로 확정 요건은 단위 테스트의 실패이며, DB 테스트의 실패는 이번 실행의 관측 결과일 뿐 일반 보장으로 삼지 않는다.
- **전체 `./gradlew cleanTest test`**: 829개(이전 825 + 신규 4), 실패 0·에러 0, 스킵 3(기존 Windows 심볼릭 링크 테스트).
- **실기 검증**(dev 컨테이너 `cms-app-dev` 재빌드, 8080): 동률 `userName` 회원 5건(id 594~598)을 DB에 임시 삽입 후 `GET /admin/api/members?sort=userName,asc&size=2` 3페이지 → `[598,597] [596,595] [594]`(중복·누락 없음, 동률 내 id 내림차순). 정렬 미지정 `598…594`, `sort=id,asc` `594…598`, `sort=email,desc` `598…594` 정상. 비로그인 목록 조회 401. 관리자 조회 화면(`/admin/member/manage`) 정상 렌더·콘솔 오류 0(스크린샷 확인). 임시 행 삭제 후 잔여 0건·전체 회원 수 5명(작업 전과 동일) 확인.
  - 참고: 실기 중 한글 `userName` 검색어가 Windows Git Bash `curl` 인자 인코딩 때문에 0건으로 조회돼(코드 문제로 단정하지 않음) ASCII 마커로 바꿔 재검증했다. 이 단계에서 만든 한글 마커 행도 삭제했다.
  - 부수 효과: 관리자 로그인 2회(curl·Playwright)로 `visit_log`에 방문 2건이 남는다(대시보드 방문자 통계에 반영). 원복 대상이 아니라 그대로 두었다.

### 이슈
- 없음(제품 코드 결함·환경 이슈 없음).

### 후속 / 범위 밖
- offset/limit 페이징이라 **조회 사이 데이터 변경 시 중복·누락은 여전히 가능**하다(keyset 페이징 전환은 별도 과제).
- `AdminActionLogRepositoryImpl`은 "항상 추가" 방식이라 `sort=id,asc`에서 `id asc, id desc` 중복 절이 생기나 무해해 손대지 않았다.
- 로드맵 완료 반영(L-01 표 항목)은 `/updateRoadmap`이 담당한다.
