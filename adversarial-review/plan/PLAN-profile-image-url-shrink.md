# PLAN — `member.profile_image_url` LONGTEXT 축소 (V4)

> 작성 2026-10-24 · v1 · 출처: `PLAN-legacy-schema-removal.md` 쟁점 5(범위 밖으로 미룬 후속 후보)

## Context

`member.profile_image_url`은 옛 Base64 data URI를 담던 흔적으로 아직 `LONGTEXT`다. 레거시 이관 러너와 `LEGACY_INLINE`이 V3(#175)에서 제거돼 이 컬럼에는 더 이상 data URI를 쓰는 경로가 없다. 운영 DB는 아직 없고 dev DB는 V3까지 적용돼 있다.

### 정찰에서 확인한 사실 (2026-10-24, master `1f16b7f`)

| 대상 | 사실 |
|---|---|
| 쓰는 곳 | `Member`의 도메인 메서드 3개뿐 — `changeUploadedProfileImage`(FileStorage 키), `changePresetProfileImage`(프리셋 경로), `resetProfileImage`(NULL). 직접 setter 없음 |
| 값의 최대 길이 | 업로드 키 = `yyyy/MM/dd/`(11) + UUID(36) + `.`+확장자(`[a-z0-9]{1,10}` → 최대 11) = **최대 58자**. 프리셋 = `AdminMemberService.DEFAULT_PROFILE_IMAGE_MAP` 4종 중 최장 `/img/undraw_profile_1.svg` = 25자 |
| 읽는 곳 | `ProfileImageUrls`(응답 URL 변환), `docs/deployment.md` 수동 회수 ⑤-2의 `SELECT profile_image_url`. 인덱스·유니크 없음 |
| 엔티티 매핑 | `@Lob` + `@Column(name="profile_image_url", columnDefinition="LONGTEXT")` — `ddl-auto: validate`라 타입을 함께 바꿔야 한다 |
| 같은 성격의 다른 컬럼 | `post_attachment.storage_key`·`content_image.storage_key` = `varchar(255)` |
| 시험 파급 | `LegacySchemaRemovalUpgradeTest`가 전체 `classpath:db/migration`에 `migrate()`를 불러 `migrationsExecuted == 1`을 단언한다 → V4가 생기면 2가 되어 **깨진다**. `BaselineSchemaTest`는 V1~V3을 `migrate()`로 적용한다(전체 적용이라 V4가 자동 포함 — 단언 추가 필요) |
| 머지된 마이그레이션 | V3 수정 금지(체크섬). 첫 실배포 직전 마지막 합치기에서 V4도 V1에 흡수된다 |

## 핵심 확정 사항

1. **새 마이그레이션 `V4__shrink_profile_image_url.sql`** 한 파일, DDL 한 문장.
2. **타입은 `varchar(255)`**.
3. **엔티티는 `@Lob`·`columnDefinition` 제거 → `@Column(name="profile_image_url", length=255)`**.

## 설계

### 쟁점 1 — 목표 타입과 길이

- (a) `varchar(255)`, (b) `varchar(100)`(최대 58자에 여유), (c) `TEXT` 유지.
- → **(a)**. 최대 58자라 어느 쪽이든 충분하지만, 같은 성격(`FileStorage` 키)의 `storage_key` 컬럼이 모두 255이고 키 형식(확장자 길이 등)이 바뀌어도 컬럼을 또 바꿀 일이 없다. (b)는 근거 없는 새 숫자다. (c)는 목적(Base64 흔적 제거)에 어긋난다. 길이 상한을 서비스·엔티티 검증으로 따로 강제하지 않는다 — 값은 `FileStorage.store()` 반환값과 상수 맵에서만 오므로 255를 넘길 수 없다(과설계 방지).

### 쟁점 2 — 255자를 넘는 기존 값이 있을 때

- (a) 엄격 모드 `ALTER`가 오류로 멈춤(fail-closed), (b) 선행 `UPDATE … SET url = NULL WHERE CHAR_LENGTH > 255`(조용한 데이터 손실 + DML/DDL 혼합), (c) 아무 처리 없음(비엄격이면 잘림).
- → **(a)**. V3 쟁점 2와 같은 이유다 — 비엄격 모드의 `MODIFY`는 값을 **조용히 자르고 경고만** 남긴다(잘린 키는 파일을 못 찾아 이미지가 깨진다). `SET STATEMENT sql_mode='STRICT_ALL_TABLES' FOR`로 이 문장에만 고정한다. 그런 행이 걸리면 운영자가 정리한 뒤 `flyway repair` → 재기동한다 — `docs/migration-guide.md`에 적는다. 정리는 `Member.resetProfileImage()`와 같게 **세 컬럼을 함께** 초기화한다: `UPDATE member SET profile_image_kind='NONE', profile_image_url=NULL, profile_image_content_type=NULL WHERE CHAR_LENGTH(profile_image_url) > 255`(길이 초과는 값이 잘못됐다는 근거일 뿐이므로 kind 판단은 운영자가 하고, 이 SQL은 "이미지 없음"으로 되돌리는 기본 조치다).
- **후행 공백 예외(v2 — 리뷰 R1-1)**: MariaDB는 엄격 모드에서도 **잘리는 부분이 공백뿐이면 오류가 아니라 NOTE**로 처리할 수 있다. 앱 경로로는 공백이 든 값을 만들 수 없다(업로드 키 확장자 `[a-z0-9]`·UUID·날짜, 프리셋은 상수) → 이 경우를 막는 SQL 가드(DML 혼합·"DDL 한 문장" 포기)는 넣지 않는다. 대신 **시험이 실제 MariaDB 이미지의 동작을 확인해 그대로 고정**한다(후행 공백 1개가 붙은 256자 값으로 V4 실행 → 결과·바이트 비교, 실패든 잘림이든 관측 결과를 단언하고 이유를 주석으로 남긴다). 잘림이 관측되면 "모든 초과 값을 거부한다"는 표현을 문서에서 "비공백 초과분을 거부한다"로 정확히 쓴다. DDL이 한 문장뿐이라 멈추면 아무것도 바뀌지 않는다. 현재 그런 값을 만들 경로가 없어 실제로 걸릴 DB는 없다고 본다.
- 현재 dev DB에 길이 > 255 행이 있는지는 구현 단계에서 조회해 기록한다(없을 것으로 예상, 가정으로 두지 않고 확인).

### 쟁점 3 — `NULL` 허용·기본값·`CHARACTER SET`

- 기존 정의 `DEFAULT NULL`을 그대로 유지한다(`MODIFY \`profile_image_url\` varchar(255) DEFAULT NULL`). 문자셋·콜레이션은 테이블 기본(`utf8mb4`)을 따른다 — 값은 ASCII뿐이라 바이트 한도(255×4 = 1020)와 무관하다. `MODIFY`에서 `NOT NULL`·기본값을 빠뜨리면 정의가 달라지므로 시험이 `is_nullable`·`column_default`까지 단언한다.

### 쟁점 4 — 기존 시험·롤백 호환

- **`LegacySchemaRemovalUpgradeTest`**: `flyway(sessionVariables)`에 `.target("3")`을 추가해 "V3 적용 시험"이 V4와 무관하게 유지되게 한다(이 시험은 합치기 때 V3와 함께 지워진다). 새 시험 클래스는 V3까지 적용한 뒤 V4를 적용한다.
- **`BaselineSchemaTest`**: 새로 설치한 결과로 `profile_image_url`이 `varchar(255)`·nullable·기본값 NULL임을 `legacySchemaRemoved` 옆 새 시험으로 고정한다(클래스 주석의 "V1~V3"을 "V1~V4"로).
- **이전 앱 롤백**: V4가 적용된 DB에서 이전 앱(`@Lob`+LONGTEXT 매핑)을 띄우면 Hibernate `validate`가 컬럼 타입 불일치로 기동에 실패할 수 있다(V3와 달리 되돌림에 스키마 복구가 필요할 가능성). 실배포 전이라 롤백 대상 운영 DB가 없어 수용하되, **구현 단계에서 실제로 실패하는지 확인**해 결과와 복구 SQL(`ALTER TABLE member MODIFY profile_image_url LONGTEXT`)을 `docs/migration-guide.md`에 적는다. 확인 전에는 단정하지 않는다.

## 작업 단계

1. `V4__shrink_profile_image_url.sql` 작성.
2. `Member.profileImageUrl` 매핑 변경(`@Lob`·`columnDefinition` 제거, `length = 255`).
3. 시험: 새 `ProfileImageUrlShrinkUpgradeTest`(업그레이드 경로), `BaselineSchemaTest` 단언, `LegacySchemaRemovalUpgradeTest` `target("3")`.
4. 문서: `docs/migration-guide.md`(V4 행·실패 복구·롤백), `com.cms.admin.member/CLAUDE.md`("LONGTEXT는 별도 후보" 문장 갱신), 루트 `CLAUDE.md`는 해당 문장이 없으므로 변경 없음(확인), `PLAN-legacy-schema-removal.md` 쟁점 5에 후속 완료 표시.

## 업그레이드 경로 시험 설계 (`ProfileImageUrlShrinkUpgradeTest`)

`LegacySchemaRemovalUpgradeTest`와 같은 방식(별도 스키마, `target("3")` → 데이터 → `migrate()`).

1. **정상**: V3까지 적용 → 회원 3명(`NONE`/NULL, `PRESET`/`/img/undraw_profile_1.svg`, `UPLOADED`/58자 키) → V4 → `migrationsExecuted == 1`, `column_type = varchar(255)`, `is_nullable = YES`, `column_default = NULL`, 세 행 값 그대로.
2. **경계**: 정확히 255자 행은 보존, 256자 행이 있으면 실패(아래 3).
3. **fail-closed**: 256자(비공백) 행을 넣고 V4 → 예외, 컬럼은 여전히 `longtext`, 그 행은 잘리지 않음. 세 컬럼 초기화 SQL로 정리(`profile_image_content_type`까지 NULL임을 단언) → `repair` → `migrate` → 성공.
3-1. **후행 공백 경계(R1-1)**: 값은 `CONCAT(REPEAT('x', 255), ' ')`로 만든다(`||`는 `PIPES_AS_CONCAT` 없이는 논리 OR). **V4 실행 전에** `CHAR_LENGTH = 256`·`OCTET_LENGTH = 256`·마지막 바이트 `20`(공백)을 단언해 픽스처가 의도한 값임을 증명한 뒤 V4 → 관측 결과(실패 또는 255자로 잘림)를 길이·바이트 비교로 단언하고 주석에 MariaDB 동작을 적는다.
4. **비엄격 세션**: `sessionVariables=sql_mode=''`여도 256자 행이 있으면 실패(문장에 `STRICT_ALL_TABLES`를 고정했다는 증거) — 값이 잘리지 않음을 단언.

엔티티 매핑 정합(`validate`)은 `@DataJpaTest`·통합 시험이 컨텍스트를 올릴 때마다 자동으로 확인된다.

## 리스크

- 스키마 변경(컬럼 타입 축소)이라 되돌릴 때 데이터 길이 가정이 필요하다 — 축소 방향이므로 되돌리기(LONGTEXT 확대)는 무손실.
- 인가 정책·API·화면 변경 없음. 실기 검증은 프로필 이미지 업로드·프리셋 변경 왕복과 dev DB V4 적용 확인으로 한다.

## 구현·검증 결과 (2026-10-25)

브랜치 `refactor/profile-image-url-varchar`(master `1f16b7f`에서 분기). 계획과 달라진 결정은 없다.

### 구현 파일

- `src/main/resources/db/migration/V4__shrink_profile_image_url.sql` — `SET STATEMENT sql_mode='STRICT_ALL_TABLES' FOR ALTER TABLE member MODIFY profile_image_url varchar(255) DEFAULT NULL`.
- `Member.java` — `@Lob`·`columnDefinition="LONGTEXT"` 제거, `@Column(name="profile_image_url", length=255)`.
- 시험: 신규 `ProfileImageUrlShrinkUpgradeTest`(4건), `BaselineSchemaTest.profileImageUrlShrunk`, `LegacySchemaRemovalUpgradeTest`의 `flyway()`에 `target("3")`.
- 문서: `docs/migration-guide.md`(V4 행·실패 복구·한계·롤백), `com.cms.admin.member/CLAUDE.md`, `PLAN-legacy-schema-removal.md` 쟁점 5 후속 완료 표시.

### 검증 결과

- **시험**: `ProfileImageUrlShrinkUpgradeTest`·`BaselineSchemaTest`·`LegacySchemaRemovalUpgradeTest`·`com.cms.admin.member.*`·`*ConventionTest` 통과(`validate`는 새 매핑으로 컨텍스트가 올라가는 모든 `@DataJpaTest`·통합 시험이 확인). 전체 `./gradlew test`는 PR 직전 1회(`code-review-loop` 마무리).
- **후행 공백 경계(R1-1) 실측**: 255자 + 후행 공백 1개(픽스처는 V4 전에 `256|256|20`으로 증명)를 넣고 V4를 실행하면 **엄격 모드에서도 성공하고 255자로 잘린다** — 우려가 실제로 확인됐다. 비공백 256자는 실패(컬럼 `longtext` 유지·값 256자 유지). 앱 경로로는 공백이 든 값을 만들 수 없어 가드를 두지 않고 `upgrade_trailingSpaceOverflowIsTruncatedSilently`로 한계를 고정했다.
- **이전 앱 롤백 실측**: V4 스키마에서 옛 매핑(`@Lob`)으로 `@DataJpaTest`를 띄우면 `Schema validation: wrong column type encountered in column [profile_image_url] in table [member]; found [varchar (Types#VARCHAR)], but expecting [longtext (Types#CLOB)]`로 컨텍스트 생성이 실패한다 → 되돌릴 때 `ALTER TABLE member MODIFY profile_image_url LONGTEXT DEFAULT NULL`이 먼저 필요하다(`docs/migration-guide.md`에 기록).
- **dev DB 사전 조회**: V3까지 적용, 회원 1명(이미지 없음), 길이 > 255 행 0건, 컬럼 `longtext`.
- **실기(dev 스택 재빌드)**: 기동 로그 `Migrating schema cms to version "4 - shrink profile image url"` → `Successfully applied 1 migration`, `validate` 통과, DB 컬럼 `varchar(255)|YES|NULL`. 골든 패스(admin 로그인 → 내 정보): ① 프리셋 선택 → `PRESET`·25자·`/img/undraw_profile_1.svg`, ② PNG 업로드 → `UPLOADED`·`image/png`·51자 키, 다운로드 `200 image/png`·282바이트, 화면 프로필 카드·상단바 반영(`profile-image-url-shrink-screenshots/01-uploaded.png`), ③ 초기화 → `204`, DB `NONE`/`NULL`/`NULL`로 **원복**, 저장 파일 0개, 초기화 후 `GET` 404, CSRF 없는 `DELETE` 403. 앱 로그 오류 없음.

### 이슈

- 시험 첫 실행은 Docker Desktop 엔진이 꺼져 있어 `Could not find a valid Docker environment`로 8건 모두 실패했다(코드 문제 아님) — 엔진을 켠 뒤 통과. `docs/troubleshooting.md` 기록 대상 아님(환경 미기동).
- 내 계산 실수로 업로드 키 길이를 49로 단언했다가 실제 51(`yyyy/MM/dd/`10+1 + UUID 36 + `.png` 4)로 고쳤다 — 계획의 "최대 58자" 계산은 맞다(`.`+확장자 최대 11).

### 후속

- 첫 실배포 직전 마지막 합치기에서 V4를 V1(`profile_image_url varchar(255) DEFAULT NULL`)에 흡수하고 `ProfileImageUrlShrinkUpgradeTest`·`BaselineSchemaTest` V4 단언 문구를 정리한다. V1 주석의 `LEGACY_INLINE` 언급도 그때 함께 지운다.

## 개정 이력

- v1 (2026-10-24): 최초 작성.
- v3 변경 (R2, codex 새 지적 1건 수용): 3-1 시험 픽스처의 `||`는 기본 sql_mode에서 논리 OR라 256자 값을 만들지 못함 → `CONCAT(REPEAT…)`로 교체하고 V4 전에 길이·바이트를 단언해 픽스처를 증명. 다른 새 지적 없음.
- v2 변경 (R1, codex 리뷰 2건 모두 수용): ① 엄격 모드도 공백만 잘리면 NOTE일 수 있어 "초과 값은 반드시 실패" 단정을 철회 — 앱 경로로 공백이 불가능해 가드는 넣지 않고(기각: SQL 사전조건은 과설계·DML 혼합), 후행 공백 경계 시험(3-1)으로 실제 동작을 고정·문서화. ② 복구 SQL이 `profile_image_content_type`을 빠뜨렸던 것을 `Member.resetProfileImage()`와 같은 세 컬럼 초기화로 수정, 시험에도 단언 추가.
