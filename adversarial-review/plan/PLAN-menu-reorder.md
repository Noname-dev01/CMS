# PLAN — 메뉴 순서 재조정 API + 트리 드래그 앤 드롭 (같은 부모 안)

> 상태: ✅ 구현·검증 완료 (2026-09-30, 커밋·PR 전) — 계획 v4 (적대적 리뷰 4라운드: v3 3라운드 ship → 사용자 요청으로 `scope` 방식 재설계 → v4 4라운드 ship)
> ⚠️ **2026-10-01 대체됨**: 이 계획의 API·화면은 `PLAN-menu-structure-apply.md`(PR C)에서 삭제되고 "드래그 초안 + [반영] 일괄 반영"으로 대체됐다. 현행 계약은 그 계획서와 `menu/CLAUDE.md`를 따른다. 이 문서는 설계 이력으로만 남긴다.
> 출처: `adversarial-review/menu-management-plan.md` "알려진 한계 — 순서(ord) 재조정"(196행)
> 유형: feat · 스키마 변경 없음 · 인가 정책 변경 없음 · 신규 의존성 없음
> 범위 밖(별도 후속 PR): **부모 이동**(`PATCH /admin/api/menus/{id}/parent`, 깊이·순환 검증 포함) — 이 PR 완료 후 사용자에게 진행 여부를 질문한다.

## Context

메뉴 순서는 지금 (1) 생성 시 자동 배치(`max(ord)+1`)와 (2) 메뉴 하나씩 `PATCH /admin/api/menus/{id}`로 `ord`를 고치는 것만 가능하다. 형제를 한 번에 재정렬하는 API도, 드래그로 옮기는 화면도 없다(`reorder`·`dnd` 코드 0건, 트리는 `plugins: ['types']`만 사용). 이 계획은 **같은 부모 아래 형제들의 순서를 한 번에 확정하는 API**와 **그것을 호출하는 드래그 앤 드롭**을 추가한다.

### 정찰에서 확정한 사실

| 사실 | 근거 |
|---|---|
| 메뉴 API는 컨트롤러 `@PreAuthorize("hasRole('ADMIN')")` + `SecurityConfig`의 `/admin/**` → `hasRole("ADMIN")`이 이중으로 커버한다. 새 경로(`/admin/api/menus/order`)도 이 규칙 안이라 **인가 정책 변경 없음** | `MenuController.java`, `SecurityConfig.java:71` |
| **API 컨벤션은 URI에 동사를 금지**한다(명사·복수형·소문자). 원본 계획서의 `reorder`는 예시("등")였고 그대로 쓰면 규칙 위반 | `api-conventions` 스킬 "URI 규칙" |
| `menu` 테이블은 **PK 외 인덱스가 없다**(`up_menu_no` 인덱스·FK 없음) | `V1__init_schema.sql:37-50` |
| 그래서 `WHERE up_menu_no = ? FOR UPDATE` 같은 집합 조건 잠금은 전체 행 스캔이 되어, REPEATABLE READ(MariaDB 기본)에서 **테이블 전체 행(및 갭)을 잠근다** → 다른 부모의 행까지 잠가 기존 잠금 경로와 데드락 사이클을 만들 수 있다 | InnoDB 잠금 규칙(비인덱스 조건은 스캔한 모든 행을 잠금) — 아래 결정 2에서 우회 |
| `Menu` 엔티티에는 `@DynamicUpdate`가 **없다** → 더티체킹 시 **전체 컬럼 UPDATE**. 잠금 전에 엔티티를 영속성 컨텍스트에 올리면 오래된 값으로 다른 컬럼(이름·`useYn`)을 덮어쓰는 lost update가 난다(`AdminMemberService.updateMyInfo` 행 잠금 누락과 같은 결함군, 감사 H-02·M-04) | `Menu.java`, `menu/CLAUDE.md` |
| 기존 잠금 그래프: 일반 수정 = 대상 행 / 재활성화 = **자식→부모** / 비활성화 = 부모(대상) 행, 활성 자식 존재 조회는 비잠금 / 하위 생성 = 부모 행. "항상 부모→자식"이라고 주장할 수 없다 | `remediation-plan.md` PR 4 잠금 표, `MenuService.java` |
| 잠금 조회 `findByIdForUpdate(id)`는 PK 점 조회다. **존재하는 행**의 PK 일치 조회는 레코드 잠금이지만, 존재하지 않는 키 조회는 갭 잠금 경로가 있을 수 있다(MariaDB 구현) — "언제나 레코드 잠금만"이라고 단정하지 않고 통합 테스트로 잠금 범위를 실증한다(쟁점 7). 잠금 타임아웃·데드락은 이미 `GlobalApiExceptionHandler`가 `PessimisticLockingFailureException`으로 처리한다 | `MenuRepository.java`, `GlobalApiExceptionHandler.java:280-286` |
| **jstree 3.3.12는 드래그 중 위치 검사에만 `more.dnd=true`를 전달하고, 드롭 후 `move_node()` 내부 최종 검사에는 `{core:true, origin, is_multi, is_foreign}`만 전달한다(`dnd` 없음).** 최종 검사를 거부하면 `move_node.jstree` 이벤트가 발생하지 않는다 | 설치된 `jstree.min.js` 직접 확인(2026-09-30, 리뷰 1라운드 P1-1 검증) |
| 사이드바만 2단으로 그린다. **생성 API·관리 트리는 깊이 제한이 없다**(재귀 조립) → reorder는 깊이와 무관하게(임의 부모 아래) 동작해야 하고 검증 범위를 2단으로 줄이지 않는다 | `MenuService.createMenu`(깊이 검증 없음), `assembleTree` |
| `ord`는 nullable·비음수이며 **생성 API는 명시 `ord`를 허용하고**(생략 시에만 `max+1`), PATCH도 중복 `ord`를 허용한다 → 형제 안에 중복 `ord`가 정상 API로 생길 수 있다 | `MenuCreateRequest`, `MenuService.createMenu:55` |
| 감사 로그는 `@AdminActionLogged`의 `targetIdExpression`으로 **결과 객체 getter에서 `Long` 하나만** 추출한다. 상세 payload 컬럼은 없다 | `AdminActionLogAspect.extractTargetId` |
| 새 액션 타입은 `AdminActionTypes.ALL` + **활동 로그 화면 `admin/log/manage.html`의 `ACTION_TYPE_LABELS`** 둘 다 등록해야 하며 `AdminActionTypeSyncTest`·`AdminActionTypeLabelSyncTest`가 누락을 실패로 잡는다 | 두 테스트 파일, `log/manage.html:167-169` |
| 트리 화면은 기본으로 **활성 메뉴만**(`useYn=true`) 로드하고, "비활성 포함" 토글이 켜져야 `useYn=all`로 전체를 로드한다 | `manage.html:407-412` |
| 현재 CDN 빌드(`jstree 3.3.12 jstree.min.js`)에 **`dnd` 플러그인이 포함돼 있다**(`.plugins.dnd=` 확인). 신규 의존성 없음. 원본 계획서의 "dnd 플러그인 제거"는 화면에서 안 켰다는 뜻 | CDN 파일 직접 확인 |
| `instance.destroy()`가 컨테이너에 바인딩한 커스텀 이벤트를 모두 제거한다 → 트리를 다시 만들 때마다 이벤트를 재바인딩해야 한다(`select_node` 선례) | `docs/troubleshooting.md` "jstree destroy" 항목, `manage.html:471-473` |
| 사이드바는 요청마다 DB에서 메뉴를 읽는다(캐시 없음) → 순서 변경은 다음 페이지 로드부터 반영 | `AdminSidebarAdvice` |
| dev DB 메뉴 8건은 전부 2단, 형제 내 `ord`가 0부터 겹치지 않는다(마이그레이션 불필요) | dev DB 조회 |

