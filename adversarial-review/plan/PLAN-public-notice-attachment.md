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
- **v5 (2026-09-29, 후속 작업 — 스트리밍 전환 착수)**: 문서 하단 "후속 작업 — 스트리밍 전환" 섹션 신규 추가. v1~v4가 "명시적 수용"으로 남겼던 `byte[]` 전량 로딩 위험(리스크 표 "자원 고갈" 행)을 `/suggestRoadmap`(2026-09-29) 선택으로 해소하는 작업이며, 결정 2의 "`fileStorage.load()`는 트랜잭션 안에서 호출한다" 문단과 결정 3의 `PublicNoticeAttachmentDownload(byte[])` 정의를 이 섹션이 대체한다(원문은 이력 보존을 위해 그대로 둔다). `/plan-review-loop` 리뷰 대상.
- **v6 (2026-09-29, codex 리뷰 1차 반영 — needs-attention, 5개 지적 전부 수용, 사용자 결정 불필요)**:
  - **수용(높음1)**: S5의 "예외를 삼켜도 짧은 Content-Length 때문에 컨테이너가 연결을 닫는다"는 가정이 검증되지 않았다(예외 흡수 시 컨테이너에는 정상 완료로 보임) — 타당(근거 없이 단정한 것). 커밋 후 실패는 예외를 컨테이너까지 전파하고 `PublicWebExceptionAdvice`가 `isCommitted()`이면 뷰 렌더링 없이 재던지도록 변경, "advice 무수정" 제약 철회, 실제 Tomcat 통합 테스트로 검증(S5·테스트 3번 개정).
  - **수용(중간2)**: `@Transactional` 메서드가 열린 스트림을 반환한 뒤 프록시 commit이 실패하면 스트림을 닫을 수 없다 — 타당. 서비스를 `findPublishedAttachment()`(트랜잭션·메타데이터만)와 `openAttachment()`(트랜잭션 없음)로 분리해 구조적으로 제거(S3 개정, `TransactionTemplate` 대안은 같은 효과에 더 복잡해 기각). `open()`의 `size()` 실패 시 채널 close 추가(S1·테스트 1번).
  - **수용(중간3)**: 서비스 빈 직접 호출 통합 테스트는 OSIV 인터셉터를 통과하지 않아 "웹 요청 진행 중 커넥션 반환"을 증명하지 못한다 — 타당. `RANDOM_PORT` 실제 웹 요청 + 전송 중 latch로 멈춘 시점에 Hikari `activeConnections` 단언으로 교체하고 구현 첫 단계 스파이크로 배치(테스트 6번·작업 단계 0번·S3 OSIV 주의).
  - **수용(중간4)**: `loadUnder()`는 부모 디렉터리만 `toRealPath`로 검증하므로 최종 파일 심볼릭 링크는 막지 못한다 — 코드 확인 결과 타당. `open()`은 `NOFOLLOW_LINKS`로 최종 링크를 거부하고 문서 표현을 "부모 경로 검증"으로 한정, 기존 `load()`는 범위 밖이라 후속으로 기록(S1·정찰 7번·테스트 1번·리스크 표).
  - **수용(낮음5)**: 레이트리밋은 요청 진입 빈도만 제한하며 동시 전송 수·점유 시간은 제한하지 않는다 — 타당(표현이 과장). 비목표·리스크 표 문구 정정. 동시성 제한 추가는 이번 범위에 넣지 않음.
- **v7 (2026-09-29, codex 리뷰 2차 반영 — needs-attention, 1개 지적 수용, 사용자 결정 불필요)**:
  - **수용(중간1)**: S5의 "커밋 전이면 HTML 500" 계약은 응답이 이미 오염된(`Content-Length` 설정·`getOutputStream()` 획득·버퍼에 일부 기록) 미커밋 상태에서는 성립하지 않는다 — 타당(내가 "미커밋 = 첫 바이트 전"이라 단정함; Spring 예외 처리 경로는 `Content-Length`를 남기고, `getOutputStream()` 이후 `getWriter()` 렌더링은 `reset()`이 필요). 응답 상태를 3구간으로 구분하고(무손대 구간은 첫 청크를 먼저 읽어 기존 경로 유지 / 오염된 미커밋 구간은 컨트롤러가 `reset()` 후 재던짐 / 커밋 후 구간은 advice 재던짐) 테스트 3번에 두 실패 케이스(출력 스트림 확보 후 첫 읽기 실패, 버퍼 미만 기록 후 flush 없이 읽기 실패)를 추가한다. `reset()` 후 보안 헤더 복원 여부는 실제 Tomcat 테스트로 확인한다.
