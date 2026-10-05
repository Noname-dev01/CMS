# CLAUDE.md — com.cms.admin.notification

이 디렉터리(관리자 알림 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.
계획서: `adversarial-review/plan/PLAN-admin-notification.md`(적대적 리뷰 5라운드 ship, 2026-10-05).

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## 무엇이 알림이 되나

상단바 벨에 보이는 **그 관리자 본인의** 알림이다. 쪽지·실시간 푸시·이메일은 없다.

| 종류(`NotificationType`) | 사건 | 수신자 | 생성 지점 |
|---|---|---|---|
| `ACCOUNT_STATUS` (E1) | 관리자가 내 계정 상태를 바꿈(잠금·비활성·활성 복구), 로그인 연속 실패 자동 잠금 | 대상 본인 | `AdminMemberService.updateAdminMember`, `AdminAccountAutoLockEvent` |
| `PERMISSION` (E2) | 역할 변경, 개별 권한 부여·회수 | 대상 본인 | `AdminMemberService.updateAdminMember`, `MemberPermissionService.replace` |
| `PASSWORD_EXPIRY` (E3) | 비밀번호 만료 7일 이내 | 본인 | 로그인 성공 처리(`VisitLoggingAuthenticationSuccessHandler`) |
| `ADMIN_ACCOUNT_LOCKED` (E4) | **다른** 관리자 계정의 자동 잠금 | 다른 **ACTIVE ADMIN 전원**(잠긴 본인 제외) | `AdminAccountAutoLockEvent` |

삭제(`DELETED`)로 바뀌는 계정에는 알림을 만들지 않는다. 잠금·비활성 계정은 세션이 만료되므로 E1은 **다시 로그인할 수 있게 된 뒤**에 보인다.

## 생성 계약 — 최선 노력, 원 업무를 되돌리지 않는다

- **E1·E2(관리자 수정·권한 저장)**: 원 트랜잭션은 `NotificationRequestedEvent`를 **발행만** 한다. `NotificationEventListener`가 `AFTER_COMMIT`에서 `NotificationRecorder.record()`(`REQUIRES_NEW`)로 저장한다 — 롤백되면 알림이 생기지 않고, 알림 저장이 실패해도(예외는 삼키고 ERROR 로그) 원 변경은 이미 커밋돼 있다. 감사 로그와 같은 계약이다.
- **E1·E4(자동 잠금)**: `AdminAccountAutoLockEvent`를 같은 리스너가 구독한다. 한 `REQUIRES_NEW` 트랜잭션에서 잠긴 본인 E1 1건 + 다른 ACTIVE ADMIN E4를 **다중 행 `INSERT … VALUES` 한 문장**으로 저장한다. **수신자 ID는 잠금 없는 일반 SELECT**(`MemberRepository.findActiveAdminIdsExcluding`)로 읽는다 — `INSERT … SELECT … FROM member`는 REPEATABLE READ에서 원본 읽기가 공유 잠금을 걸어 수신자가 아닌 회원의 변경까지 지연시킨다(`findActiveAdminIdsForUpdate`와 혼동 금지). 남는 한계: `notification.member_id` FK 검사가 수신자 ADMIN 행에 공유 잠금을 걸어, 그 행을 다른 트랜잭션이 수정 중이면 저장이 대기할 수 있다(타임아웃·예외 삼킴).
- **이벤트의 아이디·시각은 정규 값이다**: `AdminAccountAutoLockEvent.userId`는 로그인 요청의 `username` **원문**(대소문자·후행 공백 변형, 길이 무제한)이라 알림 메시지에 쓰지 않는다. 이벤트에 추가된 `canonicalUserId`(DB의 `member.userId`, ≤50자)와 `lockedAt`(잠금 전이 시각)을 쓴다 — 요청 원문을 쓰면 `message`(255자) 상한을 깨 E1·E4 저장 전체가 실패할 수 있다.
- **E3(비밀번호 임박)**: `expiresAt = passwordChangedAt + 90일`이 `(now, now+7일]`이면 생성(기존 만료 쿼리와 같은 시·분·초 기준 — 날짜 차이로 계산하지 않는다). 판정 기준은 로그인 재확인이 읽은 fresh 스냅샷(`MemberSnapshot.passwordChangedAt`)이고, 저장은 **"회원의 현재 `password_changed_at`이 스냅샷과 같을 때만"** 조건부 `INSERT … SELECT … ON DUPLICATE KEY UPDATE id = notification.id`다(**`id = id`는 `member.id`·`notification.id`가 모호해 오류**). `dedupe_key = "PASSWORD_EXPIRY:" + passwordChangedAt`로 **비밀번호 주기당 1건**이다. 반환값(행 수)에 의존하지 않는다 — 중복 시 값이 드라이버 설정에 달라진다.
- **로그인 경로는 `REQUIRES_NEW`를 쓰지 않는다**(요청당 커넥션 2개 금지 — `LoginFailureService.unlockIfLockExpired` 주석의 풀 고갈 규칙). 성공 핸들러는 트랜잭션 밖이라 `recordPasswordExpiryIfNear`와 `purgeOldReadNotifications`는 **서로 다른 `@Transactional`(REQUIRED) 메서드**로 각각 자기 트랜잭션을 열고, 핸들러가 각각 try/catch로 감싸 한쪽 실패가 다른 쪽과 로그인을 막지 않는다. **정리(D7·D8)**: 읽은 지 90일이 지난 본인 알림만 삭제(미읽음은 지우지 않음). 스케줄러가 없어 로그인하지 않는 회원의 오래된 알림은 남는다.

## 리스너 실행 순서 — 메서드 `@Order`, 그리고 `AFTER_COMMIT`이 `AFTER_COMPLETION`보다 먼저

알림 저장의 연결·락 대기가 **세션 만료·권한 캐시 무효화를 막지 않게** 알림을 가장 늦게 실행한다. 두 가지 사실을 알아야 한다(`docs/troubleshooting.md` 참조).

1. Spring 6.2의 메서드 리스너는 **메서드의 `@Order`만** 읽는다 — 클래스에 붙인 `@Order`는 무시되고 없으면 `LOWEST_PRECEDENCE`다. 그래서 `AdminSessionRevokeListener.onRevoke`(10)·`AdminAccountAutoLockListener.onAutoLock`(20)·`PermissionChangedListener.onCommitted`(10)에 **메서드 `@Order`를 명시**했고 알림 리스너는 `NotificationEventListener.ORDER`(100)다(클래스 `@Order`는 그대로 두되 메서드 값이 실제 순서를 정한다).
2. Spring은 `AFTER_COMMIT` 콜백을 **모두 실행한 뒤에** `AFTER_COMPLETION`을 실행한다. 권한 캐시 무효화는 원래 `AFTER_COMPLETION`뿐이라 알림 저장(`AFTER_COMMIT`)이 막히면 `@Order`와 무관하게 무효화가 늦어진다 → `PermissionChangedListener`에 **`AFTER_COMMIT` 무효화(`@Order(10)`)를 추가**하고 기존 `AFTER_COMPLETION` 무효화는 롤백·결과 불명용 백스톱으로 남겼다(무효화는 멱등 — 커밋 성공 경로에서 `invalidate()`는 2회 호출되며, 그래서 시험은 `times(1)`이 아니라 `atLeastOnce()`로 단언한다).

`NotificationGenerationIntegrationTest`가 순서를 고정한다(세션 만료·캐시 무효화가 알림 저장보다 먼저). 변이 실험(`PermissionChangedListener.onCommitted`의 `@Order` 제거)으로 이 시험이 실패함을 확인했다.

## API — `/admin/api/members/me/notifications`

전부 `hasAnyRole('ADMIN','MANAGER')`, 경로가 `/admin/api/members/me/**`라 기존 상시 허용(`MY_INFO`) 게이트 안이다 — **카탈로그·`SecurityConfig` 변경 없음**. 회원 ID는 세션 principal에서만 읽는다(경로·본문으로 받지 않음 — IDOR 방지).

| 메서드·경로 | 설명 |
|---|---|
| `GET ?size=10&beforeId=` | 본인 알림 최신순(id 내림차순) **`beforeId` 커서**(offset 페이징은 목록이 바뀌면 중복·누락). size 상한 50. 응답 `{content, unreadCount, hasMore}` |
| `GET /unread-count` | 배지용 |
| `PATCH /{id}` `{"read": true}` | 단건 읽음 — **원자적 조건부 UPDATE**(`read_at IS NULL`일 때만)라 경합해도 최초 읽음 시각이 보존된다. 이미 읽은 알림은 기존 시각을 유지한 채 200(멱등), 없거나 남의 알림이거나 숨겨진 종류면 **404**(존재 숨김) |
| `PATCH` `{"read": true}` | 본인에게 보이는 미읽음 전부 읽음 |

`read: false`(읽음 취소)는 400. 감사 로그는 남기지 않는다(본인 알림 읽음은 관리 행위가 아니다).

**D11 — 열람 시점 권한**: ADMIN 전용 종류(`ADMIN_ACCOUNT_LOCKED`, 다른 계정 아이디·잠금 시각)는 **요청 시점에 ADMIN인 사용자에게만** 보인다. 목록·`unreadCount`·단건·전체 읽음 쿼리의 **조건**으로 걸러(조회 후 응답에서 거르지 않는다 — `hasMore`·count·커서가 어긋나지 않게) 강등되면 E4가 보이지 않고 미읽음 수에서도 빠지며, 단건 읽음은 404, 전체 읽음은 보이는 행만 갱신(숨겨진 E4는 미읽음으로 남아 재승격 시 다시 보인다).

## 스키마 (V20)

`notification(member_id FK ON DELETE CASCADE, type, message, link_url, dedupe_key, read_at, create_date)` + `UNIQUE (member_id, dedupe_key)` + 인덱스 `(member_id, id)`(목록 커서)·`(member_id, read_at, id)`(미읽음 수·정리). **FK가 CASCADE인 이유**: 앱은 회원을 하드 삭제하지 않지만(`DELETED` 상태 전이만), 회원 행을 직접 지우는 시험·운영 SQL이 알림 때문에 조용히 실패하지 않게 한다(`member_permission`은 RESTRICT지만 알림은 회원 없이 의미가 없는 종속 데이터). 실패 복구·롤백은 `docs/migration-guide.md` "V20 실패 복구".

## 화면 (`static/js/admin/topbar-notification.js`)

배지는 **페이지 로드 시 1회만** 조회한다(폴링 없음 — 폴링이 세션 유휴 타임아웃을 연장한다). 드롭다운은 열 때 목록을 읽고 `beforeId`로 이어 읽으며 이미 그린 id는 중복 제거한다. **읽음 PATCH는 클라이언트 큐로 한 번에 하나씩** 보내고 큐가 비면 `unread-count`를 재조회해 배지를 서버 값으로 확정한다 — 요청 세대로 늦은 응답을 버리기만 하면 PATCH A·B의 완료 순서가 뒤집힐 때 최신 응답을 버려 배지가 서버와 어긋난다. 렌더링은 `textContent`만, 링크는 같은 출처 경로(`SAFE_PATH`)만, 시각은 서버 문자열을 시간대 변환 없이 표시한다. E4 링크 `/admin/member/manage?id=`는 통합 검색과 같은 `?id=` 진입으로 회원 상세를 바로 연다.

## 시험

`NotificationMigrationTest`(V20 적용·실패 복구), `NotificationRepositoryDataJpaTest`(커서·D11·원자적 읽음·정리·E3 조건부 INSERT), `NotificationServiceTest`·`NotificationControllerTest`, `NotificationApiIntegrationTest`(IDOR·D11 강등/재승격·동시 읽음·커서), `NotificationGenerationIntegrationTest`(E1·E2·E4·롤백·저장 실패 격리·리스너 순서), `NotificationLoginIntegrationTest`(E3 경계·스냅샷 불일치·동시 중복·정리), `NotificationMessagesTest`. 회원을 직접 지우는 기존 시험은 FK CASCADE로 별도 수정이 필요 없다.

## 남은 한계

- E3·정리는 **로그인 시점에만** 동작한다(스케줄러 없음) — 로그인하지 않는 동안에는 알림이 생기지 않고 오래된 읽은 알림은 남는다.
- 알림 저장은 최선 노력이라 DB 장애·풀 포화 시 유실될 수 있다(원 업무는 유지).
- E4는 자동 잠금 횟수 × ACTIVE ADMIN 수만큼 생성된다(별도 묶음·억제 없음 — 관리자가 `ACTIVE`로 복구하면 30분 전에도 다시 잠길 수 있다).
