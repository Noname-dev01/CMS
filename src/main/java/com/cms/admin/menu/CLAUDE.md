# CLAUDE.md — com.cms.admin.menu

이 디렉터리(메뉴 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## Menu

- `MenuAccessRole`: `ALL`(공용, ADMIN·MANAGER 노출) / `ADMIN`(ADMIN 전용 노출). DB 컬럼 null은 ALL로 정규화(레거시 행 호환)
- 사이드바는 `AdminSidebarAdvice` → `MenuService.getSidebarMenus()`가 활성 메뉴를 역할 필터링해 동적 렌더링한다. SB Admin 2 UI 제약으로 **2단(최상위 + 직계 하위)까지만** 그린다
- 기본 메뉴 시드는 Flyway `V3__seed_default_menus.sql`이 담당한다 — menu 테이블이 **완전히 비었을 때만** 전체 시드하며, 행이 하나라도 있으면 건드리지 않는다 (보충 기능 없음)
- `accessRole`은 사이드바 **노출** 제어일 뿐이며, 실제 접근 차단은 Security(`@PreAuthorize` 등)가 담당한다
