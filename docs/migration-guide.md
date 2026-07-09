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
| V2 | `V2__backfill_menu_access_role.sql` | 방어적 `ADD COLUMN IF NOT EXISTS` + access_role 3단계 백필 (멱등) |
| V3 | `V3__seed_default_menus.sql` | 기본 메뉴 시드 — menu 테이블이 완전히 빌 때만 실행 (보충 기능 없음) |

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
- 여러 행을 조건부로 INSERT할 때 행 단위 `(SELECT COUNT(*) ...) = 0` 조건은 첫 INSERT 직후 거짓이 되는 함정이 있다 — 선두에서 세션 변수로 초기 상태를 캡처한다 (V3 참고).
- 부모-자식 FK성 참조는 `LAST_INSERT_ID()`로 실제 생성 ID를 캡처한다. AUTO_INCREMENT 시작값을 가정하지 않는다.
- 기존 DB에도 도달해야 하는 데이터 보정은 반드시 V2 이상(= baseline-version 초과)에 둔다.