- **3차 확인 리뷰(2026-09-29) 결과: ship.** v7 반영으로 새로 생긴 결함이나 결정 간 모순 없음(codex가 저장소 코드 재대조). `reset()` 후 보안 헤더 복원·커밋 후 연결 종료·OSIV 상태의 커넥션 반환은 **아직 실증되지 않은 검증 대상**으로 계획에 명시돼 있으며(테스트 3·6번, 작업 단계 0번 스파이크), 구현 완료 판정에는 이 실증이 필요하다. `plan-review-loop` 3라운드 종료, 승인 단계로 진행.
- **v8 (2026-09-29, 구현 중 스파이크 결과 반영 — 사용자 결정)**: 승인 후 작업 단계 0(스파이크)을 실행한 결과 **S3의 "전송 중 DB 커넥션 비점유" 전제가 기본 설정에서는 성립하지 않았다.** `OsivConnectionSpikeTest`(실제 웹 요청·`RANDOM_PORT`, 서비스 `@Transactional` 종료 후 컨트롤러가 latch로 대기)에서 OSIV 기본값(true)일 때 Hikari `activeConnections=1`(idle=9, total=10), 환경변수 `SPRING_JPA_OPEN_IN_VIEW=false` 대조 실험에서는 `activeConnections=0`이었다 — 즉 OSIV가 요청 끝까지 JDBC 연결을 붙잡는다(v6에서 "코드만으로 확정 못 함"으로 남겼던 질문의 실측 답). **부수 사실**: 현행 `byte[]` 방식도 응답 본문을 클라이언트에 쓰는 동안 같은 연결을 잡고 있으므로 느린 클라이언트의 풀 점유는 이미 존재하던 문제다(이번 전환이 만든 회귀가 아님). 계획서 S3의 사전 약속대로 구현을 멈추고 사용자에게 보고했으며, **사용자 결정(2026-09-29): 전역 `spring.jpa.open-in-view=false` 채택.** 근거: 대조 실험으로 효과 실증, JPA 연관관계 매핑(`@OneToMany`·`@ManyToOne` 등)이 코드에 없어(주석 1건뿐) 지연 로딩 의존이 있을 가능성이 낮으나 **전역 설정 변경이므로 전체 테스트·실기 검증으로 회귀를 확인한다**(작업 단계 0.5 신규). 테스트 6번은 스파이크 테스트를 실제 다운로드 경로로 확장한 회귀 가드로 확정한다(누가 OSIV를 다시 켜면 실패). `application.yml` 공통 설정 1줄 추가가 이번 범위에 새로 포함된다.
- **v9 (2026-09-29, 구현 결과 반영)**: 하단 "구현·검증 결과 — 스트리밍 전환" 참조. 구현 중 달라진 결정 — 복사 버퍼 8KB→4KB(S5의 "8KB"는 4KB로 읽는다), 스파이크 테스트를 실서버 통합 테스트에 흡수, advice 재던짐은 Tomcat 결과를 바꾸지 않으나 컨트롤러의 예외 삼킴은 연결을 끊지 못함을 변이 실험으로 확인.

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

## 후속 작업 — 스트리밍 전환 (2026-09-29)

> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "후속 과제 — ② 공개 첨부 다운로드 완료 시 기록"의 "무인증 다운로드 경로의 자원 고갈 위험(명시적 수용)" — `/suggestRoadmap`(2026-09-29)으로 선택. 스키마 변경 없음·인가 정책(`SecurityConfig`) 변경 없음·신규 의존성 없음.

### 목표와 비목표

- **목표**: `GET /notices/{id}/attachments/{attachmentId}`가 요청 1건당 파일 전체(최대 10MB)를 힙에 올리지 않고, 고정 크기 버퍼로 디스크→응답으로 흘려보낸다. HEAD는 본문 바이트를 전혀 읽지 않는다. 전송 중에는 DB 커넥션을 점유하지 않는다.
- **비목표**: 동시 연결 수·디스크 IO·Tomcat 스레드 점유 축소(스트리밍으로 줄지 않는다 — 기존 `cms.rate-limit`은 IP별 **요청 진입 빈도**만 완화할 뿐 진행 중 전송 수·점유 시간의 상한은 보장하지 않으며, 이번 범위에 동시성 제한을 추가하지도 않는다), Range/이어받기 지원(현재도 미지원, 그대로 유지), admin 다운로드·프로필 이미지 경로 변경(`load(byte[])` 유지 — 무관한 변경을 섞지 않는다).

### 정찰 사실 (코드·실험으로 확인)

1. 현재 경로: `PublicNoticeService.downloadPublishedAttachment()`(`@Transactional(readOnly=true)`) → `FileStorage.load()` → `LocalDiskFileStorage.loadUnder()`의 `Files.readAllBytes()` → `PublicNoticeAttachmentDownload(String, byte[])` → `ResponseEntity<byte[]>`. HEAD는 `@GetMapping`의 암묵 처리라 GET과 동일하게 전량 로딩한다.
2. `FileStorage`의 실제 구현체는 `LocalDiskFileStorage` 하나이며, `LocalDiskFileStorageTest`(`defaultNamespaceMethods_throwUnsupportedOperationException`)에 **익명 구현체**가 있어 인터페이스에 추상 메서드를 추가하면 그 테스트가 컴파일되지 않는다. 그 외 테스트는 `FileStorage`를 Mockito mock/spy로만 쓴다.
3. 이 프로젝트에 `spring.jpa.open-in-view` 설정이 없다(Boot 기본값 true). 서비스 트랜잭션이 끝나면 커넥션이 풀로 반환되는지는 **구현 시 실측으로 검증한다**(추측 금지 — 아래 테스트 6번).
4. **Windows 실험(2026-09-29, JDK 17 Corretto, `Files.newByteChannel` + `Channels.newInputStream`)**: 파일을 열고 100바이트를 읽은 뒤 `Files.delete()`를 호출하면 삭제가 성공(`exists=false`)하고, 열린 핸들로 나머지 전량을 정상적으로 끝까지 읽을 수 있었다(`read total=1048576`). 즉 Java NIO의 기본 공유 모드(`FILE_SHARE_DELETE`)에서는 전송 중 관리자 삭제가 삭제 자체를 실패시키지도, 진행 중인 다운로드를 깨뜨리지도 않는다. 리눅스(unlink 시맨틱)도 동일.
5. Spring MVC의 `AbstractMessageConverterMethodProcessor`는 `Resource` 반환값에 `Range` 헤더가 오면 리전 처리 경로로 들어간다 — `InputStreamResource` **자체**만 제외되고 그 서브클래스는 제외되지 않는다. `ResourceHttpMessageConverter`는 `InputStreamResource`의 Content-Length를 스스로 계산하지 않는다. 즉 "`InputStreamResource` 서브클래스로 `contentLength()` 오버라이드"는 Range 함정이 있다(구현 단계에서 사용 중인 Spring 버전 소스로 재확인한다 — 이 사실은 결정 S2의 근거 중 하나일 뿐 유일한 근거가 아니다).
6. `PublicWebExceptionAdvice`(`Exception` 폴백, HTML 뷰 반환)는 응답이 이미 커밋된 뒤의 예외를 구분하지 않는다. 스트리밍은 이 상황(본문 전송 중 IO 오류·클라이언트 중단)을 처음으로 정상 경로에 들인다.
7. (v6, codex 4로 확인) `LocalDiskFileStorage.loadUnder()`의 경로 검증은 **부모 디렉터리**의 `toRealPath`만 검사하며 최종 파일 자체의 심볼릭 링크는 검사하지 않는다.
8. 테스트 파급: `PublicNoticeControllerTest`(다운로드 5건이 `PublicNoticeAttachmentDownload(String, byte[])` 생성자 사용), `PublicNoticeServiceTest`(다운로드 6건이 `fileStorage.load()` 스텁), `PublicNoticeAttachmentIntegrationTest`(4건, DTO 접근), `LocalDiskFileStorageTest`(익명 구현체). `SecurityConfigTest`·레이트리밋 테스트는 영향 없을 것으로 보이나 구현 시 재확인.

