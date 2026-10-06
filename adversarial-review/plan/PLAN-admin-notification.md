# PLAN — 관리자 알림(상단바 벨)

> 상태: v6 (2026-10-05) — **적대적 리뷰 5라운드 ship, 신규 지적 0건**(1~4라운드 17건 전부 반영, R-9는 사용자 결정 D11). 구현은 사용자 승인 후 착수
>
> **개정 이력**
> - v6 변경(4라운드 needs-attention, codex `gpt-6.1-sol`, 신규 지적 1건 수용 — 해법은 권고와 다르게):
>   - R-17 수용: E4의 `INSERT … SELECT … FROM member WHERE status = 'ACTIVE' AND user_type = 'ROLE_ADMIN'`는 `member`에 이 조건용 인덱스가 없어 전체를 스캔하고, MariaDB `REPEATABLE READ`에서 `INSERT … SELECT` 원본 읽기는 공유 잠금을 설정하므로(스캔한 범위만큼) 수정 중인 **수신자가 아닌** MANAGER 행에서 대기하거나 그 잠금이 다른 회원 변경을 지연시킬 수 있다(예외 삼킴·리스너 순서와 무관한 DB 잠금 전파). **해법: `member` 인덱스를 추가하지 않고, 수신자 ID를 잠금 없는 일반 SELECT(일관된 읽기)로 먼저 읽은 뒤 `notification`에 다중 행 `INSERT … VALUES`(한 문장)로 저장한다** — 원본 잠금 자체가 사라지고 스키마 변경도 늘지 않는다(ADMIN 수는 소수). 남는 한계: `notification.member_id` FK 검사가 부모(수신자 ADMIN) 행에 공유 잠금을 걸어 수신자 행을 다른 트랜잭션이 수정 중이면 대기할 수 있다 — 수신자에 한정, `innodb_lock_wait_timeout`으로 제한, 예외는 삼킴(§5-C, R13, §7). E3의 조건부 `INSERT … SELECT … WHERE m.id = ? AND m.password_changed_at = ?`는 PK 단건이라 잠금이 한 행에 한정되므로 유지(별도 트랜잭션)
> - v5 변경(3라운드 needs-attention, codex `gpt-6.1-sol`, 신규 지적 1건 수용; v4의 메서드 `@Order` 보완은 Spring 6.2.19 구현과 부합함이 확인됨):
>   - R-16 수용: 목록 쿼리(`member_id = ? AND id < ? ORDER BY id DESC LIMIT ?`, 읽음·미읽음 혼재)를 지원하는 인덱스가 없다 — `(member_id, read_at, id)`는 중간 열 `read_at`이 고정되지 않아 이 정렬에 쓸 수 없다(동등 조건 뒤에 정렬·범위 열이 와야 함). 미읽음·E4는 무제한 누적되므로 DB 비용이 데이터량에 따라 커질 수 있다 → 목록용 **`(member_id, id)` 인덱스를 추가**한다. 기존 `(member_id, read_at, id)`는 미읽음 수(`read_at IS NULL`)와 90일 정리(`read_at < …`)에 맞으므로 유지한다(§4). 구현 단계에서 첫 페이지·`beforeId` 후속 페이지·MANAGER(D11 필터) 조회를 `EXPLAIN`으로 확인한다(§7, §8)
> - v4 변경(2라운드 needs-attention, codex `gpt-6.1-sol`, 신규 지적 3건 전부 수용 — 모두 v3 수정에서 생긴 문제):
>   - R-13 수용: 기존 `AdminSessionRevokeListener`(`@Order(10)`)·`AdminAccountAutoLockListener`(`@Order(20)`)의 `@Order`는 **클래스**에 붙어 있는데 Spring 6.2의 메서드 리스너(`ApplicationListenerMethodAdapter`)는 **메서드**의 `@Order`만 읽고 없으면 `LOWEST_PRECEDENCE`다. v3의 "알림에 `@Order(30)`을 붙인다"는 설정은 오히려 기존 두 리스너보다 **먼저** 실행되게 한다(클래스에 붙이면 적용되지 않음). `PermissionChangedListener`도 우선순위가 없다 → **기존 세 리스너의 메서드에 `@Order`를 명시**(세션 만료 `10`·감사 `20`·권한 캐시 무효화 `10`, 알림 `100`)하고, 알림 저장을 멈춰 둔 상태에서 세션 만료·캐시 무효화가 먼저 끝났음을 검증하는 시험을 추가한다(§5-C, §7, §8)
>   - R-14 수용: 조건부 `INSERT … SELECT … FROM member`와 `ON DUPLICATE KEY UPDATE id = id`를 결합하면 `member.id`·`notification.id` 모호성 오류가 난다(MariaDB 회귀시험이 같은 형태를 `Column … is ambiguous`로 거부) — 로그인 핸들러가 삼키면 E3가 계속 실패한다. **`ON DUPLICATE KEY UPDATE id = notification.id`**로 한정하고 실제 MariaDB 시험에서 최초 생성·중복 생성을 모두 확인한다(§5-C, §7)
>   - R-15 수용: 요청 세대만으로는 읽음 PATCH A·B의 완료 순서가 뒤집힐 때 최신 응답을 버려 배지가 서버와 어긋난다(B가 먼저 끝나 `unreadCount=1` 반영 → A의 `0` 응답은 이전 세대라 폐기). **읽음 요청은 클라이언트에서 한 번에 하나씩 직렬화(큐)하고, 큐가 비면 `unread-count`를 한 번 재조회해 그 값으로 배지를 갱신**한다. 시작·완료 순서를 뒤집는 화면 시험 추가(§5-E, §7)
> - v3 변경(1라운드 needs-attention, codex `gpt-6.1-sol`, 지적 12건 — 전부 수용(R-9는 사용자 결정 반영)):
>   - R-1 수용(코드 확인: `LoginFailureService`가 이벤트를 DB 값이 아니라 **로그인 요청의 `username` 원문**으로 만들고 `LockingAuthenticationFailureHandler`는 길이 검증 없음, `utf8mb4_general_ci` 비교라 대소문자·후행 공백 변형도 같은 계정에 매칭): `AdminAccountAutoLockEvent`에 **DB의 정규 `userId`와 잠금 시각(`lockedAt`)**을 추가(기존 `userId` 필드는 요청 원문이라 알림에 쓰지 않는다). 메시지 길이는 서버가 보장(`userId` ≤ 50자 + 고정 문구)해 E1·E4 저장 전체가 길이 때문에 실패하지 않게 한다(§5-B, §5-C)
>   - R-2 수용: 테스트 정리 경로 전수 조사를 단계 1에 추가 — `memberRepository.deleteById()` 직접 호출(`LoginFailureLockoutIntegrationTest`, `AdminMemberUpdateConcurrencyIntegrationTest` 등, 일부는 실패를 삼킴)을 모두 찾아 알림 선삭제로 바꾸고, E4 수신자 누적(남은 ACTIVE ADMIN)을 막는 시험 격리 규칙을 정한다(§7, §8)
>   - R-3 수용: E4는 관리자 수만큼 반복 저장하지 않고 **단일 `INSERT … SELECT`**로 저장한다. 기존 `AdminAccountAutoLockListener`도 `REQUIRES_NEW`를 쓰므로 구조는 선례와 같지만, 리스너 순서를 `AdminSessionRevokeEvent`·권한 캐시 무효화 **뒤**로 고정(@Order)하고 작은 커넥션 풀 병렬 자동 잠금 시험을 추가한다. "계정당 30분 상한" 문구는 정정(관리자가 ACTIVE로 복구하면 30분 전에도 다시 잠길 수 있음)(§5-C, R11)
>   - R-4 수용: E3 판정을 `expiresAt = passwordChangedAt + 90일`(기존 만료 쿼리와 같은 시·분·초 비교) 기준으로 하고 메시지에 **절대 만료일**을 넣는다(§5-B, §5-C)
>   - R-5 수용: 재확인이 읽은 최신 `passwordChangedAt`을 `MemberSnapshot`에 추가해 전달하고, 알림 INSERT는 "회원의 현재 `password_changed_at`이 스냅샷과 같을 때만" 조건부(`INSERT … SELECT … WHERE`)로 해 재확인 직후 비밀번호가 바뀌면 이전 주기 알림이 생기지 않게 한다(§5-C)
>   - R-6 수용: E3 생성과 90일 정리를 **별도 트랜잭션**으로 분리하고, 중복은 예외 없이 `INSERT … ON DUPLICATE KEY UPDATE id = id`로 처리한다(JPA save + 예외 삼킴은 rollback-only·커밋 시점 오류로 정리까지 막는다). 로그인 경로는 기존 규칙(요청당 커넥션 2개 금지 — `LoginFailureService.unlockIfLockExpired` 주석)대로 **REQUIRES_NEW를 쓰지 않고** 성공 핸들러(트랜잭션 밖)에서 각 서비스 메서드가 자기 REQUIRED 트랜잭션을 연다(§5-C)
>   - R-7 수용: 단건 읽음을 `UPDATE … WHERE id AND member_id AND read_at IS NULL` 원자적 갱신으로 하고, 0행이면 존재·소유 확인 후 이미 읽음이면 기존 `read_at` 유지 200, 아니면 404(§5-D)
>   - R-8 수용: 읽음 처리 뒤 배지는 응답의 실제 `unreadCount`를 쓰고, count·목록·읽음 요청 모두 요청 세대로 늦은 응답을 버린다(§5-E)
>   - R-10 수용: V20 실패 복구·앱 롤백·재배포 절차와 시험 추가(§4, §7)
>   - R-11 수용: 실제 이벤트 발행 후 외부 트랜잭션 롤백, Recorder flush·커밋 실패, 동시 로그인 유니크 충돌 시험 추가. 기존 "409로 끝난 권한 저장에 알림 없음" 시험은 이벤트 발행 전 종료라 증명이 아니므로 **발행 뒤 롤백**으로 교체(§7)
>   - R-9 수용(사용자 결정, D11): E4(다른 계정 정보)는 **요청 시점에 ADMIN인 사용자에게만 열람**된다 — 목록·미읽음 수·단건·전체 읽음 쿼리에 `type <> 'ADMIN_ACCOUNT_LOCKED' OR :isAdmin` 조건을 둔다. 강등되면 E4가 보이지 않고 미읽음 수에서도 빠지며 다시 승격되면 다시 보인다(통합 검색이 현재 권한으로 결과를 거르는 것과 같은 원칙). 생성 시점 수신자 조건(ACTIVE ADMIN)과 열람 시점 조건(현재 ADMIN)을 모두 둔다(§5-D, R12)
>   - R-12 수용: `page` offset 대신 **`beforeId` 커서**(`?beforeId=&size=`)로 바꿔 목록 변동에서도 중복·누락이 없게 한다(§5-D, §5-E)
> - v2: 사용자 결정 반영(§1 D3~D9) — **자동 잠금은 다른 ACTIVE ADMIN에게도 알림**(E4 신설, 아이디 표시 + 회원 상세 링크), 배지는 페이지 로드 시만, **읽은 알림 90일 후 정리**(로그인 성공 시 본인 것만), 비밀번호 임박은 주기당 1회, 전용 목록 페이지 없음(드롭다운만). §10 미결 질문 해소
> - v1: 초안
>
> 근거: **정적 정찰 기준**(코드를 열어 대조, 빌드·테스트 미실행). 기준 커밋 = master `e76e416`(#88~#91 머지). 브랜치 `feat/admin-notification`
> 유형: feat · **스키마 변경**(V20 `notification` 테이블) · 인가 정책 변경 **없음**(기존 `MY_INFO` 게이트 `/admin/api/members/me/**` 안에 둔다) · 신규 의존성 없음

## 0. 요약

#88에서 제거한 상단바 알림(종) 데모를 실제 기능으로 되살린다. 시스템이 **그 관리자 본인에게** 알릴 사건 3종을 알림으로 저장하고, 상단바 벨에 미읽음 배지와 최근 알림 드롭다운을 보여 준다.

| # | 사건 | 수신자 | 발생 지점 |
|---|---|---|---|
| E1 | 내 계정 상태 변경(잠금·비활성·삭제·활성 복귀, 로그인 연속 실패 자동 잠금) | 대상 회원 본인 | `AdminMemberService.updateAdminMember`(관리자 수정), `LoginFailureService`(자동 잠금) |
| E2 | 내 권한 변경(역할 변경, 개별 권한 부여·회수) | 대상 회원 본인 | `AdminMemberService.updateAdminMember`(역할), `MemberPermissionService.replace`(개별 권한) |
| E3 | 비밀번호 만료 7일 전 | 본인 | 로그인 성공 처리(`VisitLoggingAuthenticationSuccessHandler`) |
| E4 | 다른 관리자 계정의 로그인 연속 실패 자동 잠금 | **ACTIVE 상태 ROLE_ADMIN 전원**(잠긴 본인 제외) | `LoginFailureService`(자동 잠금) — E1과 같은 이벤트 |

## 1. 확정된 사용자 결정

| # | 쟁점 | 결정 |
|---|---|---|
| D1 | 알림 사건 | E1·E2·E3 세 가지 — 2026-10-05 |
| D2 | 쪽지와의 관계 | 쪽지(⑤)는 별도 기능·별도 PR. 이 PR은 시스템 알림만 |
| D3 | 다른 관리자 사건 알림 | **자동 잠금만** 다른 ADMIN에게도 알린다(E4). 관리자가 수동으로 바꾼 상태·권한은 본인에게만 |
| D4 | E4 표시 | 잠긴 계정 **아이디를 메시지에 표시** + 회원 관리 상세 링크(`/admin/member/manage?id={memberId}`, #91의 `?id=` 진입 재사용) |
| D5 | E4 수신 범위 | **ACTIVE 상태 ROLE_ADMIN만**(잠긴 계정이 ADMIN이면 본인 알림 E1만 받는다) |
| D6 | 배지 갱신 | **페이지 로드 시만**(폴링 없음 — 폴링이 세션 유휴 타임아웃을 연장하는 부작용 회피) |
| D7 | 보관 정책 | **읽은 알림은 읽은 지 90일 후 정리**, 미읽음은 지우지 않는다 |
| D8 | 정리 시점 | **로그인 성공 시 그 회원 것만**(스케줄러 없음, GET에 쓰기 부작용 없음) |
| D9 | 비밀번호 임박 빈도 | **비밀번호 주기당 1회**(만료 7일 이내 첫 로그인) |
| D10 | 목록 화면 | **드롭다운만**(최근 10건 + 더 불러오기). 전용 페이지 없음 |
| D11 | 강등 후 E4 열람 | **현재 ADMIN일 때만** 열람(목록·미읽음 수·읽음 처리 모두). 강등되면 E4는 보이지 않고 재승격되면 다시 보인다 — 2026-10-05 |

## 2. 정찰 사실 표

| # | 사실 | 근거 | 이 계획의 처리 |
|---|---|---|---|
| F1 | 관리자 수정은 대상 행 잠금 아래에서 `roleChanged`·`statusChanged`를 계산하고, 실변경이면 `AdminSessionRevokeEvent`(AFTER_COMMIT 리스너)를 발행한다. 역할 변경 시 개별 권한 행 삭제 후 `PermissionChangedEvent`도 낸다 | `AdminMemberService` :240-270 | 같은 지점에서 알림 이벤트를 **발행만** 하고 저장은 커밋 후 리스너가 한다(§5-C) |
| F2 | 자동 잠금은 `AdminAccountAutoLockEvent`를 발행하고 `AdminAccountAutoLockListener`(AFTER_COMMIT, `@Order(20)`)가 감사를 남긴다 — 감사를 원 트랜잭션에서 직접 호출하지 않는 이유(롤백 불일치·예외 전파)가 주석에 있다 | `AdminAccountAutoLockListener` | 같은 이벤트를 알림 리스너가 하나 더 구독한다(기존 리스너 수정 없음) |
| F3 | 개별 권한 저장은 변경이 있을 때만 `PermissionChangedEvent(memberId)`를 발행하고, 리스너는 **AFTER_COMPLETION**에서 캐시를 무효화한다. 결과 객체에 `getAuditLabel()`(`v3→v4: +공지사항.생성`)이 있다 | `MemberPermissionService` :88-130, `permission/CLAUDE.md` | 캐시 이벤트를 알림에 재사용하지 않는다 — 캐시 이벤트는 롤백에도 실행되고(AFTER_COMPLETION) 역할 변경 경로에서는 행 삭제 시에만 나기 때문. 알림 전용 이벤트를 따로 발행한다 |
| F4 | 비밀번호 만료는 배치 없이 **로그인 시점**에만 판정된다(`PASSWORD_EXPIRY_DAYS = 90`, 성공 직전 재판정). 스케줄러가 없다 | `PasswordExpiryService`, `member/CLAUDE.md` | E3도 로그인 성공 시점에 판정한다(스케줄러 도입 없음). 로그인하지 않는 동안에는 알림이 생기지 않는다 — 한계로 명시 |
| F5 | 시각은 주입된 KST `Clock`만 쓴다(`ClockUsageConventionTest`가 `LocalDateTime.now()` 직접 호출을 잡는다) | 루트 `CLAUDE.md` | 알림 생성·읽음 시각 모두 `LocalDateTime.now(clock)` |
| F6 | `/admin/api/members/me/**`는 상시 허용 `MY_INFO` 게이트(ADMIN·MANAGER)이고, `{id}=me`는 `Long` 변환 실패로 400이라 다른 회원 API와 충돌하지 않는다 | `AdminFeature.MY_INFO`, `permission/CLAUDE.md` | 알림 API를 `/admin/api/members/me/notifications`(본인 리소스 `me` 별칭 + 중첩 — `api-conventions`)에 둔다 → **카탈로그·게이트 변경 없음** |
| F7 | `AdminEndpointAuthorizationConventionTest`: API는 인가 선언이 있어야 한다(ALWAYS 경로 한정 `hasAnyRole('ADMIN','MANAGER')` 허용) | `permission/CLAUDE.md` | 알림 API 전부 `@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")` |
| F8 | `member_permission`은 `member` FK(RESTRICT)라 시험 정리 코드가 `TestMembers.delete`로 권한 행을 먼저 지운다. 앱은 회원을 하드 삭제하지 않는다(`DELETED` 상태 전이만) | `permission/CLAUDE.md`, `TestMembers` | `notification`도 `member` FK(RESTRICT)로 두고 `TestMembers.delete`가 알림 행을 먼저 지우게 고친다 |
| F9 | 상단바는 모든 관리자 페이지가 포함하는 프래그먼트이고, 검색 드롭다운이 vanilla 정적 스크립트(`topbar-search.js`)를 `defer`로 로드한다 | `fragments/topbar.html` | 같은 방식으로 `topbar-notification.js` 추가. 데이터는 `textContent`로만 렌더링 |
| F10 | 상태 변경(잠금 등)은 대상자 세션을 만료시킨다 — 잠긴·비활성 계정은 로그인할 수 없다 | 루트 `CLAUDE.md` "세션 등록·강제 만료" | E1 알림은 **다시 로그인할 수 있게 된 뒤에** 보인다(잠금 → 해제 후 확인). `DELETED`는 다시 볼 수 없으므로 생성하지 않는다 |

## 3. 범위

**포함**
- V20 `notification` 테이블, 엔티티·리포지토리·서비스
- 알림 생성: E1·E2(커밋 후 리스너), E3(로그인 성공 시)
- API: 목록·미읽음 수·단건 읽음·전체 읽음(본인 것만)
- 상단바 벨 + 미읽음 배지 + 드롭다운(최근 N건, 모두 읽음)
- 문서(`member/CLAUDE.md` 또는 신규 `notification` 패키지 `CLAUDE.md`, `docs/migration-guide.md` 필요 시)

**제외**
- 자동 잠금 이외의 다른 관리자 사건 알림(D3)
- 실시간 푸시(WebSocket·SSE)·폴링(D6), 이메일 발송
- 스케줄러 기반 만료 임박 판정·정리(F4, D8)
- 전용 알림 목록 페이지(D10)
- 쪽지(⑤)

## 4. 스키마 (V20)

```sql
CREATE TABLE notification (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    member_id    BIGINT       NOT NULL,
    type         VARCHAR(40)  NOT NULL,   -- ACCOUNT_STATUS / PERMISSION / PASSWORD_EXPIRY / ADMIN_ACCOUNT_LOCKED
    message      VARCHAR(255) NOT NULL,   -- 서버가 상수 템플릿 + enum 라벨로 조립. 사용자 입력은 E4의 잠긴 계정 아이디(최대 50자)뿐
    link_url     VARCHAR(255) NULL,       -- 같은 출처 경로만(예: /admin/member/settings)
    dedupe_key   VARCHAR(100) NULL,       -- 같은 사건의 중복 생성 방지(E3: 비밀번호 주기당 1건)
    read_at      DATETIME(6)  NULL,
    create_date  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT uk_notification_member_dedupe UNIQUE (member_id, dedupe_key),
    INDEX idx_notification_member_id (member_id, id),          -- 목록: member_id = ? AND id < ? ORDER BY id DESC LIMIT ?
    INDEX idx_notification_member_read (member_id, read_at, id) -- 미읽음 수(read_at IS NULL)·90일 정리(read_at < ?)
);
```

- `dedupe_key`가 NULL이면 유니크 제약 대상이 아니다(MariaDB) — E1·E2는 매번 생성
- **인덱스 확인(v5 R-16)**: 구현 시 읽음·미읽음이 섞인 다량 데이터(회원당 수천 건)로 첫 페이지, `beforeId` 후속 페이지, 미읽음 수, 90일 정리, MANAGER(D11 `type` 조건) 조회를 `EXPLAIN`/`ANALYZE`로 확인해 PK 역순 스캔이나 filesort가 없음을 기록한다(조회 수는 `size` 상한 50으로도 제한)
- 문자셋·엔진·`DATETIME` 정밀도는 기존 마이그레이션(V18 등) 관례를 따른다(구현 시 대조)
- **정상 적용 후 앱 롤백**: 이전 앱은 이 테이블을 모르므로 남아 있어도 무해하다(엔티티가 없을 뿐, `ddl-auto: validate`는 매핑된 테이블만 검사). Flyway는 적용 이력에 있고 코드에 없는 마이그레이션을 기본값으로 무시하므로 V20 파일이 없는 이전 앱도 기동할 수 있다(구현 시 시험으로 확인 — "반드시 기동 실패"도, "반드시 기동 성공"도 단정하지 않는다)
- **V20 실패 복구(v3 R-10)**: MariaDB DDL은 암묵 커밋이라 `CREATE TABLE` 커밋 뒤 Flyway 이력 기록 전에 프로세스가 중단되면 "테이블은 있고 성공 이력은 없는" 상태나 `success=0` 이력이 남을 수 있다. `docs/migration-guide.md`의 V17~V19 복구 절차(실제 객체·`flyway_schema_history` 확인 → **성공 이력 없는 버전의 잔여 객체만** DROP → `flyway repair` → 재기동)를 따르고 V20을 추가 기록한다. **실제 알림 데이터가 든 성공 이력 테이블은 일괄 DROP하지 않는다.** `MemberPermissionMigrationTest`의 복구 시험 관례로 "V20 객체만 있고 성공 이력 없음" 복구와 잘못된 절차의 반례를 시험한다
- **롤백 후 재배포**: 구버전 운영 중에는 알림이 생성되지 않아 신버전 재기동 시 빈 구간이 생길 뿐 데이터 불일치는 없다(알림은 다른 테이블을 되살리지 않는 독립 데이터). 정리·권한 재초기화 절차는 필요 없다

## 5. 설계

### 5-A. 패키지

`com.cms.admin.notification` — `domain/Notification`, `domain/NotificationType`, `repository/NotificationRepository`, `service/NotificationService`(조회·읽음), `service/NotificationRecorder`(생성 — 리스너·로그인 핸들러가 호출), `event/*`, `controller/NotificationController`, `dto/*`

### 5-B. 메시지 (사용자 입력 없음)

| type | 메시지 예 | 링크 |
|---|---|---|
| ACCOUNT_STATUS | "관리자가 내 계정 상태를 '잠금'으로 변경했습니다." / "로그인 연속 실패로 계정이 자동 잠금되었습니다(2026-10-05 14:03)." / "계정이 다시 활성화되었습니다." | 없음 |
| PERMISSION | "역할이 '매니저'에서 '관리자'로 변경되었습니다." / "권한이 변경되었습니다: +공지사항 조회, -공지사항 삭제" | 없음 |
| PASSWORD_EXPIRY | "비밀번호가 3일 후 만료됩니다. 내 설정에서 변경해주세요." | `/admin/member/settings` |
| ADMIN_ACCOUNT_LOCKED | "관리자 계정 'mgr01'이(가) 로그인 연속 실패로 자동 잠금되었습니다(2026-10-05 14:03)." | `/admin/member/manage?id={memberId}` |

- PASSWORD_EXPIRY 메시지는 **절대 만료일**을 포함한다(예: "비밀번호가 2026-10-08 14:03에 만료됩니다…" — 나중에 읽어도 오해가 없게 "3일 후"를 쓰지 않는다)
- 상태·역할·기능·동작은 enum → 고정 한국어 라벨로만 변환한다. 사용자 입력은 **E4의 잠긴 계정 아이디 하나만** 메시지에 들어간다(D4). **이 아이디는 로그인 요청의 `username` 원문이 아니라 DB의 정규 `member.userId`여야 한다**(v3 R-1) — 현재 `AdminAccountAutoLockEvent.userId`는 요청 원문(대소문자·후행 공백 변형, 길이 무제한)이므로 알림에 쓰지 않고, 이벤트에 `canonicalUserId`·`lockedAt`을 추가한다. `userId` 컬럼 상한(50자)이 있어 메시지는 항상 255자 안이다. 화면은 반드시 `textContent`로만 표시한다(HTML 이스케이프는 표시 계층 책임). 이름(`userName`)은 넣지 않는다
- 시각 표기(E1 자동 잠금·E4)는 **이벤트가 담은 `lockedAt`**을 쓴다 — 리스너의 `now`는 실제 잠금 시각과 다르고, 커밋 후 재조회하면 이미 해제됐을 수 있다
- 누가 바꿨는지(행위자)는 넣지 않는다 — 감사 로그가 원본이며 알림은 "무엇이 바뀌었나"만

### 5-C. 생성 경로와 트랜잭션

- **E1·E2(관리자 수정·권한 저장)**: 원 트랜잭션에서 `NotificationRequestedEvent(memberId, type, message, linkUrl, dedupeKey)`를 **발행만** 한다. `NotificationEventListener`가 `@TransactionalEventListener(AFTER_COMMIT)`에서 `NotificationRecorder.record()`(`REQUIRES_NEW`)로 저장한다
  - 원 트랜잭션이 롤백되면 알림이 생기지 않는다. 알림 저장이 실패해도 원 변경은 이미 커밋됐고 예외는 삼켜 ERROR 로그만 남긴다(**최선 노력** — 감사 로그와 같은 계약, F2)
  - 대상 회원이 `DELETED`로 바뀐 경우는 발행하지 않는다(F10)
- **E1·E4 자동 잠금**: 기존 `AdminAccountAutoLockEvent`(`canonicalUserId`·`lockedAt` 추가 — v3 R-1)를 알림 리스너가 별도로 구독(AFTER_COMMIT). 한 `REQUIRES_NEW` 트랜잭션에서 ① 잠긴 본인에게 E1 1건 ② 수신자 ID(`status = ACTIVE AND user_type = ROLE_ADMIN AND id <> 잠긴 회원`)를 **잠금 없는 일반 SELECT(일관된 읽기)**로 읽은 뒤 `notification`에 **다중 행 `INSERT … VALUES` 한 문장**으로 E4를 저장한다(관리자 수만큼 반복 저장하지 않는다 — v3 R-3. **`INSERT … SELECT … FROM member`는 쓰지 않는다** — `REPEATABLE READ`에서 원본 읽기가 공유 잠금을 걸어 수신자가 아닌 회원의 변경까지 지연시킬 수 있고 `member`에 이 조건용 인덱스도 없다, v6 R-17. 수신자 조회는 이 SELECT 시점 기준이며 직후 승격·강등은 반영하지 않는다). 남는 한계: `notification.member_id` FK 검사가 수신자 ADMIN 행에 공유 잠금을 걸므로 그 행을 다른 트랜잭션이 수정 중이면 저장이 대기할 수 있다(수신자에 한정, `innodb_lock_wait_timeout`으로 제한, 예외는 삼킴). 실패는 삼키고 ERROR 로그(E1·E4 함께 유실될 수 있다 — 최선 노력)
  - **실행 순서(v3 R-3, v4 R-13 정정)**: 알림 저장의 연결·락 대기가 세션 만료·권한 캐시 무효화를 막지 않도록 알림 리스너를 **그 뒤**에 실행해야 한다. 그러나 Spring 6.2의 메서드 리스너는 **메서드의 `@Order`만** 읽고(클래스 `@Order`는 무시, 없으면 `LOWEST_PRECEDENCE`) 기존 `AdminSessionRevokeListener`(10)·`AdminAccountAutoLockListener`(20)는 `@Order`가 **클래스**에 있어 지금은 사실상 우선순위가 없다. 따라서 ① 기존 세 리스너(세션 만료·감사·`PermissionChangedListener` 캐시 무효화)의 **메서드에 `@Order`를 명시**한다(세션 만료 `10`, 감사 `20`, 캐시 무효화 `10`) ② 알림 리스너 메서드는 `@Order(100)`(더 큰 값 = 나중)으로 둔다 ③ 알림 저장을 의도적으로 멈춘(블로킹) 상태에서 세션 만료·캐시 무효화가 이미 끝났음을 시험으로 고정한다. 기존 `AdminAccountAutoLockListener`(감사)도 `REQUIRES_NEW`를 쓰므로 구조는 선례와 같다. 클래스 `@Order`는 제거하지 않고 두되(변경 최소화) 메서드 값이 실제 순서를 정한다는 점을 주석으로 남긴다
  - **풀 포화**: 알림 저장의 연결 획득 대기는 로그인 실패 응답과 관리자 수정 응답을 지연시킬 수 있다. 계약: 연결 획득 실패·타임아웃은 삼키고 ERROR 로그(알림 유실 허용), 요청 자체는 실패시키지 않는다. 작은 풀(`maximumPoolSize` 2~3)에서 병렬 자동 잠금 시 로그인 실패 응답이 막히지 않는지 시험으로 확인한다(§7)
  - **빈도**: "계정당 30분 상한"은 절대적이지 않다 — 관리자가 `ACTIVE`로 복구하면 30분 전에도 다시 잠길 수 있다. 생성량 상한은 (잠금 횟수 × ACTIVE ADMIN 수)이며 별도 억제는 두지 않는다
- **E2 개별 권한**: `replace()`가 실제 변경(추가·회수 키 집합)이 있을 때만 발행. 메시지는 추가·회수 키를 라벨로 변환(감사 라벨 문자열을 그대로 쓰지 않는다 — 버전 번호는 사용자에게 의미 없음)
- **E2 역할 변경**: `roleChanged`일 때 발행(개별 권한 삭제 여부와 무관)
- **E3 비밀번호 임박**: 로그인 성공 처리에서 **재확인 통과 뒤**(세션이 확정된 경우에만) 판정한다.
  - **판정(v3 R-4)**: 기존 만료 쿼리(`passwordChangedAt <= now − 90일`, 시·분·초 비교)와 같은 기준으로 `expiresAt = passwordChangedAt + 90일`을 계산하고, `now < expiresAt <= now + 7일`이면 생성한다(날짜 차이로 계산하지 않는다 — 23시에 바꾼 비밀번호의 90일째 오전 로그인에서 판정이 어긋남). 표시는 절대 만료일
  - **최신 스냅샷(v3 R-5)**: `LoginFailureService.MemberSnapshot`에 `passwordChangedAt`을 추가한다(재확인이 이미 fresh 조회로 읽는 값). 이 스냅샷 값으로 판정하고, 저장은 **"회원의 현재 `password_changed_at`이 스냅샷과 같을 때만"** 조건부(`INSERT … SELECT … WHERE m.password_changed_at = :snapshot`)로 하여 재확인 직후 다른 세션이 비밀번호를 바꿔도 이전 주기의 알림이 생기지 않게 한다. 재확인 통과가 이후까지 상태·비밀번호 고정을 보장하는 것은 아니다
  - **중복(v3 R-6, v4 R-14)**: `dedupe_key = "PASSWORD_EXPIRY:" + passwordChangedAt`. INSERT는 네이티브 `INSERT … SELECT … FROM member m WHERE … ON DUPLICATE KEY UPDATE id = notification.id`로 **예외 없이** 처리한다(**`id = id`는 `member.id`·`notification.id`가 모호해 오류** — 반드시 대상 테이블로 한정. 실제 MariaDB 시험으로 최초·중복 생성을 모두 확인한다)(JPA `save` + 유니크 예외 삼킴은 rollback-only·flush/커밋 시점 오류를 만들 수 있다). 지정 유니크 키 충돌만 무시되고 다른 무결성 오류는 그대로 실패(→ 삼킴·ERROR 로그)
  - **트랜잭션(v3 R-6)**: 성공 핸들러는 트랜잭션 밖이다. `NotificationRecorder.recordPasswordExpiry()`와 `purgeReadNotifications()`는 **서로 다른 `@Transactional`(REQUIRED) 메서드**로 각각 자기 트랜잭션을 연다 — 로그인 경로는 `REQUIRES_NEW`로 요청당 커넥션 2개를 잡지 않는다(`LoginFailureService.unlockIfLockExpired` 주석의 풀 고갈 규칙). 핸들러가 각 호출을 개별 try/catch로 감싸 한쪽 실패가 다른 쪽과 로그인을 막지 않는다
- **정리(D7·D8)**: 같은 로그인 성공 지점에서 그 회원의 `read_at < now − 90일`인 알림을 조건부 벌크 DELETE(별도 트랜잭션, 위 E3 생성과 분리 — v3 R-6). 미읽음은 지우지 않는다. 로그인하지 않는 회원의 오래된 알림은 남는다 — 한계로 명시
- 시각: 생성·읽음·정리 모두 `LocalDateTime.now(clock)`, 일수 계산은 KST 날짜 기준

### 5-D. API (`/admin/api/members/me/notifications`, 전부 `hasAnyRole('ADMIN','MANAGER')`)

| 메서드·경로 | 설명 | 응답 |
|---|---|---|
| `GET /admin/api/members/me/notifications?size=10&beforeId=` | 본인 알림 최신순(id desc). **`beforeId` 커서**(생략하면 처음부터, 있으면 `id < beforeId`), size 상한 50 — offset 페이징은 목록이 바뀌면 중복·누락(v3 R-12) | `{content:[{id,type,message,linkUrl,read,createDate}], unreadCount, hasMore}` (다음 페이지는 마지막 항목의 `id`를 `beforeId`로) |
| `GET /admin/api/members/me/notifications/unread-count` | 배지용 | `{unreadCount}` |
| `PATCH /admin/api/members/me/notifications/{id}` 본문 `{"read": true}` | 단건 읽음(멱등). 본인 것이 아니거나 없으면 **404**(존재 숨김) | 200 + 항목 + `unreadCount` |
| `PATCH /admin/api/members/me/notifications` 본문 `{"read": true}` | 본인 미읽음 전부 읽음(조건부 벌크 UPDATE) | `{updated, unreadCount}` |

- **단건 읽음은 원자적 갱신(v3 R-7)**: `UPDATE notification SET read_at = :now WHERE id = :id AND member_id = :principalId AND read_at IS NULL`. 1행이면 방금 읽음, 0행이면 `member_id` 조건으로 존재를 확인해 — 이미 읽은 알림이면 **기존 `read_at`을 유지한 채 200**, 없거나 남의 것이면 404. 조회 후 엔티티 수정 방식은 쓰지 않는다(경합 시 최초 읽음 시각이 덮어써져 90일 정리 기준이 흔들린다). 단건·단건, 단건·전체 읽음 경합 시험을 둔다
- **열람 시점 권한 필터(D11, v3 R-9)**: 목록·`unreadCount`·단건·전체 읽음 모두 서버가 요청 시점의 `Authentication`으로 ADMIN 여부를 판정해 비ADMIN에게는 `type = ADMIN_ACCOUNT_LOCKED` 행을 **쿼리 조건으로 제외**한다(조회 후 응답에서 거르지 않는다 — `hasMore`·`unreadCount`·커서가 어긋나지 않게). 비ADMIN이 E4의 단건 읽음을 요청하면 **404**(존재 숨김), 전체 읽음은 보이는 행만 갱신해 숨겨진 E4는 미읽음으로 남는다(재승격 시 그대로 보임). 판정 함수는 `AdminSearchService.isAdmin`과 같은 `ROLE_ADMIN` 권한 확인이다
- `read: false`(읽음 취소)는 지원하지 않는다 → 400
- 본인 식별은 세션 principal(`CustomUserDetails.getId()`)만 쓴다. 경로에 회원 ID를 받지 않는다
- CSRF: PATCH는 기존 규칙대로 `X-CSRF-TOKEN` 헤더 필수
- 감사 로그(`@AdminActionLogged`)는 남기지 않는다 — 본인 알림 읽음은 관리 행위가 아니다

### 5-E. 화면 (`topbar-notification.js`)

- 상단바에 벨 아이콘 + 미읽음 배지(0이면 숨김, 99 초과 "99+")
- 페이지 로드 시 `unread-count` 1회 조회(폴링 없음 — D6)
- 벨 클릭 → 드롭다운을 열 때 목록 조회(최근 10건). 항목: 메시지·상대 시각·미읽음 표시. [더 불러오기]는 마지막 항목의 `id`를 `beforeId`로 보내고, 응답 항목은 이미 표시한 `id`와 **중복 제거**한다(커서라 정상 경로에서는 중복이 없지만 방어). 클릭하면 단건 읽음 PATCH 후 `linkUrl`이 있으면 이동(같은 출처 경로 검사 — 검색과 같은 규칙)
- [모두 읽음] 버튼 → 전체 읽음 PATCH
- **배지는 항상 서버 값으로 갱신한다(v3 R-8)** — "0으로 고정"하거나 클라이언트에서 감소시키지 않는다(전체 읽음 직후 새 알림이 저장됐거나, 이미 읽은 항목·동시 클릭일 수 있다)
- **읽음 요청은 직렬화한다(v4 R-15)**: 요청 세대로 늦은 응답을 버리면, 읽음 PATCH A·B가 겹쳐 B가 먼저 끝나 `unreadCount=1`을 반영한 뒤 A의 `0` 응답이 이전 세대라 폐기돼 서버(미읽음 0)와 배지(1)가 어긋난다(요청 시작 순서는 서버 처리 순서를 보장하지 않는다). 따라서 **읽음 PATCH는 클라이언트 큐로 한 번에 하나씩** 보내고, **큐가 비면 `GET …/unread-count`를 한 번 재조회해 그 값으로 배지를 확정**한다. 목록·count 조회에는 요청 세대를 그대로 쓴다(읽음 큐가 진행 중일 때 시작된 count·목록 응답은 큐 종료 뒤 재조회가 덮는다)
- 렌더링은 `textContent`만, 요청 세대로 늦은 응답 무시(검색 드롭다운과 같은 패턴), 401은 세션 만료 안내
- 검색 드롭다운과 동시에 열리지 않게 서로 닫는다

## 6. 위험·불변식

| # | 위험 | 대응 |
|---|---|---|
| R1 | 다른 회원의 알림 열람·읽음 처리(IDOR) | 모든 쿼리를 `member_id = principal.id` 조건으로, 단건은 `findByIdAndMemberId` → 없으면 404. 시험으로 교차 회원 접근 거부 고정 |
| R2 | 알림 저장 실패가 원 업무(상태·권한 변경, 로그인)를 깨뜨림 | AFTER_COMMIT + `REQUIRES_NEW` + 예외 삼킴(E1·E2), 로그인 경로는 try/catch로 로그인 계속(E3). 실패 주입 시험 |
| R3 | 원 트랜잭션 롤백인데 알림만 생김 | AFTER_COMMIT에서만 저장(롤백 시 미실행). 시험은 **이벤트 발행 뒤** 외부 트랜잭션을 롤백시켜 확인한다(§7 — 409로 끝나는 권한 저장은 이벤트 발행 전 종료라 증명이 되지 않는다) |
| R4 | XSS | 메시지는 서버 상수·enum 라벨만, 화면은 `textContent`, 링크는 같은 출처 경로만 |
| R5 | E3 중복(동시 로그인·재로그인마다 생성) | `(member_id, dedupe_key)` 유니크 + 위반 무시 |
| R6 | 알림 무한 증가 | 목록은 페이징, 읽은 알림 90일 후 로그인 시 정리(D7·D8). 미읽음과 로그인하지 않는 회원의 알림은 남는다 |
| R7 | 배지 조회 부하 | 페이지 로드당 `unread-count` 1회(인덱스 `(member_id, read_at)` 카운트), 폴링 없음 |
| R10 | E4 링크 권한 | 링크(`/admin/member/manage`)는 ADMIN 전용이고 열람 자체가 현재 ADMIN에게만 허용되므로(D11) 링크가 403인 알림이 보이지 않는다. 페이지를 연 뒤 강등되면 다음 요청이 기존 게이트에서 403 |
| R12 | 강등된 ADMIN이 E4(다른 계정 아이디·잠금 시각)를 계속 열람(v3 R-9) | 열람 시점 필터(D11)를 목록·count·읽음 쿼리 조건으로 적용. 시험: ADMIN→MANAGER 강등 후 목록·count에서 E4 제외, 단건 읽음 404, 재승격 후 다시 노출 |
| R11 | E4 대량 생성(여러 계정을 돌며 무차별 대입, 관리자가 복구해 반복 잠금) | 생성량 ≤ 잠금 횟수 × ACTIVE ADMIN 수(30분 잠금은 절대 상한이 아니다 — 관리자 복구로 30분 전에도 재잠금 가능). 다중 행 `INSERT … VALUES` 한 문장으로 비용을 줄이고 별도 묶음·억제는 범위 밖 |
| R13 | E4 저장이 `member` 행 잠금을 전파(v6 R-17) | 수신자 ID는 잠금 없는 일반 SELECT로 읽고(`INSERT … SELECT FROM member` 금지) 다중 행 `INSERT … VALUES`로 저장. FK 검사의 수신자 행 공유 잠금 대기는 수신자에 한정·타임아웃으로 제한·예외 삼킴. 시험: 수신자가 **아닌** MANAGER 행을 `FOR UPDATE`로 잡은 채 E4 저장이 완료됨 |
| R8 | 잠긴 계정의 E1 알림이 해제 전에는 보이지 않음 | 의도된 한계(F10). 잠금 사실은 해제 후 첫 로그인에서 확인 |
| R9 | 회원 하드 삭제 시 FK 위반 | 앱은 하드 삭제하지 않는다(F8). **시험 정리는 `TestMembers.delete`만 고치면 부족하다(v3 R-2)** — `memberRepository.deleteById()`를 직접 부르는 기존 통합 시험(`LoginFailureLockoutIntegrationTest`, `AdminMemberUpdateConcurrencyIntegrationTest` 등, 일부는 삭제 실패를 삼켜 시험은 통과하지만 회원이 남는다)을 전수 조사해 알림 선삭제로 바꾸고, **남은 ACTIVE ADMIN이 후속 E4 수신자로 누적되지 않도록** 자동 잠금·ADMIN 생성을 다루는 시험은 사건별로 만든 회원만 수신자가 되게 정리한다 |

## 7. 시험

1. 마이그레이션: V20 적용, 유니크·FK 동작, **실패 복구**("V20 객체만 있고 성공 이력 없음" 복구와 잘못된 절차 반례 — `MemberPermissionMigrationTest` 관례), 이전 앱 기동 호환 확인(v3 R-10)
2. `NotificationService` 단위: 목록 최신순·size 상한·`beforeId` 커서(중간에 새 알림이 삽입돼도 중복·누락 없음), 단건 읽음 멱등(**이미 읽은 알림은 기존 `read_at` 유지**), 타인 알림 404, 전체 읽음 건수. **경합 시험**: 단건·단건, 단건·전체 읽음이 동시에 와도 최초 `read_at`이 보존됨(v3 R-7)
3. 생성 경로 통합(Testcontainers, 실제 트랜잭션):
   - 관리자가 상태를 LOCKED로 바꾸면 커밋 후 알림 1건, 400으로 끝나면 0건, `DELETED`는 0건. **이벤트 발행 뒤 외부 트랜잭션 롤백 시험(v3 R-11)**: 서비스가 이벤트를 발행한 다음 같은 트랜잭션을 롤백시키면 알림 0건(409 권한 저장은 발행 전 종료라 이 증명에 쓰지 않는다)
   - **자동 잠금 이벤트 정확성(v3 R-1)**: 로그인 요청 `username`이 `ADMIN01`·`admin01 `(후행 공백)·긴 후행 공백 등 변형이어도 알림 메시지에는 DB의 정규 `userId`가 들어가고 길이 때문에 E1·E4가 실패하지 않으며, 표시 시각은 `lockedAt`
   - **저장 실패 격리(v3 R-11)**: Recorder의 flush·커밋 실패를 주입해도 관리자 **상태·역할·개별 권한 변경** 각각의 원 업무가 커밋되고, 로그인 경로 실패 주입도 로그인 성공
   - **E4 잠금 비전파(v6 R-17)**: 실제 MariaDB에서 수신자가 **아닌** MANAGER 행을 별도 트랜잭션이 `SELECT … FOR UPDATE`로 잡은 상태에서도 E4 저장이 대기 없이 완료됨(`INSERT … SELECT FROM member`였다면 대기하는 경로). 수신자 ADMIN 행이 잠긴 경우는 대기 후 타임아웃·예외 삼킴(원 업무는 이미 커밋)으로 끝나는지 확인
   - **리스너 순서(v4 R-13)**: 알림 리스너를 의도적으로 블로킹한 상태에서 같은 이벤트의 세션 만료(`AdminSessionRevokeListener`)와 권한 캐시 무효화(`PermissionChangedListener`)가 이미 완료돼 있음 — 기존 클래스 `@Order`가 메서드 리스너에는 적용되지 않는다는 점(메서드 `@Order` 명시)이 회귀로 고정되도록 순서 자체를 단언한다
   - **E3 SQL(v4 R-14)**: 실제 MariaDB에서 `INSERT … SELECT … ON DUPLICATE KEY UPDATE id = notification.id`가 최초 생성·같은 주기 중복 생성·스냅샷 불일치(0행) 모두 오류 없이 수행됨
   - **풀 포화(v3 R-3)**: `maximumPoolSize` 2~3의 작은 풀에서 병렬 자동 잠금을 돌려도 로그인 실패 응답이 막히지 않고 세션 만료가 알림 저장보다 먼저 일어남(리스너 `@Order`)
   - **E3 동시 로그인 유니크 충돌(v3 R-6)**: 같은 비밀번호 주기에 동시 로그인해도 알림 1건, 예외 없음, 같은 요청의 90일 정리는 영향 없이 수행됨. 재확인 직후 비밀번호를 바꾸면(스냅샷 불일치) 이전 주기 알림이 생기지 않음(v3 R-5)
   - 역할 변경 1건, 개별 권한 부여·회수 1건(메시지에 추가·회수 라벨), 변경 없음 0건
   - 자동 잠금: 본인 E1 1건 + ACTIVE ADMIN마다 E4 1건(잠긴 본인, ACTIVE가 아닌 ADMIN, MANAGER는 제외), 잠긴 계정이 ADMIN이어도 자기에게 E4 없음
   - 로그인 성공 시 읽은 지 90일 지난 알림 삭제, 89일·미읽음은 유지
   - 로그인 성공 시 만료 7일 이내면 1건, 같은 주기 재로그인은 추가 없음, 8일 이상 남으면 0건, 재확인 실패(잠긴 계정)면 0건. **경계(v3 R-4)**: 23시에 바꾼 비밀번호의 90일째 오전 로그인(`expiresAt` 기준 1일 미만 남음)에서도 알림이 생성되고, 7일을 막 넘긴 잔여 시간에는 생성되지 않음. 메시지에 절대 만료일 포함
   - **열람 시점 권한(D11, v3 R-9)**: E4를 받은 ADMIN을 MANAGER로 강등하면 목록·`unreadCount`에서 E4가 빠지고 단건 읽음은 404, 전체 읽음은 E4를 건드리지 않으며, 재승격하면 다시 보임. MANAGER는 처음부터 E4를 받지 않음
4. 컨트롤러 슬라이스: 인가(ADMIN·MANAGER 200, USER 403, 비로그인 JSON 401), CSRF 없는 PATCH 403, `read:false` 400
5. 교차 회원 IDOR 통합: A의 알림 id로 B가 PATCH → 404, A 알림 불변
6. `ClockUsageConventionTest`·`AdminEndpointAuthorizationConventionTest` 통과
7. 화면 로직(가능한 범위, v4 R-15): 읽음 PATCH 두 건의 시작·완료 순서를 뒤집어도(B가 먼저 완료) 큐 종료 뒤 `unread-count` 재조회 값으로 배지가 서버와 일치
8. playwright: 배지 표시 → 드롭다운 → 단건 읽음(배지 감소) → 링크 이동 → 모두 읽음, MANAGER 동일

## 8. 단계 (각 단계 끝에 `./gradlew test`)

1. V20 + 엔티티·리포지토리 + **테스트 정리 전수 조사**(`memberRepository.deleteById()` 직접 호출·`DELETE FROM member` 모두 찾아 알림 선삭제 — `TestMembers.delete`만으로는 부족, v3 R-2) + 이벤트 필드 보강(`AdminAccountAutoLockEvent`에 `canonicalUserId`·`lockedAt`, `MemberSnapshot`에 `passwordChangedAt`)
2. 조회·읽음 서비스 + API + 시험
3. 생성: 이벤트·리스너(E1·E2·E4) + **기존 세 리스너 메서드에 `@Order` 명시**(v4 R-13) + 순서 시험
4. 생성: 로그인 시 E3 + 시험
5. 상단바 벨·드롭다운
6. 문서·playwright

## 9. 문서

- `notification` 패키지 `CLAUDE.md` 신설(생성 계약·최선 노력·E3 한계) 또는 `member/CLAUDE.md` — 구현 시 결정
- 루트 `CLAUDE.md` "지침 파일 지도"에 새 패키지 문서 추가
- `config/CLAUDE.md`: 접근 제어 표 변경 없음(기존 `/admin/api/members/me/**`) — 한 줄 주석만

## 10. 미결 사항

- 없음 — v1의 Q1~Q5는 §1 D3~D10으로 결정됐다(2026-10-05).

## 구현 메모 (2026-10-05)

- 구현 완료(브랜치 `feat/admin-notification`, 커밋·PR 전). 전체 시험 통과, playwright로 실제 앱(자동 잠금 → E1·E4, 관리자 수정 → E1, 비밀번호 임박 → E3, 읽음·전체 읽음·상세 이동·MANAGER 시점) 확인.
- 계획과 달라진 점:
  1. **`notification.member_id` FK는 `ON DELETE CASCADE`**(계획 §4는 RESTRICT 관례). 회원을 직접 지우는 기존 통합 시험이 10곳이라 개별 수정 대신 DB가 막도록 했다(R-2의 목적 — 알림 때문에 정리가 조용히 실패하지 않게). 앱은 회원을 하드 삭제하지 않아 운영 동작은 같다.
  2. **리스너 순서 보강이 계획보다 한 가지 더 필요했다**: Spring은 `AFTER_COMMIT` 콜백을 모두 실행한 **뒤에** `AFTER_COMPLETION`을 실행하므로 v4의 "메서드 `@Order` 명시"만으로는 권한 캐시 무효화(원래 `AFTER_COMPLETION`)가 알림 저장보다 먼저 실행되지 않는다 → `PermissionChangedListener`에 **`AFTER_COMMIT`(`@Order(10)`) 무효화를 추가**하고 `AFTER_COMPLETION`은 백스톱으로 남겼다(커밋 성공 경로에서 `invalidate()` 2회 — 기존 시험 3곳 `times(1)` → `atLeastOnce()`). `docs/troubleshooting.md`에 기록.
  3. 후속 DROP 마이그레이션(PR B) 번호는 V20이 알림 테이블에 쓰여 **V21 이상**이다(`permission/CLAUDE.md` 정정).
  4. E4 메시지 조사는 받침 판정(`euro`)을 추가했다("'잠금'으로", "'관리자'로").
- 확인하지 못한 것: 드롭다운 "더 불러오기"(10건 초과) 화면 동작과 한글 조사 외 다국어, 401(세션 만료) 상태의 벨 UI는 브라우저로 확인하지 못했다(코드 경로만 구현 — 목록 커서는 통합 시험이 고정). `EXPLAIN`으로 인덱스 사용을 확인하는 절차(§4)는 데이터가 거의 없는 테스트 DB라 수행하지 않았다 — 운영 데이터 규모에서 재확인 필요.
