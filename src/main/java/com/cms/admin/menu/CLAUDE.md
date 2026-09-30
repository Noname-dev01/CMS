# CLAUDE.md — com.cms.admin.menu

이 디렉터리(메뉴 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## Menu

- `MenuAccessRole`: `ALL`(공용, ADMIN·MANAGER 노출) / `ADMIN`(ADMIN 전용 노출). DB 컬럼 null은 ALL로 정규화(레거시 행 호환)
- 사이드바는 `AdminSidebarAdvice` → `MenuService.getSidebarMenus()`가 활성 메뉴를 역할 필터링해 동적 렌더링한다. SB Admin 2 UI 제약으로 **2단(최상위 + 직계 하위)까지만** 그린다
- 기본 메뉴 시드는 Flyway `V3__seed_default_menus.sql`이 담당한다 — menu 테이블이 **완전히 비었을 때만** 전체 시드하며, 행이 하나라도 있으면 건드리지 않는다 (보충 기능 없음)
- `accessRole`은 사이드바 **노출** 제어일 뿐이며, 실제 접근 차단은 Security(`@PreAuthorize` 등)가 담당한다
- **형제 순서 재조정 API + 트리 드래그 앤 드롭**(`MenuService.reorderMenus`, 2026-09-30, 계획서 `adversarial-review/plan/PLAN-menu-reorder.md`): `PUT /admin/api/menus/order`(본문 `{upMenuNo, scope, menuNos}`, ADMIN 전용 — URI에 동사를 못 쓰는 API 컨벤션 때문에 `reorder`가 아니라 명사 `order`). 같은 부모 아래 **형제 전체의 `ord`를 0..n-1로 다시 매긴다**(멱등). `scope=ALL`은 형제 전체, `scope=ACTIVE`는 활성 형제만 담고 — 서버가 현재 표시 순서 F(`ord` 오름차순, **null 먼저**, 동률 `menuNo`)를 만든 뒤 **비활성 형제는 F의 자리에 그대로 두고 활성 자리에만 요청 순서를 채운다**(예: F=[A,B(비활성),C,D], 요청 [D,A,C] → D0 B1 A2 C3). 요청이 스냅샷 기준 scope 집합과 다르거나, 잠근 최신 엔티티로 활성 집합을 **다시 검증**해 달라졌으면 409(롤백). **잠금 규약**: 형제 id를 엔티티가 아닌 값 프로젝션(`SiblingRow`)으로 먼저 읽고(`Menu`에 `@DynamicUpdate`가 없어 잠금 전 엔티티를 올리면 오래된 값이 전체 컬럼 UPDATE로 동시 수정을 덮어씀) **형제 전체를 `menuNo` 오름차순으로 `findByIdForUpdate`로 하나씩 잠근다 — 부모 행은 잠그지 않는다**(재활성화의 자식→부모 순서와 반대 방향 사이클 방지, 잠금 순서는 `(-깊이, menuNo)`). `menu` 테이블엔 PK 외 인덱스가 없어 집합 조건 `FOR UPDATE`를 쓰면 REPEATABLE READ에서 테이블 전체가 잠기므로 쓰지 않는다. **약한 계약**: 요청한 형제들끼리의 상대 순서만 보장하고, 스냅샷 이후 **동시에 생성된 형제의 위치는 보장하지 않는다**(명시·중복 `ord` 때문에 끼어들 수 있음 — "항상 맨 뒤"는 틀린 주장이었다). 같은 집합의 순서를 다른 관리자가 먼저 바꿨다면 마지막 쓰기가 이긴다(순서 stale 미검출). 요청 상한 1000개(초과 400). 감사 로그 `MENU_REORDER`, `targetId`는 **부모 메뉴 번호**(최상위는 null, 새 순서 payload는 남지 않음). 화면(`manage.html`): 같은 부모 안 이동만 허용(`check_callback`은 드롭 후 최종 검사에 `more.dnd`가 없으므로 `dnd`를 요구하지 않는다 — `docs/troubleshooting.md` 참조), 실제로 그려진 트리 범위(`treeScope`)를 `scope`로 전송, `loadSeq`·`selectSeq` 세대 번호로 늦게 온 응답 폐기, 저장·삭제·재정렬은 `busy`로 상호 배제, 실패 메시지는 재조회 **후에** 표시.
- **같은 행 쓰기의 비관적 잠금 일관화**(`MenuService.updateMenu()`, 2026-09-27, 감사 M-04): 생성/재활성화 시 부모 row, 비활성화 시 대상 row를 잠그는 것과 동일하게, **일반 수정(이름 등)도 최초 조회부터 `findByIdForUpdate()`를 쓴다** — 과거에는 `useYn=false`(비활성화) 분기만 잠그고 그 외 일반 수정은 잠금 없는 `findById()`를 써서, "일반 수정 조회 → 비활성화 커밋 → 일반 수정 커밋" 순서가 겹치면 방금 커밋된 비활성화가 되돌아가는 lost update가 실 MariaDB 경합으로 재현됐다(위 `AdminMemberService.updateMyInfo` 행 잠금 누락, 감사 H-02와 같은 결함군). **범위 밖으로 남은 잔여 위험**: `templates/admin/menu/manage.html`의 `buildPayload()`는 편집 필드와 무관하게 매 PATCH에 화면 로드 시점의 `useYn` 체크박스 상태를 항상 포함한다 — 따라서 "화면에서 이름만 수정"해도 다른 관리자가 그 사이 비활성화한 것을 모른 채 저장하면 명시적 `useYn=true` 재전송으로 비활성화가 되돌아갈 수 있다(고전적 stale-form 문제, 행 잠금만으로는 해결 불가). 상세는 `docs/troubleshooting.md`·`adversarial-review/remediation-plan.md` "PR 4" 섹션 참조.