### 핵심 설계 결정

**결정 S1. `FileStorage`에 추상 메서드 `StoredFileStream open(String storageKey)`를 추가한다(네임스페이스 변형 없음).**
- 선택지: (A) 추상 메서드, (B) 네임스페이스 메서드처럼 `default`로 `UnsupportedOperationException`, (C) 스트림만 반환(크기 별도 조회).
- **결정: (A)+크기 동봉.** `StoredFileStream`은 `InputStream`과 `long size`를 함께 담는 `Closeable` record다. 이유: (1) `open`은 핵심 읽기 연산이라 "안전한 실패 default"(B)는 구현체가 조용히 미지원인 채 남는 것을 허용해 오히려 나쁘다 — 네임스페이스 default는 "격리가 조용히 깨지는" 위험을 막으려는 특수 사례였다. 실제 구현체가 하나뿐이라 추상 메서드의 비용은 익명 구현체 테스트 1건 수정뿐이다. (2) 크기를 별도 `size()` 조회로 분리하면 stat과 open 사이 파일이 바뀌는 경합이 생기므로, **같은 핸들에서** 크기를 얻어 Content-Length와 실제 바이트 수가 어긋나지 않게 한다(파일은 `CREATE_NEW`로만 쓰여 불변). 네임스페이스 변형은 공개 첨부만 쓰므로 만들지 않는다(요청받지 않은 추상화 금지).
- `LocalDiskFileStorage.open()`은 `loadUnder()`와 **같은 사전 검증**을 공유한다: 예약 네임스페이스 거부(`isReservedNamespace`), `resolveTarget`(정규화), `realPathOrThrow`+`verifyWithinRoot`. **(v6 정정)** 이 검증이 보장하는 범위는 "최종 파일의 **부모 디렉터리**의 실제 경로가 루트 하위임"까지이며, 최종 파일 자체가 외부를 가리키는 심볼릭 링크인 경우는 막지 못한다(codex 4 — 코드 확인: `loadUnder`는 `target.getParent()`만 `toRealPath`). 따라서 새 `open()`은 채널을 `LinkOption.NOFOLLOW_LINKS`로 열어 **최종 링크를 거부**한다(파일은 항상 `CREATE_NEW`로 만든 일반 파일이라 정상 경로에 영향이 없다; 링크로 인한 open 실패는 `IllegalStateException`(fail-closed 500)). 기존 `load()`의 같은 한계는 이번 범위 밖이라 변경하지 않고 후속으로 기록한다. `NoSuchFileException`→`StorageFileNotFoundException`, 그 외 `IOException`→`IllegalStateException` 계약은 `loadUnder`와 같다. 중복을 피하려고 대상 경로 해석+부모 검증 헬퍼를 추출해 두 메서드가 공유한다.
- **(v6, 리뷰 2)** 채널 open 성공 후 `size()`가 실패하면 채널을 닫고 예외를 던진다(누수 방지 — 테스트로 검증).

**결정 S2. 스트림 수명은 컨트롤러가 소유한다 — `ResponseEntity`/`Resource`/`StreamingResponseBody`를 쓰지 않고 `HttpServletResponse`에 직접 쓴다.**
- 선택지: (A) `ResponseEntity<InputStreamResource>`, (B) `StreamingResponseBody`, (C) 컨트롤러가 `try-with-resources`로 `response.getOutputStream()`에 직접 복사.
- **결정: (C).** 이유: (A)는 정찰 5번의 Range 함정과, 컨버터 선행 경로(예: `Accept` 협상 실패)에서 쓰기 전 예외가 나면 스트림이 닫히지 않을 수 있는 누수 경로가 있다. (B)는 비동기 디스패치로 처리되어 Security·레이트리밋 필터·`PublicWebExceptionAdvice`와의 상호작용(별도 스레드, 재디스패치 시 인증 컨텍스트)이 새 위험원이다. (C)는 close가 코드상 결정적이고, 이 컨트롤러가 이미 쓰는 `response.sendError(404)` 패턴과 일관된다. 반환 타입은 `void`(`HttpServletResponse` 인자가 있으므로 Spring이 뷰 해석을 하지 않는다).
- 헤더(`Content-Type: application/octet-stream`, `Content-Disposition`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, `Content-Length`)는 기존 `toResponse()`와 동일한 값을 응답에 직접 설정한다. `Content-Disposition`은 기존과 같이 `ContentDisposition.attachment().filename(name, UTF_8).build().toString()`을 그대로 써서 CR/LF 인코딩 계약(테스트 10번)을 유지한다.

