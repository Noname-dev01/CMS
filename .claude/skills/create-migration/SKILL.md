---
name: create-migration
description: 새 Flyway 마이그레이션(V{n})을 이 프로젝트 규칙대로 만든다 — 번호 계산, SQL/Java 선택, 멱등·DDL/DML 분리, 업그레이드 경로 시험 판단, 엔티티 영향 보고, migration-guide 표 갱신. 인자로 변경 내용을 받는다.
disable-model-invocation: true
argument-hint: "<스키마·데이터 변경 내용>"
---

# 새 마이그레이션 만들기

규칙 원본은 `docs/migration-guide.md`다. 이 스킬은 그 규칙을 **작업 순서**로 묶은 것이며, 둘이 어긋나면 가이드가 이긴다(가이드를 먼저 읽는다).

## 0. 금지 확인

- **머지된 마이그레이션은 고치지 않는다**(체크섬 불일치로 기동 실패). `.claude/hooks/protect-files.js` 훅이 Edit/Write를 막는다 — 막히면 우회하지 말고 새 버전을 추가한다.
- 유일한 예외는 "첫 실배포 직전 마지막 합치기"다. 이 스킬의 범위가 아니다(별도 계획 문서로 진행하고, 그때만 사용자가 `/hooks`에서 훅을 끈다).

## 1. 번호와 파일 정하기

```bash
ls src/main/resources/db/migration src/main/java/db/migration
git fetch origin && git ls-tree --name-only origin/master src/main/resources/db/migration/ src/main/java/db/migration/
```

- 다음 번호 = 두 위치(SQL·Java)를 합친 **최대 버전 + 1**. 열린 PR이 같은 번호를 쓰는지 `gh pr list`로 확인한다(번호 충돌은 머지 순서로 깨진다).
- 파일명 `V{n}__{snake_case_설명}.sql`. 코드로만 가능한 데이터 변환만 `src/main/java/db/migration/V{n}__….java`(`BaseJavaMigration`, JDBC만 — 엔티티·스프링 빈 금지, 선례 V10·V11).

## 2. 작성 규칙 (가이드 "새 마이그레이션 작성 시 주의" 요약)

- DDL과 DML을 한 파일에 섞지 않는다(MariaDB DDL은 암묵 커밋 → 실패 시 롤백 불가). 둘 다 필요하면 버전을 나눈다(선례 V9 DDL / V10 DML).
- 멱등하게 쓴다 — `ADD COLUMN IF NOT EXISTS`, FK는 `FOREIGN KEY IF NOT EXISTS (…)`, 백필은 `WHERE … IS NULL`/`WHERE NOT EXISTS`.
- 기존 테이블에 `NOT NULL`을 더하면 반드시 `DEFAULT`를 현행 동작 값으로 둔다(이전 앱 롤백 호환).
- 문자열 정확 비교는 `COLLATE utf8mb4_nopad_bin`. AUTO_INCREMENT 시작값을 가정하지 말고 `LAST_INSERT_ID()`로 잡는다.
- 값을 잘라낼 수 있는 `MODIFY`는 `SET STATEMENT sql_mode='STRICT_ALL_TABLES' FOR …`로 고정한다(선례 V4).
- 시드의 시각은 `NULL` 또는 `NOW()`만(`ClockUsageConventionTest` 밖의 알려진 한계).

## 3. 엔티티·영향 범위 (구현 전에 사용자에게 보고)

- `ddl-auto: validate`라 엔티티 매핑과 스키마가 다르면 기동이 실패한다 → 엔티티 필드·`@Column` 정의를 함께 바꾼다.
- 보고 항목: 바뀌는 테이블·컬럼, 연관 엔티티, 기존 행 영향 유무, **이전 앱으로 롤백했을 때 기동·동작 여부**(V4·V16 설명 참고), 첫 실배포 합치기 때의 처리(V1 흡수 / 삭제).

## 4. 시험

| 마이그레이션 성격 | 시험 |
|---|---|
| 기존 행에 영향(`NOT NULL`·유니크·CHECK 추가, 타입 변경, UPDATE·DELETE·INSERT…SELECT) | **업그레이드 경로 시험** `{주제}UpgradeTest` — 직전 버전까지 `Flyway.target("{n-1}")` → 기존 행 삽입 → V{n} → 단언. 본보기 `src/test/java/com/cms/BoardDisplaySettingsUpgradeTest.java`(`MariaDbContainerSupport` 위 별도 스키마, `@AfterEach` DROP) |
| 새 테이블·시드만 | `BaselineSchemaTest`에 단언 추가 또는 기능 통합 시험 |
| 재실행 멱등을 주장하는 경우 | 같은 업그레이드 시험에서 두 번 적용해 확인(선례 `BoardSortOrderPinUpgradeTest`) |

```bash
./gradlew test --tests 'com.cms.BaselineSchemaTest' --tests 'com.cms.*UpgradeTest' --tests '*ConventionTest'
```

마이그레이션은 공용 코드라 PR 전에 전체 `./gradlew test`를 한 번 돌린다(루트 `CLAUDE.md` 작업 방식 4).

## 5. 문서

- `docs/migration-guide.md` "현재 마이그레이션 구성" 표에 행을 추가한다 — 날짜·계획 문서·DDL/DML 여부·멱등 여부·시험 이름·롤백 호환·합치기 때 처리.
- 되돌릴 수 없는 변경(컬럼·테이블 삭제, 데이터 변환)은 `docs/deployment.md`에 배포 전 백업·롤백 절차를 적는다.