## 핵심 쟁점과 결정

### 쟁점 1 — 엔드포인트 형태

| 선택지 | 평가 |
|---|---|
| A. `PATCH /admin/api/menus/reorder` (원안) | 동사 URI — 컨벤션 위반 |
| **B (채택). `PUT /admin/api/menus/order`, 본문 `{ upMenuNo, scope, menuNos }`** (`scope`는 쟁점 3, v4) | "형제 순서"라는 명사 자원을 **통째로 교체**하는 의미라 PUT이 자연스럽고 멱등하다. 최상위(`upMenuNo=null`)도 하나의 경로로 표현된다(루트 메뉴엔 부모 id가 없어 `/{id}/children/order` 형태는 루트를 못 담는다) |
| C. `PUT /admin/api/menus/{parentId}/children-order` + 루트용 별도 경로 | 경로 2개 — 과하다 |

**결정: B.** 동작: 요청한 `menuNos` 순서를 반영해 해당 부모 아래 **형제 전체**의 `ord`를 0..n-1로 다시 매긴다(`scope=ACTIVE`면 비활성 형제는 표시 순서상 제자리에 두고 활성 자리에만 요청 순서를 채움 — 쟁점 3). `{id}`(Long) 경로 변수와는 리터럴 경로가 우선 매칭되므로 충돌하지 않는다(`PUT /{id}`는 없음).
**이 결정은 사용자 원문 요청(`PATCH .../reorder`)과 달라진다** — 승인 시 별도 고지.

### 쟁점 2 — 잠금 전략 (핵심)

| 선택지 | 평가 |
|---|---|
| A. 부모 행 + 형제 행 집합 잠금 (`FOR UPDATE`) | 최상위엔 부모가 없다. 집합 조건은 인덱스가 없어 **전체 테이블 잠금**이 되고, 부모→자식 순서가 재활성화(자식→부모)와 반대라 데드락 사이클이 생길 수 있다 |
| B. 가상 앵커(고정 행/네임드 락) | 새 개념·새 인프라 필요, 기존 잠금 규약과도 따로 논다 — 과설계 |
| **C (채택). 형제 id를 "엔티티 없이" 비잠금 프로젝션으로 읽고, `menuNo` 오름차순으로 각 행을 기존 `findByIdForUpdate(id)`로 하나씩 잠근다. 부모 행은 잠그지 않는다** | 잠금이 전부 PK 점 조회라 잠금 범위가 정확히 형제 행뿐이다. 새 잠금 프리미티브가 없고 기존 테스트 기법(리포지토리 프록시 advice)을 재사용한다 |

**사이클 분석(결정 C)**
- reorder는 같은 형제 집합 안에서 **항상 `menuNo` 오름차순**으로만 잠근다 → 서로 다른 reorder끼리 사이클 없음(같은 집합은 같은 순서, 다른 집합은 행이 겹치지 않음).
- 일반 수정·비활성화는 **행 하나**만 잠근다 → 단독으로 사이클을 만들 수 없다.
- 재활성화(자식→부모): 자식 B를 잡고 부모 P를 기다린다. 자식들의 reorder는 P를 잠그지 않으므로 P를 기다리는 쪽이 없다. **루트 형제 reorder는 P를 잠그지만 자식 B는 잠그지 않는다**(형제 = 루트 행들뿐) → reorder가 B를 기다리지 않아 사이클이 성립하지 않는다. 대기만 발생.
- 하위 생성(부모 P 행): 루트 reorder가 P를 잡고 있으면 생성은 대기 후 진행(대기만).
- 부모를 안 잠그는 이유: reorder는 `ord`만 바꾸고 `useYn`·계층을 바꾸지 않아 "활성 부모 아래 활성 자식" 불변식과 무관하다.
- **전역 잠금 순서(리뷰 1라운드가 확인한 형태)**: 잠금은 `(-깊이, menuNo)` 순으로 설명된다 — 형제 reorder는 같은 깊이에서 `menuNo`가 증가하는 방향, 재활성화는 자식→부모로 깊이가 감소하는 방향이라 서로 반대 방향 사이클이 성립하지 않는다(정상 트리·부모 관계 불변 전제, 임의 깊이에서도 동일).
- 잠금 범위 주장은 **PK 점 조회가 존재하는 행에 대해서만 레코드 잠금**이라는 전제다. 잠금 전에 id 스냅샷을 읽으므로 그 사이 행이 하드 삭제된 경우(앱 경로엔 하드 삭제 없음, 직접 DB 조작·테스트 정리에서만 가능)엔 갭 잠금 가능성이 있어, 그 경우 404로 응답하고 트랜잭션을 롤백한다.

**동시 생성과의 관계 — 더 약한 계약을 채택한다 (리뷰 1라운드 P1-2·P1-3 수용)**

v1의 "동시에 생성된 형제는 항상 맨 뒤"라는 주장은 **틀렸다**. 반례(정상 API 데이터): 형제 A/B/C의 `ord`가 모두 0(중복 허용) → 재정렬 R이 id `[A,B,C]`를 읽고 `[C,B,A]`를 요청 → 생성 N이 부모 P를 잠그고 `max=0`을 읽어 D를 `ord=1`로 생성·커밋 → R이 A/B/C만 잠그고 C=0, B=1, A=2로 커밋 → D의 id가 더 커서 최종 **C, B, D, A**. 명시 `ord`를 준 생성도 같은 결과를 낸다. 이 결과는 어떤 직렬 실행으로도 설명되지 않는다(N→R이면 D 누락으로 409, R→N이면 D의 자동 `ord`는 3).

선택지:
| 선택지 | 평가 |
|---|---|
| A. 생성과 reorder가 같은 잠금을 공유(부모 행 잠금 추가) | 부모→자식 잠금이 생겨 기존 재활성화(자식→부모)와 직접 충돌 → 데드락. 루트 생성은 부모가 없어 어차피 못 막음. 기각 |
| B. 생성 경로를 손봐서 reorder와 직렬화 | 이번 범위 밖의 다른 기능(생성) 변경 + 루트 앵커 문제 재발. 기각 |
| **C (채택). 계약을 약하게 명시** | 아래 |