**결정 S3. (v6 개정) 서비스를 둘로 나눈다 — DB 확인은 트랜잭션 안에서 메타데이터만 반환하고, 파일 열기는 트랜잭션 밖에서 한다.**
- v5 초안은 `@Transactional` 메서드 안에서 `open()`해 열린 스트림을 반환했다. codex 2가 지적했듯 스트림 반환 **후** 트랜잭션 프록시의 commit이 실패하면 호출자는 DTO를 받지 못해 스트림을 닫을 수 없다(누수 경로). `TransactionTemplate` 대안도 있으나 서비스를 둘로 나누는 쪽이 더 단순하고 구조적으로 누수를 없앤다.
- `PublicNoticeService`:
  - `@Transactional(readOnly=true) Optional<PublicNoticeAttachmentRef> findPublishedAttachment(Long noticeId, Long attachmentId)` — 기존과 같은 순서로 (1) notice 공개 조건 재검증 (2) `findByIdAndNoticeId`(IDOR 차단)를 수행하고 `(originalFilename, storageKey)`만 담은 record를 반환한다. 열린 자원이 없으므로 commit 실패가 나도 누수가 없다.
  - **트랜잭션 없는** `Optional<PublicNoticeAttachmentDownload> openAttachment(PublicNoticeAttachmentRef ref)` — `fileStorage.open()`을 호출하고 `StorageFileNotFoundException`→`Optional.empty()`, 그 외 예외는 전파(500)한다. 컨트롤러가 두 메서드를 순서대로 호출한다(Controller→Service 방향 유지). 기존 단일 메서드 `downloadPublishedAttachment()`는 제거한다.
  - `PublicNoticeAttachmentRef`는 publicweb 내부용 record이며 `storageKey`를 담으므로 **Model·뷰에 절대 넣지 않는다**(기존 `PublicNoticeAttachment` 뷰 DTO에는 `storageKey` 필드가 없다는 결정 3은 그대로).
- 이 구조는 (a) 파일을 여는 동안·전송 중에 DB 커넥션을 요구하지 않고(트랜잭션이 이미 끝남), (b) 열린 스트림이 트랜잭션 프록시를 통과하지 않는다. TOCTOU 계약("재검증 SELECT를 실행한 시점의 공개 상태")은 그대로이며, SELECT와 `open()`이 별도 단계로 분리되는 정도의 차이는 이미 수용한 약한 보장 범위 안이다.
- **소유권 계약**: `openAttachment()`가 반환한 DTO(`Closeable`)를 받은 컨트롤러가 즉시 `try-with-resources`로 닫는다. DTO 반환~try 진입 사이에 예외가 날 수 있는 코드를 두지 않는다.
- **OSIV 주의(리뷰 3)**: `open-in-view`가 기본값(true)이라 트랜잭션이 끝나도 Hibernate가 요청 끝까지 JDBC 연결을 잡을 수 있는지는 코드만으로 확정하지 못했다(Spring의 `HibernateJpaVendorAdapter`가 연결 처리 모드를 바꾸는지 미확인). 서비스를 나눠도 이 질문은 남으므로 **구현 첫 단계의 스파이크**(테스트 계획 6번)로 실측한다. 연결이 요청 끝까지 잡히는 것으로 판명되면 전송 중 커넥션 점유가 발생하므로 즉시 멈추고 사용자 결정 사항으로 올린다(선택지 예: 전역 `spring.jpa.open-in-view=false`는 다른 화면의 지연 로딩에 영향을 줄 수 있어 별도 영향 조사 필요).

**결정 S4. HEAD는 전용 핸들러로 분리하고, 파일을 열어 크기만 읽은 뒤 즉시 닫는다(바이트 미독).**
- 선택지: (A) HEAD도 GET 핸들러를 타되 본문 쓰기만 건너뜀, (B) 별도 스토리지 `size()` 조회로 파일을 열지 않음, (C) 전용 HEAD 핸들러가 `open()`→크기→`close()`.
- **결정: (C).** 이유: (A)는 핸들러 안에서 요청 method로 분기해야 해 읽기 어렵다. (B)는 인터페이스 메서드를 하나 더 만들고 `open()`과 검증 경로를 이중화해 404 판정이 GET/HEAD 간에 어긋날 위험이 있다. (C)는 공개 조건 재검증·IDOR·파일 존재 확인(404)이 GET과 **정확히 같은 코드 경로**(`findPublishedAttachment`→`openAttachment`)를 타고, 힙에 올라가는 바이트가 0이며, 파일 핸들을 잠깐 여닫는 비용(O(1))만 든다. **사용자가 요청한 문구 "HEAD는 파일을 열지 않고 헤더만 응답"과의 차이**: "본문 바이트를 읽지 않는다"로 정의를 조정한다(핸들 open/close는 발생) — 승인 단계에서 별도 고지한다. Spring은 같은 경로에 명시 `HEAD` 매핑이 있으면 GET 핸들러의 암묵 HEAD 처리보다 우선한다(테스트 4번이 실증).
- HEAD 응답 헤더는 GET과 동일(단, 본문 없음, `Content-Length`는 GET과 같은 값).

