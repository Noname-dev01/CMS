# CLAUDE.md — com.cms.admin.inquiry

이 디렉터리(문의함 관리 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.
계획서: `adversarial-review/plan/PLAN-inquiry.md`(적대적 리뷰 6라운드 ship, 2026-10-12). 공개 폼 쪽은 `com.cms.publicweb.inquiry`의 `CLAUDE.md`.

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## 무엇인가

비로그인 방문자가 공개 폼(`/inquiry`)으로 남긴 문의를 관리자가 조회·처리(접수 → 처리 중 → 답변 완료)·삭제하는 문의함(`/admin/inquiry/manage`)이다. **답변 메일은 보내지 않는다** — 관리자가 문의자 이메일로 직접 회신하고 시스템은 상태만 기록한다. 이메일은 `mailto:` 링크도 만들지 않는다(텍스트만).

**여섯 번째 기능 단위 위임(`DELEGABLE`) 기능 `INQUIRY`**(READ·UPDATE·DELETE — 관리자가 문의를 만들지 않으므로 CREATE 없음). 신규 MANAGER의 기본값은 권한 없음이다(이름·이메일이 담긴 화면). 다른 기능 권한과 독립(`InquiryPermissionMatrixIntegrationTest`).

## 방문자 입력은 전부 신뢰하지 않는 평문이다

이름·이메일·제목·본문은 방문자가 쓴 값이다. **서버는 정책 검증을 통과한 원문 그대로 저장**하고(이스케이프·필터링 없음), 표시 계층이 책임진다 — 관리자 화면은 `textContent` + CSS `white-space: pre-wrap`만 쓰고 HTML 삽입 API·`th:utext`·자동 링크화를 하지 않는다(`AdminInquiryTemplateConventionTest`가 금지 표면을 잠근다). 감사 로그에는 방문자 입력을 남기지 않는다(`targetLabel` 없음, `safeErrorMessage` 고정 문구, `InquiryAdminApiIntegrationTest.delete_`가 확인).

## 입력 정책 (`InquiryInputPolicy`)

공개 컨트롤러가 **캡차 검증보다 먼저** 실행한다(DB 없음 — 형식 오류가 외부 호출·토큰 소비를 일으키지 않게). 제목·본문은 쪽지의 `MessageTextPolicy`(서로게이트·제어문자·양방향 제어문자·길이)를 **그대로 재사용**한다(`admin.message` → 이 패키지 정적 호출 한 곳, 정책 클래스를 `common`으로 옮기는 리팩터링은 하지 않았다). 상한: 이름 50 · 이메일 100(trim 후, **소문자화하지 않음** — 관리자가 회신 주소로 그대로 쓴다) · 제목 100 · 본문 2000, 원문 크기 방어 이름 100·이메일 200·제목 200·본문 4000. 이메일 형식은 RFC 엄밀 검증이 아니라 공백·따옴표·꺾쇠·쉼표·세미콜론·여러 `@`를 거르는 실용 검사다. 오류 문구는 고정 한국어이고 입력을 되돌려주지 않는다.

## 접수와 소프트 상한 (`InquiryService.submit`)

- **트랜잭션 하나에 INSERT 한 문장**이다. 미처리(`status <> 'ANSWERED'`) 문의가 **1,000건 이상이면 `InquiryClosedException`**(공개 폼이 503). 스팸이 만드는 행은 항상 미처리이고 답변 완료는 관리자 행위로만 늘어 이 상한이 스팸 주도의 DB 성장을 막는다. **소프트 상한**이다 — 잠금 없는 일관 읽기 COUNT라 동시 접수가 몰리면 조금 넘을 수 있다(가드 행 직렬화는 하지 않았다).
- 방문자 IP·User-Agent는 저장하지 않는다(레이트리밋은 메모리 버킷만 쓴다). `consent_at`(개인정보 동의 시각)은 접수 시각과 같은 값이다.
- **스케줄러가 없어 자동 삭제는 없다.** 삭제는 `DELETE` 권한자의 하드 삭제가 유일한 파기 수단이다(운영 권고는 `docs/deployment.md` "문의 폼").

## 상태 변경·삭제 (행 락)

문의 행을 `findByIdForUpdate`(`PESSIMISTIC_WRITE`)로 잡아 같은 문의의 PATCH와 DELETE를 직렬화한다(`concurrentPatchAndDelete`). 전이는 **제한하지 않는다**(관리자가 실수를 되돌릴 수 있게). 같은 값 재요청은 멱등 200이고 쓰기·`update_date` 갱신이 없다(감사는 SUCCESS로 남는다). 마지막 쓰기 우선(낙관적 버전 없음 — 상태가 세 값뿐이라 충돌 비용이 낮다). GET 상세는 상태를 바꾸지 않는다. 삭제는 행이 사라진 뒤 응답을 만들 수 없어 삭제 전 스냅샷을 만들어 감사 `targetId`를 남긴다.

## API (`/admin/api/inquiries`)

| 메서드·경로 | 권한 | 설명 |
|---|---|---|
| `GET ?status=&page=&size=` | READ | 최신순(id 내림차순), 본문 미포함, `status` 필터(잘못된 값 400), 응답에 `unresolvedCount`(미처리 전체) |
| `GET /{id}` | READ | 본문 포함, 없으면 404 |
| `PATCH /{id}` `{"status":…}` | UPDATE | 상태 변경, 값 누락·미정의 400, 없으면 404, 감사 `INQUIRY_STATUS` |
| `DELETE /{id}` | DELETE | 하드 삭제 204, 없으면 404, 감사 `INQUIRY_DELETE` |

게이트 `/admin/inquiry/manage`·`/admin/api/inquiries`·`/admin/api/inquiries/**`는 `SecurityConfig`의 `DELEGABLE` 루프가 `AdminFeature.INQUIRY`에서 자동 생성한다(관리 쪽 `SecurityConfig` 변경 없음 — 공개 `/inquiry` 규칙만 새로 추가됐다).

## 새 문의 알림 (`INQUIRY_RECEIVED`)

문장은 **상수**("새 문의가 접수되었습니다. 문의함에서 확인해주세요.")이고 문의 내용·방문자 정보를 담지 않는다. 수신자 = ACTIVE ADMIN 전원 + `member_permission`에 `INQUIRY`/`READ` 행이 있는 ACTIVE MANAGER(`MemberRepository.findInquiryNotificationRecipientIds`, **`utf8mb4_nopad_bin` 정확 일치** — 기본 콜레이션이면 `inquiry`·`'INQUIRY '` 같은 변형 행도 일치해 "문의함은 403인데 알림은 옴"이 된다). **이미 미읽음 문의 알림이 있는 회원에게는 새로 만들지 않는다**(벨에 회원당 미읽음 최대 1건 — 스팸이 와도 넘치지 않는다, 경합으로 중복 1건이 생겨도 무해).

**생성 계약은 다른 알림(E1~E4)과 다르다** — `AFTER_COMMIT` 리스너·이벤트를 쓰지 않는다. 리스너의 `REQUIRES_NEW`는 제출 트랜잭션의 커넥션을 쥔 채 두 번째 커넥션을 요구해, 풀 크기만큼 동시 제출이 오면 풀 획득 대기로 응답과 다른 DB 요청이 막힌다(무인증 공개 경로라 동시성을 통제할 수 없다). 그래서 **컨트롤러가 `InquiryService.submit()`(트랜잭션 프록시, 반환 시점에 커밋·반환 완료)이 끝난 뒤 트랜잭션 없는 `InquiryNotifier.notifyReceived()`를 부르고**, 이 클래스가 `NotificationRecorder.recordInquiryReceived()`(`READ_COMMITTED`, REQUIRED — 새 트랜잭션 하나)를 부른다. 실패는 삼키고 클래스명만 ERROR 로그에 남긴다(접수는 이미 커밋 — 알림 최선 노력). `PublicInquirySmallPoolIntegrationTest`(풀 2개·8건 동시)가 고정하고, 구 설계(컨트롤러 `@Transactional` + `REQUIRES_NEW`)로 되돌리면 8건 중 6건이 500이 됨을 변이 실험으로 확인했다.

**락 대기 상한**: 수신자 회원 행을 다른 트랜잭션이 수정 중이면 `notification.member_id` FK 검사가 대기한다(기본 50초). 문의 알림 INSERT 한 문장에만 `SET STATEMENT max_statement_time=3, innodb_lock_wait_timeout=1 FOR INSERT …`를 건다 — **`innodb_lock_wait_timeout`은 락마다 새로 계산돼 합산 상한이 아니라서** 문장 전체 상한이 필요하다(MariaDB 10.11 + 이 드라이버에서 동작함을 시험으로 확인). 검사 지점에서 중단하는 방식이라 엄밀한 벽시계 상한이 아니라 대략의 상한이다. 초과하면 문장이 롤백돼(일부 행도 남지 않음) 예외가 나고 `InquiryNotifier`가 삼킨다. `InquiryNotificationIntegrationTest.lockWaits_areBoundedByStatementTimeout`이 각 대기 약 0.8초(<1초)·누적 4초 조건을 만들어 고정하고, **`max_statement_time`만 제거하면 4007ms가 걸려 이 시험이 실패함**을 변이 실험으로 확인했다.

**열람 시점 권한(D11 확장 없음)**: 권한을 회수당한 MANAGER에게 기존 문의 알림이 남을 수 있다. 문장이 상수라 노출되는 것은 "문의가 왔다"는 사실뿐이고 링크는 403이다 — 알림 필터를 권한 인지형으로 일반화하는 변경은 범위 밖이라 수용했다.

## 스키마 (V47~V48)

`inquiry(id, name 50, email 100, title 100, body TEXT, status varchar(20), consent_at, create_date, update_date)` + 인덱스 `(status, id)`(상태 탭·미처리 COUNT), 모두 NOT NULL, 하드 삭제. V47 = DDL 1문, V48 = 메뉴 시드(`/admin/inquiry/manage` '문의함', 최상위 맨 끝, 멱등). 롤백·복구는 `docs/migration-guide.md` "V47~V48"과 `docs/deployment.md` "문의 폼".

## 시험

`InquiryInputPolicyTest`·`InquiryAdminApiIntegrationTest`(목록·상세·상태·삭제·감사·본문 비변형·PATCH/DELETE 동시성)·`InquiryPermissionMatrixIntegrationTest`(핸들러 4 × 권한 6조합)·`InquiryNotificationIntegrationTest`(수신자·변형 행·락 대기 상한)·`AdminInquiryTemplateConventionTest`·`InquiryMigrationTest`. 공개 쪽은 `com.cms.publicweb.inquiry`의 `CLAUDE.md`.

## 남은 한계

- 보존 기간 자동 삭제 없음(스케줄러 없음), 문의 검색·CSV·답변 메모·첨부 없음.
- 미처리 상한은 소프트(정확한 개수 보장 없음).
- 권한 회수 뒤에도 기존 문의 알림은 남는다(상수 문장, 링크 403).
