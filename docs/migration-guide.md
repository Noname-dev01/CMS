# Flyway 마이그레이션 가이드

> 작성일: 2026-07-10 (Flyway 도입 PR)

## 기본 원칙

- **스키마 변경은 Flyway 마이그레이션 파일로만 한다.** `ddl-auto`는 `validate`로 고정되어 있어 Hibernate가 스키마를 변경하지 않는다.
- 마이그레이션 파일 위치: `src/main/resources/db/migration/`
- 파일명 규칙: `V{버전}__{설명}.sql` (버전은 마지막 버전 +1, 설명은 snake_case)
- 한 번 머지된 마이그레이션 파일은 **절대 수정하지 않는다** (체크섬 불일치로 기동 실패). 수정이 필요하면 새 버전을 추가한다.

## 현재 마이그레이션 구성

| 버전 | 파일 | 내용 |
|---|---|---|
| V1 | `V1__init_schema.sql` | baseline 스키마 (member, menu, admin_action_log, visit_log — 인덱스 포함, dev DB 실물 추출) |
| V2 | `V2__backfill_menu_access_role.sql` | 방어적 `ADD COLUMN IF NOT EXISTS` + access_role 3단계 백필 (멱등). 컬럼은 권한관리 PR ②부터 엔티티에서 매핑하지 않고 V16에서 제거된다 |
| V3 | `V3__seed_default_menus.sql` | 기본 메뉴 시드 — menu 테이블이 완전히 빌 때만 실행 (보충 기능 없음) |
| V4~V12 | (파일명 참조) | 공지·첨부·감사 로그 라벨 등 — `src/main/resources/db/migration/` 참조 || V13 | `V13__create_permission_tables.sql` | 권한 테이블 `permission_role`·`role_permission` (DDL만) || V14 | `V14__seed_manager_notice_permissions.sql` | MANAGER × 공지 4동작 시드 — **일회성 초기화이며 복구 수단이 아니다**(수동 재실행은 회수한 권한을 되살린다) || V15 | `V15__seed_permission_menu.sql` | "권한 관리" 메뉴 시드(멱등, 최상위 맨 끝, `ord`는 INT 상한으로 제한) || V17 | `V17__add_member_permission_version.sql` | `member.permission_version`(회원별 권한 낙관적 버전, DDL 1문) || V18 | `V18__create_member_permission.sql` | 회원별 허용 행 테이블 `member_permission`(DDL 1문) || V19 | `V19__copy_manager_permissions_to_members.sql` | 배포 시점의 `ROLE_MANAGER` 허용 행을 `DELETED`가 아닌 기존 MANAGER 전원에게 복사 — **일회성 초기화이며 복구 수단이 아니다**(수동 재실행은 회수한 권한을 되살린다) || V16 | `V16__drop_menu_access_role.sql` | `menu.access_role` 컬럼 제거(`DROP COLUMN IF EXISTS`, **되돌릴 수 없음 — 아래 백업 필수**) |


## V16 배포 전 백업과 복구 (menu.access_role 제거, 권한관리 PR 4/4)

V16은 `menu.access_role` 컬럼을 지운다. 사이드바 노출은 PR ②부터 권한 판정기가 정하고 앱은 이 컬럼을 읽지 않지만, **컬럼 값은 되돌릴 수 없이 사라지고 PR ① 이전 코드는 이 컬럼을 매핑해 기동에 실패한다**(롤백 호환표: `docs/deployment.md` "권한관리 롤백 주의").

- **배포 전 필수**: DB 백업을 먼저 받는다(`make prod-backup` — `docs/deployment.md` "백업", 논리 덤프에 `menu` 테이블 전체가 들어간다). 추가로 값만 따로 남기려면 `SELECT menu_no, menu_url, access_role FROM menu;` 결과를 파일로 저장해 둔다.
- 확인: 배포 직전에 V15까지 적용돼 있는지(`SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;`), 이 PR 이전 코드로 되돌릴 계획이 없는지 점검한다. 되돌려야 하면 아래 복구를 먼저 한다.
- **복구(이전 코드로 되돌려야 할 때만)**: ① `ALTER TABLE menu ADD COLUMN access_role VARCHAR(20) NULL;` ② 백업(또는 저장해 둔 값)에서 `UPDATE`로 값을 복원 ③ 이전 앱 배포. `flyway_schema_history`의 V16 기록은 그대로 두면 이전 코드(마이그레이션 파일에 V16이 없는 버전)가 `validate`에서 이력 불일치로 실패할 수 있으므로, 이 경우는 **되돌리기보다 roll-forward(수정 버전 배포)를 기본**으로 한다.
- **`IF EXISTS`는 SQL을 다시 돌릴 때만 멱등이다** — 컬럼이 이미 없는 환경(수동 삭제 등)에서 V16 SQL이 실패하지 않는다는 뜻이지, 실패한 마이그레이션의 재기동 복구가 된다는 뜻이 아니다.
- **V16 실패 복구(`flyway_schema_history`에 `success=0` 기록이 남은 경우)**: 잠금 대기 초과(`ALTER`가 장시간 메타데이터 잠금에 막힘) 등으로 V16이 실패하면 Flyway가 실패 기록을 남기고, **다음 기동의 `migrate`는 SQL을 실행하기 전에 "failed migration to version 16"으로 중단한다 — 단순 재기동으로는 복구되지 않는다**(원인을 없애도 마찬가지, `MenuAccessRoleDropMigrationTest`가 재현). ① 실패 원인 제거(예: 긴 트랜잭션·잠금 해소, 앱 인스턴스 정지) ② 실제 상태 확인 — `SHOW COLUMNS FROM menu LIKE 'access_role';`(컬럼이 남아 있어야 정상 실패, 이미 없으면 DDL이 적용된 것)와 `SELECT version, success FROM flyway_schema_history WHERE version = '16';` ③ **같은 마이그레이션 구성(`locations`·접속 정보)으로 `flyway repair`**(실패 기록 제거) ④ 재기동(`migrate` 재실행)으로 성공을 확인한다. 부분 적용 흔적이 없는 단일 `ALTER`라 별도 수동 정리는 필요 없다(V13과 달리).