**결정 S5. (v6 개정) 응답 시작 후 실패는 컨테이너까지 전파해 연결을 끊는다 — 삼키지 않는다.**
- v5 초안은 커밋 후 `IOException`을 삼키고 "Content-Length보다 짧게 끝나면 컨테이너가 연결을 닫는다"고 가정했다. codex 1이 지적했듯 이 가정은 검증되지 않았고(Tomcat `IdentityOutputFilter.end()`는 남은 길이를 검사하지 않는다는 지적), 예외를 삼키면 컨테이너에는 정상 완료로 보여 클라이언트가 잘린 파일을 정상으로 받거나 연결 재사용 시 응답 경계가 깨질 수 있다.
- **결정 (v7 보강 — 응답 상태 3구간을 구분한다)**: "커밋 전 = 첫 바이트 전"이라는 등식은 성립하지 않는다(서블릿 출력 버퍼에 일부를 쓰고도 미커밋일 수 있고, 헤더·`getOutputStream()` 선택 상태·`Content-Length`가 이미 설정된 뒤일 수 있음 — codex 2차).
  1. **응답 무손대 구간**: 컨트롤러는 응답 헤더·출력 스트림에 손대기 **전에 첫 청크(버퍼 크기 8KB)를 먼저 읽어 둔다.** 이 읽기가 실패하면 응답 상태가 전혀 변하지 않았으므로 예외를 그대로 던져 기존 `PublicWebExceptionAdvice`가 HTML 500 뷰를 반환한다(기존 계약 유지). 빈 파일(크기 0)도 이 단계에서 EOF로 처리한다.
  2. **미커밋이지만 응답이 오염된 구간**(헤더·`Content-Length` 설정, 출력 스트림 획득, 버퍼에 일부 바이트 기록 후 다음 읽기 실패): 컨트롤러가 IOException을 잡아 `if (!response.isCommitted()) response.reset();`으로 **상태·헤더(`Content-Length` 포함)·버퍼·출력 방식(`getOutputStream` 선택)을 전부 초기화한 뒤** 예외를 재던진다. 그러면 advice가 상태 500 + HTML 뷰를 정상 렌더링한다. `reset()`이 지우는 보안·캐시 헤더(`X-Content-Type-Options`, `Cache-Control` 등)는 Spring Security가 응답 커밋 시점에 다시 쓰도록 되어 있어 복원될 것으로 예상하지만 **추측이므로 실제 Tomcat 테스트로 확인한다**(테스트 3번).
  3. **커밋 후 구간**: 컨트롤러는 그대로 재던지고, `PublicWebExceptionAdvice.handleUnexpected`가 `response.isCommitted()`이면 뷰를 렌더링하지 않고 **예외를 재던진다**(`throws Exception`) — 예외가 서블릿 컨테이너까지 전파되어 컨테이너가 연결을 중단하도록 한다. **이에 따라 이전 초안의 "`PublicWebExceptionAdvice`는 수정하지 않는다" 제약을 철회한다**(수정 범위는 이 `isCommitted()` 분기 한 곳뿐).
- 이 전파 경로가 실제로 연결을 끊는지는 **가정하지 않고 실제 Tomcat으로 검증한다**(테스트 계획 3번): 일부 바이트를 전송하고 flush한 뒤 읽기 예외를 내는 스토리지 스텁으로 실제 서버(`RANDOM_PORT`)에 요청해, 클라이언트가 Content-Length 미충족(EOF/예외)을 관찰하고 응답 본문에 HTML이 섞이지 않는지 확인한다. 이 검증이 실패하면(연결이 끊기지 않으면) 명시적 연결 중단 수단을 재설계한다.
- 클라이언트 중단(`ClientAbortException` 등 연결 끊김)은 정상 운영에서 흔하므로 ERROR 스택트레이스 폭주를 막기 위해 커밋 후 분기에서 WARN 한 줄(요청 경로·예외 클래스)만 남기고 재던진다. 파일명·storageKey·스택트레이스는 남기지 않는다.

**결정 S6. Range·`Accept-Ranges`는 지원하지 않고, 이전 동작과 동일하게 무시한다.** 요청받지 않은 기능이며 스트리밍 전환의 목적(힙 점유 제거)과 무관하다.

**결정 S7. 전송 중 관리자 삭제 경합은 별도 처리 없이 수용한다.** 정찰 4번 실험으로 Windows에서 열린 핸들이 삭제에 영향을 받지 않음을 확인했다(리눅스는 unlink 시맨틱상 동일 — 이 부분은 이 머신에서 실측하지 못했고 CI 러너에서 회귀 테스트가 확인한다). 삭제 트랜잭션의 `afterCommit` 파일 삭제는 그대로 성공하며, 진행 중이던 다운로드는 끝까지 완료된다. 이는 TOCTOU "약한 보장"의 범위 안이다.

**결정 S8. TOCTOU 창 확대를 문서로 명시한다.** 약한 보장 계약("재검증 SELECT를 실행한 시점의 공개 상태")은 그대로이나, 스트리밍에서는 "SELECT 이후~응답 완료 사이" 창이 **전송 시간(느린 클라이언트는 수십 초)만큼** 길어진다. 잘못된 보증으로 읽히지 않도록 `publicweb/notice/CLAUDE.md`와 이 계획서의 리스크 표에 명시한다. 강한 보장(락 유지)을 채택하지 않는 이유(무인증 경로가 관리자 쓰기를 블로킹하는 DoS 표면)는 v2 사용자 확정 그대로다.

### 작업 단계 (의존 방향 안쪽 → 바깥쪽)