**채택 계약**
1. **기준 시점**: 요청 `menuNos`가 일치해야 하는 "현재 형제 집합"(`scope`에 따라 전체 또는 활성만 — 쟁점 3)은 **이 트랜잭션의 첫 비잠금 읽기(스냅샷)가 본 형제 집합**이다. 이 시점 이후 커밋된 형제는 검증 대상이 아니며 순서 변경도 받지 않는다(id 목록은 스냅샷이고, 뒤이은 `FOR UPDATE`는 그 목록의 행 값만 최신으로 읽는다).
2. **보장**: 요청한 형제들끼리의 **상대 순서**는 요청대로 확정된다. 각 행의 이름·`useYn` 등 다른 컬럼은 잠금 후 최신값으로 보존된다.
3. **비보장**: 스냅샷 이후 동시에 생성된 형제의 **위치**(요청 형제들 사이에 끼어들 수 있음 — 명시·중복 `ord` 때문). 이 경우에도 순서는 결정적이며 다음 재정렬로 정리된다.
4. 위 계약은 `MenuConcurrencyIntegrationTest`의 동시 생성 계약 테스트로 고정한다(쟁점 7).

### 쟁점 3 — 요청이 담아야 하는 집합, 화면의 "활성만 보기" (v4에서 재결정)

**배경**: 화면 기본은 활성 메뉴만 로드한다. v3는 "서버는 형제 전체만 허용, 화면은 `비활성 포함`이 켜졌을 때만 드래그"로 정했으나, 사용자 요청으로 **기본 화면에서도 드래그가 되게** 바꾼다. v1이 이 방식을 기각한 이유("제자리 유지"가 동률·null `ord`에서 모호)는 아래 **표시 순서 기준 위치 고정 규칙**으로 결정적으로 정의해 해소한다.

| 선택지 | 평가 |
|---|---|
| A. 서버 전체 집합 필수 + 화면은 `비활성 포함` 켰을 때만 드래그 (v3) | 규칙은 단순하나 기본 화면에서 드래그 불가 — 사용자 요청과 어긋남 |
| **B (채택, v4). 요청에 `scope`(`ALL`\|`ACTIVE`)를 싣고, 서버가 scope별 집합·배치 규칙을 처리** | 화면은 "지금 트리에 실제로 그려진 범위"를 그대로 보낸다. 서버 규칙은 늘지만 결정적이다 |
| C. 화면이 드래그 직전에 `useYn=all`을 몰래 다시 조회해 병합 | 경쟁 조건이 새로 생기고 사용자가 못 본 항목이 섞인다 — 기각 |

**요청**: `{ upMenuNo: Long|null, scope: "ALL"|"ACTIVE", menuNos: [..] }` — `scope`는 필수(`@NotNull`), 모르는 값은 400.

**서버 규칙**
1. **기준 집합(스냅샷)**: 이 트랜잭션의 첫 비잠금 읽기(쟁점 2의 채택 계약 1번)가 본 형제에서 — `ALL`이면 **형제 전체**, `ACTIVE`면 **`useYn=true`인 형제만**. `menuNos`는 그 집합과 **정확히 같아야** 한다. 다르면 409. 중복·null·빈 목록·상한(1000) 초과는 400.
2. **잠금은 scope와 무관하게 형제 전체**를 `menuNo` 오름차순으로 잡는다(전체를 다시 매겨야 하므로 — 쟁점 2의 사이클 분석 그대로).
3. **잠금 후 재검증**: 잠금으로 적재된(최신) 엔티티에서 `ACTIVE`면 활성 집합을 다시 계산해 `menuNos`와 같은지 확인한다. 스냅샷과 잠금 사이에 다른 관리자가 비활성화·재활성화했다면 집합이 달라져 **409**(적용 없이 롤백). 스냅샷에 있던 형제가 잠금 시점에 없으면(직접 DB 조작 등) 409.
4. **배치 규칙(결정적)** — 잠금 후 최신 값으로 현재 **표시 순서 F**를 만든다: `ord` 오름차순(**null은 맨 앞** — 화면·사이드바가 쓰는 `order by ord asc, menu_no asc`의 MariaDB NULL 정렬과 동일), 동률은 `menuNo` 오름차순.
   - `ALL`: 목표 순서 T = 요청 순서.
   - `ACTIVE`: F를 앞에서부터 훑으며 **비활성 형제는 그 자리(F의 위치)에 그대로 두고**, 활성 형제 자리에는 요청 순서의 다음 id를 채운다 → T.
   - 두 경우 모두 T의 인덱스 0..n-1을 각 형제의 새 `ord`로 삼는다(바뀐 행만 갱신).
   - 예: F=[A(0), B(1, 비활성), C(2), D(3)], `ACTIVE` 요청 [D, A, C] → T=[D, B, A, C] → D=0, B=1, A=2, C=3. `ord`가 전부 null이거나 중복이어도 F가 `menuNo`로 결정적이므로 T도 결정적이다.
5. **화면 관점**: 활성만 보는 상태에서 드래그해도 숨은 비활성 메뉴는 원래 위치를 지킨다. "비활성 포함"을 켜면 숨은 메뉴가 기대와 다른 자리에 보일 수 있고, 재활성화하면 그 자리에 나타난다(사용자에게 고지).

**이 검증이 막는 것과 막지 않는 것(리뷰 1라운드 추가 지적 수용)**: 막는 것은 *집합이 달라진* 요청뿐이다. 다른 관리자가 같은 집합의 **순서만** 먼저 바꿔 놓았다면 후속 요청은 성공한다 — **마지막 쓰기가 이기며**, "stale 화면 전반을 방지한다"고 주장하지 않는다(순서 자체의 낙관적 락은 만들지 않는다 — 과설계). 특히 `ACTIVE`에서는 F가 잠금 시점의 최신 `ord`로 계산되므로, 사용자가 본 화면과 다른 최신 배치 위에 요청 순서가 적용될 수 있다(집합이 같으면 허용).

**상한**: 요청 `menuNos`는 최대 **1000개**(`@Size(max=1000)`). 생성 API에는 부모별 형제 수 제한이 없어 형제가 1000개를 넘으면 이 기능을 쓸 수 없다 — 사이드바가 SB Admin 2 2단이라 실제 규모(수십 개 이하)에서는 도달하지 않는 지원 범위로 명시하며, 초과 시 서버는 400 메시지로 사유를 알린다(화면은 서버 `message`를 그대로 표시). 이 상한은 요청 크기 기준이며 `ALL` 형제 수가 1000을 넘으면 `ALL` 요청이 불가하다. 숫자 `ord` 입력(기존 `PATCH`)은 그대로 남아 키보드·소수 항목 대안이 된다. 이 기능은 `useYn`을 요청에 싣지 않으므로 기존 stale-form 문제(`buildPayload()`의 `useYn` 되돌림)를 일으키지 않는다(그 문제 자체는 이번 범위 밖).