## 환경별 동작

- **빈 DB (CI·신규 환경)**: 별도 설정 없이 V1부터 전체 실행된다. `baseline-version: 1`은 빈 DB에는 영향이 없다.
- **기존 DB (Flyway 도입 전부터 데이터가 있는 환경)**: 아래 전환 절차를 따라 **일회성 baseline**을 수행한다. baseline 후 V1은 건너뛰고 V2부터 적용된다.

## 기존 DB 전환 절차 (일회성)

`baseline-on-migrate`는 상시 설정에 두지 않는다 — 사전 점검을 건너뛴 기존 DB가 조용히 영구 baseline되는 것을 막기 위함이다. 전환 시에만 환경변수로 1회 켠다.

### 1. 사전 점검 체크리스트 (필수)

baseline은 "기존 DB 스키마 = V1"을 검증 없이 신뢰한다. 드리프트가 있는 DB가 baseline되면 Flyway history에 V1이 적용된 것처럼 영구 기록되므로, **baseline 전에 반드시 아래를 대조한다.**

```sql
-- 4개 테이블 전부 실행해 V1__init_schema.sql과 대조
SHOW CREATE TABLE member;
SHOW CREATE TABLE menu;
SHOW CREATE TABLE admin_action_log;
SHOW CREATE TABLE visit_log;

-- 인덱스 대조 (ddl-auto: validate는 인덱스를 검증하지 않는다 — 여기가 유일한 방어선)
SHOW INDEX FROM member;            -- uk_member_user_id, uk_member_email
SHOW INDEX FROM admin_action_log;  -- idx_log_create_at_id(create_at,id), idx_log_action_user_id, idx_log_action_type
SHOW INDEX FROM visit_log;         -- idx_visit_at
SHOW INDEX FROM menu;              -- (PRIMARY만)
```

- 컬럼 정의·타입·인덱스가 V1과 **하나라도 다르면 baseline을 중단**하고, 차이를 해소(수동 ALTER로 V1에 맞춤)한 뒤 다시 점검한다.
- `AUTO_INCREMENT=N` 값 차이는 데이터 상태이므로 무시한다.

### 2. 일회성 baseline 기동

```bash
# 사전 점검 통과 후 1회만
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true ./gradlew bootRun
```

- 1회차 기동: baseline(version 1) 기록 + V2·V3 적용 (백필 완료된 DB에서는 둘 다 no-op).
- 이후 기동부터는 `flyway_schema_history`가 존재하므로 환경변수 없이 정상 기동된다.