0. **스파이크(가장 먼저)**: OSIV 상태의 웹 요청에서 전송 중 커넥션이 반환되는지 실측하는 테스트(아래 6번)를 먼저 작성·실행한다. 결과가 S3 전제를 깨면 이후 단계를 진행하지 않고 사용자에게 보고한다.
0.5. **(v8) 전역 `spring.jpa.open-in-view: false`** 를 `application.yml` 공통 `spring.jpa` 아래에 추가하고(주석으로 이유·근거 기록), 전체 테스트와 화면 실기 검증으로 지연 로딩 회귀를 확인한다. 스파이크 테스트는 OSIV를 켜 두면 실패하는 회귀 가드로 남긴다(최종 형태는 테스트 6번).
1. `common/storage`: `StoredFileStream` record 신설, `FileStorage.open()` 추가·javadoc 갱신(기존 "스트리밍 관용구가 없어 byte[] 기반" 문구 정정), `LocalDiskFileStorage`에 `open()` 구현 + `loadUnder()`와 검증 헬퍼 공유.
2. `publicweb/notice/dto/PublicNoticeAttachmentDownload`: `(String originalFilename, long contentLength, InputStream content)` + `Closeable`.
3. `PublicNoticeService`: `findPublishedAttachment()`(트랜잭션, 메타데이터만)와 `openAttachment()`(트랜잭션 없음) 2분할, `PublicNoticeAttachmentRef` 신설, 기존 `downloadPublishedAttachment()` 제거.
4. `PublicNoticeController`: 다운로드를 직접 쓰기(`void`)로 전환, HEAD 전용 핸들러 추가. `PublicWebExceptionAdvice`: 응답 커밋 후에는 뷰를 렌더링하지 않고 재던지는 분기 추가.
5. 테스트 갱신·추가(아래), 익명 `FileStorage` 구현체 수정.
6. 문서: `publicweb/notice/CLAUDE.md`·루트 `CLAUDE.md` 해당 문구, 이 계획서 "구현·검증 결과" 갱신, `plan/README.md` 표기.

### 테스트 계획

1. `LocalDiskFileStorageTest`: `store`→`open` 왕복(스트림 전량 읽기 = 원본 바이트, `size` = 원본 길이); 없는 키 → `StorageFileNotFoundException`; 경로 탈출(`../`)·부모 디렉터리 심볼릭 링크 탈출·예약 네임스페이스(`profile/...`) 키는 `open`에서도 거부(기존 `load` 거부 테스트와 대칭); **(v6, 리뷰 4)** 최종 파일이 외부를 가리키는 심볼릭 링크이면 `open`이 거부(Windows 등 심볼릭 링크 생성 권한이 없으면 기존 테스트처럼 `assumeTrue`로 건너뜀); **(v6, 리뷰 2)** `size()` 실패 시 채널이 닫힘(닫힘 여부를 관측할 수 있는 채널 스텁 또는 패키지 접근 헬퍼로 검증); **열린 상태에서 `delete` 후에도 남은 바이트를 끝까지 읽을 수 있다**(정찰 4번을 회귀 테스트로 고정); 익명 `FileStorage` 구현체에 `open` 추가.
2. `PublicNoticeServiceTest`: 기존 다운로드 6건을 `findPublishedAttachment`/`openAttachment` 2단계로 전환(성공·비공개 notice·타 notice·호출 순서·`StorageFileNotFoundException`→empty·기타 예외 전파). 호출 순서 테스트는 "notice 재검증 → 첨부 조회 → (별도 호출) `open`"을 계속 고정하고, `findPublishedAttachment`는 파일 스토리지를 전혀 호출하지 않음을 검증한다.
3. `PublicNoticeControllerTest`(MockMvc 슬라이스): 기존 다운로드 계약(헤더 4종·404·비숫자 404·서비스 예외 HTML 500·CR/LF 인코딩) 유지 + `Content-Length` 헤더 = DTO의 `contentLength`; 본문 전송 후 스트림이 close됨; 첫 바이트 전 읽기 실패 시 HTML 500·스트림 close. **(v6, 리뷰 1) 커밋 후 실패는 MockMvc가 아니라 실제 Tomcat으로 검증한다**: 신규 `@SpringBootTest(webEnvironment=RANDOM_PORT)` 통합 테스트에서 `FileStorage`를 "N바이트 전송 후 예외"를 내는 스텁 스트림으로 바꾸고 실제 HTTP 클라이언트로 요청해 (a) 클라이언트가 선언된 Content-Length 미충족(연결 종료/예외)을 관찰하고 (b) 수신 바이트에 HTML 에러 페이지가 섞이지 않았으며 (c) 스트림이 close됨을 단언한다. 이 테스트는 연결 재사용(같은 커넥션으로 이어지는 후속 요청)이 깨지지 않는지도 확인한다. **(v7, codex 2차) 미커밋 구간 실패 두 케이스를 같은 실서버 테스트에 추가한다**: (i) 첫 청크 읽기 실패(응답 무손대 → 기존 HTML 500), (ii) 첫 청크 이후 버퍼 미만만 기록하고 flush 없이 다음 읽기 실패(오염된 미커밋 → `reset()` 후 HTML 500). 두 경우 모두 응답이 완전한 HTML 500이고, 첨부파일 `Content-Length`가 남아 있지 않으며, 보안·캐시 헤더(`X-Content-Type-Options`·`Cache-Control`)가 복원돼 있고, 입력 스트림이 close됨을 확인한다. MockMvc 슬라이스에서도 (i)(ii)를 `MockHttpServletResponse.reset()` 기준으로 같이 확인한다.
4. HEAD: `head("/notices/1/attachments/7")`이 200·GET과 같은 `Content-Length`·본문 0바이트·스트림 `close`(읽기 0회)를 확인; HEAD의 404(empty)도 GET과 동일.
5. `PublicNoticeAttachmentIntegrationTest`(Testcontainers): 기존 4개 시나리오를 2단계 API로 전환(성공 시 스트림 내용·`contentLength` 확인 후 close).
6. **(v6, 리뷰 3) 커넥션 비점유 실측(스파이크)**: 서비스 빈 직접 호출이 아니라 **실제 웹 요청**(`RANDOM_PORT`, OSIV 인터셉터 포함)으로 검증한다. `FileStorage`를 "일부 바이트 전송 후 latch로 대기"하는 스텁 스트림으로 바꾸고, 전송이 latch에서 멈춘 시점(응답 완료 전)에 Hikari `HikariPoolMXBean.getActiveConnections() == 0`을 단언한다. 테스트 자체가 외부 트랜잭션·추가 DB 작업으로 풀 수치를 오염시키지 않도록 데이터 준비를 요청 시작 **전에** 끝내고, 단언 직전 다른 DB 접근이 없음을 보장한다. 실패 시 S3의 OSIV 주의 절차를 따른다.
7. `SecurityConfigTest`·레이트리밋 테스트가 영향받지 않는지 전체 스위트로 확인(변경 없음이 기대).