### 쟁점 4 — 쓰기 방식(오래된 엔티티 덮어쓰기 방지)

1. 형제는 **엔티티가 아닌 프로젝션**(`menuNo`·`ord`·`useYn`만, `order by menuNo`, 루트는 `is null` 변형)으로 비잠금 읽어 스냅샷을 만든다 — 엔티티를 영속성 컨텍스트에 올리지 않는다.
2. 형제 전체를 `menuNo` 오름차순으로 `findByIdForUpdate`로 잠그면서 **처음으로 엔티티를 적재**한다(잠금 후 적재라 최신 값).
3. 잠근 엔티티로 쟁점 3의 재검증(3)·F 계산(4)·T 산출을 수행한다. 재검증 실패는 아무것도 쓰지 않고 예외(409)로 롤백한다.
4. `ord`가 바뀌는 행만 도메인 메서드 `Menu.changeOrd(ord, now)`로 갱신한다(`ord`·`updateDate`만 변경). 전체 컬럼 UPDATE지만 값은 잠금 후 최신이라 안전하다. 순서가 그대로인 행은 건드리지 않는다.
5. 시각은 서비스가 1회 산출한 `LocalDateTime.now(clock)`을 전달한다(프로젝트 Clock 규약, `ClockUsageConventionTest`).

`@DynamicUpdate` 추가는 무관한 변경이라 하지 않는다.

### 쟁점 5 — 응답과 감사 로그

- 응답 200: `{ upMenuNo, menus: [{ menuNo, ord }, ...] }`(적용된 순서). 본문이 필요한 이유는 감사 Aspect가 결과 getter에서만 `targetId`를 뽑기 때문이다(`deactivateMenu`가 `MenuResponse`를 반환하는 것과 같은 이유).
- 액션 타입 `MENU_REORDER` 추가: `AdminActionTypes` 상수 + `ALL`, 활동 로그 화면 `ACTION_TYPE_LABELS`("메뉴 순서 변경"). 동기화 테스트 2종이 누락을 잡는다.
- `targetType="MENU"`, `targetIdExpression="upMenuNo"` → **부모 메뉴 번호**를 기록한다. 최상위 재정렬은 `null`. 로그에 새 순서 payload는 남지 않는다(컬럼 없음) — 알려진 한계로 기록. 스키마 변경은 하지 않는다.

### 쟁점 6 — 드래그 앤 드롭 화면 규칙 (설계 제약)

- 제약(UI): jstree 3.3.12 + jQuery + Thymeleaf. 신규 라이브러리 없음. 사이드바만 2단이고 **관리 트리·생성 API는 깊이 제한이 없으므로** 화면 규칙도 특정 깊이를 가정하지 않는다(같은 부모 아래 형제면 어느 깊이든 동작).
- `plugins: ['types', 'dnd']`, `dnd: { copy: false, drag_selection: false, inside_pos: 'last' }`.
- **`core.check_callback(operation, node, parent, position, more)`은 `move_node`에서 두 검사 단계를 모두 통과시켜야 한다**(리뷰 P1-1, 설치 빌드로 확인한 사실):
  - **공통(드래그 중 + 드롭 후 최종 검사 모두)**: `parent.id === node.parent`(같은 부모, 루트는 `'#'`)이고, `more.is_multi`·`more.is_foreign`이 아니며, 화면이 드래그 가능한 상태(아래 상태 규칙)일 때만 true. `more.dnd`는 **요구하지 않는다**(최종 검사엔 없다).
  - **드래그 중에만**: `more.dnd`이고 `more.pos === 'i'`(안으로 넣기)이면 false.
  - 그 외 operation(`create_node`·`rename_node`·`delete_node`·`copy_node`)은 전부 false. → 다른 부모·다른 트리로의 이동(부모 이동)은 이번 범위에서 화면상 불가능하다.
- **화면 상태 규칙 (리뷰 P2-4·P2-5 수용)**:
  - `treeScope`(`'all'` | `'active'` | `null`): **트리 초기화가 끝난 뒤에만 그 응답의 범위(`useYn=all`→`'all'`, `useYn=true`→`'active'`)로 설정된다.** 조회 시작 시 `null`로 내려간다(토글 체크박스 값이 아니라 **실제로 그려진 트리의 범위**를 본다). 요청의 `scope`는 이 값을 그대로 보낸다(`'all'`→`ALL`, `'active'`→`ACTIVE`).
  - `loadSeq`(요청 세대 번호): `loadTree()`가 호출될 때마다 증가시키고, 응답 도착 시 자신의 세대가 최신이 아니면 **폐기**한다(토글을 빠르게 바꿔 응답 순서가 뒤집혀도 마지막 요청만 그린다).
  - `selectSeq`(선택 상태 세대 번호): **폼·`mode`를 바꾸는 모든 전이 지점에서 증가**시키고, 상세 응답은 자신이 요청될 때의 세대가 **응답 도착 시점에도 같을 때만** `mode`/`fillForm`을 실행하며 아니면 **폐기**한다. 증가 지점: 메뉴 선택(자기 요청용), `resetSelectionState()`, **`btnNewTop`·`btnNewChild`(생성 모드 진입)**, 재정렬 시작, `loadTree()` 시작, **`loadTree()`의 새 트리 교체(`initTree`) 직전**. 낡은 `ord`가 폼을 다시 채워 재정렬을 되돌리는 것과, 트리 교체 뒤·생성 모드 진입 뒤 늦은 상세 응답이 편집 폼을 되살리는 것(리뷰 2라운드 P2-1)을 막는다. 교체 시점에 올리는 이유: `loadTree()`는 응답이 올 때까지 옛 트리를 남겨 두므로, 시작 시점의 증가만으로는 그 사이 옛 트리에서 시작한 새 상세 요청을 무효화하지 못한다.
  - `busy`(변경 작업 진행 중): 저장·비활성화·재정렬을 **상호 배제**한다 — 하나가 진행 중이면 다른 변경 작업은 시작하지 않는다(재정렬은 `is_draggable`/`check_callback`에서 거부, 저장·삭제는 안내 메시지 후 무시). 이미 전송된 요청은 취소하지 않고 끝나길 기다린다.
  - **`dnd.is_draggable`와 `check_callback` 둘 다** `treeScope !== null && !busy`를 **드롭 시점에** 검사한다(트리가 아직 교체 중이면 `treeScope`가 `null`이라 드래그 불가). 기본 화면(활성만)에서도 드래그가 가능하다.
  - `treeScope==='active'`일 때 트리 위에 "비활성 메뉴는 원래 위치를 유지합니다" 안내를 표시한다(숫자 `ord` 입력은 항상 사용 가능).
