# Flyway 도입 계획

> 작성일: 2026-07-10 (Codex 적대적 리뷰 반영: 2026-07-10)
> 브랜치: `chore/flyway-migration`
> 배경: [project-direction-roadmap.md](project-direction-roadmap.md) 1단계(기반 마감)의 최우선 과제

## 계획에 영향을 준 사실 확인 결과

- CI(`.github/workflows/ci.yml`)는 매 실행마다 **빈 MariaDB** 서비스 컨테이너에서 `SPRING_PROFILES_ACTIVE: dev`로 테스트를 돌리고, 로컬 dev DB는 볼륨에 데이터가 유지된다. 즉 마이그레이션이 **"빈 DB 전체 생성"과 "기존 DB 무변경 통과" 두 경로를 모두 지원**해야 한다.
- 기존 시드 SQL(`docs/migration/2026-07-09_seed_default_menus.sql`)에 "Flyway 도입 시 `R__seed_default_menus.sql`로 이관한다"는 계획이 주석으로 남아 있다. (→ Codex 2차 리뷰 결과 R__ 대신 V3 versioned로 변경, 3번 참고)
- 현재 테이블은 4개(member, menu, admin_action_log, visit_log)로, 베이스라인을 만들기 가장 쉬운 시점이다.

## 작업 단계

### 1. 베이스라인 스키마 추출 — `V1__init_schema.sql`

- dev DB(`cms-db-dev`)를 기동하고 `SHOW CREATE TABLE`로 **member, menu, admin_action_log, visit_log** 4개 테이블의 실제 DDL을 추출해 작성한다.
- 손으로 쓰지 않고 실물에서 추출하는 이유: 이 스키마를 만든 게 Hibernate 자신이므로, 이후 `validate` 전환이 안전해진다.
- **엔티티 `@Table(indexes/uniqueConstraints)` 선언 전부**를 포함한다 (Codex 리뷰 medium 지적 반영 — 이전 판은 admin_action_log 인덱스 3종만 열거해 `idx_visit_at`이 빠져 있었다). `validate`는 인덱스를 검사하지 않으므로, 여기서 빠뜨리면 아무도 못 잡는다. 실물 확인(2026-07-10, dev DB) 기준 전체 목록:

  | 테이블 | 인덱스/제약 |
  |---|---|
  | member | `uk_member_user_id`, `uk_member_email` (유니크) |
  | admin_action_log | `idx_log_create_at_id(create_at,id)`, `idx_log_action_user_id`, `idx_log_action_type` |
  | visit_log | `idx_visit_at(visit_at)` |
  | menu | (PRIMARY만) |

### 1-b. 기존 DB 데이터 보정 — `V2__backfill_menu_access_role.sql` (Codex 리뷰 high 지적 반영)

- **문제**: baseline 전략상 기존 DB는 V1을 실행하지 않는다. 그런데 `access_role`은 컬럼 추가 후 **기존 행 백필이 필수**인 이력이 있다(미백필 시 MANAGER 사이드바에 ADMIN 링크 노출 — UI 권한 회귀). 백필 안 된 기존 환경이 baseline되면 이 보정을 영구히 건너뛴다.
- **해결**: 기존 수동 SQL(`2026-07-09_add_menu_access_role.sql`)의 백필을 `V2__backfill_menu_access_role.sql`로 이관한다. V2는 baseline-version(1) 이후이므로 **기존 DB에서도 반드시 실행**되고, `WHERE access_role IS NULL` 조건이라 멱등하며, 빈 DB에서는 no-op이다. (baseline 사전 점검으로 차단하는 대안은 수동 개입에 의존하므로 배제 — 2026-07-10 결정)
- **백필은 3단계로 구성하고, 미분류 행의 기본값은 `ADMIN`(보수적)으로 한다** (Codex 4차 리뷰 high 지적 반영, 2026-07-10 결정):
  1. 알려진 ADMIN 전용 URL 패턴 → `ADMIN` (원본 SQL과 동일)
  2. 알려진 공용 메뉴(`/admin` 대시보드, `/admin/member/info` 내 정보, '회원 관리' 그룹) → `ALL` **명시 지정** — 단순히 나머지를 ADMIN으로 뒤집으면 정당한 공용 메뉴까지 MANAGER 사이드바에서 사라지는 부작용이 있으므로 반드시 이 단계가 필요하다
  3. 어느 패턴에도 안 걸린 미지의 레거시 행 → `ADMIN` (fail-closed. 원본 SQL은 ALL이었으나 변경 — 미분류 행이 MANAGER에게 노출되는 방향보다 숨는 방향이 안전하고, 레거시 행은 정적 사이드바 시절 렌더된 적 없던 데이터라 숨어도 기능 손실이 아니며, 메뉴 관리 화면에서 ALL로 복구 가능)
  - 참고: Codex는 "백필에서 빠진 행이 null로 남는다"고 지적했으나 이는 부정확 — 3단계 catch-all이 null을 전부 소진한다. 실제 쟁점은 미분류 행의 기본값 방향이었고 위와 같이 확정. `accessRole`은 노출 제어일 뿐 실제 접근 차단은 Security(403)가 담당하므로 어느 쪽이든 권한 침해는 없다. NOT NULL 전환 제안은 앱의 null→ALL 정규화(레거시 호환 계약)와 충돌하는 스코프 확장이라 이번 PR에서 불채택, 후속 과제로 남긴다.