### 리스크 (v5 추가·정정)

| 리스크 | 대응 |
|---|---|
| **(v5, v6 개정)** 스트림 close 누락 → 파일 핸들 누수(무인증 경로라 누적 시 fd 고갈) | 결정 S2·S3 — 트랜잭션 프록시 밖에서 `open()`하므로 commit 실패 누수 경로가 구조적으로 없고, 컨트롤러 `try-with-resources` 단일 소유. `open()` 내부 `size()` 실패 시 채널 close(테스트 1번). 테스트 3·4번이 close를 검증 |
| **(v5)** 트랜잭션 종료 후에도 커넥션이 반환되지 않으면 느린 클라이언트가 풀을 고갈(스트리밍이 기존보다 **나빠짐**) | 결정 S3 — 통합 테스트 6번이 실측으로 증명. 실패 시 설계 재검토(서비스 메서드 분리 등) |
| **(v5)** TOCTOU 창이 전송 시간만큼 확대 | 결정 S8 — 문서 명시. 약한 보장 계약 자체는 불변 |
| **(v5, v6 개정)** 본문 전송 중 오류로 잘린 파일이 정상 응답처럼 전달될 수 있음 | 결정 S5 — 예외를 삼키지 않고 컨테이너까지 전파(`PublicWebExceptionAdvice`가 커밋 후에는 재던짐). 연결 종료·HTML 미첨가·연결 재사용 무결성을 실제 Tomcat 통합 테스트(테스트 3번)로 검증 |
| **(v5)** HEAD도 파일 핸들을 잠깐 연다 | 결정 S4 — 힙 0바이트·O(1). 사용자 문구("열지 않고")와의 차이는 승인 단계에서 고지 |
| 기존 리스크 "무인증 경로 `byte[]` 전량 로딩으로 인한 자원 고갈" | **본 작업으로 힙 점유 해소.** 동시 연결·디스크 IO·Tomcat 스레드 점유는 그대로이며, 기존 레이트리밋은 요청 진입 빈도만 완화할 뿐 동시 전송 수·점유 시간 상한은 보장하지 않는다(codex 5) — 이번 범위에서 동시성 제한은 추가하지 않는다 |
| **(v6)** `open()`이 기존 `load()`와 달리 최종 심볼릭 링크를 거부(`NOFOLLOW_LINKS`) — 두 메서드의 검증 강도가 다름 | 의도된 차이. 기존 `load()`의 부모 경로만 검증하는 한계는 이번 범위 밖이라 후속으로 기록(정상 경로는 일반 파일만 생성) |

## 구현·검증 결과 — 스트리밍 전환 (2026-09-29)

### 핵심 확정 사항

계획서 v8까지의 설계를 구현했다. 구현·검증 중 **계획과 달라지거나 새로 확정된 것**:

1. **전역 `spring.jpa.open-in-view: false` 채택**(v8, 사용자 결정) — 스파이크가 "전송 중 커넥션 비점유" 전제가 기본 설정에서 깨짐을 실측했다(OSIV 켬: 요청 진행 중 `activeConnections=1`, 끔: 0). 현행 `byte[]` 방식도 같은 이유로 응답 쓰는 동안 커넥션을 잡고 있었으므로 이번 전환이 만든 회귀가 아니라 기존 문제였다.
2. **복사 버퍼를 8KB가 아니라 4KB로 확정** — 계획서 S5는 "첫 청크(버퍼 크기 8KB)"로 적었으나, `MockHttpServletResponse` 버퍼(4KB)가 8KB 첫 청크로 이미 "커밋됨"이 되어 "미커밋이지만 오염" 구간 테스트가 성립하지 않았다. Tomcat 출력 버퍼(8KB)보다 작게 잡으면 첫 청크 직후가 컨테이너와 무관하게 항상 미커밋이 되어 구간이 결정적이다(계획서 S5의 "8KB" 표기는 4KB로 읽는다).
3. **스파이크 테스트를 별도 유지하지 않고 실서버 통합 테스트에 흡수** — `OsivConnectionGuardTest`(스파이크에서 개명)를 삭제하고 `PublicAttachmentStreamingServerTest`의 "전송 중 활성 커넥션 0" 테스트가 실제 다운로드 경로로 대체했다(중복 제거).
4. **`PublicWebExceptionAdvice`의 커밋 후 재던짐은 Tomcat 결과를 바꾸지 않는다**(변이 실험) — 재던짐을 제거해도 실서버 테스트가 통과했다(뷰 렌더링이 `getOutputStream()` 이후 `getWriter()` 충돌로 예외를 내 결과적으로 연결이 끊김). 반면 **컨트롤러가 IOException을 삼키면 Tomcat이 연결을 끊지 않아 클라이언트가 무한정 대기**함을 변이 실험으로 실측했다(codex 1차 지적 실증). advice 재던짐은 그 우발적 경로에 기대지 않는 명시적 경로이며 MockMvc 테스트가 고정한다.

### 구현 파일

**신규**
- `src/main/java/com/cms/common/storage/StoredFileStream.java` — 스트림 + 같은 핸들에서 읽은 크기, `Closeable`
- `src/main/java/com/cms/publicweb/notice/dto/PublicNoticeAttachmentRef.java` — 공개 조건 통과 후 파일 참조(`storageKey` 포함, Model 금지)
- `src/test/java/com/cms/publicweb/notice/PublicAttachmentStreamingServerTest.java` — 실제 Tomcat·OSIV·DB, 5개 테스트