### 3. 전환 확인

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
-- 기대: << Flyway Baseline >> (1), V2, V3 모두 success=1
```

## 시드 데이터 정책

- 기본 메뉴 시드(V3)는 **menu 테이블이 완전히 비었을 때만** 전체 실행된다. 행이 하나라도 있으면 커스터마이즈된 데이터로 간주해 건드리지 않는다 — 부분 상태 "보충" 기능은 의도적으로 없다. 필요해지면 별도 마이그레이션으로 그때 결정한다.
- 기본 관리자 계정은 SQL이 아니라 `TestMemberLoader`(dev 전용)가 담당한다 — BCrypt 해시를 런타임에 생성해야 하기 때문.

## 새 마이그레이션 작성 시 주의

- **DML 마이그레이션에 DDL을 섞지 않는다.** MariaDB에서 DDL은 암묵적 커밋을 유발해 실패 시 롤백 보장이 깨진다.
- 시드/백필류는 항상 멱등하게 작성한다 (`WHERE ... IS NULL`, 세션 변수 가드 등).
- **권한 테이블 시드(V14)는 일회성 초기화이지 복구 수단이 아니다.** `WHERE NOT EXISTS`는 부분 실패 후 재시도에서 중복 삽입을 막을 뿐이며, 이미 성공한 V14를 수동으로 다시 실행하면 ADMIN이 회수한 MANAGER 권한을 되살린다(Flyway는 성공한 마이그레이션을 재실행하지 않는다). 누락·삭제된 권한은 권한관리 화면/API로만 복구한다 — `com.cms.admin.permission`의 `CLAUDE.md` 참조.
- **V17~V19 실패 복구**(DDL 암묵 커밋으로 Flyway 이력과 어긋난 경우 — V17·V18은 파일당 DDL 1문이라 파일 안 부분 성공은 없지만, **DDL 커밋 후 이력 기록 전에 프로세스가 끊기면** 실제 스키마와 `flyway_schema_history`가 어긋난다): ① 실패 원인 제거·앱 인스턴스 정지 ② 실제 상태 확인 — `SHOW COLUMNS FROM member LIKE 'permission_version';`·`SHOW TABLES LIKE 'member_permission';`와 `SELECT version, success FROM flyway_schema_history WHERE version IN ('17','18','19');` ③ **버전별로 분기한다** — 해당 버전 행이 `success=1`이면 그 버전의 객체는 **건드리지 않는다**(`repair`는 성공한 마이그레이션을 되감지도 재실행하지도 않으므로 지우면 되살릴 방법이 없다). `success=0`이거나 이력이 없는데 DDL만 적용된 버전의 잔여 객체만 수동 `DROP`한다(시드 V19 전이라 비어 있어 안전: V17은 `ALTER TABLE member DROP COLUMN permission_version;`, V18은 `DROP TABLE member_permission;`). 예: V17 `success=1` + V18만 실패면 `member_permission`만 DROP하고 `permission_version`은 남긴다. ④ **같은 마이그레이션 구성으로 `flyway repair`**(실패 이력만 정리) ⑤ 재기동(`migrate`)으로 남은 버전이 실행돼 V17~V19 성공 확인. V19(DML)가 일부만 반영된 경우는 `NOT EXISTS`로 재시도해도 안전하다. `MemberPermissionMigrationTest`가 "V17 성공 + V18 실패" 복구와, 두 객체를 다 지우면 `permission_version`이 영영 없는 반례를 고정한다.
- **V20 실패 복구**(알림 `notification` 테이블, 2026-10-05): V20은 DDL 1문이라 파일 안 부분 성공은 없지만 **DDL 커밋 후 이력 기록 전에 프로세스가 끊기면** "테이블은 있고 성공 이력은 없는" 상태나 `success=0` 이력이 남는다. ① 앱 인스턴스 정지 ② 실제 상태 확인 — `SHOW TABLES LIKE 'notification';`와 `SELECT version, success FROM flyway_schema_history WHERE version = '20';` ③ **V20이 `success=1`이면 테이블을 건드리지 않는다**(실제 알림 데이터가 든 테이블을 일괄 DROP하지 않는다). `success=0`이거나 이력이 없는데 테이블만 있으면 알림 생성 전이라 비어 있으므로 `DROP TABLE notification;` ④ 같은 마이그레이션 구성으로 `flyway repair` ⑤ 재기동(`migrate`). 정상 적용 후 이전 앱(V20 파일 없음)을 띄워도 Flyway는 이력에 있고 코드에 없는 마이그레이션을 기본값으로 무시하므로 기동할 수 있고, 알림은 다른 테이블을 되살리는 데이터가 아니라 신버전 재배포 때 별도 정리가 필요 없다. `NotificationMigrationTest`가 "V20 객체만 있고 성공 이력 없음" 복구를 고정한다.
- **V13 부분 성공 복구**: `CREATE TABLE` 2개(`permission_role`, `role_permission`) 중 첫 번째만 성공하고 실패하면 MariaDB DDL 암묵 커밋 때문에 `flyway_schema_history`에 실패 기록이 남는다. ① 실패 원인 제거 ② 두 테이블 존재 여부를 확인해 **존재하는 것을 수동 `DROP`**(권한 행이 아직 시드 전이라 안전, `role_permission`이 FK로 `permission_role`을 참조하므로 `role_permission`을 먼저) ③ `flyway repair` 후 재기동.
- 여러 행을 조건부로 INSERT할 때 행 단위 `(SELECT COUNT(*) ...) = 0` 조건은 첫 INSERT 직후 거짓이 되는 함정이 있다 — 선두에서 세션 변수로 초기 상태를 캡처한다 (V3 참고).
- 부모-자식 FK성 참조는 `LAST_INSERT_ID()`로 실제 생성 ID를 캡처한다. AUTO_INCREMENT 시작값을 가정하지 않는다.
- 기존 DB에도 도달해야 하는 데이터 보정은 반드시 V2 이상(= baseline-version 초과)에 둔다.
