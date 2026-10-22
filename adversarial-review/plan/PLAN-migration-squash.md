# PLAN — Flyway 마이그레이션을 기준 스키마로 합치기 (실배포 전)

> 상태: v2 — 적대적 리뷰 2라운드 **ship**(codex gpt-6.1-sol, 1라운드 P1×1·P2×2 반영, 2라운드 새 지적 없음). 테스트 속도 조사(#166 tmpfs 전환) 중 "배포 전인데 개발 중 생긴 마이그레이션 58개를 계속 들고 가야 하는가"라는 의문에서 시작했다.

## 개정 이력

- 2라운드: codex gpt-6.1-sol **ship — 새 지적 없음**. 이력 테이블 분리 검증, 시드의 칸별 시각 비교, 이후 업그레이드 경로 시험 유지가 설계에 반영됐음을 저장소와 대조해 확인. 실제 스키마 동일성·단언 이전 누락·환경 재생성은 구현 후 완료 기준으로 검증한다.
- v2 변경(1라운드 — codex gpt-6.1-sol, `needs-attention` P1×1·P2×2, 결정 필요 1건):
  - R1-1(P1 Flyway 이력 테이블): 설계 2·3 변경 — 덤프에 `--ignore-table=<db>.flyway_schema_history`, 동일성 비교 전 항목에서 이 테이블 제외. B의 이력은 "V1·V2 두 행 모두 `success=1`" + Flyway `validate` 통과로 따로 확인한다. 넣으면 Flyway가 관리하는 테이블을 V1이 다시 만들려 하고, 비교하면 이력 행 수(58 대 2)가 달라 차이 0이 성립할 수 없다.
  - R1-2(P2 시각 컬럼 일괄 제외): 설계 2·3 변경 — 시드는 행마다 옛 시드의 형태(`NULL` 또는 `NOW()`)를 그대로 재현한다(예: `V51` 사이트 2행은 `NULL`, `V3` 메뉴는 `NOW()`). 비교는 A에서 `NOW()`로 들어간 칸만 "양쪽 다 NOT NULL"로 정규화하고 그 밖의 시각 칸은 값(`NULL` 포함)까지 비교한다. 일괄 제외하면 `NULL`→`NOW()` 회귀를 놓친다. "시드 시각은 `NOW()`"라는 v1 서술은 사실과 달랐다.
  - R1-3(P2 실배포 전 새 마이그레이션의 업그레이드 시험): **사용자 결정 ① — 업그레이드 시험 유지.** 설계 5 변경 — v1의 "실배포 전까지는 신규 설치 결과만 단언"을 철회한다. 합친 뒤에도 dev·prod 검증 DB가 다시 생기고 V3부터는 그 DB의 기존 행을 실제로 업그레이드하므로, 기존 행에 영향을 주는 마이그레이션에는 지금처럼 업그레이드 경로 시험을 쓴다(다음 합치기에서 정리된다는 사실이 그전까지의 검증 가치를 없애지 않는다).

## Context

`src/main/resources/db/migration/`에 V1~V58이 쌓여 있다. 이 이력이 지키는 것은 "V1부터 차례로 올라온 기존 DB를 안전하게 업그레이드한다"인데, **그런 DB가 존재하지 않는다**.

- 실배포는 하지 않았다 — prod는 "배포 가능 상태 검증"까지만 했고 실제 인터넷 배포는 범위 밖이다(`docs/deployment.md` 범위 절).
- V58까지 올라온 DB는 이 PC의 dev DB와 prod 검증 볼륨·백업뿐이다(사용자 확인, 2026-10-21 — 다른 PC·서버·공유 백업 없음). 실배포 시점은 미정이다.

그런데도 이력에 묶인 비용은 계속 쌓인다.

| 비용 | 근거 |
|---|---|
| 업그레이드 경로 시험 26개(118건) — 지킬 대상 DB가 없다 | `*MigrationTest.java` 26개, tmpfs 후에도 101초(전체 클래스 시간 489초의 21%) |
| 데이터 변환 마이그레이션 9개(공지 이관 V32, 메뉴 합치기 V57 등)의 시나리오·복구 절차 유지 | `UPDATE`/`DELETE`/`INSERT … SELECT` 포함 파일 9개 |
| 버전 번호에 묶인 문서 | `docs/migration-guide.md` 227곳, `docs/deployment.md` 87곳, 루트·패키지 `CLAUDE.md` 15개 파일, `docs/troubleshooting.md` 7곳 |
| 버전 번호를 언급하는 본 코드 주석 | `src/main/java` 103곳 |
| 테이블 하나를 이해하려면 여러 파일의 `ALTER`를 따라가야 한다 | 예: `content_image`는 V24·V27·V43~V45 |

실배포 직전에 한 번 합치는 것이 일반적이지만, 시점이 미정이라 그때까지 위 비용이 계속 늘어난다. **지금 한 번 합치고, 문서 부채를 지금 털어낸다.** 실배포 직전에는 그 사이 생긴 V3~Vn만 다시 접는 작은 작업이 남는다.

### 확정 결정 (사용자, 2026-10-21)

| 항목 | 결정 |
|---|---|
| 시점 | **지금** 합친다(실배포 직전까지 미루지 않는다) |
| 파일 구성 | **2개** — `V1__baseline_schema.sql`(테이블·인덱스·제약), `V2__baseline_seed.sql`(시드 데이터) |

### 정찰 요약 (읽은 근거)

| 사실 | 근거 |
|---|---|
| Flyway 설정은 `baseline-version: 1`뿐이고 `baseline-on-migrate`는 상시 꺼져 있다(Flyway 이전 기존 DB 전환 시에만 일회성 환경변수) | `application.yml` `spring.flyway` |
| `ddl-auto: validate` — 엔티티와 스키마가 다르면 기동 실패. 합친 스키마의 정합성 1차 방어선 | `application.yml` `spring.jpa.hibernate` |
| 운영 스크립트 중 이력에 의존하는 곳은 `prod-backup.sh`의 `flyway_max_version` 기록(메타데이터용)뿐 | `scripts/prod-backup.sh:92-122` |
| 문서의 수동 회수 절차가 `flyway_schema_history WHERE version = '32'`를 가드로 쓴다 | `docs/deployment.md:516` |
| 마이그레이션 시험 밖에서 `db/migration`을 읽는 시험은 없다. 버전 번호는 시험 4개의 `@DisplayName`·주석에만 있다 | `BannerServiceTest:221`, `AdminActionLogAspectTest:155`, `MenuExposureSidebarIntegrationTest:131·135`, `AdminSearchIntegrationTest:158` |
| 시드는 `menu`를 AUTO_INCREMENT로 넣고(명시 id 없음) V57이 행을 지워 `menu_no`에 빈 번호가 있다. `site`는 `id=1`(관리자)·`id=2`(`main`)이 코드 계약이다 | `V3`·`V15`·`V28`… `INSERT INTO menu`, `com.cms.admin.site` `CLAUDE.md` |
| 콜레이션을 명시하는 파일이 19개(`utf8mb4_nopad_bin` 정확 일치 비교 등) | `grep -l COLLATE` |
| 동결·미사용 레거시가 남아 있다 — `notice`·`notice_attachment`(V32 이후 동결 스냅샷), 배너·팝업 옛 `storage_key` 컬럼(NULL 허용·미사용) | 루트 `CLAUDE.md` 핵심 도메인 모델, `com.cms.admin.media` `CLAUDE.md` |
| 시드 `V3`·`V9`가 DB `NOW()`를 쓰는 것이 `ClockUsageConventionTest`의 문서화된 한계다 | 루트 `CLAUDE.md` "시각 원천은 KST Clock 하나" |
| 테스트 DB는 MariaDB 10.11(digest 고정), `mariadb-dump` 사용 가능 | `MariaDbContainerSupport` |

## 설계

### 1. 범위: 순수 합치기 — 결과 스키마를 바꾸지 않는다

새 V1+V2로 만든 DB는 옛 V1~V58로 만든 DB와 **스키마·시드가 같아야 한다**(아래 3의 동일성 검증으로 증명). 합치는 김에 레거시를 지우거나 이름을 고치지 않는다 — 두 변경이 섞이면 동일성 검증이 성립하지 않는다.

**범위 밖(후속 후보)**: `notice`·`notice_attachment` 동결 테이블 제거, 배너·팝업 옛 `storage_key` 컬럼 제거, 그 밖에 이관 때문에만 남은 구조. 운영 DB가 없으니 이것들도 지울 수 있지만, 합치기 PR이 머지된 뒤 일반 마이그레이션(V3~)으로 따로 한다.

### 2. 기준 스키마 만들기

1. 빈 MariaDB(테스트와 같은 digest)에 현재 V1~V58을 적용한다.
2. `V1__baseline_schema.sql`: `mariadb-dump --no-data --skip-comments --skip-add-drop-table --skip-dump-date --ignore-table=<db>.flyway_schema_history`로 테이블 정의를 뽑은 뒤 사람이 읽을 수 있게 정리한다(이력 테이블은 Flyway가 만든다 — R1-1).
   - 테이블 옵션의 `AUTO_INCREMENT=n`은 지운다(시드가 정한다).
   - FK가 걸리는 순서로 테이블을 배치한다. `SET FOREIGN_KEY_CHECKS=0`에 기대지 않는다.
   - 테이블마다 한국어 주석을 단다 — 지금 마이그레이션 주석에만 있는 **설계 이유**(예: 콜레이션을 `nopad_bin`으로 둔 이유, 가드 행 테이블의 목적)를 옮긴다. 이관 절차·복구 방법 같은 "업그레이드 이야기"는 옮기지 않는다.
3. `V2__baseline_seed.sql`: 시드 행을 **id까지 명시해서** 넣는다(`menu_no`의 빈 번호 포함 — 동일성 검증을 엄격하게 하려는 것이고, 코드가 번호에 기대는지 따지지 않아도 된다). 시각 컬럼은 **행마다 옛 시드의 형태를 그대로** 쓴다 — 옛 시드가 `NULL`로 넣은 칸은 `NULL`(예: `V51` 사이트 2행), `NOW()`로 넣은 칸은 `NOW()`(예: `V3` 메뉴). 구현 때 옛 시드 파일을 행 단위로 대조해 칸별 표를 만든다(R1-2). 명시 id로 넣은 테이블은 다음 AUTO_INCREMENT가 옛 체인과 같은지 검증 항목에 넣는다.
4. 옛 V1~V58 파일을 삭제한다.

### 3. 동일성 검증 (PR에서 일회성 수행, 결과를 PR 본문에 기록)

같은 MariaDB 이미지에 스키마 두 개를 만든다 — A: 옛 V1~V58(합치기 직전 `master` 커밋의 파일), B: 새 V1~V2. 다음을 정규화해서 비교하고 **차이 0**이어야 한다. **`flyway_schema_history`는 모든 항목에서 제외**하고, B의 이력은 "V1·V2 두 행, 모두 `success=1`"과 Flyway `validate` 통과로 따로 확인한다(R1-1).

| 비교 대상 | 출처 |
|---|---|
| 테이블·컬럼(순서, 타입, NULL, 기본값, 문자셋, 콜레이션, extra) | `information_schema.COLUMNS` |
| 테이블 옵션(엔진, 기본 콜레이션, row format) | `information_schema.TABLES`(AUTO_INCREMENT 제외) |
| 인덱스(이름, 유일성, 컬럼 순서, 접두 길이) | `information_schema.STATISTICS` |
| FK(참조 테이블·컬럼, ON DELETE/UPDATE) | `KEY_COLUMN_USAGE` + `REFERENTIAL_CONSTRAINTS` |
| CHECK 제약 | `information_schema.CHECK_CONSTRAINTS` |
| 다음 AUTO_INCREMENT 값 | `information_schema.TABLES.AUTO_INCREMENT` |
| 모든 테이블의 모든 행 | 테이블별 `SELECT … ORDER BY` PK. 시각 컬럼은 **A에서 `NOW()`로 들어간 칸만** "양쪽 다 NOT NULL"로 정규화하고, 그 밖의 칸은 `NULL` 여부와 값까지 그대로 비교한다(R1-2) |
| 보조 확인 | 테이블별 `SHOW CREATE TABLE`(AUTO_INCREMENT 절 제거) 텍스트 비교 |

검증 스크립트는 저장소에 남기지 않는다(합친 뒤에는 비교 대상인 옛 파일이 없다). 비교 쿼리와 결과 요약을 PR 본문에 남긴다.

**검증이 잡지 못하는 것(수용)**: InnoDB 내부 상태(instant `ADD COLUMN` 이력 등)는 다르다. 새 V1로 만든 DB가 이후 모든 환경의 출발점이 되므로 B가 기준이고, A의 내부 상태를 재현할 이유가 없다.

### 4. 시험 정리

- **마이그레이션 시험 26개는 삭제한다.** 업그레이드 경로(옛 버전 데이터 → 새 버전)를 검증하는 것이라 대상이 사라진다.
- 삭제 전에 각 클래스의 단언을 **"신규 설치 결과" / "업그레이드 경로"** 로 분류한다. 신규 설치 결과 단언(예: 시드 메뉴 구조, 기본 공개 사이트 1개, 공지 게시판 `board_key='NOTICE'` 존재) 가운데 **다른 통합 시험이 이미 고정하지 않는 것**만 새 시험 하나(`BaselineSeedIntegrationTest`, 일반 Spring 컨텍스트 + 기존 DB)로 옮긴다. 분류표를 PR 본문에 남긴다.
- 시험 4개의 `@DisplayName`·주석에 있는 버전 번호는 뜻으로 바꾼다(예: "V34 시드 누락" → "가드 행 시드 누락").
- 기대 효과: 마이그레이션 시험 101초 → 0, 새 시험 1개(수 초 이내).

### 5. 규칙 개정 (루트 `CLAUDE.md`, `docs/migration-guide.md`)

- 현행: "머지된 마이그레이션 파일은 수정 금지(체크섬 불일치로 기동 실패)".
- 개정: **"머지된 마이그레이션 파일은 수정 금지. 예외는 실배포 전 기준 스키마로 합치는 것뿐이다 — 2026-10-21에 V1~V58을 합쳤고, 첫 실배포 직전에 마지막으로 한 번 더 합친 뒤에는 예외가 없어진다."**
- 새 마이그레이션(V3~)의 시험 정책은 **지금과 같다** — 기존 행에 영향을 주는 마이그레이션(기존 테이블에 `NOT NULL`·유니크·CHECK 추가, 컬럼 타입 변경, `UPDATE`·`DELETE`·`INSERT … SELECT` 같은 데이터 변환)에는 업그레이드 경로 시험을 쓴다. 새 테이블 생성이나 시드만 있는 마이그레이션은 신규 설치 결과로 충분하다. 이 시험들은 실배포 직전 합치기 때 이번처럼 정리된다(R1-3, 사용자 결정 ①).
- `docs/migration-guide.md`의 "기존 DB 전환 절차(일회성 baseline)"는 Flyway 도입 전 DB를 위한 것이고 그런 DB도 없다 → 삭제. `baseline-version: 1` 설정과 주석도 함께 정리한다(신규 V1과 baseline 번호가 겹쳐 오해를 부른다).
- 시드 `NOW()` 한계 서술은 "`V3`·`V9`"에서 "`V2__baseline_seed.sql`"로 바꾼다.

### 6. 문서·주석 정리

버전별 배포 주의·롤백 호환표·실패 복구 절차는 "기존 DB를 V(n−1)에서 V(n)으로 올릴 때"의 이야기라 대상이 없다. 다음 원칙으로 정리한다.

| 내용 | 처리 |
|---|---|
| 업그레이드·롤백·실패 복구 절차(버전별) | **삭제** |
| 현재 스키마의 설계 이유·불변식(잠금 규약, 콜레이션, 가드 행 등) | **유지** — 버전 번호를 지우고 테이블·기능 이름으로 가리킨다 |
| 수동 회수 절차의 `version = '32'` 가드 | 가드의 목적("공지 이관이 끝난 DB인지")이 사라지므로 해당 단계 삭제, 나머지 회수 절차는 유지 |
| "롤백 시 권한 부활"류 주의 | 앱 코드 롤백(DB는 그대로)에 관한 것은 유지, 마이그레이션 버전에 관한 것은 삭제 |
| 본 코드 주석의 버전 번호(103곳) | 뜻으로 바꾼다. 설계 이유는 유지, 이관 이력 서술은 삭제 |
| 이미지 스냅샷·`docs/migration/2026-07-09_seed_default_menus.sql` 등 과거 기록 | 손대지 않는다(이력 자료) |

### 7. 환경 재생성 (머지 후 사용자 작업)

- 이 PC의 dev DB: 새 V1과 체크섬이 달라 기동이 실패한다 → dev DB 볼륨을 지우고 다시 만든다. 정확한 명령은 구현 때 `Makefile`·compose 파일로 확인해 PR에 적는다.
- prod 검증 볼륨과 기존 백업: 같은 이유로 못 쓴다 → 볼륨을 지우고, 기존 백업은 "옛 체인 백업이라 복원 불가"로 표시하거나 지운다.
- CI는 매번 빈 DB라 영향이 없다(`prod-smoke`의 백업·복구 왕복 포함).

## PR 분할

| PR | 내용 | 근거 |
|---|---|---|
| ① 기준 스키마 | 설계 2·3·4·5 — 새 V1·V2, 옛 파일 삭제, 동일성 검증 결과, 시험 정리, 규칙 개정(루트 `CLAUDE.md`, `migration-guide.md`), `application.yml` baseline 주석 | 스키마·시험·규칙은 함께 바뀌어야 CI가 의미를 가진다 |
| ② 문서·주석 | 설계 6 — `deployment.md`, 패키지 `CLAUDE.md`들, `troubleshooting.md`, 본 코드 주석 | 동작 변경이 없는 대량 텍스트 변경이라 리뷰를 분리한다. ① 머지 직후 바로 진행해 문서가 낡은 기간을 짧게 한다 |

## 완료 기준

- [ ] 새 V1·V2로 만든 DB와 옛 V1~V58로 만든 DB의 동일성 검증 차이 0(설계 3의 모든 항목)
- [ ] `./gradlew cleanTest test` 전체 통과, CI(`test`·`prod-smoke`) 통과
- [ ] 마이그레이션 시험 26개 삭제, 신규 설치 단언 분류표와 이전 결과를 PR 본문에 기록
- [ ] 규칙 개정이 루트 `CLAUDE.md`·`migration-guide.md`에 반영
- [ ] (PR ②) 버전 번호 잔여: `docs`·`CLAUDE.md`·`src/main/java`에서 `\bV[0-9]{1,2}\b` 검색 결과가 과거 기록 자료와 새 V1·V2 언급뿐
- [ ] dev DB·prod 검증 볼륨 재생성 절차를 PR에 적고, 이 PC에서 실제로 수행해 앱 기동 확인

## 위험과 대응

| 위험 | 대응 |
|---|---|
| 합친 스키마가 미묘하게 다르다(콜레이션, 기본값, 인덱스 접두 길이 등) | 설계 3의 정규화 비교 + `ddl-auto: validate` + 전체 시험 |
| 시드 id·AUTO_INCREMENT가 달라 코드 계약(`site` `id=1·2`)이 깨진다 | id 명시 시드 + AUTO_INCREMENT 비교 항목 |
| 문서 정리 중 아직 유효한 운영 지식(잠금 규약 등)까지 지운다 | 설계 6의 "유지" 원칙, PR ②를 별도 리뷰 |
| 실배포 전에 누군가 V58 체인 DB를 만들어 둔다 | 사용자 확인으로 없음. 머지 후 재생성 절차를 따른다 |
| 다음 합치기(실배포 직전)를 잊는다 | 규칙 개정 문구에 명시 + 로드맵 "실배포 인프라" 항목의 선행 작업으로 기록 |