**수정**
- `FileStorage`(`open()` 추상 메서드 추가, javadoc 정정), `LocalDiskFileStorage`(`open()` 구현: `NOFOLLOW_LINKS`, 크기 조회 실패 시 채널 close, `loadUnder`와 사전 검증 헬퍼 공유)
- `PublicNoticeService`(`findPublishedAttachment()` 트랜잭션 / `openAttachment()` 트랜잭션 없음으로 2분할, 기존 `downloadPublishedAttachment()` 제거), `PublicNoticeAttachmentDownload`(스트림형 `Closeable`)
- `PublicNoticeController`(직접 스트리밍, HEAD 전용 핸들러, 응답 상태 3구간 처리, 4KB 버퍼), `PublicWebExceptionAdvice`(커밋 후 재던짐)
- `application.yml`(`spring.jpa.open-in-view: false`)
- 테스트: `LocalDiskFileStorageTest`(open 9건 추가, 익명 구현체 수정), `PublicNoticeServiceTest`(2단계 API로 전환), `PublicNoticeControllerTest`(스트리밍 계약 16건으로 재작성), `PublicNoticeAttachmentIntegrationTest`(2단계 API로 전환)
- 문서: `CLAUDE.md`, `com.cms.publicweb.notice/CLAUDE.md`, `plan/README.md`, `docs/troubleshooting.md`
- 스키마 변경 없음, `SecurityConfig` 변경 없음, 신규 의존성 없음.

### 검증 결과

- `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 전체: **813개, 실패·에러 0, 스킵 3**(전부 Windows에서 심볼릭 링크를 만들 수 없어 `assumeTrue`로 건너뜀 — 기존 1건 + 신규 2건).
- **변이 실험으로 테스트가 실제로 결함을 잡는지 확인**: (a) OSIV 재활성 → "전송 중 활성 커넥션" 테스트가 `expected: 0 but was: 1`로 실패 (b) 컨트롤러가 전송 중 예외를 삼킴 → 실서버 테스트 2건 실패(클라이언트 대기 타임아웃, 500 대신 200) (c) advice 재던짐 제거 → MockMvc 테스트 실패(위 4번). 실험 후 원복 확인(`MUTATION` 문자열 0건).
- **실기 검증**(`bootRun --server.port=8099`, 사용자 dev 스택 8080 미간섭, dev DB 공유): 관리자 로그인 → 공지 1건·첨부 2건(27B·10MB 무작위) 생성 → 비로그인 상태에서 curl·브라우저로 확인:
  - 27B·10MB 모두 200, `Content-Length` 정확, 다운로드 sha256이 업로드 원본과 일치, 10MB를 0.09초에 수신. 헤더 `application/octet-stream`·`nosniff`·`no-store`·`Content-Disposition` 유지.
  - HEAD: 200·같은 `Content-Length`·본문 0바이트. `Range` 헤더는 이전처럼 무시(200 전체).
  - 404 계열(없는 첨부·없는 notice·비숫자·타 notice) 전부 동일한 HTML 404, HEAD 404, 비인증 POST 403.
  - `useYn=false` 전환 후 GET·HEAD·상세 모두 404, 재공개 후 200. 실파일을 옮기면 GET·HEAD 404, 복원 후 200.
  - **느린 클라이언트 12개(`--limit-rate 300k`, 풀 크기 10 초과)가 10MB를 전송 중인 동안 DB 조회 요청(`/notices`, `/admin`)이 12~57ms로 정상 응답**했고 12개 모두 200·10,485,760바이트를 완주.
  - 브라우저(Playwright): 공개 상세에서 첨부 링크 클릭 → 실제 다운로드, sha256 일치. 관리자 대시보드·공지 관리(상세 모달의 첨부 목록 포함)·회원 관리·메뉴 관리·활동 로그·내 정보 화면이 `open-in-view=false`에서도 정상 렌더링, 콘솔 오류 0(favicon 404 제외 — 기존 사안), 서버 로그에 `ERROR`·`LazyInitializationException` 없음. 스크린샷 4장: `screenshots/streaming-admin-dashboard.png`·`streaming-admin-notice.png`·`streaming-admin-notice-detail.png`·`streaming-admin-member.png`·`streaming-public-detail.png`.
  - 검증 후 dev DB 원복: 첨부 2건·공지 1건 삭제(공지는 소프트 삭제 후 직접 행 제거 — 총 9건으로 복귀), 실파일 제거 확인. 관리자 행위 감사 로그·방문 기록은 남김(정상 부수효과).

### 이슈

- **검증하지 못한 것**: 심볼릭 링크 관련 테스트 3건(최종 파일 링크 거부 포함)은 이 Windows 환경에서 링크를 만들 수 없어 실행되지 않았다 — Linux CI에서 처음 실행된다. `NOFOLLOW_LINKS` 동작은 코드상 근거뿐이며 이 머신에서는 실증하지 못했다. 열린 핸들 삭제 실험도 Windows에서만 실측했다.
- **알려진 잔여 사항(범위 밖)**: 기존 `load()`는 최종 파일 자체가 링크인 경우를 막지 못한다(부모 디렉터리만 검증) — 후속. 동시 연결·디스크 IO·Tomcat 스레드 점유의 상한은 없다(`cms.rate-limit`은 진입 빈도만 완화).
- `open-in-view=false`는 전역 설정이라 지연 로딩 의존이 새로 생기면 `LazyInitializationException`이 난다 — 현재 엔티티에는 연관관계 매핑이 없다.

### 후속

- 기존 `LocalDiskFileStorage.load()`에도 최종 링크 거부를 적용할지 검토.
- 실제 공개 트래픽·적대적 접근이 예상되면 동시 전송 수 상한(예: 진행 중 다운로드 세마포어) 검토 — 로드맵 "후속 과제 — ② 공개 첨부 다운로드" 항목 갱신은 `/updateRoadmap` 담당.