- `move_node.jstree` 이벤트에서 이동된 노드의 (새) 부모 아래 자식 노드 id 순서를 읽어 `PUT /admin/api/menus/order`(`X-CSRF-TOKEN` 헤더 필수) 호출. 자식 노드 id는 `MenuTreeResponse.id`(= `menuNo` 문자열)이며 `data.menuNo`로도 확인한다. `upMenuNo`는 이동 전 부모의 `data.menuNo`(루트는 `null`).
- **성공/실패 모두** `selectSeq` 증가 + `resetSelectionState()` 후 `loadTree()`로 서버 상태를 다시 그린다(로컬 이동 결과를 믿지 않음). 폼에 저장 안 한 편집이 있으면 사라진다 — 알려진 동작.
- **실패 메시지는 재조회 이후에 표시한다**(리뷰 추가 지적 수용): `loadTree()`가 시작 시 `hideTreeError()`를 호출하므로, 실패 시 `await loadTree()`가 끝난 **다음에** `showTreeError(서버 message)`를 호출한다. 409(집합 변경)도 같은 경로.
- `destroy()`가 컨테이너 이벤트를 지우므로 `initTree()`에서 `select_node`와 같은 자리에 `move_node`도 재바인딩한다.
- 키보드 대안: 기존 `ord` 숫자 입력을 유지한다(변경 없음).

### 쟁점 7 — 테스트 전략

| 층 | 내용 |
|---|---|
| `MenuServiceTest`(Mockito) | 정상 재정렬(ord 0..n-1, 바뀐 행만 갱신·`updateDate` 고정 Clock), 순서 그대로면 갱신 없음, 부모 없음 404, 집합 불일치(누락·추가·타 부모 id) 409, 중복 id 400, **`scope` 규칙(v4)**: `ALL`(형제 전체 = 요청) / `ACTIVE`(활성 형제만 = 요청, 비활성 id 포함·활성 누락은 409), **배치 규칙**(F=[A0,B1(비활성),C2,D3]·`ACTIVE` [D,A,C] → D0 B1 A2 C3, `ord` 전부 null·중복인 경우의 결정성, null 우선 정렬), **잠금 후 재검증**(스냅샷은 활성이었지만 잠근 엔티티가 비활성/재활성된 경우 409이고 어떤 `changeOrd`도 호출 안 됨), `scope` 누락·미지의 값은 컨트롤러 400, **잠금 순서가 `menuNo` 오름차순**(요청 순서가 아님)임을 `InOrder`로 단언, 부모 행은 잠그지 않음(`never`), **깊이 무관**(2단 이하가 아닌 임의 부모 id로도 동작), 형제 id를 **엔티티가 아닌 프로젝션으로** 읽음(엔티티 선로딩 없음)을 verify |
| `MenuControllerTest`(`@WebMvcTest`) | 200 응답 본문, 빈 목록·null 원소·중복·1001개 400, ADMIN만 허용(MANAGER 403·비인증 401), `PUT /order`와 `PATCH /{id}` 경로 충돌 없음, CSRF 없는 PUT 403 |
| `MenuConcurrencyIntegrationTest`(Testcontainers) | ① **잠금 대기 경로(reorder 선행)**: reorder가 첫 형제를 잠근 채 멈춘 상태에서 동시 `updateMenu(이름)`·`deactivateMenu`가 시작되고, 기존 `verifyOrderedWrites`처럼 `INNODB_LOCK_WAITS`로 **실제 DB 락 대기를 관측한 뒤** reorder를 해제 → 둘 다 반영되고 새 `ord`도 반영(순차 실행을 경합으로 오인하지 않음) ①-b **스냅샷 이후·첫 잠금 이전 커밋 보존(리뷰 2라운드 P2-2 — 이 설계의 핵심 구간)**: reorder가 **형제 id 프로젝션을 읽은 뒤 첫 `findByIdForUpdate` 직전에 정지** → 그 사이 다른 트랜잭션이 요청 대상 형제의 **이름 변경**과 **비활성화**를 각각 커밋 → reorder 재개 → 잠금 조회가 반환한 엔티티와 **최종 DB**에서 변경된 이름·`useYn=false`가 보존되고 새 `ord`가 반영됨(오래된 스냅샷을 가진 트랜잭션에서도 `FOR UPDATE`가 최신 엔티티를 적재하는지 검증; 이 테스트가 비잠금 읽기까지 수정 커밋 뒤에 도는 경우와 구분되도록 정지 지점을 advice로 강제; `scope=ALL` 경우) ①-c **(v4) `scope=ACTIVE` 재검증**: 같은 정지 지점에서 다른 트랜잭션이 요청에 든 활성 형제 하나를 **비활성화**하거나 숨은 비활성 형제 하나를 **재활성화**해 커밋 → 재개 시 **409이고 모든 형제의 `ord`가 그대로**(롤백) ② **잠금 범위 실증**: reorder가 형제 행을 모두 잡고 멈춘 상태에서 **다른 부모(또는 루트) 아래 행**을 `innodb_lock_wait_timeout=1` 세션으로 수정하면 대기 없이 성공(테이블 전체 잠금이 아님을 증명) + 테스트 로그에 `@@transaction_isolation` 기록 ③ **루트 reorder vs 자식 재활성화**가 데드락 없이 완료 ④ 기존 부모 비활성화 vs 자식 재활성화 테스트 유지 ⑤ **동시 생성 계약**: reorder가 id 스냅샷을 읽고 첫 잠금 전에 멈춘 사이 (a) 명시 `ord`로 D 생성, (b) `ord` 중복 상태에서 자동 생성 — 둘 다 예외 없이 완료되고 **요청한 형제들의 상대 순서는 보존**, D는 유지됨(D의 위치는 단언하지 않음 — 채택 계약) |
| `AdminActionTypeSyncTest`·`AdminActionTypeLabelSyncTest` | 새 액션 타입 등록 누락 방지(기존 테스트가 그대로 검증) |
| Playwright 실기 | **실제 마우스 드롭 → `PUT /admin/api/menus/order` 요청 발생 → 새로고침 후 순서 유지·사이드바 반영·원복**을 필수 확인(`browser_run_code_unsafe`로 `page.mouse.move/down/up` 단계 이동 — `use_html5` 미사용이라 마우스 이벤트). 추가: **기본 화면(활성만)에서 비활성 형제가 끼어 있는 그룹을 드래그**해 PUT `scope=ACTIVE`가 나가고 새로고침 후 활성 순서가 반영되며 비활성 형제는 `비활성 포함` 보기에서 원래 자리에 그대로임(검증용 임시 메뉴는 원복), `비활성 포함`을 켠 상태의 드래그는 `scope=ALL`, 다른 부모로 놓기·안으로 넣기 거부(PUT 미발생), 트리 교체 중(`page.route`로 응답을 지연시켜 `treeScope===null`인 동안) 드래그해도 PUT 미발생, 토글 두 번 빠르게 눌러 응답 순서를 뒤집어도 마지막 요청 결과로 그려짐, 상세 조회 응답을 지연(`page.route`)시킨 채 ⓐ 재정렬한 뒤, ⓑ **토글을 바꿔 트리 교체가 끝난 뒤**(교체 전 옛 트리에서 선택한 상세), ⓒ **"최상위 추가"/"하위 추가"를 누른 뒤** 늦게 도착해도 폼이 편집 모드로 되살아나지 않음(세 경로 모두), 409(stale) 시 재조회 뒤에도 오류 메시지가 남음 |