- **V2 선두에 방어적 ALTER를 포함한다** (Codex 2차 리뷰 high 지적 반영): `ALTER TABLE menu ADD COLUMN IF NOT EXISTS access_role VARCHAR(20) NULL;` — access_role 컬럼이 아직 없는 기존 DB(수동 SQL 미적용 + 최근 앱 미기동)가 baseline되면 V1의 컬럼 추가를 영구히 건너뛰어 V2의 UPDATE가 "unknown column"으로 기동 실패하는 경로를 막는다. MariaDB는 `ADD COLUMN IF NOT EXISTS`를 지원하므로(CI: mariadb:10.11) 컬럼이 이미 있는 DB·빈 DB에서는 no-op이다.
- 사실 확인(2026-07-10): 현 dev DB는 `menu.access_role IS NULL` 0건(7행 모두 백필 완료)이라 실행해도 no-op — 이 마이그레이션은 백필 안 된 미지의 기존 환경에 대한 방어다. prod 환경은 현재 존재하지 않는다(#3 커밋에서 제거).

### 2. 의존성 및 설정

- `build.gradle`: `flyway-core` + MariaDB용 모듈 추가(Flyway 10부터 DB 지원이 모듈로 분리됨 — 정확한 아티팩트명은 구현 시 context7로 확인). 버전은 Boot BOM 관리에 맡긴다.
- `application.yml`(공통):
  ```yaml
  spring:
    flyway:
      baseline-version: 1
      # baseline-on-migrate는 상시 설정하지 않는다 (아래 참고)
  ```
  → 빈 DB(CI·신규 환경)는 baseline 없이도 V1부터 전체 실행된다. 데이터 보정은 V2 이상에 두어야 기존 DB에 도달한다는 점이 핵심 규칙(1-b 참고).
- **`baseline-on-migrate`는 상시 공통 설정에 두지 않는다** (Codex 4차 리뷰 high 지적 반영, 2026-07-10): 상시 켜두면 1-c의 수동 점검을 건너뛴 어떤 기존 DB든 조용히 영구 baseline된다. 대신 **기존 DB 전환 시에만 일회성으로** 환경변수 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`를 붙여 기동한다(Spring Boot relaxed binding). 이후 기동부터는 history가 존재하므로 불필요하다. 절차는 1-c 체크리스트와 함께 마이그레이션 가이드에 문서화 — 수동 점검이 우회될 수 있는 창이 "영구"에서 "명시적 전환 1회"로 줄어든다.
- `application-dev.yml`: `ddl-auto: update` → `validate`. 이 시점부터 스키마 변경은 마이그레이션 파일로만 가능해진다.

### 3. 메뉴 시드 이관 — `V3__seed_default_menus.sql` (별도 커밋)

- ~~repeatable(R__) 이관~~ → **versioned(V3) + 빈 테이블 가드로 변경** (Codex 2차 리뷰 high 지적 반영, 2026-07-10 결정). 이유: `MenuDataLoader`의 원래 의미는 "menu 테이블이 **완전히 비었을 때만** 전체 시드"(`count() > 0`이면 스킵)인데, R__ + 행 단위 NOT EXISTS 가드는 의미가 다르다 — 관리자가 커스터마이즈(URL 변경 등)한 기존 DB에서 시드 파일이 수정될 때마다 기본 메뉴가 예고 없이 재생성될 수 있다(운영 데이터 오염). 원 시드 SQL 주석의 "R__ 이관" 계획은 이 결정으로 대체한다.
- **V3 구현 규칙**: 스크립트 선두에서 `SET @menu_empty := ((SELECT COUNT(*) FROM menu) = 0);`로 초기 상태를 캡처하고, 모든 INSERT의 조건으로 `@menu_empty`를 사용한다. 행 단위 `WHERE (SELECT COUNT(*) FROM menu) = 0` 방식은 **첫 INSERT 직후 테이블이 비어 있지 않게 되어 이후 행이 전부 스킵되는 함정**이 있으므로 금지. (Flyway는 한 마이그레이션을 단일 커넥션에서 실행하므로 세션 변수가 스크립트 전체에 유지된다 — 구현 시 실제 동작 검증 필수)
- **부모 메뉴 ID를 고정값으로 가정하지 않는다** (Codex 4차 리뷰 medium 지적 반영): '회원 관리' 그룹 INSERT 직후 `SET @member_group_id := LAST_INSERT_ID();`로 실제 생성 ID를 캡처해 자식 3건의 `up_menu_no`에 사용한다. `COUNT(*)=0`인 테이블이라도 과거 삭제 이력으로 AUTO_INCREMENT가 1이 아닐 수 있어, 고정 menu_no(예: 3) 가정은 자식 메뉴를 고아로 만들거나 잘못된 부모에 붙인다. (기존 `MenuDataLoader`가 `memberGroup.getMenuNo()`로 실제 ID를 따라가던 의미의 SQL 등가물)
- **V3에는 DDL을 절대 섞지 않는다** (Codex 3차 리뷰 high 지적 대응): Flyway는 각 마이그레이션을 개별 트랜잭션으로 실행하고 실패 시 롤백하지만(공식 FAQ, context7로 확인), MariaDB에서 DDL은 암묵적 커밋을 유발해 이 보장을 깨뜨린다. V3가 순수 DML이고 4개 테이블 모두 InnoDB(실물 확인)이므로, **부분 INSERT 후 실패 시 전체 롤백 → 재시도 시 테이블이 다시 빈 상태 → 완전 재시드**가 보장된다. Codex가 우려한 "부분 시드가 남은 채 재시도가 조용히 성공"하는 경로는 이 규칙이 지켜지는 한 발생하지 않는다. (Codex의 SIGNAL 사후 검증 제안은 MariaDB에서 compound statement가 필요해 복잡도 대비 이득이 없어 불채택 — 대신 신규 환경 리허설의 "메뉴 7행 확인"이 방어선)
- 기존 DB에 메뉴가 1~6행만 있는 부분 상태는 '커스터마이즈된 기존 데이터'로 **보존**한다 — 위 롤백 보장으로 V3 자신이 부분 상태를 만들 수 없으므로, 비어 있지 않은 테이블은 전부 시드 이전부터 존재한 데이터이며 "기존 데이터 절대 불가침" 원칙(이미 확정)이 그대로 적용된다.
  - **이 보존은 회귀가 아니다** (Codex 5차 리뷰 high 지적 검증 결과, 2026-07-10): Codex는 "`MenuDataLoader` 삭제로 부분 상태의 런타임 보정 경로가 사라진다"고 했으나 전제가 부정확하다. `MenuDataLoader`도 `count() > 0`이면 전체 스킵(`MenuDataLoader.java:24`)이라 부분 상태를 보정한 적이 없고, non-dev 환경에는 `@Profile("dev")`라 애초에 존재하지도 않았다. 행 단위 보충 능력은 수동 시드 SQL(`NOT EXISTS` 가드)에만 있었는데, 그것이 바로 커스터마이즈 재생성 위험 때문에 2차 리뷰에서 의도적으로 포기한 기능이다. 즉 V3 빈 테이블 가드는 `MenuDataLoader`의 의미를 그대로 보존하며, 삭제 전후로 부분 상태 DB의 동작은 동일하다.
- 효과: 빈 DB(신규 환경·CI)는 전체 시드, 기존 DB는 데이터 상태와 무관하게 **절대 건드리지 않음**. 기본 메뉴 "보충" 기능은 제공하지 않는 것으로 확정 — 필요해지면 별도 마이그레이션으로 그때 결정한다.
- **`MenuDataLoader`와 관련 테스트를 삭제**해 시드 소스를 하나로 만든다 (2026-07-10 사용자 확정).
- `TestMemberLoader`는 유지한다 — BCrypt 해시를 런타임에 생성해야 해서 SQL로 옮길 수 없고, dev 전용 편의 기능으로 성격이 다르다.

### 4. 문서 정리

- `docs/migration/`의 기존 SQL 3개는 삭제하지 않고 이관 위치를 명시한 헤더를 달아 이력으로 보존한다: 인덱스 SQL → V1, access_role SQL → V1(컬럼) + V2(방어적 ALTER + 백필), 시드 SQL → V3.
- CLAUDE.md(환경 설정·주의사항 섹션)와 README에 "스키마 변경은 Flyway 마이그레이션으로만" 규칙을 반영한다.
- 기존 DB baseline 사전 점검 체크리스트(1-c)를 docs에 마이그레이션 가이드로 문서화한다.

### 1-c. 기존 DB baseline 사전 점검 절차 (Codex 3차 리뷰 high 지적 반영)

- **문제**: baseline은 "기존 DB 스키마 = V1"을 검증 없이 신뢰한다. 드리프트가 있는 기존 DB가 baseline되면 Flyway history상 V1이 적용된 것처럼 남는다.
- **대응 (2026-07-10 결정: 문서화된 수동 점검)**: 마이그레이션 가이드(docs)에 baseline 전 필수 점검 체크리스트를 문서화한다 — 4개 테이블 `SHOW CREATE TABLE` / `SHOW INDEX` 결과를 V1과 대조하고, 불일치 시 baseline을 중단한다.
- **비례성 근거**: 현존 기존 DB는 V1의 추출 원본인 로컬 dev DB 하나뿐(정의상 일치)이고, CI는 항상 빈 DB라 baseline 경로를 타지 않으며, 컬럼 수준 드리프트는 `ddl-auto: validate`가 모든 환경에서 기동 시마다 검출한다(사각지대는 인덱스뿐). Codex가 권장한 자동 대조 스크립트 + CI 시나리오는 현 단계에서 과잉 방어로 판단, **3단계(실배포) 착수 시점에 재판단**한다.

## 검증 (3종)

| 시나리오 | 방법 | 기대 결과 |
|---|---|---|
| 기존 환경 | 1-c 점검 후 dev DB로 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` 일회성 `bootRun` → 이후 env 없이 재기동 | 1회차: baseline 기록 + V2·V3(모두 no-op) 적용, 스키마·데이터 무변경. 2회차: env 없이 정상 기동(baseline 불필요 확인) |
| **신규 환경 (핵심)** | 별도 포트에 임시 빈 MariaDB 컨테이너 → `bootRun` (baseline env 없이) | V1 + V2 + V3 전체 적용 후 정상 기동, **메뉴 7행 시드 + `access_role IS NULL` 0건 확인** = 신규 배포 리허설 |
| **V3 부모 ID 안정성 (Codex 4차 지적 반영)** | 빈 menu 테이블에 `ALTER TABLE menu AUTO_INCREMENT = 100` 적용 후 V3 실행 | 메뉴 7행 시드되고 자식 3건의 `up_menu_no`가 실제 '회원 관리' 그룹 menu_no와 일치 (고아 없음) |
| **V3 가드 동작 (Codex 2차 지적 반영)** | 신규 환경 DB에서 메뉴 1행의 menu_url 변경 후 V3 체크섬 변조 없이 재기동 + 기존 dev DB 메뉴 행 수 비교 | 기존 데이터가 있는 DB에서 시드 재실행·보충이 일어나지 않음 (세션 변수 가드 실동작 검증) |
| **인덱스 대조 (Codex 지적 반영)** | 신규 환경 DB에 `SHOW INDEX FROM` 4개 테이블 실행 | 엔티티 `@Table` 선언(1번 표)과 1:1 일치 — `idx_visit_at` 포함. validate가 못 잡는 인덱스 누락을 여기서 잡는다 |
| 회귀 | env 명시 후 `./gradlew test` | 전체 통과 (CI가 빈 DB 경로를 다시 검증) |

UI 변경이 없으므로 playwright 검증은 해당 없음.

## 예상 리스크

- `validate` 전환 직후 타입 불일치로 기동 실패 가능(LONGTEXT, enum 컬럼 등) — 실물 추출 방식이라 가능성은 낮지만, 발생 시 V1을 실물에 맞추는 방향으로 수정한다.
- 로컬 검증 시 셸에 오염된 `DB_PASS` 환경변수 함정(기존에 겪은 문제) — 검증 명령에 env를 항상 명시한다.

## 결정 사항 (전부 확정 — 2026-07-10)

- ~~MenuDataLoader 삭제 여부~~ → **삭제 확정** (사용자 결정). 시드 소스는 V3 단일화.
- ~~기존 DB 데이터 보정: 마이그레이션 실행 vs 사전 점검 차단~~ → **V2 보정 마이그레이션으로 확정** (근거는 1-b 참고)
- ~~시드 이관 방식: R__ vs versioned~~ → **V3 + 빈 테이블 가드로 확정** (근거는 3번 참고)
- ~~기존 DB 드리프트 검증: 자동 스크립트 vs 문서화된 수동 점검~~ → **문서화된 수동 점검으로 확정** (근거는 1-c 참고. 자동 스크립트는 3단계 실배포 시점 재판단)
- 메뉴 1~6행 부분 상태의 취급 → **보존** (별도 결정 아님 — 기확정된 "기존 데이터 절대 불가침" 원칙의 따름 결정, 3번 참고)
- ~~V2 백필 미분류 행 기본값: ALL(원본 유지) vs ADMIN(보수)~~ → **ADMIN(fail-closed)으로 확정** (2026-07-10, 근거는 1-b 참고. 알려진 공용 메뉴는 2단계에서 ALL 명시 지정)
- baseline-on-migrate 운용 방식 → **상시 설정 제거, 일회성 env 오버라이드로 확정** (2026-07-10, 근거는 2번 참고. 3차에서 확정한 수동 점검 결정과 양립하는 보강)

## Codex 적대적 리뷰 반영 이력 (2026-07-10)

### 1차 리뷰

| 지적 | 심각도 | 검증 결과 | 반영 |
|---|---|---|---|
| baseline이 access_role 백필을 영구히 건너뜀 | high | 유효 (현 dev DB는 백필 완료 0건이라 실해 없음, 구조적 위험은 실재) | 1-b: `V2__backfill_menu_access_role.sql` 추가 |
| V1 인덱스 체크리스트에 `idx_visit_at` 누락 | medium | 유효 (`VisitLog` 엔티티 `@Table(indexes)` 선언 및 dev DB 실물 확인) | 1번 체크리스트를 전체 목록 표로 교체 + 검증에 `SHOW INDEX` 대조 추가 |

### 2차 리뷰

| 지적 | 심각도 | 검증 결과 | 반영 |
|---|---|---|---|
| access_role 컬럼 없는 기존 DB가 baseline되면 V2가 "unknown column"으로 기동 실패 | high | 유효 (1차 반영이 만든 숨은 전제 — 현존 dev DB는 컬럼 보유로 실해 없으나 일반 기존 DB 경로에서 실재) | 1-b: V2 선두에 `ADD COLUMN IF NOT EXISTS` 방어적 ALTER 추가 |
| R__ 시드가 MenuDataLoader의 count()==0 의미를 바꿔 커스터마이즈된 운영 메뉴를 재생성할 수 있음 | high | 유효 (`MenuDataLoader.java:24` 실코드로 의미 차이 확인. 현 dev DB는 기본 7행 무수정이라 실해 없음. 메뉴 삭제는 소프트라 앱 경유 물리 삭제는 불가하나 URL 변경 시나리오는 실재) | 3번: R__ → V3 versioned + 빈 테이블 세션 변수 가드로 변경, 보충 기능은 미제공 확정 |

### 3차 리뷰

| 지적 | 심각도 | 검증 결과 | 반영 |
|---|---|---|---|
| baseline이 기존 DB 스키마 드리프트를 무검증 승인 | high | 원칙적으로 유효 (단 현존 기존 DB는 V1 추출 원본인 dev DB 하나뿐이고 CI는 항상 빈 DB — 컬럼 드리프트는 validate가 검출, 사각지대는 인덱스) | 1-c: baseline 사전 수동 점검 체크리스트 문서화로 확정. 자동 스크립트는 과잉 방어로 불채택, 3단계 시점 재판단 |
| V3 부분 실패 후 재시도가 영구 결손을 정상 처리로 숨김 | high | **핵심 메커니즘 불성립** — Flyway 공식 FAQ(context7): 마이그레이션별 개별 트랜잭션 + 실패 시 롤백. V3는 순수 DML + 4개 테이블 InnoDB(실물 확인)이므로 부분 상태가 남을 수 없음. 단 MariaDB DDL 암묵적 커밋이 보장을 깨므로 전제 조건 있음 | 3번: "V3에 DDL 금지" 규칙 명문화 + 부분 상태(1~6행) 보존 원칙 명시. SIGNAL 사후 검증은 복잡도 대비 이득 없어 불채택 (리허설의 메뉴 7행 확인이 방어선) |

### 4차 리뷰

| 지적 | 심각도 | 검증 결과 | 반영 |
|---|---|---|---|
| 수동 baseline 점검은 누락 가능 — 상시 baseline-on-migrate가 드리프트를 조용히 승인 | high | 부분 유효 (자동 preflight 부분은 3차 기각 결정의 재제기라 재론 안 함. "상시 설정 제거" 부분은 독립적으로 타당) | 2번: baseline-on-migrate를 상시 설정에서 제거, 기존 DB 전환 시 일회성 env 오버라이드로 변경 |
| 백필 미커버 메뉴가 ALL로 노출 (보안 영향) | high | 부분 유효 — "null로 남는다"는 부정확(catch-all이 null 소진). 실쟁점은 미분류 행 기본값 방향이며, accessRole은 노출 제어일 뿐 실제 차단은 Security(403)라 권한 침해는 아님 | 1-b: 백필 3단계 재설계(알려진 ADMIN → 알려진 ALL 명시 → 나머지 ADMIN fail-closed). NOT NULL 전환은 앱의 null→ALL 정규화 계약과 충돌해 불채택(후속 과제) |
| V3 부모 메뉴 ID 고정값 가정 위험 (AUTO_INCREMENT ≠ 1) | medium | 유효 (COUNT=0이어도 과거 삭제 이력으로 AUTO_INCREMENT가 1이 아닐 수 있음 — 구체적이고 실재하는 함정) | 3번: `LAST_INSERT_ID()` 세션 변수 캡처 규칙 명문화 + 검증에 AUTO_INCREMENT=100 케이스 추가 |

### 5차 리뷰

| 지적 | 심각도 | 검증 결과 | 반영 |
|---|---|---|---|
| MenuDataLoader 삭제로 부분 메뉴 상태(1~6행)의 런타임 보정 경로가 사라져 영구 미복구 — 자동 보정 vs 배포 실패 처리 결정 필요 | high | **전제 부정확 — 기각** (`MenuDataLoader.java:24`는 `count() > 0`이면 전체 스킵: 부분 상태 보정 능력이 원래 없었고, non-dev에는 `@Profile("dev")`라 존재하지도 않음. 삭제 전후로 부분 상태 DB의 동작은 동일. 행 단위 보충은 수동 시드 SQL만의 기능이었고 2차 리뷰에서 커스터마이즈 재생성 위험 때문에 의도적으로 포기 확정. 부분 상태 발생 경로의 실재성도 낮음 — V3는 롤백 보장으로 자신이 만들 수 없고, 앱은 소프트 삭제만 지원하며, 현존 DB는 dev 7행 완전·prod 없음) | 3번에 "보존은 회귀가 아님" 근거 명문화. Codex가 요구한 재결정은 기확정 사항("보충 미제공, 필요 시 별도 마이그레이션")의 재제기라 재론 안 함 |
| baseline 안전성이 수동 체크에 의존 — 자동 preflight(스키마 대조 스크립트) 필요 | medium | **재제기 — 기각 유지** (3차 high로 제기되어 "현 단계 과잉 방어, 3단계 실배포 시점 재판단"으로 확정, 4차에서도 재론 안 함 처리. 5차의 논거 — baseline 후 불일치 영구 은폐, validate의 인덱스 사각지대 — 는 모두 1-c에 이미 기록된 내용으로 신규 사실 없음. 4차 반영으로 baseline-on-migrate가 일회성 env 오버라이드로 축소되어 노출 창도 이미 최소화됨) | 반영 없음 — 1-c 결정 유지 (자동 스크립트는 3단계 실배포 시점 재판단) |
