# CLAUDE.md — com.cms.admin.message

이 디렉터리(관리자 쪽지 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.
계획서: `adversarial-review/plan/PLAN-admin-message.md`(적대적 리뷰 7라운드 ship, 2026-10-05).

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## 무엇인가

ADMIN·MANAGER가 서로 주고받는 **1:1 쪽지**(제목 + 본문, 평문)다. 알림(`com.cms.admin.notification`)이 **서버가 상수로 만든 문장**인 것과 달리 쪽지는 **다른 회원이 쓴 임의 입력**이라 입력 검증·표시 XSS·수신자 노출·삭제 의미·발송 빈도·감사 범위가 쟁점이다. 벨 알림과 연동하지 않는다(봉투 배지가 별도 — D14).

## 인가

- **API**는 `/admin/api/members/me/messages`·`/admin/api/members/me/message-recipients` — 기존 상시 허용 `MY_INFO` 게이트(`/admin/api/members/me/**`) 안이라 카탈로그·`SecurityConfig` 변경이 없고, 회원 ID는 세션 principal에서만 읽는다(IDOR 방지). 전부 `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")`다.
- **쪽지함 페이지 `/admin/member/messages`**는 `AdminFeature.MY_INFO`의 `gatePatterns`에 **정확 경로 1개**로 추가됐다(인가 정책 변경, 2026-10-05 사용자 승인). ALWAYS 게이트는 **HTTP 메서드를 구분하지 않으므로** 이 경로에는 GET 핸들러만 둘 수 있다 — `MessagePageMethodConventionTest`가 `RequestMappingHandlerMapping`에 등록된 **모든 매핑(별칭·와일드카드·ANY 포함)**을 검사해 GET/HEAD 외 핸들러가 생기면 CI가 실패한다(기존 `AdminEndpointAuthorizationConventionTest` 스캐너는 경로 배열의 **첫 경로만** 읽어 재사용하지 않았다). 하위·유사 경로(`/messages/x`·`/messagesX`)는 ADMIN 캐치올이라 MANAGER에게 403이고 세미콜론 경로는 방화벽이 400으로 거부한다(`SecurityConfigTest`).
- **현재 자격 가드(`MessageActorGuard`, D16)**: 세션의 역할·상태는 로그인 당시 스냅샷이고 상태·역할 변경 후 세션 만료는 최선 노력이며 인스턴스 로컬이라, **모든 쪽지 API가 요청마다 DB의 현재 상태·역할을 확인**한다(`ACTIVE`이고 ADMIN·MANAGER일 때만 통과, 아니면 403). `REQUIRES_NEW + readOnly + READ_COMMITTED`의 **스칼라 조회**라 호출자의 외부 RR 스냅샷("ACTIVE")을 읽지 않는다(R3-2 — 가드를 `REQUIRED`로 바꾸면 `guard_ignoresOuterSnapshot`이 실패하는 변이 실험으로 확인). 이 가드는 **쪽지 API 한정**이다(공지 등 다른 기능은 기존 세션 계약 그대로). 배지(`unread-count`)는 페이지마다 호출되므로 페이지당 PK 조회 1회가 늘어난다(수용). **수신자**는 `ACTIVE`가 아니어도 받을 수 있다(`LOCKED`·`PASSWORD_EXPIRED` 허용, D5).
- **호출 순서(고정)**: 컨트롤러 인가 → 가드(조회 종료) → 시도 버킷 → 감사 Aspect → 서비스 트랜잭션. 그래서 **가드 거부(403)와 트랜잭션 전 시도 제한 429는 감사에 남지 않는다**(서비스에 진입하지 않음).

## 입력 규칙 (`MessageTextPolicy`)

서버는 제목·본문을 **원문 그대로 저장**한다(이스케이프·필터링 없음 — 표시 계층 책임). 화면은 `textContent` + CSS `white-space: pre-wrap`만 쓰고 **자동 링크화를 하지 않는다**. 검증 파이프라인(순서 고정):
1. 원문 크기 방어 — DTO `@Size`(제목 raw ≤ 200, 본문 raw ≤ 4000 UTF-16 단위, 서비스 진입 전)
2. 고립 서로게이트(잘못된 UTF-16) 거부 — 기본 인코딩은 이를 조용히 `?`로 바꿔 서로 다른 입력이 같은 값이 된다
3. 본문 개행 정규화(`\r\n`·`\r` → `\n`) — 길이를 줄이기만 하므로 정상 입력을 거부하지 않는다(`a`×1999 + CRLF는 원문 2001이어도 허용)
4. 허용 문자 — 제목: 제어문자(Cc, 개행·탭 포함)·`Zl`·`Zp`·양방향 제어문자(U+202A~202E, U+2066~2069) 거부. 본문: Cc 중 `\n`·`\t` 외, `Zl`·`Zp`, 양방향 제어문자 거부. **ZWJ(U+200D) 등 일반 `Cf`는 허용**(이모지 합성 시퀀스가 깨지지 않게)
5. 최종 길이 — 제목 ≤ 100(trim 후)·본문 ≤ 2000 UTF-16 단위, 공백만이면 거부
실패는 400이고 **메시지는 고정 한국어 문구**다(사용자 입력을 되돌려주지 않는다).

## 보내기 — 트랜잭션 계약과 빈도 한도

`AdminMessageService.send`는 **`@Transactional(REQUIRES_NEW, READ_COMMITTED)`**다. `REQUIRES_NEW`는 호출자에게 외부 트랜잭션·스냅샷이 있어도 항상 새 물리 트랜잭션으로 실행한다(REQUIRED는 참여하며 **격리 수준도 무시한다** — `send_ignoresOuterSnapshot`이 변이 실험으로 고정). `READ_COMMITTED`는 낡은 스냅샷·`innodb_snapshot_isolation=ON`의 **오류 1020**(스냅샷 이후 수정된 행을 잠글 때)·갭 잠금을 없앤다 — **격리를 `REPEATABLE_READ`로 바꾸면 ON 컨텍스트에서 1020이 실제로 재현돼 시험이 실패한다**(확인함). 순서:

1. (DB 없음) 제목·본문 검증
2. **첫 DB 접근 = 발신자별 상태 행** `INSERT INTO admin_message_sender_state ... ON DUPLICATE KEY UPDATE member_id = member_id` — 행을 만들며 배타 잠금을 얻어 **같은 발신자의 발송만 직렬화**한다. **회원 행을 잠그지 않는다** — 쪽지 INSERT의 FK 검사가 양쪽 회원 행에 공유 잠금을 걸어 A→B/B→A 동시 발송이 교착한다
3. `now = LocalDateTime.now(clock)` — **잠금 취득 후** 산출
4. **삭제와 무관한 발송 이력**(`admin_message_send_log`, 발신자·시각만)으로 최근 1분 10건·24시간 300건(롤링 창)을 센다 → 초과 시 429(`RateLimitedException` → `RATE_LIMITED` + `Retry-After`). 쪽지를 지워도 한도가 회복되지 않는다(R1-3)
5. 수신자 검증 — 없음·자기 자신·`ROLE_USER`·`DISABLED`·`DELETED`가 **같은 400 문구**("쪽지를 받을 수 없는 수신자입니다.")다(상태별 문구는 계정 상태 탐지 통로)
6. 쪽지·이력 INSERT, 본인의 24시간 지난 이력 정리 — **만료 id를 일관 읽기로 조회한 뒤 PK로 지운다**(`(sender_id, sent_at)` 범위 DELETE는 갭 잠금으로 다른 발신자의 INSERT를 막을 수 있다)

- **교착(수용된 한계)**: 회원 수정은 대상 회원을 배타 잠근 뒤 강등·비활성화 시 **활성 ADMIN 전체를 배타 잠금**한다(최후 ADMIN 가드). 쪽지 INSERT의 FK 공유 잠금과 맞물려 **S→U→S 교착**이 가능하다 — 회원 FK를 두는 한(`ON DELETE RESTRICT`로 상대 보관함 보존) 어떤 잠금 구조로도 제거되지 않아 **409로 수용**한다(발송 트랜잭션 전체 롤백 — 쪽지·이력 모두 저장되지 않으니 화면이 "다시 시도" 안내). **순수 상호 발송은 교착하지 않는다**(`mutualSendsWithWidenedWindow_noDeadlock`, 409 0건). `GlobalApiExceptionHandler`는 `PessimisticLockingFailureException` 외에 **원인 체인의 MariaDB 오류 1020·1213**(`UncategorizedDataAccessException`·`TransactionSystemException`)도 409로 매핑한다.
- **수신자 검증 직후 `DELETED` 전이**: FK는 행 존재만 보장하므로 INSERT가 성공해 삭제 계정 보관함에 쪽지가 남는다 — 그 계정은 로그인할 수 없어 실해가 없다.
- 설정 `cms.message.send-per-minute`(10)·`send-per-day`(300)·`search-per-minute`(30)·`attempt-per-minute`(30)·`max-keys`(10000) — **기본값이 있어 생략해도 기동하고 0 이하·상한 초과는 바인딩 검증으로 기동 실패**한다.

## 목록·단건·읽음·삭제

- **목록** `GET ...?box=inbox|sent&size=20&beforeId=` — id 내림차순 `beforeId` 커서(size 기본 20·상한 50), 본인 쪽에서 삭제한 행 제외, **본문 미포함**, 상대는 현재 `member` 행에서 조인한 `id·userId·userName`만(이메일·역할·상태 비노출, 상대가 `DELETED`여도 쪽지는 보인다). 보낸 쪽지함의 `read`·`readAt`이 **읽음 확인**(D10)이다. **`readAt`은 미읽음이면 키가 생략된다**(프로젝트 전역 Jackson NON_NULL — 화면은 키 부재를 미읽음으로 처리한다).
- **단건** `GET .../{id}` — 소유 조건 `(sender_id = :me AND sender_deleted_at IS NULL) OR (recipient_id = :me AND recipient_deleted_at IS NULL)`(괄호 고정). 아니면 404(존재 숨김). **읽음을 일으키지 않는다**(GET 부작용 없음).
- **읽음** `PATCH .../{id}` `{"read": true}` — **수신자만**. `recipient_id = :me AND recipient_deleted_at IS NULL AND read_at IS NULL`일 때만 갱신하는 **원자적 UPDATE**(경합해도 최초 읽음 시각 보존), 0행이면 존재 확인 — 이미 읽은 수신자의 쪽지면 기존 `read_at` 유지 200(멱등), 그 밖(발신자 본인 포함)은 404. 응답에 서버가 센 `unreadCount`.
- **삭제** `DELETE .../{id}` — **본인 쪽 보관함에서만**(D8, 상대에게는 남음). **알고리즘·SQL 종류가 고정**이다: ① `REQUIRES_NEW + READ_COMMITTED`의 **첫 DB 접근이 조건부 UPDATE**(일반 SELECT·엔티티 로딩 선행 금지) ② 발신측 UPDATE가 0행이면 수신측 UPDATE ③ 둘 다 0행이면 404(없음·남의 것·이미 지운 것) ④ 성공하면 **네이티브 조건부 DELETE를 직접 실행**(`... AND sender_deleted_at IS NOT NULL AND recipient_deleted_at IS NOT NULL`) — 물리 삭제 여부를 일반 SELECT·엔티티 플래그로 판단하지 않는다. 두 쪽이 동시에 지워도 **DELETE 영향 행 합계는 1**이고 최종 행이 없다(`AdminMessageSnapshotIsolation{Off,On}IntegrationTest` — **`innodb_snapshot_isolation` OFF·ON 전용 Spring 컨텍스트**, `connection-init-sql` + `@DirtiesContext(AFTER_CLASS)`로 풀 폐기. `SET SESSION`은 풀의 물리 연결에 남고 HikariCP는 이를 복원하지 않아 공유 컨텍스트에서 설정하면 다른 시험을 오염시킨다). 물리 DELETE 호출을 제거하면 두 컨텍스트 모두 실패하는 변이 실험으로 확인했다. 삭제는 HTTP 의미상 멱등이다(이미 지운 쪽의 반복 요청이 404인 것은 존재 숨김 정책). 감사 로그는 남기지 않는다.
- **FK는 모두 RESTRICT**(R1-13): 회원 행을 직접 지우는 SQL이 상대 보관함의 쪽지를 조용히 지우지 못한다(앱은 회원을 하드 삭제하지 않는다 — `DELETED` 전이). 쪽지를 만드는 시험의 정리는 `TestMembers.delete`가 쪽지·이력·상태 행을 먼저 지운다.

## 수신자 검색 (`MessageRecipientService`, D4)

- **검색 원문을 보존한다(R6-1)**: 회원 생성 API는 아이디 앞뒤 공백을 막지도 정규화하지도 않아 앞 공백이 든 아이디가 정상 데이터일 수 있다. ① **원문 그대로의 `userId` 정확 일치**를 먼저 조회(원문 코드포인트 ≤ 50일 때, `member.user_id`는 `utf8mb4_general_ci` 유일 제약이라 최대 1건) ② 부분 검색은 **trim 후 2~50코드포인트**일 때만(`userId`·`userName` `contains` — QueryDSL이 `%`·`_`를 이스케이프). **1코드포인트 입력은 정확 일치만** — 한 글자 아이디·이름도 정상 계정이라, 저장된 아이디를 **원문 그대로** 입력하면 수신 가능한 어떤 계정도 항상 선택할 수 있다. 화면은 검색어를 보내기 전에 공백을 제거하지 않는다.
- 대상은 ADMIN·MANAGER 중 `ACTIVE`·`LOCKED`·`PASSWORD_EXPIRED`(자기 제외), 정확 일치가 앞, 회원 `id`로 중복 제거, 10건까지·더 있으면 `truncated: true`. 노출 필드는 `id`·`userId`·`userName`뿐이다.
- **MANAGER에게 관리자 명단이 새로 열리는 유일한 경로다**(D4 수용). 1코드포인트 정확 조회는 수신 가능 계정의 **존재 확인 경로**이고 **D17은 이를 차단하지 않고 속도만 제한한다**(영문·숫자 36개 후보를 약 12초에 순회 가능 — 사실 그대로 기록).
- **검색·발송 시도 남용 제한(D17)**: 인증 회원 ID 키의 **인메모리 토큰 버킷 — 버스트 30 + 평균 분당 30**(`MessageRateLimiter`·`MemberTokenBucketLimiter`, 규칙 ID `message-search`·`message-attempt`, 요청당 1회 소비). **엄격한 "임의의 1분 30회"가 아니다**(시작 즉시 30회 후 2초마다 1회면 첫 60초에 59회). **공개 경로 필터(`cms.rate-limit.*`)와 분리**돼 `cms.rate-limit.enabled=false`에도 유지되고(IP 키로 소비하면 같은 사무실 IP의 회원들이 서로를 차단한다), 같은 회원의 여러 세션은 같은 버킷을 쓴다. 단일 인스턴스·fail-open(캐시 포화 시 정확성 흐트러짐)을 수용 — 다중 인스턴스 정확 제한은 하지 않는다.

## 감사와 로그 비유출 (`@AdminActionLogged.safeErrorMessage`)

- 발송은 `MESSAGE_SEND`(대상 = 수신자 ID, **제목·본문 없음**, `targetLabel` 없음)로 감사한다. 읽음·삭제·검색·목록은 남기지 않는다. 감사는 **서비스에 진입한 발송 시도**만 기록한다(DTO 크기 상한·JSON 파싱 400은 서비스에 들어가지 않아 기록되지 않는다).
- 실패 감사의 `errorMessage`는 **`safeErrorMessage` 고정 문구**("쪽지 발송에 실패했습니다.")다 — 서비스 반환 이후 트랜잭션 어드바이저가 던지는 flush·커밋 예외의 메시지에 SQL·값이 섞일 수 있어서다(공용 감사 모듈의 소규모 확장 — `com.cms.admin.log`의 `CLAUDE.md`). 결과 객체는 실제 `Long getRecipientId()`를 가져야 한다(record 접근자·중첩 getter는 Aspect 추출 규칙에 맞지 않는다).
- **서버 로그 보장은 "검증된 JDBC·세션 설정에서의 보장"이다**: `safeErrorMessage`는 감사 **저장값**만 보호하고 Hibernate `SqlExceptionHelper`가 찍는 JDBC 예외 출력은 코드로 막을 수 없다(로거를 끄면 전 기능의 진단이 사라진다). 코드가 보장하는 전제는 ① 길이 선검증으로 "Data too long"이 나오지 않음 ② 유일 키는 자동 증가 PK와 상태 행(`ON DUPLICATE`)뿐 ③ FK·CHECK 위반 메시지는 제약·테이블 이름만 담음이고, **운영 전제**(코드가 강제하지 않음 — `docs/deployment.md`)는 Connector/J 진단 옵션 `dumpQueriesOnException`·`includeInnodbStatusInDeadlockExceptions` 비활성, 바인딩·JDBC 쿼리 DEBUG 로깅 비활성, 연결 `utf8mb4`+strict mode다. 시험(`AdminMessageInstrumentedIntegrationTest`)은 본문 표식을 심은 요청에서 FK 위반·`beforeCommit` 롤백을 주입해 **로그 출력 전체(메시지·Throwable·cause)에 표식이 없음**을 검사한다.
- **롤백 시험은 `beforeCommit` 동기화로 실제 커밋 전에 실패시킨다** — `MemberPermissionApiIntegrationTest`의 DataSource 프록시는 **실제 `commit()`을 수행한 뒤** 예외를 던지는 "커밋 응답 유실" 모사라 롤백 시험의 선례가 아니다. 커밋 응답 유실은 쪽지·이력이 저장돼 있어도 FAIL 감사만 남는 한계다(호출자가 재시도하면 중복 쪽지가 생길 수 있다).

## 스키마 (V21)

`admin_message(sender_id, recipient_id, title, body, read_at, sender_deleted_at, recipient_deleted_at, create_date)` + CHECK(`sender_id <> recipient_id`) + 인덱스 `(recipient_id, recipient_deleted_at, id)`(받은 목록)·`(recipient_id, recipient_deleted_at, read_at, id)`(미읽음)·`(sender_id, sender_deleted_at, id)`(보낸 목록) — **삭제 열을 인덱스에 포함**해 무기한 보관에서 한쪽만 삭제된 행이 쌓여도 삭제된 행을 대량으로 건너뛰며 읽지 않게 한다. `admin_message_sender_state(member_id)`(잠금 전용 행)·`admin_message_send_log(sender_id, sent_at)`(발송 이력). 실패 복구·롤백은 `docs/migration-guide.md` "V21 실패 복구"(성공 이력이 없고 기대 스키마이며 **세 테이블이 모두 비어 있을 때만** DROP — 데이터가 있으면 백업 후 별도 분기).

**비용 기준은 "인덱스 사용"이 아니라 핸들러 읽기 수다**(`AdminMessageIndexCostIntegrationTest`): 보이는 20건이 가장 오래되고 최신 10만 건이 한쪽 삭제된 회원에서 받은 목록 첫 페이지·커서 페이지·미읽음 수·보낸 목록·이력 집계(창 밖 10만 건 + 창 안 5건)가 모두 **2~68회**(기준 300 미만)이고, 삭제 열이 없는 단순 인덱스 `(recipient_id, id)` 대조군은 같은 질의가 **5만 회를 넘게** 읽는다(측정 장치가 위반을 실제로 잡는다). 쪽지 시각은 앱 KST `Clock`이다 — **시험 데이터를 DB `NOW()`로 넣으면 컨테이너 시각(UTC)이라 앱의 한도 창 밖에 놓인다**(`docs/troubleshooting.md`).

## 화면

- **상단바**(`topbar.html` + `static/js/admin/topbar-message.js`): 봉투 + 미읽음 배지(페이지 로드 시 1회, 폴링 없음 — 폴링은 세션 유휴 타임아웃을 연장한다) + 드롭다운(최근 받은 쪽지 5건, 항목은 쪽지함 `?id=`로 이동). **배지의 값·요청 세대·재조회는 이 스크립트가 단독으로 소유**하고 쪽지함 페이지는 `window.CmsMessageBadge.markChangeStarted()`·`refreshAfterChange()`로만 갱신한다 — 두 스크립트가 각자 count를 조회하면 늦게 도착한 최초 응답이 읽음 처리 뒤 배지를 이전 값으로 되돌리고 폴링이 없어 복구되지 않는다(playwright로 첫 응답을 4초 지연시켜 재현·확인).
- **쪽지함**(`templates/admin/member/messages.html` + `message-box.js`): 받은/보낸 탭(`beforeId` 커서 [더 보기], 이미 그린 id 중복 제거), 상세 모달(요청 세대·`AbortController`·응답 `id` 일치 검사로 늦은 응답이 다른 쪽지를 덮지 않음, 받은 미읽음 쪽지는 열면 읽음 PATCH — 큐로 직렬화), 작성 모달(수신자 검색은 **입력할 때마다 선택된 수신자를 즉시 해제**하고 결과를 눌러야만 `recipientId`가 설정된다, 남은 글자 수는 UTF-16 길이로 서버 기준과 같다, 보내는 중 버튼 잠금), 답장(수신자·`Re: 제목`을 미리 채우되 **코드포인트 경계를 보존해** 100 UTF-16 단위로 자른다), 삭제(2단계 확인). 모든 데이터는 `textContent`로만 그린다. 쪽지 시각은 서버 문자열을 시간대 변환 없이 표시한다.

## 시험

`AdminMessageMigrationTest`(V21·실패 복구 3분기·V21 파일이 없는 마이그레이션 위치로 롤백 호환), `MessageTextPolicyTest`, `MessageRecipientServiceTest`, `AdminMessageControllerTest`(슬라이스), `AdminMessageSendIntegrationTest`·`AdminMessageBoxIntegrationTest`(실제 스택 — IDOR·한도·가드·검색·삭제 의미·동시성), `AdminMessageInstrumentedIntegrationTest`(경합 창 확대 지연 주입·`REQUIRES_NEW`/`READ_COMMITTED` 관측·가드 외부 스냅샷 반례·커밋 직전 실패·FK 위반·로그 비유출), `AdminMessageSnapshotIsolation{Off,On}IntegrationTest`, `AdminMessageIndexCostIntegrationTest`, `MessagePageMethodConventionTest`, `MessageRateLimiterTest`·`MemberTokenBucketLimiterTest`. **변이 실험으로 감도를 확인했다**: 상태 행 잠금 제거·`send`의 격리를 RR로·`REQUIRES_NEW`→`REQUIRED`·물리 DELETE 제거·가드 `REQUIRES_NEW`→`REQUIRED`·쪽지함 컨트롤러에 POST 추가가 각각 해당 시험을 실패시킨다. **동시성 시험은 Hikari 기본 풀(10)이 동시 서비스 트랜잭션 수를 한도와 같게 만들어 잠금이 없어도 우연히 통과하지 않게** 분당 한도를 4로, 스레드를 8로 낮췄다(`docs/troubleshooting.md`).

## 남은 한계

- 발송 한도는 DB 이력이라 다중 인스턴스에서도 정확하지만, 검색·시도 제한은 단일 인스턴스·fail-open이다.
- 마지막 발송 이후 발송하지 않는 회원의 이력은 남는다(제목·본문 없음, 회원당 최대 300+행). 쪽지 자체의 자동 정리는 없다(무기한 보관, D9).
- 회원 수정과의 교착은 409로 수용하고, 쪽지는 저장되지 않으니 재시도하면 된다.
- 쪽지는 벨 알림과 연동하지 않는다.