과설계 경계: 낙관적 락(version 컬럼)·순서 stale 검출, 일괄 순서 이력 테이블, 키셋 페이징, 서버 부분 집합 규칙, 생성 경로 변경은 만들지 않는다.

## 변경 범위

| 파일 | 변경 |
|---|---|
| `menu/Menu.java` | `changeOrd(Integer, LocalDateTime)` 추가 |
| `menu/MenuRepository.java` | 형제 스냅샷 프로젝션 2개(부모 있음/루트, `menuNo`·`ord`·`useYn`만 — 엔티티 아님) 추가 |
| `menu/dto/request/MenuOrderRequest.java`, `menu/dto/request/MenuOrderScope.java`(enum `ALL`·`ACTIVE`), `menu/dto/response/MenuOrderResponse.java` | 신규 DTO(`@NotEmpty`·`@Size(max=1000)`·`@NotNull` 원소, `scope` `@NotNull`) |
| `menu/service/MenuService.java` | `reorderMenus(...)` + `@AdminActionLogged(MENU_REORDER)` |
| `menu/controller/MenuController.java` | `PUT /order` + Swagger 문서 |
| `log/constant/AdminActionTypes.java`, `templates/admin/log/manage.html` | `MENU_REORDER` 상수·라벨 |
| `templates/admin/menu/manage.html` | dnd 플러그인·`check_callback`·`move_node` 핸들러·안내 문구 |
| 테스트 | 위 표 |
| `menu/CLAUDE.md`, `plan/README.md`, 이 계획서 | 기록 |

**변경하지 않는 것**: 스키마·Flyway·`SecurityConfig`·`Menu`의 `@DynamicUpdate`·부모 이동·깊이 검증·`ord` PATCH 계약.

## 영향 범위 보고 (프로젝트 작업 방식 5·6항)

- **스키마/마이그레이션**: 없음(`ord`는 기존 nullable 컬럼, 인덱스 추가도 하지 않음).
- **API**: 신규 `PUT /admin/api/menus/order` 1개. 기존 엔드포인트 계약 불변. 호출하는 화면은 `templates/admin/menu/manage.html` 하나.
- **인가 정책**: 변경 없음(기존 `/admin/**` ADMIN 전용이 커버).
- **다른 화면**: 사이드바는 다음 로드 때 새 순서를 반영. 활동 로그 화면은 새 액션 라벨 1개.

## 리스크

- 중간. 잠금 규약(형제 id 오름차순 + 부모 미잠금)이 이 계획의 핵심이라 동시성 통합 테스트로 실증해야 한다. 잠금 범위(다른 그룹 행이 안 잠김)는 이론이 아니라 실제 MariaDB 이미지에서 테스트로 확인한다.
- 화면 쪽 위험이 커졌다: 요청 세대 번호·`treeScope`·`busy` 상태기계가 추가돼 `manage.html` 변경이 작지 않다 → Playwright로 **실제 드롭→PUT→새로고침 유지**와 경쟁 상황(응답 지연·토글 연타)을 필수 검증한다(표시만 확인하지 않는다).
- 드래그 자동 검증이 불안정할 수 있음 → API 레벨(curl·통합 테스트)을 주 증거로 하되, 드롭→PUT 확인은 브라우저 검증에서 생략하지 않는다.
- **사용자 결정·고지 사항**: (1) URI가 `PATCH .../reorder`에서 `PUT .../order`로 바뀜, (2) **(v4) 기본 화면(활성만)에서도 드래그 가능 — 단 숨은 비활성 메뉴는 표시 순서상 원래 위치를 유지하므로 "비활성 포함"을 켜면 기대와 다른 자리에 보일 수 있음**, (3) 감사 로그에 새 순서 payload가 남지 않음, (4) **동시에 생성된 형제의 위치는 보장하지 않는 약한 계약**(요청한 형제들의 상대 순서만 보장), (5) 같은 집합의 순서를 다른 관리자가 먼저 바꿨다면 마지막 쓰기가 이김(순서 stale 미검출), (6) 형제 1000개 초과 시 미지원(400).

## 완료 기준

- [ ] `PUT /admin/api/menus/order`가 요청 순서대로 형제 `ord`를 0..n-1로 재배정하고(`scope=ACTIVE`면 비활성 형제는 표시 순서상 제자리), 스냅샷 기준 `scope`별 집합(`ALL`=형제 전체, `ACTIVE`=활성 형제)이 아니거나 잠금 후 재검증에서 집합이 달라졌으면 409, 중복·빈 목록·1001개 이상 400, 부모 없음 404, ADMIN 외 401/403이다. 임의 깊이의 부모 아래에서도 동작한다.
- [ ] 잠금은 `menuNo` 오름차순 형제 행뿐이며(부모 미잠금) 동시 `updateMenu`/`deactivateMenu`와 경합해도 이름 변경·비활성화가 되돌아가지 않고(두 순서 모두), **다른 그룹 행은 잠기지 않으며(잠금 범위 실증)**, 루트 reorder와 자식 재활성화가 데드락 없이 끝난다(통합 테스트).
- [ ] 동시 생성(명시 `ord`·중복 `ord`)이 끼어도 예외 없이 완료되고 **요청한 형제들의 상대 순서가 보존**되며 생성된 메뉴가 유지된다(위치는 비보장 — 채택 계약, 통합 테스트).
- [ ] 활동 로그에 `MENU_REORDER`가 SUCCESS/FAIL로 기록되고 활동 로그 화면에 라벨이 보이며 동기화 테스트가 통과한다.
- [ ] 화면(Playwright): **기본 화면(활성만)과 "비활성 포함" 양쪽에서** 트리가 그려진 뒤 **실제 마우스 드롭 → PUT 발생(`scope` 각각 `ACTIVE`/`ALL`) → 새로고침 후 순서 유지·사이드바 반영**, 활성만 보기에서 옮겨도 비활성 형제는 제자리, 다른 부모로 놓기·안으로 넣기 거부, 트리 교체 중(`treeScope===null`)·전송 중 드래그 불가, 토글 연타 시 마지막 응답만 반영, 지연된 상세 응답이 재정렬 뒤 폼을 다시 채우지 않음, 실패 시 오류 메시지가 재조회 뒤에도 표시됨.
- [ ] 전체 `./gradlew test` 통과, 실서버 실기 검증·원복 기록.

## 개정 이력

