# PLAN — 공개 공지 상세 첨부파일 다운로드

> 작성일: 2026-08-03
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 Top 3 (2026-07-29 선정) — ②"
> 선행 완료: ① 공지사항(notice) 관리 CRUD (`6c5ca4c` #16), ② 파일 스토리지 + 첨부파일 (`174e925` #18), ③ 공개 공지 페이지 (`7ab80a5` #21)

## 개정 이력

- v1 (2026-08-03): 최초 작성(plan 모드 정찰·설계 결과 — Explore 에이전트 2개로 `com.cms.publicweb.notice` 전체·첨부 인프라(`NoticeAttachment`·`FileStorage`·`SecurityConfig`) 실측, Plan 에이전트로 설계). 사용자 확정: URL `/notices/{id}/attachments/{attachmentId}`(`/content` 접미사 없음), 404는 `sendError(404)`+`return null`로 `error/404.html` 재사용, `Cache-Control: no-store` 적용. `/plan-review-loop` 리뷰 대상으로 제출.
- v2 (2026-08-03, codex 리뷰 1차 반영 — needs-attention, 7개 지적 중 2개는 사용자 결정, 5개는 즉시 수용):
  - **결정 필요→해결(높음1)**: "락은 경합 창을 없애지 못한다" 서술이 "REPEATABLE READ 스냅샷이 TOCTOU를 완전히 닫는다"는 과장된 주장으로 읽힌다는 지적. 실측 확인: 관리자의 `useYn=false` 커밋이 공개 다운로드 트랜잭션의 스냅샷 확정 **이전**에 완료되면 정상적으로 404가 나지만(REPEATABLE READ가 최신 커밋을 보므로), 스냅샷 확정 **이후**·응답 전송 **이전**에 커밋되면 이번 요청은 여전히 구 상태로 성공한다 — 이는 락의 유무와 무관하게 "언제 한 번은 확인하고 그 이후는 확인하지 않는다"는 check-then-act 구조 자체의 한계다. **사용자 확정(2026-08-03): 약한 보장으로 문구 정정, 락 도입 안 함.** 계약을 "이 요청이 재검증을 실행한 시점에 공개 상태였음을 보장한다 — 요청 처리 도중(파일 I/O·응답 전송) 완료되는 비공개 전환까지 차단하지는 않는다"로 명시(아래 결정 2 본문 수정). 강한 보장(응답 전송 완료까지 락 유지)은 무인증 엔드포인트가 관리자 쓰기를 오래 블로킹하는 DoS 표면을 만들어 원래 락을 배제한 이유와 정면으로 충돌하므로 채택하지 않는다.
  - **결정 필요→해결(높음2)**: 10MB·5개 상한이 "무인증 엔드포인트의 동시 반복 요청" 비용은 줄이지 않으며, `@GetMapping`이 HEAD를 암묵 처리해 HEAD 요청도 전체 파일을 로드한다는 지적. **사용자 확정(2026-08-03): 문구 정직화 + HEAD 실동작 테스트만 추가, 스트리밍·레이트리밋은 이번 범위에 포함하지 않음.** 리스크 표 문구를 "HEAD 요청도 GET과 동일하게 서비스 진입·파일 전체 로딩을 수행한다(Spring `@GetMapping` 기본 동작), 동시 반복 요청에 대한 제한은 없다"로 정직화하고, 스트리밍(`InputStreamResource`)·레이트리밋은 후속 과제로 명시(아래 리스크 표·후속 과제 수정).
  - **수용(중간3)**: `InOrder` mock 테스트만으로는 실제 DB 격리 수준·동시 커밋 결과를 검증하지 못한다는 지적 — 타당하나, 높음1을 "약한 보장"으로 정정한 결과 이 기능이 보장해야 할 것은 "매 요청 시작 시 재조회"뿐이므로 기존 계획의 통합 테스트(useYn 전환 커밋 후 재요청 → 404, Testcontainers 실 DB)로 충분하다. 별도 latch/barrier 동시성 테스트는 추가하지 않는다(과장된 보장을 낮췄으므로 그 보장을 증명할 과한 테스트도 불필요).
  - **수용(중간4)**: `SecurityConfigTest`의 HEAD 200 테스트는 인가만 확인하고 실제 다운로드 핸들러가 GET과 동일하게 동작하는지(전체 파일 로딩 등)는 검증하지 않는다는 지적 — 타당. `PublicNoticeControllerTest`에 HEAD 케이스를 추가해 실제 서비스 호출·본문 로딩이 발생함을 문서화하는 테스트로 반영(아래 테스트 계획 수정).
  - **수용(중간5)**: 업로드의 `sanitizeFilename()`(`NoticeAttachmentService.java:187`)이 CR/LF·제어문자를 제거하지 않아 "헤더 인젝션 불가" 주장을 뒷받침하는 테스트가 없다는 지적 — 타당. `report.txt\r\nX-Evil: injected` 류 페이로드에 대한 회귀 테스트를 테스트 계획에 추가(아래 테스트 계획 수정).
  - **수용(낮음6)**: "`deleted=true` + 첨부 존재 상태는 구조적으로 발생 불가능"이 과장이라는 지적(FK는 부모의 물리 삭제만 제한, DB 구조 자체가 그 공존을 막지는 않음; 운영 SQL·마이그레이션 등으로 발생 가능) — 타당, 설계 변경 없이 문구만 "현재 애플리케이션 서비스 경로에서는 발생하지 않는다(공개 조회가 `deleted=false AND useYn=true`를 항상 재검사하므로 그 상태에서도 fail-closed)"로 정정(결정 2 본문 수정).
  - **수용(낮음7)**: `fileSizeText` 출력 계약(1024/1000 기반·소수점 자리수·반올림·Locale)이 미정의라는 지적 — 타당. 1024 기반, 소수점 1자리, `RoundingMode.HALF_UP`, `Locale.ROOT` 고정으로 명시(결정 3 본문 수정).
  - Security matcher 범위·`findByIdAndNoticeId` IDOR 차단·`StorageFileNotFoundException` 구분·public 전용 DTO·`sendError(404)+null`의 MVC 처리 방향은 codex가 저장소 코드와 직접 대조해 타당함을 확인 — 변경 없음.
- v3 (2026-08-03, codex 리뷰 2차 반영 — needs-attention, 6개 지적 전부 수용, 사용자 결정 불필요):
  - **수용(중간1)**: v2 개정 이력이 "기존 통합 테스트(Testcontainers 실 DB)로 충분하다"고 서술했으나 실제로는 테스트 계획에 그런 자동화 테스트가 없고 Playwright 수동 검증뿐이었다는 지적 — 타당(사실과 다른 근거를 든 것). 아래 테스트 계획에 **신규 `PublicNoticeAttachmentIntegrationTest`(Testcontainers, `extends MariaDbContainerSupport`)**를 실제로 추가해 "notice 생성(`useYn=true`) → 첨부 업로드 → 다운로드 성공 → 별도 트랜잭션에서 `useYn=false` 커밋 → 재호출 시 `empty`"를 실 DB로 검증한다. latch/barrier 동시 커밋 테스트는 여전히 불필요(약한 보장이 동시 커밋의 승패를 보장하지 않으므로) — 이번 추가는 "순차적 전환 후 재조회가 실제로 막히는가"만 실 DB로 증명하는 것이며 v2의 논리(별도 동시성 테스트 불필요)와 모순되지 않는다.
  - **수용(중간2)**: `report.txt\r\nX-Evil: injected`는 `NoticeAttachmentService`의 확장자 검사가 마지막 `.` 이후를 확장자로 취급하므로(`lastIndexOf('.')` 기준, `NoticeAttachmentService.java:203`) 업로드 자체가 거부되어 다운로드 경로를 타지 못하는 비현실적 페이로드라는 지적 — 타당. 페이로드를 `report\r\nX-Evil: injected.txt`(마지막 `.` 뒤가 `txt`로 남아 업로드를 통과)로 교체하고, 성공 계약을 "다운로드는 200이어야 하고 CR/LF는 안전하게 인코딩되어 별도 헤더가 생기지 않는다"로 확정한다(테스트 계획 10번 수정 — "Spring이 예외로 차단해도 통과"라는 느슨한 이중 계약은 폐기: 정상 첨부가 다운로드 시 500이 되는 것은 헤더 인젝션은 아니어도 가용성 결함이라는 지적을 수용).
  - **수용(낮음3)**: "요청 시작 시점"과 "재검증 SELECT 실행 시점"이라는 두 표현이 혼용됐다는 지적(요청 수신부터 SELECT 실행까지 지연이 있을 수 있어 정확한 표현은 후자) — 타당. 결정 2·리스크 표의 "요청 시작 시점" 표현을 전부 "재검증 SELECT를 실행한 시점"으로 통일(아래 본문 수정).
  - **수용(낮음4)**: 테스트 계획 9번의 "전체 파일 로딩을 수행한다는 사실을 문서화"라는 서술이 과장이라는 지적(`@WebMvcTest` + mock Service라 실제 `FileStorage.load()`·10MB 할당은 실행되지 않음, 검증되는 것은 "HEAD가 GET과 같은 핸들러 메서드를 거쳐 Service를 호출한다"는 사실뿐) — 타당. 테스트 설명 문구를 좁혀 정정(아래 테스트 계획 9번 수정).
  - **수용(낮음5)**: "파일은 있는데 행이 없음은 발생하지 않는다"가 과장이라는 지적 — `NoticeAttachmentService`의 `afterCommit` 파일 삭제 실패가 예외를 흡수하고 로그만 남기므로(`NoticeAttachmentService.java:176`) orphan 파일(행 삭제됨 + 실파일 잔존)이 이론상 발생할 수 있다. 다만 다운로드 경로는 항상 행 조회가 먼저이므로 이 orphan 파일에 도달할 방법이 없어 보안 영향은 없다 — 문구를 "발생할 수 있지만, 행 조회가 먼저라 다운로드 경로에서는 접근되지 않는다"로 정정(결정 2 본문 수정).
  - **수용(낮음6)**: `fileSizeText` 경계값 테스트의 `1.5MB`가 `HALF_UP` 반올림 경계가 아니라는 지적 — 타당. `1280 B(=1.25KB→"1.3 KB", HALF_UP 검증)`·`1048575 B(→"1024.0 KB")`·`1048576 B(→"1.0 MB", 단위 전환 경계)`를 테스트 목록에 추가(아래 테스트 계획 10번 수정).
- v4 (2026-08-03, codex 리뷰 3차 반영 — needs-attention, 3개 지적 전부 수용, 사용자 결정 불필요):
  - **수용(중간1)**: `PublicNoticeAttachmentIntegrationTest`의 4개 시나리오를 하나의 순차 fixture(같은 notice 재사용)로 실행하면, 시나리오 2(`useYn=false` 커밋)가 끝난 뒤 3(IDOR)·4(`StorageFileNotFoundException`)도 그 notice를 계속 쓸 경우 이미 비공개라 첫 재검증(`findByIdAndDeletedFalseAndUseYnTrue`)에서 곧장 `empty`가 되어 각 시나리오가 실제로 검증하려는 분기(`findByIdAndNoticeId` IDOR 조건, `fileStorage.load()`의 `StorageFileNotFoundException`)를 타지 않고도 테스트가 통과해버린다는 지적 — 타당. **4개 시나리오를 각각 독립된 테스트 메서드로 분리**하고, 시나리오마다 자신만의 공개 notice(+필요 시 두 번째 notice)를 새로 만들어 다른 시나리오의 상태 변경(특히 `useYn=false` 전환)이 서로에게 새지 않게 한다(아래 테스트 계획 수정). 각 테스트 종료 후 첨부 행 → notice 행 → 실파일 순으로 정리(기존 `NoticeAttachmentTransactionIntegrationTest` 패턴과 동일 — MariaDB 컨테이너가 JVM 단위 싱글턴이라 테스트 간 데이터가 남으면 다음 테스트에 영향을 줄 수 있음).
  - **수용(중간2)**: CR/LF 헤더 인젝션 테스트(`PublicNoticeControllerTest` 10번)가 "이 파일명으로 **업로드** → 다운로드 시 200"이라고 서술하지만, `PublicNoticeControllerTest`는 `@WebMvcTest` + `PublicNoticeService` mock이라 실제 `NoticeAttachmentService.upload()` 검증 경로를 전혀 실행하지 않는다는 지적 — 타당. 테스트를 두 곳으로 분리한다: (a) 업로드 성공 자체는 기존 `NoticeAttachmentServiceTest`(또는 통합 테스트)에 "CR/LF 포함 파일명 업로드 성공" 케이스로 별도 추가(이 계획의 신규 파일이 아니라 기존 admin 테스트 파일에 케이스 1건 추가하는 최소 변경), (b) `PublicNoticeControllerTest`는 원래 계획대로 mock `PublicNoticeAttachmentDownload`(CR/LF 포함 파일명)를 서비스가 반환하도록 스텁해 컨트롤러의 헤더 처리만 검증(200·`X-Evil` 헤더 부재·`Content-Disposition` raw CR/LF 부재) — 이는 애초에 mock 기반이라 "업로드 성공"을 증명할 필요가 없는 컨트롤러 단위 테스트의 정상 범위이므로 문구만 "업로드 후"가 아니라 "mock이 CR/LF 포함 파일명을 반환할 때"로 정정한다(아래 테스트 계획 수정).
  - **수용(낮음3)**: 결정 2 "락은 쓰지 않는다" 근거 4번에 "약한 보장(요청 시작 시점 재확인)"이라는 구 표현이 남아 있었다는 지적 — 타당(v3에서 놓친 잔여 문구). "약한 보장(재검증 SELECT 시점 재확인)"으로 정정(아래 결정 2 본문 수정).
  - **참고(CR/LF 페이로드 재검증)**: codex가 `report\r\nX-Evil: injected.txt`를 실제 `NoticeAttachmentService` 검증 로직(경로 구분자 없음 → 파일명 유지, `lastIndexOf('.')` 뒤 `txt` → 확장자·Content-Type 허용)과 Spring 6.2.19 `ContentDisposition`(CR/LF가 `filename*`에서 `%0D%0A`로 퍼센트 인코딩되어 raw CR/LF가 남지 않음) 양쪽으로 직접 대조해 "이 페이로드는 업로드를 통과하고, 다운로드 응답은 안전하게 인코딩된다"는 v3의 주장이 정확함을 확인 — 페이로드·계약 자체는 추가 수정 불필요.
- **4차 확인 리뷰(2026-08-03) 결과: ship.** 3차 리뷰의 3개 지적(통합 테스트 fixture 분리, CR/LF 검증 책임 분리, 락 근거 표현 통일)이 전부 충분히 반영됐고, 이번 반영 과정에서 새로 생긴 문제나 v1~v4 결정 간 모순이 없음을 codex가 저장소 코드 재대조로 확인 — `plan-review-loop` 4라운드 종료, 승인 단계로 진행. 약한 TOCTOU 보장·`byte[]` 전체 로딩·무인증 반복 요청 제한 부재는 이미 사용자가 명시적으로 수용한 잔여 위험으로, 이 계획을 막는 미해결 결함이 아님을 재확인.

## Context

로드맵 `adversarial-review/project-direction-roadmap.md`의 "실행 로드맵 Top 3 (2026-07-29 선정)" ②번 항목.

`PLAN-public-notice.md`(2026-07-28)는 공개 공지 페이지를 만들면서 **첨부파일 노출을 의도적으로 범위 제외**했다(본문만 공개). `NoticeAttachment`·`FileStorage`·`NoticeAttachmentRepository` 인프라는 이미 완비되어 있고, 이 작업의 목표는 비로그인 사용자가 공지 상세(`/notices/{id}`)에서 그 공지에 달린 첨부파일을 목록으로 보고 다운로드할 수 있게 하는 것이다.

소프트 삭제·비노출로 전환된 공지의 첨부는 여전히 접근 불가능해야 한다 — **다운로드 시점 재검증**(목록 조회 이후 상태가 바뀌는 TOCTOU 방지)이 핵심 요구사항이다.

**사용자 확정 결정 (2026-08-03)**
- 다운로드 URL: `GET /notices/{id}/attachments/{attachmentId}` (admin의 `/content` 접미사 없음 — 공개 측엔 첨부의 다른 표현(메타데이터 JSON 등)이 없어 접미사가 아무것도 구분하지 않음)
- 404 응답: `response.sendError(404)` + `return null` → 기존 `error/404.html` 재사용(브라우저에서 상세 404와 동일 UX)
- 캐시: `Cache-Control: no-store` 적용(중간 프록시·브라우저 캐시가 TOCTOU 재검증을 우회하지 못하게)

## 스키마 · 인가 정책 영향 (승인 필요 항목)

- **스키마 변경: 없음.** 기존 `NoticeAttachment` 테이블·`NoticeAttachmentRepository`·`FileStorage`를 그대로 재사용한다. Flyway 마이그레이션 파일 추가 없음.
- **인가 정책 변경: 없음.** `SecurityConfig`의 기존 규칙
  ```java
  .requestMatchers(HttpMethod.GET,  "/notices", "/notices/**").permitAll()
  .requestMatchers(HttpMethod.HEAD, "/notices", "/notices/**").permitAll()
  .requestMatchers("/notices", "/notices/**").denyAll()
  ```
  이 `/notices/**`는 `/**`가 0개 이상 세그먼트를 매칭하는 Spring Security `PathPatternRequestMatcher` 기본 동작상 `/notices/{id}/attachments/{aid}`까지 **이미 포괄**한다(실측 확인). 즉 이 라우트는 **코드 수정 없이 추가하는 즉시 GET/HEAD 무인증 공개**가 된다 — 이것이 곧 이번 작업의 핵심 리스크이며, 접근 통제는 SecurityConfig가 아니라 전량 Service의 재검증 로직이 짊어진다.
  - `SecurityConfig.java` 자체는 수정하지 않되, "라우트 추가만으로 무인증 공개된다"는 암묵적이고 보안 직결인 동작을 `SecurityConfigTest`에 명시적으로 고정한다(아래 테스트 계획 참조).

## 핵심 설계 결정

### 1. Service는 `PublicNoticeService`에 확장 — 별도 클래스 신설 안 함

**선택지**
- (A) `PublicNoticeService`에 메서드 추가
- (B) 별도 `PublicNoticeAttachmentService` 신설

**결정: (A).** `PLAN-public-notice.md` 결정 1의 "노출+미삭제 불변식을 타입 단위로 격리"는 클래스 수를 늘리는 게 목적이 아니라 **검증해야 할 불변식 진술을 하나로 유지**하는 게 목적이다. 별도 클래스로 쪼개면 (1) 불변식 진술이 둘로 늘고 (2) "공지 + 첨부 목록" 조립 책임이 Controller로 새며 (3) 상세 렌더링에 공개조건 SELECT가 두 번(공지 조회 + 첨부 조회 각각 재검증) 나갈 수 있다. `PublicNoticeControllerTest`의 `MockConfig`에 새 mock 빈을 추가할 필요도 없어 기존 슬라이스 구조가 그대로 유지된다.

`NoticeAttachmentRepository`·`FileStorage`를 추가 주입(생성자 3인자로 변경 — `PublicNoticeServiceTest`의 기존 `setUp()` 및 6개 테스트가 컴파일 영향을 받는다).

**admin `NoticeAttachmentService`는 재사용하지 않는다.** 실측 확인: 그 클래스의 `list()`는 `useYn`을 전혀 검사하지 않고, `download()`는 notice를 조회조차 하지 않는다(첨부가 속한 notice의 공개 여부를 판단할 수 있는 지점이 아예 없음) — 재사용하면 공개 조건 미검증인 채로 다운로드가 뚫린다. 예외 계약도 `ResourceNotFoundException`(전역 advice가 JSON으로 응답)이라 공개 경로에 그대로 쓰면 결정 5와 충돌한다. publicweb이 admin **Repository**에 직접 의존하는 것은 이미 확립된 패턴(`NoticeRepository`)이므로 계층 규칙 위반이 아니다.

### 2. TOCTOU 재검증 — 한 트랜잭션 안에서 "notice 먼저, 첨부 나중", 락 없음

```java
@Transactional(readOnly = true)
public Optional<PublicNoticeAttachmentDownload> downloadPublishedAttachment(Long noticeId, Long attachmentId) {
    // 1) 공개 조건 재검증이 항상 먼저 — 이 SELECT가 트랜잭션 스냅샷을 확정한다
    if (noticeRepository.findByIdAndDeletedFalseAndUseYnTrue(noticeId).isEmpty()) {
        return Optional.empty();
    }
    // 2) findByIdAndNoticeId 복합 조건으로 IDOR 차단(다른 notice의 attachmentId는 empty)
    // 3) fileStorage.load() — StorageFileNotFoundException만 catch → Optional.empty()
    //    그 외 IllegalStateException은 전파(실제 장애는 500 유지)
}
```

**선행 사실(실측)**: `NoticeService.deleteNotice()`(94–96행)가 첨부 잔존 시 `ConflictException`(409)으로 소프트 삭제를 차단한다 → **현재 애플리케이션 서비스 경로에서는 `deleted=true` + 첨부 존재 상태가 발생하지 않는다**(v2 정정 — DB 구조 자체가 이 공존을 막는 것은 아니다. FK는 부모의 물리 삭제만 제한하며, 운영 SQL·데이터 마이그레이션 등 다른 경로로는 이론상 발생할 수 있다. 다만 공개 조회가 `deleted=false AND useYn=true`를 매 요청 재검사하므로 그 상태에서도 fail-closed로 동작한다). 따라서 정상 경로에서 TOCTOU가 실제로 방어해야 할 전이는 `useYn true→false` 하나다.

**순서가 notice 먼저인 이유**: 보안 조건에서 fail-fast하고, 첫 SELECT가 트랜잭션 스냅샷(MariaDB 기본 REPEATABLE READ)을 확정하므로 이후 첨부 SELECT가 같은 스냅샷을 본다 — "notice는 공개 상태로 보이는데 첨부만 다른 시점 상태"인 교차 불일치가 생기지 않는다.

**(v2 정정, v3에서 표현 통일) 이 재검증이 보장하는 것은 "재검증 SELECT를 실행한 시점의 공개 상태"뿐이다 — 완전한 차단이 아니다.** codex 리뷰(높음1)에서 지적된 대로, "락은 경합 창을 없앤다/없애지 못한다"는 이분법 자체가 정확하지 않았다. 실제 계약은 다음 둘 중 하나이고 반드시 하나를 명시적으로 선택해야 한다:
- **약한 보장(채택)**: 이 요청이 재검증 SELECT를 실행한 시점에 공개 상태였음을 보장한다. 관리자의 `useYn=false` 커밋이 그 SELECT **이전**에 끝나면 이 요청은 정상적으로 404를 받는다(REPEATABLE READ가 최신 커밋을 본다). 그 SELECT **이후**·응답 전송 **완료 이전**에 커밋되면, 이번 요청은 스냅샷에 따라 성공할 수 있다 — 이는 check-then-act 구조 자체의 한계이며 락 유무와 무관하다(락을 잡아도 락 해제 이후 응답 전송 전에 관리자가 커밋하면 동일한 창이 남는다).
- **강한 보장(미채택)**: 비공개 전환 커밋 이후 완료되는 다운로드까지 전부 차단한다. 응답 전송이 끝날 때까지 락을 유지해야 하므로, 무인증 엔드포인트가 관리자의 `PESSIMISTIC_WRITE`(소프트 삭제·첨부 업로드/삭제)를 블로킹하는 DoS 표면이 생긴다. **사용자 확정(2026-08-03)으로 미채택.**

**락은 쓰지 않는다(사용자 확정 — 약한 보장 채택, 4가지 근거)**
1. 읽기 전용이라 read-modify-write가 없다 — lost update 위험 자체가 없다.
2. 위에서 정리한 대로 락은 "요청 시작 이후 완료 이전"의 창을 없애지 못한다(강한 보장을 위해서는 응답 전송까지 락을 유지해야 하는데, 그 비용이 더 크다).
3. `PESSIMISTIC_READ`를 잡으면 admin의 `findByIdAndDeletedFalseForUpdate`(PESSIMISTIC_WRITE, 소프트 삭제·첨부 업로드/삭제에 사용)와 직접 경합한다. **무인증 공개 엔드포인트가 관리자 쓰기를 블로킹하는 DoS 표면**이 되므로 오히려 해롭다.
4. 방어 대상이 단일 컬럼(`useYn`) UPDATE뿐이라, 약한 보장(재검증 SELECT 시점 재확인)만으로도 "목록에서 본 뒤 오래 지난 링크로 계속 받는" 흔한 오남용은 충분히 막는다.

**남는 창(수용)**: (a) 위에서 정리한 재검증 이후~응답 완료 사이의 비공개 전환 창(약한 보장의 명시적 한계). (b) DB 스냅샷과 실파일 사이 — 첨부 삭제의 실파일 제거는 `afterCommit`이므로 "행은 보이는데 파일은 없음"이 가능하다. `StorageFileNotFoundException`(=`IllegalStateException`) → `Optional.empty()` → 404로 **fail-closed** 처리한다(admin `download()`와 동일 전략). **(v3 정정)** 반대 방향(파일은 있는데 행이 없음)도 발생할 수 있다 — `NoticeAttachmentService`의 `afterCommit` 파일 삭제 실패는 예외를 흡수하고 로그만 남기므로(`NoticeAttachmentService.java:176`) orphan 파일이 이론상 남을 수 있다. 다만 다운로드 경로는 항상 행 조회가 먼저이므로 이 orphan 파일에는 애초에 도달할 방법이 없어 보안 영향은 없다.

`fileStorage.load()`는 트랜잭션 안에서 호출한다(파일당 10MB 상한, admin `download()`도 동일 — 디스크 I/O 동안 커넥션을 점유하는 트레이드오프는 서비스 메서드를 둘로 쪼개는 복잡도보다 낫다고 판단).

### 3. DTO — 첨부 목록은 `PublicNoticeDetail`의 필드로, 다운로드는 별도 record

**신규 `com.cms.publicweb.notice.dto.PublicNoticeAttachment`** — 형제 DTO(`PublicNoticeSummary`/`PublicNoticeDetail`)와 동일한 `@Getter @Builder` + `static from(NoticeAttachment)` 스타일.

| 필드 | 노출 | 이유 |
|---|---|---|
| `id` | O | 다운로드 URL 조립용 |
| `originalFilename` | O | 화면 표시 + 다운로드 파일명 |
| `fileSize`(bytes) | O | 어차피 Content-Length로 드러나는 값 |
| `fileSizeText` | O | 표시용 파생 문자열("512 B"/"324 KB"/"1.2 MB") |
| `storageKey` | **X** | 서버 내부 경로·UUID — 필드 자체를 두지 않아 실수로 새어나갈 수 없게 |
| `contentType` | X | 응답이 항상 octet-stream 강제라 무의미 |
| `noticeId`, `createDate` | X | URL에 이미 있음 / 화면에 쓰지 않음 |

**파일 크기 표시 형식은 DTO 책임(선택지: DTO vs 템플릿 산술식)** — 단위 변환은 반올림·경계(0B, 1023B, 1KB 미만) 판단이 있는 *규칙*이라 단위 테스트 가능해야 한다. `from()` 안의 private static 헬퍼로 처리한다. Thymeleaf `#numbers` 산술식은 경계 처리를 못하고 테스트도 불가하다.

**(v2 추가) 출력 계약 명시(codex 리뷰 낮음7 수용)** — codex 리뷰에서 1024/1000 기반·소수점 자리수·반올림·Locale이 미정의라는 지적을 받아 다음으로 확정한다:
- **1024 기반**(KiB/MiB 관례를 따르되 표기는 "KB"/"MB"로 단순 표기 — admin 화면에 이미 정착된 관례가 없으므로 이번에 확정).
- 1024 미만은 `"{bytes} B"`(소수점 없음), 그 이상은 **소수점 1자리** 고정(`"1.2 MB"`, `"1.0 MB"`도 `.0` 유지 — 자릿수 흔들림 방지).
- 반올림은 `RoundingMode.HALF_UP`.
- **`Locale.ROOT`로 고정**(서버 기본 Locale에 영향받지 않도록 — `String.format(Locale.ROOT, ...)`).
- 경계값 예시: `1023 B`(그대로), `1024 B → "1.0 KB"`, `1048575 B(1MB-1B) → "1024.0 KB"`(다음 단위로 올림 표시하지 않음 — 1048576 이상만 MB 표기), `1048576 B → "1.0 MB"`.

**신규 `PublicNoticeAttachmentDownload`** — `record(String originalFilename, byte[] content)`. admin `NoticeAttachmentDownload`와 동형이지만 재사용하지 않는다(publicweb DTO 경계 유지 — 기존 결정 4 "authorId 제외"와 동일 논리). `contentType` 필드 부재가 곧 "octet-stream 강제" 의도의 타입 표현.

**`PublicNoticeDetail` 수정** — `List<PublicNoticeAttachment> attachments` 필드 추가, 팩터리를 `from(Notice, List<NoticeAttachment>)` **단일 시그니처로 교체**(호출부가 첨부를 빠뜨릴 수 없게 단일 인자 오버로드를 남기지 않는다). `@Builder.Default`로 빈 리스트 기본값을 보장한다(빌더 경유 생성 시 null 방지 — 이 DTO는 빌더로만 생성되므로 `@NoArgsConstructor` 경로의 Lombok 초기화 예외는 실사용 문제 아님).

### 4. Controller — `id`·`attachmentId` 모두 String 파싱, 404는 `sendError`+`null`

```java
@GetMapping("/{id}/attachments/{attachmentId}")
public ResponseEntity<byte[]> attachment(@PathVariable String id,
                                         @PathVariable String attachmentId,
                                         HttpServletResponse response) throws IOException
```

- `id`·`attachmentId` 모두 **String으로 받아 기존 `parseId()` 재사용** — 기존 관례(`PLAN-public-notice.md` 결정 3-1)를 그대로 확장한다. `Long`으로 바인딩하면 `MethodArgumentTypeMismatchException`이 컨트롤러 진입 전에 발생하고, 전역 `@RestControllerAdvice`인 `GlobalApiExceptionHandler`가 JSON으로 응답해버린다(공개 HTML 페이지에 JSON이 노출되는 결함).
- 실패 3종(비숫자 id·attachmentId / 비공개·삭제 notice / 없는 첨부·타 notice 첨부)을 **모두 동일한 404**로 응답한다(존재 여부 열거 방지 — 상세 페이지와 동일 원칙).
- **사용자 확정: `response.sendError(HttpServletResponse.SC_NOT_FOUND)` + `return null`.** Spring MVC의 `HttpEntityMethodProcessor`는 핸들러 반환값이 `null`이면 `requestHandled=true`로 처리하고 종료하므로 `ResponseEntity` 반환 타입에서도 정상 동작한다 — **이 계약에 의존한다는 사실을 코드 주석으로 명시**한다. `sendError`는 컨테이너 에러 디스패치(`/error` → `CustomErrorController`)를 유발해 상세 페이지(`response.setStatus`+뷰 이름 반환)와는 다른 경로지만, 최종적으로 동일한 `error/404.html`을 렌더링해 브라우저 UX는 동일하다. MockMvc는 기본적으로 에러 디스패치를 수행하지 않으므로 컨트롤러 테스트는 **상태 코드만** 단언한다(뷰 이름 단언 불가 — 별도 통합 테스트 없이는 실제 렌더링 확인 불가, 실기 검증(Playwright)으로 보완).
- **예외를 절대 던지지 않는다.** `ResourceNotFoundException`을 던지면 `PublicWebExceptionAdvice`의 `@ExceptionHandler(Exception.class)` 폴백이 먼저 매칭되어 **404가 아니라 HTML 500**이 된다. 이것이 Service가 예외 대신 `Optional`을 반환해야 하는 결정적 이유이며, `FileStorage.load()`의 `StorageFileNotFoundException`을 Service에서 **반드시 catch**해 `Optional.empty()`로 변환해야 하는 이유이기도 하다(catch하지 않으면 "파일이 이미 지워진 첨부"가 404가 아닌 500이 됨). 그 외 I/O 실패(디스크 장애 등)는 의도적으로 전파시켜 advice의 HTML 500을 타게 한다(admin 서비스의 기존 판단과 동일 — 실제 서버 장애와 "자원 없음"을 구분).
- 성공 응답 헤더: `application/octet-stream` + `ContentDisposition.attachment().filename(name, UTF_8)`(RFC 5987 퍼센트 인코딩 → 파일명 기반 헤더 인젝션 불가, admin과 동일 패턴) + `X-Content-Type-Options: nosniff` + **`Cache-Control: no-store`**(사용자 확정).

### 5. 템플릿 — `detail.html`에 조건부 첨부 섹션

```html
<div class="notice-attachments" th:if="${not #lists.isEmpty(notice.attachments)}">
  <h3 class="notice-attachments-title">첨부파일</h3>
  <ul>
    <li th:each="file : ${notice.attachments}">
      <a th:href="@{/notices/{id}/attachments/{fid}(id=${notice.id}, fid=${file.id})}"
         th:text="${file.originalFilename}"></a>
      <span class="notice-attachment-size" th:text="${file.fileSizeText}"></span>
    </li>
  </ul>
</div>
```

`th:utext` 금지(`PublicNoticeTemplateConventionTest` 회귀 대상). 파일명은 업로드 시 경로 구분자만 정리되므로 `<script>` 같은 문자가 그대로 저장될 수 있어 `th:text` 이스케이프가 유일한 방어선 — 테스트로 고정한다. URL은 문자열 연결이 아닌 `@{...(id=,fid=)}` 경로 변수로 조립(Thymeleaf가 URL 인코딩). `download` 속성은 붙이지 않는다 — `Content-Disposition`이 이미 파일명을 지정하며, 서버가 404를 줄 때 `download` 속성이 있으면 브라우저 동작이 예측 불가해진다.

`static/css/public/notice.css`에 `.notice-attachments*` 스타일 추가(기존 카드 톤과 동일). **템플릿 파일을 신규 생성하지 않으므로** `PublicNoticeTemplateConventionTest`의 `TEMPLATES` 상수 배열은 수정 불필요 — 단, `storageKey` 문자열 부재 검증 1건은 추가한다.

## 작업 단계

의존 방향 안쪽부터. 각 단계 후 `./gradlew compileJava`로 컴파일 확인.

1. **브랜치**: `feat/public-notice-attachment`
2. **DTO**: `PublicNoticeAttachment.java`(신규), `PublicNoticeAttachmentDownload.java`(신규) — `com.cms.publicweb.notice.dto`
3. **DTO 확장**: `PublicNoticeDetail.java` — `attachments` 필드 + 팩터리 `from(Notice, List<NoticeAttachment>)`로 교체
4. **Service**: `PublicNoticeService.java` — 생성자 3인자(`NoticeAttachmentRepository`·`FileStorage` 추가), `findPublishedNotice()` 첨부 조립 확장, `downloadPublishedAttachment()` 신규(결정 2)
5. **Controller**: `PublicNoticeController.java` — `/{id}/attachments/{attachmentId}` 라우트 추가(결정 4)
6. **템플릿·CSS**: `detail.html`(결정 5), `static/css/public/notice.css`
7. **테스트** (아래 별도 섹션)
8. **문서**: CLAUDE.md 현행화(엔드포인트 목록·공개 공지 문단) + 이 계획서 구현·검증 결과 기록

### 신규 파일

```
src/main/java/com/cms/publicweb/notice/dto/PublicNoticeAttachment.java
src/main/java/com/cms/publicweb/notice/dto/PublicNoticeAttachmentDownload.java
src/test/java/com/cms/publicweb/notice/service/PublicNoticeAttachmentIntegrationTest.java  # (v3 신설) Testcontainers TOCTOU·IDOR·StorageFileNotFoundException 실 DB 검증
```

### 수정 파일

```
src/main/java/com/cms/publicweb/notice/service/PublicNoticeService.java     # 생성자 3인자, 메서드 추가·확장
src/main/java/com/cms/publicweb/notice/controller/PublicNoticeController.java  # 라우트 추가
src/main/java/com/cms/publicweb/notice/dto/PublicNoticeDetail.java          # 첨부 필드·팩터리 교체
src/main/resources/templates/public/notice/detail.html                     # 첨부 목록 UI
src/main/resources/static/css/public/notice.css                            # 첨부 스타일
src/test/java/com/cms/publicweb/notice/service/PublicNoticeServiceTest.java
src/test/java/com/cms/publicweb/notice/controller/PublicNoticeControllerTest.java
src/test/java/com/cms/config/SecurityConfigTest.java
src/test/java/com/cms/publicweb/notice/PublicNoticeTemplateConventionTest.java
src/test/java/com/cms/admin/notice/service/NoticeAttachmentServiceTest.java  # (v4 신설) CR/LF 포함 파일명 업로드 성공 케이스 1건 추가
CLAUDE.md
```

**재사용(수정 없음)**: `NoticeAttachmentRepository`(`findByNoticeIdOrderByIdAsc`·`findByIdAndNoticeId` 기존재), `NoticeRepository`(`findByIdAndDeletedFalseAndUseYnTrue`), `common/storage/FileStorage`, `SecurityConfig`, `templates/error/404.html`

## 테스트 계획

**`PublicNoticeServiceTest`** (단위, mock repository — 주 검증 지점) ⚠️ 생성자 인자가 3개로 늘어 기존 `setUp()`·6개 테스트가 컴파일 영향을 받는다.
1. 첨부가 `id` 오름차순으로 DTO 조립
2. 첨부 0건이면 `attachments`가 빈 리스트(null 아님)
3. 비공개 공지면 첨부 Repository를 **호출하지 않는다**(`verifyNoInteractions`)
4. `downloadPublishedAttachment` 성공 — 파일명·바이트 그대로 반환
5. **TOCTOU**: notice가 비공개/삭제면 `empty` + `noticeAttachmentRepository`·`fileStorage` **미호출**(`verifyNoInteractions`)
6. **IDOR**: `findByIdAndNoticeId`가 empty면 `empty` + `fileStorage` 미호출
7. **호출 순서**: `InOrder`로 notice 재검증 → 첨부 조회 → 파일 로드 순서 고정(재검증이 뒤로 밀리는 회귀 차단)
8. `StorageFileNotFoundException` → `Optional.empty()`(404 매핑)
9. 그 외 `IllegalStateException` → 그대로 전파(`assertThrows`, 500 유지)
10. **(v3 수정) `fileSizeText` 경계값** 단위 테스트: `0B`·`1023B`·`1024B`(→"1.0 KB") + `1280B`(=1.25KB→"1.3 KB", **`HALF_UP` 반올림 경계 검증**) + `1048575B`(→"1024.0 KB") + `1048576B`(→"1.0 MB", **단위 전환 경계 검증**) — 기존 `1.5MB`는 반올림 경계가 아니라는 codex 지적(2차, 낮음6)을 수용해 위 경계값들로 교체

**`PublicNoticeControllerTest`** (`@WebMvcTest`, `MockConfig` 변경 없음)
1. 상세에 첨부 링크 `/notices/1/attachments/7`·파일명 렌더
2. 첨부 0건이면 "첨부파일" 섹션 문자열 부재
3. **파일명 XSS**: `<script>alert(1)</script>.txt` → 원문 미포함, `&lt;script&gt;` 포함
4. **storageKey 미노출**: 상세 HTML에 UUID/경로 문자열 부재
5. 다운로드 200 — `Content-Type: application/octet-stream`, `Content-Disposition`에 인코딩된 파일명, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, 바디 바이트 일치
6. Service가 `Optional.empty()` → **404**(비공개 notice·없는 첨부·타 notice 첨부 공통)
7. `/notices/abc/attachments/1`, `/notices/1/attachments/abc` → **404 + 서비스 미호출**(`verifyNoInteractions`) + JSON 아님
8. 다운로드 Service가 `RuntimeException` 던지면 → **HTML 500 + `public/notice/error` 뷰**(`PublicWebExceptionAdvice`가 `ResponseEntity<byte[]>` 핸들러에도 적용됨을 고정 — 결정 4의 핵심 회귀 방지선)
9. **(v2 추가, codex 지적4 수용, v3에서 표현 정정) HEAD 실동작**: `mockMvc.perform(head("/notices/1/attachments/7"))` → 200 + 서비스가 실제로 호출됨(`verify(publicNoticeService).downloadPublishedAttachment(...)`) — 이 테스트가 증명하는 것은 **"HEAD가 GET과 동일한 핸들러 메서드를 거쳐 Service를 호출한다"는 사실뿐**이다(`@WebMvcTest` + mock Service이므로 실제 `FileStorage.load()`·10MB `byte[]` 할당까지 검증하지는 않는다 — v3, codex 2차 지적4 수용. 서비스 메서드가 HEAD/GET을 구분하지 않으므로 실제 전체 로딩도 동일하게 발생한다는 것은 설계상 추론이며, 이 테스트가 직접 증명하는 범위는 아니다)
10. **(v2 추가, codex 1차 지적5 수용, v3·v4에서 페이로드·계약·배치 정정) 파일명 헤더 인젝션 회귀**: **(v3)** 원본 페이로드 `report.txt\r\nX-Evil: injected`는 `NoticeAttachmentService`의 확장자 검사가 마지막 `.` 이후를 확장자로 취급하므로(`lastIndexOf('.')` 기준, `NoticeAttachmentService.java:203`) 업로드 자체가 거부되어 다운로드 경로를 타지 못하는 비현실적 페이로드였다(codex 2차 지적2 수용) — **`report\r\nX-Evil: injected.txt`**(마지막 `.` 뒤가 `txt`로 남아 업로드 확장자 검사를 통과. codex 3차 리뷰가 `NoticeAttachmentService`의 확장자 판정·Content-Type 허용 목록·Spring 6.2.19 `ContentDisposition`의 실제 퍼센트 인코딩 동작을 직접 대조해 이 페이로드가 업로드를 통과하고 다운로드 응답이 안전하게 인코딩됨을 재확인)로 교체. **(v4 정정)** 이 테스트는 `PublicNoticeControllerTest`(`@WebMvcTest` + `PublicNoticeService` mock)이므로 **실제 업로드 경로(`NoticeAttachmentService.upload()`)를 실행하지 않는다** — "업로드 후 다운로드"라는 v3의 서술이 부정확했다(codex 3차 지적2 수용). 따라서 이 케이스는 **mock이 CR/LF 포함 파일명을 가진 `PublicNoticeAttachmentDownload`를 반환하도록 스텁**하고, 그 응답이 **정확히 200**이며 `X-Evil` 헤더가 생기지 않고 `Content-Disposition`에 raw CR/LF가 노출되지 않는지(퍼센트 인코딩됨)만 검증한다 — 컨트롤러의 헤더 처리 로직만 대상으로 하는 mock 기반 단위 테스트의 정상 범위이며, "Spring이 예외로 차단해도 통과"라는 이중 계약은 여전히 폐기한다(v3). **업로드 자체가 이 CR/LF 파일명을 실제로 받아들이는지는 별도로 검증**한다 — 아래 신규 항목(admin `NoticeAttachmentServiceTest`) 참조.

**(v4 신설, codex 3차 지적2 수용) `NoticeAttachmentServiceTest`(기존 admin 테스트 파일, 신규 파일 아님) 케이스 1건 추가**: `report\r\nX-Evil: injected.txt` 파일명으로 업로드 요청 → 성공(확장자·Content-Type 검사를 통과해 저장됨)을 검증. 이 케이스가 `PublicNoticeControllerTest` 10번(mock 기반, 다운로드 응답 헤더만 검증)과 짝을 이뤄 "업로드는 이 파일명을 실제로 받아들인다" + "다운로드 응답은 안전하게 인코딩된다"는 계약 전체를 커버한다.

**`SecurityConfigTest`** — 스텁 컨트롤러(`PublicNoticeStubController`)에 `GET /notices/{id}/attachments/{aid}` 매핑 추가 후 (a) 비인증 GET 200 (b) 비인증 HEAD 200 (c) CSRF 포함 비인증 POST → `/admin/login` 302(denyAll). "라우트 추가만으로 무인증 공개된다"는 사실을 명시적으로 고정한다(위 인가 정책 영향 참조).

**`PublicNoticeTemplateConventionTest`** — `detail.html`에 `storageKey` 문자열 부재 검증 1건 추가.

**(v3 신설, codex 2차 지적1 수용, v4에서 독립 테스트로 분리) 통합(Testcontainers, `extends MariaDbContainerSupport`)** — 신규 `PublicNoticeAttachmentIntegrationTest`. v2 개정 이력이 "기존 Testcontainers 통합 테스트로 충분하다"고 서술했으나 실제로는 그런 자동화 테스트가 계획에 없었다는 codex 지적을 수용해 실제로 추가한다. **(v4 정정)** 4개 시나리오를 하나의 순차 fixture(같은 notice 재사용)로 두면 안 된다는 codex 지적을 수용 — `useYn=false` 전환 시나리오가 끝난 뒤 그 notice를 IDOR·파일삭제 시나리오가 계속 쓰면 이미 비공개라 첫 재검증에서 곧장 `empty`가 되어, 그 시나리오가 실제로 검증하려는 분기(`findByIdAndNoticeId`, `StorageFileNotFoundException`)를 타지 않고도 테스트가 통과해버린다. **4개를 각각 독립된 테스트 메서드로 분리**하고 시나리오마다 자신만의 공개 notice(+필요 시 두 번째 notice)를 새로 생성한다:
1. **다운로드 성공**: notice 생성(`useYn=true`, `deleted=false`) → 첨부 업로드 → `downloadPublishedAttachment` 성공(파일명·바이트 일치) 확인
2. **TOCTOU**: 별도의 notice 생성(`useYn=true`) → 첨부 업로드 → `useYn`을 **별도 트랜잭션에서 `false`로 커밋** → 동일 첨부로 재호출 시 `Optional.empty()`(TOCTOU 약한 보장 — "재검증 SELECT 시점에 공개였는지"를 실 DB로 증명. 커밋 완료 후 재요청만 검증하며, 그 사이의 좁은 경합 창 자체는 재현하지 않는다 — latch/barrier 동시성 테스트는 여전히 불필요, 결정 2 참조). 이 notice는 이 테스트에서만 비공개로 전환되며 다른 시나리오와 공유하지 않는다
3. **IDOR**: 서로 다른 공개 notice A·B를 각각 생성(둘 다 `useYn=true` 유지) → notice A의 id + notice B 소유 attachmentId로 호출 → `empty`(`findByIdAndNoticeId` 복합 조건 실 DB 검증)
4. **StorageFileNotFoundException**: 별도의 공개 notice 생성 → 첨부 업로드(행+실파일 존재 확인) → `FileStorage`가 가리키는 실파일만 직접 삭제(행은 유지) → 호출 → `empty`(fail-closed 경로 실 DB 검증)

각 테스트 종료 후(`@AfterEach` 또는 각 테스트 말미) 첨부 행 → notice 행 → 실파일 순으로 정리한다 — 기존 `NoticeAttachmentTransactionIntegrationTest`와 동일한 패턴이며, `MariaDbContainerSupport`가 JVM 단위 싱글턴 컨테이너라 정리하지 않으면 다음 테스트 실행에 데이터가 남아 영향을 줄 수 있다.

**회귀**: `./gradlew test` 전체 통과.

## 검증 (실기)

1. `./gradlew test` 전체 통과(Docker 필요 — Testcontainers)
2. `./gradlew bootRun`(dev) 기동 — Flyway `validate` 통과(스키마 무변경 확인)
3. **Playwright**
   - ADMIN 로그인 → 공지에 첨부 업로드 → 비로그인 브라우저 컨텍스트로 `/notices/{id}` 첨부 목록 노출·다운로드 성공(골든 패스)
   - **관리자에서 해당 공지 `useYn=false` 전환 → 같은 다운로드 URL 재요청 → 404 페이지** (가장 중요한 수동 검증, TOCTOU 재검증 실증)
   - 다른 공지의 attachmentId로 접근 → 404 / 비숫자 id·attachmentId → 404(JSON 아닌 HTML)
   - 관리 화면(공지 CRUD·첨부 CRUD) 회귀 없음 스크린샷
4. 스크린샷 보관. Playwright를 쓸 수 없는 상황이면 그 사실을 명시하고 완료를 주장하지 않는다.

## 리스크

| 리스크 | 대응 |
|---|---|
| `/notices/**` 광역 `permitAll`이 이 라우트도 자동으로 무인증 공개시킴 | `SecurityConfigTest`로 명시 고정(테스트 계획 참조) + `PublicNoticeController` 클래스 주석에 명시 |
| **(v2 정정, v3 표현 통일)** TOCTOU: 목록 조회 후 `useYn`이 꺼져도 이전에 받은 링크로 계속 다운로드됨 | 결정 2 — 다운로드 요청마다 한 트랜잭션 안에서 공개 조건을 재검증하되, **이 재검증은 "재검증 SELECT를 실행한 시점에 공개였음"만 보장한다.** 그 SELECT 이후·응답 전송 완료 이전에 관리자가 `useYn=false`를 커밋하면 이번 요청은 성공할 수 있다(약한 보장, 사용자 확정 — 강한 보장은 무인증 엔드포인트가 관리자 쓰기를 블로킹하는 DoS 표면을 만들어 채택하지 않음). **(v3)** `PublicNoticeAttachmentIntegrationTest`(Testcontainers)가 "커밋 완료 후 재요청 → 404"를 실 DB로 증명하며, 그 사이의 좁은 경합 창 자체는 테스트로 재현하지 않는다(과장된 보장을 요구하지 않으므로) |
| IDOR: 다른 notice의 attachmentId로 접근 | `findByIdAndNoticeId` 복합 조건(기존 Repository 메서드 재사용) |
| DB 행은 있는데 실파일이 이미 삭제됨(첨부 삭제가 `afterCommit`에 파일 제거) | `StorageFileNotFoundException` → `Optional.empty()` → 404 fail-closed(결정 2) |
| 다운로드 핸들러 예외가 `PublicWebExceptionAdvice`(HTML 500 뷰 반환 advice)와 상호작용해 404가 500이 될 위험 | 결정 4 — Service는 예외 대신 `Optional` 반환, `StorageFileNotFoundException`을 Service에서 반드시 catch. 컨트롤러 테스트 8번으로 advice 적용 자체는 고정하되 정상 404 경로는 예외로 표현되지 않음을 보증 |
| 캐시된 첨부가 `useYn=false` 전환 후에도 계속 제공됨 | `Cache-Control: no-store`(사용자 확정) |
| **(v2 정정, v3 표현 정정)** 무인증 경로에서 `byte[]` 전체 로딩으로 인한 자원 고갈 | 파일당 10MB·공지당 5개 상한은 **한 건당** 비용만 제한하며, 동시·반복 요청 자체를 막지는 않는다(admin과 달리 인증 없이 누구나 반복 요청 가능 — 단순 비교는 부적절하다는 codex 지적 수용). **`@GetMapping`은 HEAD도 GET과 동일한 핸들러 메서드를 거쳐 Service를 호출한다**(Spring 기본 동작 — 테스트 계획 9번이 "핸들러 호출까지"는 실증하되, mock Service 기반 슬라이스 테스트라 실제 파일 전체 로딩 자체는 통합 테스트 범위 밖이다, v3 표현 정정). 사용자 확정(2026-08-03)으로 이번 범위는 문구 정직화까지만 하고 스트리밍(`InputStreamResource`)·애플리케이션 레벨 레이트리밋은 도입하지 않는다 — 소규모 포트폴리오 운영 규모를 전제한 명시적 위험 수용이며(codex 2차 리뷰: "소규모 운영"은 의도적 공격을 줄이지 않으므로 실제 공개 트래픽·적대적 접근이 예상되면 재검토 필수), 실제 공개 트래픽이 발생하면 재검토 대상(로드맵 후속 과제로 기록) |
| `storageKey`(서버 내부 경로) 노출 | `PublicNoticeAttachment` DTO에 필드 자체를 두지 않음 + 컨트롤러 테스트 4번·템플릿 컨벤션 테스트로 이중 고정 |
| **(v2 추가, v3 계약 확정)** 파일명 CR/LF 등 제어문자로 인한 응답 헤더 인젝션 | 업로드 측 `sanitizeFilename()`은 경로 구분자·길이만 처리하고 CR/LF를 제거하지 않는다(codex 지적, `NoticeAttachmentService.java:187`). 다운로드 측 `ContentDisposition.filename(name, UTF_8)`이 안전하게 인코딩해 **정상적으로 200 응답하고 별도 헤더가 생기지 않음**을 테스트 계획 10번(현실적인 페이로드로 v3 정정됨)으로 실제 검증해 고정 |

## 승인 후 이어갈 워크플로우

이 계획은 8단계 워크플로우의 1(정찰)·2(설계)에 해당한다. 승인 시:
3. `plan-review-loop` 스킬로 이 문서에 대해 적대적 리뷰 라운드 반복(ship 판정까지)
4. 리뷰 반영 결과를 다시 보고 → 승인
5~8. 구현 → 테스트 → Playwright 실기 검증 → CLAUDE.md·이 계획서 기록

커밋/PR은 사용자 확인 후 `/code-review-loop` → `/commitPR`로 처리한다.

## 구현·검증 결과 (2026-08-03)

### 핵심 확정 사항

계획서 v4(ship) 그대로 구현했다. 구현 중 계획과 달라진 판단은 없다 — `fileSizeText` 경계값·CR/LF 테스트 배치·통합 테스트 4개 독립 시나리오 분리 등 v4까지 반영된 설계를 그대로 코드화했다.

### 구현 파일

**신규**
- `src/main/java/com/cms/publicweb/notice/dto/PublicNoticeAttachment.java` — 첨부 메타 DTO(`storageKey`·`contentType`·`noticeId` 필드 없음), `fileSizeText`(1024 기반·소수점 1자리·`HALF_UP`·`Locale.ROOT`)
- `src/main/java/com/cms/publicweb/notice/dto/PublicNoticeAttachmentDownload.java` — 다운로드 record(`contentType` 없음)
- `src/test/java/com/cms/publicweb/notice/service/PublicNoticeAttachmentIntegrationTest.java` — Testcontainers, 시나리오 4개(성공/TOCTOU/IDOR/StorageFileNotFoundException) 각각 독립 테스트 메서드

**수정**
- `src/main/java/com/cms/publicweb/notice/service/PublicNoticeService.java` — 생성자 3인자(`NoticeAttachmentRepository`·`FileStorage` 추가), `downloadPublishedAttachment()` 신규, `findPublishedNotice()`가 첨부 목록까지 조립
- `src/main/java/com/cms/publicweb/notice/controller/PublicNoticeController.java` — `/{id}/attachments/{attachmentId}` 라우트, `sendError(404)`+`return null`, `Cache-Control: no-store`
- `src/main/java/com/cms/publicweb/notice/dto/PublicNoticeDetail.java` — `attachments` 필드(`@Builder.Default` 빈 리스트), 팩터리를 `from(Notice, List<NoticeAttachment>)` 단일 시그니처로 교체
- `src/main/resources/templates/public/notice/detail.html`, `src/main/resources/static/css/public/notice.css` — 첨부 목록 UI
- `src/test/java/com/cms/publicweb/notice/service/PublicNoticeServiceTest.java` — 생성자 3인자 전환 + TOCTOU·IDOR·호출순서·`StorageFileNotFoundException`·`fileSizeText` 경계값 테스트 추가
- `src/test/java/com/cms/publicweb/notice/controller/PublicNoticeControllerTest.java` — 첨부 렌더링·다운로드·HEAD·CR/LF 헤더 인젝션 테스트 추가
- `src/test/java/com/cms/config/SecurityConfigTest.java` — `/notices/{id}/attachments/{attachmentId}` GET/HEAD/POST 인가 회귀 3건 + `PublicNoticeStubController` 매핑 추가
- `src/test/java/com/cms/publicweb/notice/PublicNoticeTemplateConventionTest.java` — `detail.html`에 `storageKey` 문자열 부재 검증 추가
- `src/test/java/com/cms/admin/notice/service/NoticeAttachmentServiceTest.java` — CR/LF 포함 파일명 업로드 성공 케이스 1건 추가(공개 다운로드 헤더 인젝션 테스트의 짝)
- `CLAUDE.md` — 패키지 구조·핵심 도메인 모델(Notice)·보안 표·엔드포인트 목록
- 스키마 변경 없음(Flyway 최대 버전 V10 그대로, `bootRun` 기동 시 Flyway `Schema cms is up to date`로 확인)

### 검증 결과

- `./gradlew test` 전체 통과(56개 테스트 클래스, 실패·에러 0건 — Docker 기동 후 Testcontainers 기반 `PublicNoticeAttachmentIntegrationTest` 포함)
- `./gradlew bootRun`(dev) 기동 성공, Flyway 스키마 무변경 재확인
- **Playwright 실기 검증** (ADMIN 로그인 → 공지 2건 생성(하나는 첨부 업로드) → 쿠키 삭제로 비로그인 전환):
  - 골든 패스: `/notices/{id}`에서 첨부 목록(`report.txt`, `32 B`) 렌더링 → `GET /notices/{id}/attachments/{attachmentId}` 실제 요청으로 200 + `application/octet-stream` + `Content-Disposition`(파일명 포함) + `X-Content-Type-Options: nosniff` + `Cache-Control: no-store` + 바이트 내용 일치 확인
  - **TOCTOU 재검증**: 관리자가 해당 공지를 비노출로 전환·저장 → 같은 다운로드 URL 재요청 시 첨부 다운로드·상세 페이지 모두 404(HTML, JSON 아님) 확인 — 목록에서도 즉시 사라짐 확인
  - **IDOR**: 서로 다른 두 공지(A·B) 생성 후 A의 id + B 소유 attachmentId 조합 → 404 확인
  - 비숫자 id·attachmentId, 존재하지 않는 notice id, 존재하지 않는 attachmentId → 전부 404(HTML) 확인
  - 404 페이지 렌더링 스크린샷(`public-notice-attachment-404.png`), 비노출 전환 후 목록 스크린샷(`public-notice-list-after-hide.png`), 첨부 없는 공지 상세 스크린샷(`public-notice-detail-no-attachments.png`) 확보
  - 관리 화면 회귀 없음: 대시보드·회원 관리·공지사항 관리 정상 렌더링 확인(스크린샷 3장: `admin-dashboard-regression.png`, `admin-member-manage-regression.png`, `admin-notice-manage-regression.png`)
  - 검증에 사용한 테스트 공지 2건·첨부 1건은 검증 후 관리 화면에서 삭제해 dev DB를 원복(공지 0건 확인)
- Playwright MCP 도구는 정상 동작해 실기 검증을 전부 수행함(제약 없음)

### 이슈

- 없음 — 계획서 v4 대비 구현·테스트·실기 검증 과정에서 새로 발견된 결함 없음.

### 후속

- 리스크 표에 기록된 잔여 위험(약한 TOCTOU 보장의 명시적 한계, 무인증 경로 자원 고갈 명시적 수용)은 소규모 포트폴리오 운영 규모를 전제로 이번 범위에서 수용 — 실제 공개 트래픽·적대적 접근이 예상되면 스트리밍(`InputStreamResource`) 전환·레이트리밋 도입을 재검토(로드맵 후속 과제로 기록).
- 로드맵 "실행 로드맵 Top 3 (2026-07-29 선정)" ②번 완료 → 다음은 ③번(프로필 이미지 Base64-in-DB → FileStorage 이관) 후보로 재평가 가능.
