# CLAUDE.md — com.cms.admin.menu

이 디렉터리(메뉴 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## Menu

- `MenuAccessRole`: `ALL`(공용, ADMIN·MANAGER 노출) / `ADMIN`(ADMIN 전용 노출). DB 컬럼 null은 ALL로 정규화(레거시 행 호환)
- 사이드바는 `AdminSidebarAdvice` → `MenuService.getSidebarMenus()`가 활성 메뉴를 역할 필터링해 동적 렌더링한다. SB Admin 2 UI 제약으로 **2단(최상위 + 직계 하위)까지만** 그린다
- 기본 메뉴 시드는 Flyway `V3__seed_default_menus.sql`이 담당한다 — menu 테이블이 **완전히 비었을 때만** 전체 시드하며, 행이 하나라도 있으면 건드리지 않는다 (보충 기능 없음)
- `accessRole`은 사이드바 **노출** 제어일 뿐이며, 실제 접근 차단은 Security(`@PreAuthorize` 등)가 담당한다
- **같은 행 쓰기의 비관적 잠금 일관화**(`MenuService.updateMenu()`, 2026-09-27, 감사 M-04): 생성/재활성화 시 부모 row, 비활성화 시 대상 row를 잠그는 것과 동일하게, **일반 수정(이름 등)도 최초 조회부터 `findByIdForUpdate()`를 쓴다** — 과거에는 `useYn=false`(비활성화) 분기만 잠그고 그 외 일반 수정은 잠금 없는 `findById()`를 써서, "일반 수정 조회 → 비활성화 커밋 → 일반 수정 커밋" 순서가 겹치면 방금 커밋된 비활성화가 되돌아가는 lost update가 실 MariaDB 경합으로 재현됐다(위 `AdminMemberService.updateMyInfo` 행 잠금 누락, 감사 H-02와 같은 결함군). **범위 밖으로 남은 잔여 위험**: `templates/admin/menu/manage.html`의 `buildPayload()`는 편집 필드와 무관하게 매 PATCH에 화면 로드 시점의 `useYn` 체크박스 상태를 항상 포함한다 — 따라서 "화면에서 이름만 수정"해도 다른 관리자가 그 사이 비활성화한 것을 모른 채 저장하면 명시적 `useYn=true` 재전송으로 비활성화가 되돌아갈 수 있다(고전적 stale-form 문제, 행 잠금만으로는 해결 불가). 상세는 `docs/troubleshooting.md`·`adversarial-review/remediation-plan.md` "PR 4" 섹션 참조.