- v1 (2026-09-30): 최초 작성.
- v2 변경 (2026-09-30, 1라운드 codex no-ship — 지적 전부 수용, 기각 없음):
  - P1-1 수용 — 설치된 `jstree.min.js`에서 드롭 후 최종 `move_node` 검사가 `{core, origin, is_multi, is_foreign}`만 받고 `dnd`가 없음을 직접 확인. `check_callback`을 "같은 부모 조건은 두 단계 공통, `pos==='i'` 차단은 드래그 중에만"으로 수정하고 `more.dnd` 요구를 삭제. 검증은 표시가 아니라 실제 드롭→PUT→새로고침 유지를 필수로.
  - P1-2 수용 — v1의 "동시 생성 형제는 항상 맨 뒤" 주장은 틀렸음(명시·중복 `ord` 반례 C,B,D,A). 부모 잠금 추가(자식→부모 재활성화와 충돌)·생성 경로 변경(범위 밖) 대신 **더 약한 계약**(요청 형제들의 상대 순서만 보장, 동시 생성 형제의 위치는 비보장)으로 변경하고 통합 테스트로 고정.
  - P1-3 수용 — 검증 기준 시점을 "이 트랜잭션의 첫 비잠금 읽기 스냅샷"으로 명시, 스냅샷 이후 커밋된 형제는 검증·순서 변경 대상이 아님.
  - P2-4·P2-5 수용 — `treeScope`(실제로 그려진 범위)·`loadSeq`/`selectSeq`(응답 세대 번호로 늦은 응답 폐기)·`busy`(저장·삭제·재정렬 상호 배제) 상태 규칙 추가, `is_draggable`·`check_callback` 둘 다 드롭 시점에 검사. 지연 응답·토글 연타 시나리오를 검증에 추가.
  - P2-6 수용 — `@Size(max=200)`을 1000으로 상향하고 지원 범위·초과 시 400 안내를 명시(생성 API에 부모별 제한이 없어 초과 시 미지원).
  - 조건부 긍정 의견 반영 — 잠금 순서를 `(-깊이, menuNo)` 전역 순서로 설명 보강, "PK 조회는 언제나 레코드 잠금" 표현을 좁혀(존재하는 행 한정, 없는 키는 갭 잠금 가능) **잠금 범위 실증 통합 테스트** 추가.
  - (v2 변경 계속) 추가 지적 수용 — 집합 검증이 "순서 stale"을 막지 못하고 마지막 쓰기가 이김을 명시, 오류 메시지를 `loadTree()`(내부에서 `hideTreeError`) 이후에 표시, 2단 제한은 사이드바 표시 제한일 뿐 API·트리는 깊이 무제한이므로 깊이 무관 검증(테스트 포함).
- v3 변경 (2026-09-30, 2라운드 codex no-ship — P2 2건 모두 수용, 기각 없음):
  - P2-1 수용 — `loadTree()`는 응답이 올 때까지 옛 트리를 남기므로 "시작 시 `selectSeq` 증가"만으로는 그 사이 옛 트리에서 시작한 상세 요청을 못 막고, `btnNewTop`·`btnNewChild`는 `resetSelectionState()`를 호출하지 않아(코드 확인) 늦은 응답이 생성 모드를 편집 모드로 되돌릴 수 있음. `selectSeq`를 **폼·`mode`를 바꾸는 모든 전이 지점**(트리 교체 직전, 생성 모드 진입 포함)에서 올리고 응답은 도착 시점의 세대와 일치할 때만 적용. Playwright 검증에 (트리 교체 뒤·생성 모드 진입 뒤) 지연 응답 시나리오 추가.
  - P2-2 수용 — 테스트 ①이 "reorder의 비잠금 읽기까지 수정 커밋 뒤에 도는 경우"도 통과해 핵심 구간을 검증하지 못함. **id 프로젝션을 읽은 뒤 첫 잠금 직전에 정지 → 다른 트랜잭션이 이름 변경·비활성화 커밋 → 재개 → 잠금 조회 결과·최종 DB에서 변경값 보존**을 강제하는 테스트(①-b)와, 실제 `INNODB_LOCK_WAITS` 대기 관측 후 해제하는 잠금 대기 경로 테스트(①)로 분리.
- v4 변경 (2026-09-30, v3 3라운드 ship 뒤 **사용자 요청**: "기본 화면에서도 드래그 되게"): 쟁점 3을 재결정. v3의 "서버 전체 집합 필수 + `비활성 포함` 켰을 때만 드래그"를 **요청 `scope`(`ALL`|`ACTIVE`) 방식**으로 교체.
  - `ACTIVE`: 요청 = 활성 형제 집합(스냅샷 기준), 잠금은 형제 전체, **잠금 후 활성 집합 재검증**(다르면 409·롤백), 배치는 **표시 순서 F(ord null 우선·동률 menuNo) 기준으로 비활성 형제는 제자리, 활성 자리에 요청 순서를 채워** 전체 0..n-1 재부여. v1이 이 방식을 기각했던 사유("제자리" 모호함)를 F 기준 위치 고정으로 결정적으로 정의해 해소.
  - 화면은 `treeScope`(실제로 그려진 범위)를 `scope`로 그대로 전송하고, 드래그 조건에서 `treeScope==='all'` 요구를 `treeScope!==null`로 완화. "비활성 포함을 켜세요" 안내를 "비활성 메뉴는 원래 위치를 유지합니다"로 교체.
  - 잠금 전략·동시 생성 약한 계약·URI·감사 로그·`selectSeq` 규칙은 변경 없음. 프로젝션에 `ord`·`useYn` 추가, `MenuOrderScope` enum·테스트(scope 규칙·배치·재검증 409·ACTIVE 동시 상태 변경) 추가.

## 구현·검증 결과 (2026-09-30)

### 핵심 확정 사항
- 계획 v4 그대로 구현했다. **계획과 달라진 결정 없음**(URI `PUT /admin/api/menus/order`, 형제 전체 `menuNo` 오름차순 잠금·부모 미잠금, `scope=ALL|ACTIVE`와 위치 고정 배치, 잠금 후 재검증, 약한 동시 생성 계약, 상한 1000, 감사 `MENU_REORDER`·`targetId`=부모).
- 사용자 원문 요청의 URI(`PATCH .../reorder`)는 API 컨벤션(URI 동사 금지) 때문에 `PUT .../order`로 바뀌었고 승인 단계에서 고지·승인됐다.

### 구현 파일
| 파일 | 변경 |
|---|---|
| `menu/Menu.java` | `changeOrd(Integer, LocalDateTime)` |
| `menu/MenuRepository.java` | `SiblingRow` 값 프로젝션 + `findSiblingRowsByUpMenuNo`·`findRootSiblingRows` |
| `menu/dto/request/MenuOrderRequest.java`·`MenuOrderScope.java`, `menu/dto/response/MenuOrderResponse.java` | 신규 DTO(`@NotNull scope`, `@NotEmpty`·`@Size(max=1000)` 목록, `@NotNull` 원소) |
| `menu/service/MenuService.java` | `reorderMenus()` + `@AdminActionLogged(MENU_REORDER)` |
| `menu/controller/MenuController.java` | `PUT /order` + Swagger 문서 |
| `log/constant/AdminActionTypes.java`, `templates/admin/log/manage.html` | `MENU_REORDER` 상수·`ALL`·라벨("메뉴 순서 변경") |
| `templates/admin/menu/manage.html` | dnd 플러그인·`check_callback`·`move_node` 핸들러·`treeScope`/`loadSeq`/`selectSeq`/`busy` 규칙·안내 문구 |
| 테스트 | `MenuServiceReorderTest`(신규 17), `MenuControllerTest`(+11), `MenuConcurrencyIntegrationTest`(+9) |
| 문서 | `menu/CLAUDE.md`, `docs/troubleshooting.md`(jstree 최종 검사 `dnd` 없음), `plan/README.md`, 이 계획서 |

### 검증 결과
- **전체 `./gradlew cleanTest test`**: 866개(기존 829 + 신규 37), 실패 0·에러 0, 스킵 3(기존 Windows 심볼릭 링크 테스트). `AdminActionTypeSyncTest`·`AdminActionTypeLabelSyncTest` 통과(새 액션·라벨 등록 검증).
- **동시성 통합 테스트(Testcontainers MariaDB 10.11, 실측 격리 수준 `REPEATABLE-READ`) 9개 신규 통과**: 재조정 선행 시 동시 이름 수정·비활성화가 **실제 `INNODB_LOCK_WAITS` 락 대기**를 거친 뒤 둘 다 반영 / **스냅샷 이후·첫 잠금 이전 커밋 보존(핵심 구간)** — 잠금 조회가 최신 이름을 적재하고 비활성화가 되돌아가지 않음 / `ACTIVE` 재검증 409(비활성화·재활성화 각각, ord 불변) / **잠금 범위 실증** — 재조정이 형제를 잡은 동안 다른 그룹 행·루트 행·재조정 대상의 부모 행이 `innodb_lock_wait_timeout=1`에서도 대기 없이 수정됨(테이블 전체 잠금 아님, 부모 미잠금) / 루트 재조정 vs 자식 재활성화 **무교착** 대기 후 완료 / 동시 생성(명시 ord·중복 ord) 예외 없이 완료·요청 형제 상대 순서 보존.
- **변이 실험**(원본 복원 확인): (c) 부모 행도 잠금 → 잠금 범위 통합 테스트·단위 2개 실패(3), (d) 잠금 전 `findById`로 엔티티 선로딩 → **스냅샷 이후 커밋 보존 통합 테스트**·`ACTIVE` 재검증 통합 2개·단위 1개 실패(4), (e) `ACTIVE` 잠금 후 재검증 제거 → 통합 2개·단위 2개 실패(4).
- **실서버 실기**(dev 컨테이너 `cms-app-dev` 재빌드, 임시 부모 P와 자식 A~D·E를 API/SQL로 생성 후 원복):
  - API: `ACTIVE` [D,A,C] → `D(0) B(1,비활성) A(2) C(3)`(비활성 제자리), 재전송 멱등, `ALL` 재조정, 형제 구성 불일치·비활성 포함·타 부모 id 409(`RESOURCE_CONFLICT`), 중복·빈 목록·scope 누락 400, 없는 부모 404, CSRF 없는 PUT 403, 비로그인 401, `PATCH /order` 400, 루트 재조정(표시 순서 그대로) 200·순서 불변, 사이드바에 새 순서 반영(`C A D`, 비활성 B 숨김), 감사 로그 `MENU_REORDER` SUCCESS(target_id=부모)/FAIL 기록.
  - **브라우저(Playwright, 실제 마우스 드롭)**: 기본 화면(활성만)에서 D를 C 앞으로 드래그 → `PUT {"scope":"ACTIVE","menuNos":[40,39,37]}` 발생·DB `D0 B1(비활성) C2 A3`; "비활성 포함"에서 드래그 → `scope:"ALL"`; 다른 부모(`관리자 조회`) 위에 놓기 → PUT 0회·순서 불변; 안으로 넣기 위치는 검사 함수가 `pos=i → false`로 거부(계측 로그)하고 jstree가 같은 부모의 앞/뒤로 대체; 트리 교체 중(응답 2.5초 지연) 옛 트리 드래그 → PUT 0회·안내 숨김; 토글 연타 시 늦게 온 옛 `all` 응답 폐기(체크박스·트리·안내 일치); 지연된 상세 응답이 생성 모드 진입 뒤·트리 교체 뒤·재정렬 뒤에도 폼을 되살리지 않음(3경로); 화면이 모르는 형제를 DB에 추가한 뒤 드래그 → 409, 재조회로 새 형제 표시·오류 메시지 유지(콘솔 오류는 예상한 409 1건뿐).
  - **원복**: 임시 메뉴 6건·관련 감사 로그(`MENU_CREATE` 5·`MENU_DEACTIVATE` 1·`MENU_REORDER` 21) 삭제, 메뉴 8건·루트 `ord` 0..4 원상 확인. 관리자 로그인 4회(curl 2·Playwright 1 등)로 `visit_log`에 방문 기록이 남는다(원복 대상 아님, 그대로 둠).

### 이슈
- **jstree `check_callback`에 `more.dnd`를 요구하면 정상 드롭이 최종 검사에서 거부됨**(계획 리뷰 1라운드가 잡음, 소스로 확인) — 설계 단계에서 수정, `docs/troubleshooting.md`에 기록.
- 통합 테스트 작성 중 MariaDB 10.11에 `@@transaction_isolation`이 없어(`@@tx_isolation` 사용) 잠금 범위 테스트가 한 번 실패 — 테스트 코드 문제였고 수정해 통과(제품 코드 무관).
- 실기 중 한글 마커를 `curl` 인자로 넘기면 Windows Git Bash 인코딩으로 JSON이 깨졌다(L-01 때와 같은 환경 이슈) — ASCII 이름으로 재검증.

### 후속 / 범위 밖
- **부모 이동**(`PATCH /admin/api/menus/{id}/parent`, 깊이·순환 검증, 3행 잠금 순서 정의)은 별도 후속 PR로 남는다 — 이 PR 완료 후 사용자에게 진행 여부를 묻는다.
- 남은 알려진 한계: 동시 생성 형제의 위치 비보장, 같은 집합 순서의 마지막 쓰기 우선(순서 stale 미검출), 감사 로그에 새 순서 payload 없음, 형제 1000개 초과 미지원(400), 기존 stale-form(`buildPayload()`의 `useYn` 되돌림) 문제는 이번 범위 밖.
- 키보드 대안은 기존 `ord` 숫자 입력(변경 없음).
