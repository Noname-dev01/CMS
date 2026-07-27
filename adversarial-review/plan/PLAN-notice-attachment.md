# PLAN — 파일 스토리지 추상화 + 공지사항 첨부파일

> 작성일: 2026-07-21
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 Top 5 (2026-07-20 재선정) — ②"
> 선행 완료: ① 공지사항(notice) 관리 CRUD (`6c5ca4c` #16)

## 개정 이력
- v1 (2026-07-21): 최초 작성. `/plan-review-loop` 리뷰 대상으로 제출.
- v2 (2026-07-21, 리뷰 1차 반영):
  - 수용(2): 업로드 정리 로직을 `try{save}catch`에서 `TransactionSynchronization.afterCompletion(status != STATUS_COMMITTED)` 기반으로 변경 — JPA 오류가 flush/커밋 시점에 발생해도 정리되도록.
  - 수용(3): 삭제 시 파일 제거 실패를 `AdminSessionRevokeListener`와 동일하게 try/catch + `log.error`(storageKey·attachmentId 포함) + 예외 미전파로 명문화.
  - 부분 수용(4): Tika 미도입은 plan 모드에서 이미 승인된 사안이라 재질문하지 않음. "실행 차단이 실질적 방어선"이라는 과장 표현 삭제, 저장 루트가 정적 리소스/웹 루트 밖(직접 URL 서빙 경로 없음)이라는 제약과 다운로드 응답 `X-Content-Type-Options: nosniff` 헤더를 보완책으로 추가.
  - 수용(5): `docker-compose.dev.yml`의 `app` 서비스에 볼륨이 전혀 없음을 확인 — named volume(`notice_attachments_dev`) 추가. 다중 인스턴스 배포는 prod 프로파일 부재(로드맵 ⑤ 별건)로 이번 범위에서 단일 인스턴스 전제 명시.
  - 수용(6): 삭제(DELETE)도 다운로드와 동일하게 `findByIdAndNoticeId` 복합조건 명시(IDOR 방지).
  - 수용(7): `NoticeRepository.findByIdAndDeletedFalseForUpdate` 주석을 "PATCH·DELETE·첨부 업로드 전용"으로 갱신 필요 명시. "동시 DELETE는 항상 404" 과장 표현을 "멱등적이나 감사로그·파일삭제 콜백이 중복될 수 있음(FileStorage.delete()의 no-op 계약으로 실질 피해 없음)"으로 정정.
  - 수용(8a): `GET .../{attachmentId}/download` → `GET .../{attachmentId}/content`(URI 동사 금지 규칙).
  - 기각(8b): 10MB 초과 시 413 제안 — CLAUDE.md 상태 코드 규칙 표에 413이 없고 기존 크기 검증(프로필 이미지)도 400으로 통일돼 있어, 프로젝트가 의도적으로 좁게 유지하는 상태 코드 팔레트를 확장하지 않음. 400 유지.
  - 수용(8c): 6번째 파일 초과 → 409(`ConflictException`, RESOURCE_CONFLICT)로 변경(CLAUDE.md "409=상태 충돌·중복"에 더 부합).
  - 수용(9): 스키마에 FK `ON DELETE RESTRICT`, `storage_key` UNIQUE, `(notice_id, id)` 인덱스, 컬럼 길이/NOT NULL 명시.
  - 수용(10): `LocalDiskFileStorage`에 `toRealPath()` 기반 심볼릭 링크 탈출 방지(store/load/delete 공통), `CREATE_NEW`로 UUID 충돌 방지, 임시파일→원자적 rename, `ContentDisposition` 빌더 사용 추가.
  - 수용/설계보완(11): byte[] 유지 결정에 "관리자 전용 저빈도 기능이라 동시 요청 규모가 작다"는 근거를 명시(재질문 없이 반영).
  - 수용(12): 삭제 서비스가 `NoticeAttachmentResponse` 반환(Notice.deleteNotice()와 동일 패턴, targetId 추출용), manage.html CSRF 헤더 필수 재확인.
  - **결정 필요(1)**: 소프트 삭제된 notice의 첨부 처리 정책 — 사용자 질문 대기, 하단 "결정 대기 쟁점" 참조. 답변 전까지 ship 판정 보류.
- v3 (2026-07-21, 사용자 결정 반영): 쟁점 14 — **A안(notice 삭제 시 첨부가 남아있으면 409로 차단)** 확정. `NoticeService.deleteNotice()`가 `NoticeAttachmentRepository`를 주입받아 삭제 전 첨부 개수를 확인하도록 변경(구체 설계는 쟁점 14 결정 내역 참조). `/plan-review-loop` 2라운드 리뷰 대상으로 재제출.
- v4 (2026-07-21, 리뷰 2차 반영 — 전부 수용, 사용자 결정 불필요):
  - 수용(차단1): Dockerfile이 `/app`을 root 소유로 만든 뒤 `appuser`로 전환하면서 저장 디렉터리를 미리 만들거나 소유권을 넘기지 않음을 실측 확인 — `mkdir+chown`을 `USER appuser` 전환 전에 추가. 저장 경로 기본값을 `${APP_FILE_STORAGE_ROOT:./data/attachments}`(프로젝트 상대 경로)로 두고, 컨테이너 전용 절대경로(`/app/data/attachments`)는 `application-dev.yml`이 아니라 `docker-compose.dev.yml`의 `environment`에서만 주입 — 로컬 `bootRun`과 컨테이너 실행이 서로 다른 실제 경로를 쓰도록 분리.
  - 수용(차단2): `CREATE_NEW`(임시파일용)와 `ATOMIC_MOVE`(최종 이동용)를 같은 대상에 동시 적용한다고 서술한 v2의 논리 모순을 정정 — 쟁점 12 재작성.
  - 수용(중요3): `MultipartFile.getContentType()`이 `null`이면 저장 전 `"application/octet-stream"`으로 정규화(컬럼이 `NOT NULL`이므로). 원본 파일명이 255자를 초과하면 업로드 전 400(`InvalidRequestException`)으로 거부 — DB 커밋 실패가 엉뚱하게 409로 잘못 노출되는 경로 원천 차단.
  - 수용(중요4): 1차 리뷰 반영 때 작성한 "동시 DELETE는 DB 레벨에서 멱등적 no-op"이라는 서술이 부정확했음을 인정하고 철회 — JPA 엔티티 삭제는 영향 행 0건일 때 stale-state 계열 예외를 낼 수 있다. 첨부 삭제도 업로드와 동일하게 notice 비관적 락(`findByIdAndDeletedFalseForUpdate`)을 먼저 획득해 같은 notice의 첨부 변경(생성·삭제)을 전부 직렬화 — 경합 자체가 발생하지 않도록 구조적으로 차단(쟁점 4·7 갱신).
  - 수용(중요5): `TransactionSynchronizationManager.registerSynchronization()` 호출 자체가 실패하는 경로에 대한 방어(try/catch로 즉시 파일 정리) 추가. 실제 트랜잭션 커밋/롤백 흐름을 검증하는 통합 테스트(`NoticeAttachmentTransactionIntegrationTest`) 신규 추가.
  - 표기 정정: "목록/다운로드/삭제 모두 `findByIdAndNoticeId`" 서술에서 목록(list)은 `findByNoticeIdOrderByIdAsc(noticeId)`(attachmentId 없음)를 쓰는 것으로 표현 정정 — 실제 IDOR 방지 대상은 다운로드·삭제(단건 조회) 두 곳.
- v5 (2026-07-21, 리뷰 3차 반영 — 수용, 사용자 결정 불필요):
  - 수용(차단): v4의 "임시파일 `CREATE_NEW` + 최종 경로 `ATOMIC_MOVE`(REPLACE_EXISTING 미지정)로 무덮어쓰기 보장" 설계가 Java 17 `Files.move` 공식 계약과 맞지 않음을 확인 — `ATOMIC_MOVE` 지정 시 다른 옵션은 전부 무시되고, 대상이 이미 존재할 때 교체 여부는 구현체(OS/파일시스템) 의존적이라 "미지정 시 항상 예외"가 보장되지 않는다. 임시파일+move 구조를 폐기하고 **최종 경로에 직접 `CREATE_NEW`로 쓰는 방식으로 단순화**(쟁점 12 재작성) — storageKey를 참조하는 DB 행은 `store()` 성공 이후에만 생성되므로, 쓰기 도중 프로세스가 죽어 부분 파일이 남아도 그 키를 가리키는 행이 없어 어떤 요청도 도달할 수 없다(기존 "오펀 파일" 리스크 범주에 흡수).
- v6 (2026-07-21, 리뷰 4차 반영 — 수용, 사용자 결정 불필요):
  - 수용(차단): v5가 "`store()` 실패 시 파일이 미완성이거나, 완성돼도 DB 행이 없어 무해하다"고 결론지은 것은 **접근 안전성**(다운로드 불가)만 입증할 뿐 **디스크 용량 안전성**은 입증하지 못함을 지적받음 — Java 17 `Files.write`는 파일 생성 후 또는 일부 바이트 기록 후에도 I/O 오류(디스크 부족 등)를 던질 수 있어, 정리 로직 없이는 실패한 업로드마다 최대 10MB 잔여 파일이 누적되고 해당 키는 DB에 없어 정상 삭제 경로로도 제거 불가능하다. `store()`를 `Files.newOutputStream(target, WRITE, CREATE_NEW)`로 열어 스트림이 성공적으로 반환된 시점부터만 "이 호출이 파일을 생성했다"로 표시하고, 쓰기·close 실패 시 그 표시가 true일 때만 `deleteIfExists()`로 best-effort 정리하도록 재작성(쟁점 12). `CREATE_NEW` 단계 자체의 실패(대상 이미 존재)에는 삭제하지 않음 — 이 호출이 만든 파일이 아닐 수 있어서다.
- **v6 — 5차(최대 라운드) 리뷰 결과: SHIP** (2026-07-21). 신규 실질 지적 없음 — `CREATE_NEW`의 원자적 무덮어쓰기, `createdByThisCall` 플래그가 스트림 오픈 성공 이후에만 설정되는 안전성, try-with-resources의 쓰기/close 이중 실패 시 원예외 보존(close 예외는 suppressed), `deleteIfExists()` 정리 실패의 별도 로그 계약을 모두 Java 17 공식 문서 기준으로 재검증받아 통과. `/plan-review-loop` 종료(지적 수 추이: 12→5→1→1→0). 이하 "구현 파일"·"작업 단계"·"완료 기준"은 v6 기준 최종 확정.

## Context

현재 이 CMS에서 파일을 다루는 유일한 경로는 프로필 이미지를 **Base64 데이터 URI로 DB `LONGTEXT` 컬럼에 인라인 저장**하는 방식뿐이다(`AdminMemberService.updateMyProfileImage`, L232-259 — 검증·변환이 서비스 메서드 본문에 하드코딩, `MAX_FILE_SIZE`·`image/` 접두어 매직 값). "DB에 넣을 수 없는" 실파일(문서·압축파일 등)을 다루는 추상화가 전혀 없다.

본 작업은 로컬 디스크 기반 `FileStorage` 인터페이스를 신규 도입하고, 그 첫 소비자로 **공지사항 첨부파일 업로드/목록/다운로드/삭제**를 구현한다. 완료 시 실파일을 다루는 첫 경로가 생기고, 이후 프로필 이미지의 Base64-in-DB 이관(별도 후속 작업, 기존 데이터 마이그레이션 수반이라 본 작업 범위 밖)이 같은 인터페이스를 재사용할 수 있다.

### 확정된 정책 (사용자 승인 완료 — plan 모드 단계)
- **허용 확장자**: pdf, doc, docx, xls, xlsx, ppt, pptx, hwp, txt, csv, zip, png, jpg, jpeg, gif
- **크기·개수**: 파일당 최대 10MB, 공지당 최대 5개
- **검증 강도**: 확장자 + 선언 Content-Type 화이트리스트 (신규 의존성 없음 — Apache Tika 등 매직바이트 검사 도입 안 함)
- **소프트 삭제 정책**: notice가 소프트 삭제되어도 첨부 메타·디스크 파일은 자동으로 지우지 않는다(첨부 삭제 캐스케이드 없음). 실제 파일 제거는 개별 첨부 `DELETE` 요청에서만 발생.

### 인가 정책 영향
**없음.** `/admin/api/notices/**`가 이미 `hasAnyRole('ADMIN','MANAGER')`(`SecurityConfig.java` L56)이므로 첨부 엔드포인트를 이 하위 경로(`/admin/api/notices/{noticeId}/attachments`)에 두면 `SecurityConfig` 수정이 불필요하다. 공개 경로 신설도 없음(공개 노출은 로드맵 ③ 별건).

### 스키마 영향
**있음.** `notice_attachment` 테이블 신규 → Flyway `V10` (현재 최대 버전 V9 실측 확인, `src/main/resources/db/migration/` 2026-07-21 기준).

---

## 핵심 설계 결정 (쟁점별)

### 쟁점 1 — FileStorage 인터페이스 형태: byte[] vs Stream
- **선택지 A (byte[] 기반)**: `store(byte[], filename)` / `byte[] load(key)`. 구현·테스트 단순, `MultipartFile.getBytes()`·기존 프로필 이미지 방식과 동일 계열.
- **선택지 B (InputStream/Path 기반)**: 대용량 파일에 유리, 메모리 절약.
- **결정: A (byte[])**. 파일당 상한이 10MB로 작고(대용량 스트리밍 이점 없음), 프로젝트에 스트리밍 API 관용구가 없어 B는 과설계. 다운로드 응답도 `ResponseEntity<byte[]>`로 통일해 인터페이스 왕복이 대칭적이다.

### 쟁점 2 — 저장 키 생성 전략 (경로 탈출 방지)
- **결정**: `store()`는 서버가 `yyyy/MM/dd/<UUID>.<확장자>` 형태의 storageKey를 직접 생성해 반환한다. 원본 파일명은 storageKey에 전혀 반영하지 않는다(파일명 인젝션 원천 차단). `LocalDiskFileStorage`는 최종 절대경로를 `Path.normalize()`한 뒤 스토리지 루트로 시작하는지 검증하고, 벗어나면 `IllegalStateException`으로 방어(사용자 입력이 경로에 직접 들어가지 않으므로 실제로는 발동하지 않는 안전망 성격).
- **대안 기각**: 원본 파일명을 키에 포함(가독성 ↑)은 경로 탈출·특수문자 처리 부담이 커 기각.

### 쟁점 3 — 소프트 삭제된 notice의 첨부 API 접근 범위
- **문제**: "첨부 보존" 정책이 "소프트 삭제된 notice의 첨부도 계속 열람 가능"을 의미하는지 모호했다.
- **선택지 A**: notice가 `deleted=true`가 되어도 첨부 목록·다운로드는 계속 허용(파일 보존 = 접근도 보존).
- **선택지 B**: 첨부 API(업로드·목록·다운로드·삭제) 전체가 `NoticeService`의 기존 규칙과 동일하게 `findByIdAndDeletedFalse`로 notice 활성 여부를 먼저 검증 — 소프트 삭제된 notice는 모든 첨부 작업에서 404.
- **결정: B**. `NoticeService.getNotice()`가 이미 삭제된 notice를 404 처리해 "목록·상세·수정·삭제는 항상 `deleted=false` 필터"(CLAUDE.md)를 강제하고 있고, 현재 관리 화면에는 삭제된 notice를 조회하는 경로 자체가 없다. 첨부만 예외적으로 계속 노출하면 소프트 삭제의 의미가 깨진다. "보존"의 실제 의미는 **"notice 삭제가 첨부 삭제를 자동으로 캐스케이드하지 않는다"**로 한정한다 — 디스크 파일과 메타 행은 남아 향후 복구 기능이 생기면 재사용 가능하지만, 현재 API로는 도달 불가능해진다(=notice 자체와 동일한 접근성).
- **리뷰 1차 지적(⇒ 쟁점 10)**: 이 결정을 그대로 두면 notice 삭제 후 그 첨부를 영구히 지울 방법이 없어진다(무기한 오펀). 해소 방법은 쟁점 10에서 별도로 결정한다.

### 쟁점 4 — 업로드 시 "공지당 최대 5개" 동시성 보장
- **문제**: count-then-insert를 락 없이 하면 동시 업로드 2건이 모두 "4개"를 보고 통과해 6개가 될 수 있다(경미하지만 정책 위반).
- **선택지 A**: 검증 없이 애플리케이션 레벨 카운트만 신뢰(레이스 허용, 소프트 캡으로 문서화).
- **선택지 B**: 업로드 시 `NoticeRepository.findByIdAndDeletedFalseForUpdate(noticeId)` — Notice의 PATCH/DELETE가 쓰는 **기존 비관적 락**을 그대로 재사용해 notice 단위로 업로드를 직렬화.
- **결정: B**. 신규 락 인프라를 만들 필요 없이 기존 메서드를 재사용하며(추가 코드 없음), 쟁점 3에서 결정한 "notice 활성 검증"도 같은 호출로 동시에 해결된다(락 획득 자체가 존재·활성 검증). 부작용: notice PATCH/DELETE와 첨부 업로드가 같은 락을 다투므로 그 사이 짧은 대기가 생길 수 있으나, 공지 첨부 업로드는 저빈도 관리자 작업이라 체감 지연이 없다.
  - **[v2 정정, v4에서 재정정]** v1의 "동시 DELETE의 두 번째 요청은 항상 404가 된다"는 서술을 v2에서 "DB 삭제는 멱등적 no-op이라 안전하다"로 고쳤으나, 이 v2 서술도 부정확했다(리뷰 2차 지적 #4) — JPA/Hibernate의 엔티티 삭제는 예상 영향 행 수가 0이면(이미 다른 트랜잭션이 먼저 삭제) stale-state 계열 예외를 낼 수 있어, "안전한 no-op"이 보장되지 않는다.
  - **[v4 결정]** 첨부 **삭제도 업로드와 동일하게 notice 비관적 락(`findByIdAndDeletedFalseForUpdate`)을 먼저 획득**한 뒤 첨부 행을 조회·삭제한다. 즉 같은 notice에 대한 첨부 생성·삭제가 전부 이 락으로 직렬화되어, "두 트랜잭션이 동시에 같은 첨부 행을 읽고 각각 삭제를 시도"하는 경합 자체가 구조적으로 발생하지 않는다(두 번째 요청은 첫 번째 커밋이 끝난 뒤 락을 얻고, 그 시점에 `findByIdAndNoticeId`가 이미 없는 행을 정상적으로 404 처리). 목록 조회·다운로드는 읽기 전용이라 락이 불필요(변경 없음).
  - **[v2 추가]** `NoticeRepository.findByIdAndDeletedFalseForUpdate`의 Javadoc 주석("PATCH·DELETE 전용")을 "PATCH·DELETE·첨부 업로드·첨부 삭제 전용"으로 갱신해, 새 호출부가 생겼음을 코드에도 반영한다(리뷰 1차 지적 #7).

### 쟁점 5 — 확장자·Content-Type 검증 규칙
- **문제**: 브라우저·OS별로 같은 확장자에 다른 Content-Type을 선언한다(특히 hwp: `application/x-hwp`/`application/haansofthwp`/`application/octet-stream` 등 비표준·비일관). Content-Type을 확장자별로 1:1 엄격 대조하면 정상 파일이 오탐 거부될 위험이 크다.
- **결정**: 확장자를 1차 검증(화이트리스트 소속 여부, 대소문자 무시)으로 삼고, Content-Type은 "명백한 카테고리 불일치만 걸러내는" 보조 검증으로 삼는다. 확장자별 허용 Content-Type 집합에 **`application/octet-stream`을 공통으로 항상 포함**해 비표준 클라이언트의 제네릭 선언을 허용한다.

  | 확장자 | 허용 Content-Type (모두 `application/octet-stream` 추가 허용) |
  |---|---|
  | pdf | `application/pdf` |
  | doc | `application/msword` |
  | docx | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
  | xls | `application/vnd.ms-excel` |
  | xlsx | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
  | ppt | `application/vnd.ms-powerpoint` |
  | pptx | `application/vnd.openxmlformats-officedocument.presentationml.presentation` |
  | hwp | `application/x-hwp`, `application/haansofthwp` |
  | txt | `text/plain` |
  | csv | `text/csv`, `application/vnd.ms-excel` |
  | zip | `application/zip`, `application/x-zip-compressed` |
  | png | `image/png` |
  | jpg, jpeg | `image/jpeg` |
  | gif | `image/gif` |

  Content-Type이 `null`이면(드묾) 확장자 화이트리스트 통과만으로 허용(브라우저가 아예 값을 안 보내는 레거시 클라이언트 대응).

  **[v4 정정 — 리뷰 2차 지적 #3]** "null이면 화이트리스트 통과만으로 허용"이 v2까지는 검증 로직에서만 다뤄졌는데, `notice_attachment.content_type` 컬럼은 `NOT NULL`(쟁점 11)이라 null을 그대로 저장하면 커밋이 실패하고 `DataIntegrityViolationException`이 엉뚱하게 409로 노출된다. **업로드 서비스는 검증 통과 후 저장 직전에 `contentType == null ? "application/octet-stream" : contentType`으로 정규화한 값을 엔티티에 채운다** — 검증은 원본 null을 기준으로 하되(위 규칙 그대로), 영속화는 항상 non-null 값으로 한다.

  **[v2 명확화 — 리뷰 1차 지적 #4]** 이 검증(확장자+선언 Content-Type)은 파일 내용을 실제로 검사하지 않으므로 **스푸핑 방어 수단이 아니다** — v1의 "실행 차단이 실질적 방어선"이라는 표현은 과장이었기에 삭제한다. 이번 범위(Tika 등 매직바이트 검사 미도입)에서 정직하게 유지 가능한 보완책만 명시한다:
  - 저장 루트(`app.file-storage.root`)는 `src/main/resources/static`을 포함한 정적 리소스 경로·웹 루트 밖의 별도 디렉터리에 위치한다 — 어떤 URL 매핑으로도 직접 서빙되지 않고, 다운로드는 반드시 `NoticeAttachmentController`를 거친다.
  - 다운로드 응답은 `Content-Type: application/octet-stream` + `Content-Disposition: attachment`(브라우저 인라인 렌더링 차단) + **`X-Content-Type-Options: nosniff`**(브라우저의 MIME 스니핑에 의한 실행 유도 차단)를 함께 반환한다.
  - 이 세 가지는 "업로드된 악성 문서(매크로 포함 Office/HWP, 위장 실행파일 등)를 관리자가 다운로드해 로컬에서 직접 실행하는 상황" 자체는 막지 못한다는 한계를 인지하고 진행한다 — ADMIN·MANAGER 전용 신뢰된 관리자 간 파일 공유 기능이라는 위협 모델 하에서, 매직바이트 검사 도입(신규 의존성)은 이미 plan 모드에서 사용자가 명시적으로 보류하기로 결정한 사안이라 이번 라운드에서 재질문하지 않는다.

### 쟁점 6 — `notice_attachment.notice_id`를 FK로 걸 것인가
- **문제**: `Notice.authorId`는 논리적 스냅샷이라 FK가 없다(CLAUDE.md 명시). 첨부는 이 패턴을 따라야 하는가?
- **결정**: **DB 레벨 FK 제약을 건다** (`notice_attachment.notice_id → notice.id`, **`ON DELETE RESTRICT`** — notice는 하드 삭제 경로 자체가 없으므로 이 제약은 "미래에 실수로 하드 삭제 코드가 추가되더라도 첨부가 남아있으면 막아준다"는 안전망 성격). authorId와 달리 첨부는 진짜 부모-자식 구조적 관계이고, notice는 **하드 삭제가 없어**(소프트 삭제만 존재) FK가 걸려도 정상 운영 중 제약 위반이 발생할 일이 없다. 단, JPA 엔티티에는 `@ManyToOne` 연관관계 매핑을 쓰지 않고 **plain `Long noticeId` 컬럼**만 둔다 — 불필요한 프록시·N+1·양방향 연관관계 위험을 피하고(`@Setter`/`@Data` 금지 원칙과 같은 결의 판단), `Notice.java`를 전혀 건드리지 않는다.

### 쟁점 7 — 첨부 삭제/업로드 시 파일 I/O와 DB 트랜잭션의 정합성
- **문제(v1)**: 트랜잭션 커밋 전에 디스크 파일을 지우면, 이후 트랜잭션이 롤백될 때 DB에는 행이 남아 있는데 파일만 사라지는 불일치가 생긴다.
- **[v2 정정 — 리뷰 1차 지적 #2, #3]** v1은 삭제 경로는 AFTER_COMMIT으로 올바르게 설계했지만, 업로드 경로의 정리 로직을 `try { noticeAttachmentRepository.save(...) } catch (RuntimeException e) { fileStorage.delete(key); throw e; }`로 서술했다. 이는 `save()` 호출 자체(즉시 INSERT)에서 던져지는 예외만 잡을 뿐, Hibernate가 flush를 지연하거나(트랜잭션 종료 시점 일괄 flush) 커밋 단계에서 제약 위반이 나는 경우를 잡지 못한다 — 그 경우 트랜잭션은 롤백되지만 파일은 이미 디스크에 남는다.
- **결정(업로드)**: `fileStorage.store()` 성공 직후, 같은 트랜잭션에 `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { afterCompletion(int status) { if (status != STATUS_COMMITTED) fileStorage.delete(key); } })`를 등록한다. `save()` 자체의 즉시 예외든, flush·커밋 단계의 지연 예외든 트랜잭션이 커밋되지 못하는 모든 경로를 이 콜백 하나로 커버한다.
- **결정(삭제)**: notice 비관적 락을 먼저 획득한 뒤(쟁점 4 v4 결정) 첨부 행을 `findByIdAndNoticeId`로 조회·삭제하고, 동일하게 `TransactionSynchronizationManager.registerSynchronization()`으로 **커밋 후(afterCommit, 곧 `status == STATUS_COMMITTED`)에만** 실제 파일을 삭제한다 — `AdminSessionRevokeListener` 등 기존 AFTER_COMMIT 패턴과 같은 원칙. 이번 건은 호출부가 단 하나(`NoticeAttachmentService.delete`)뿐이라 별도 도메인 이벤트 클래스·리스너로 분리하지 않고 서비스 메서드 내 동기화 콜백으로 처리한다(이벤트/리스너 분리는 이 규모에 과설계).
- **결정(삭제 실패 시 계약)**: `afterCommit` 콜백 안에서 `fileStorage.delete()`가 예외를 던지면 **`AdminSessionRevokeListener`와 동일한 방식**으로 try/catch + `log.error("첨부 파일 삭제 실패 — 수동 정리 필요. attachmentId={}, storageKey={}", ..., e)`로 기록하고 예외를 전파하지 않는다(리뷰 1차 지적 #3). DB 삭제는 이미 커밋되어 되돌릴 수 없으므로, 여기서 예외를 던져 500을 반환해도 클라이언트가 오해(삭제가 실패한 줄 알고 재시도)할 뿐 실질적 도움이 안 된다 — 로그로 운영자가 사후에 디스크를 정리하는 것이 유일한 현실적 복구 경로임을 문서화한다(자동 재시도·outbox 패턴은 이번 범위에서 도입하지 않음 — 저빈도 관리자 기능에 과한 인프라).
- **[v4 추가 — 리뷰 2차 지적 #5]** `registerSynchronization()` 호출 자체가(극히 드물지만) 실패할 경우 방금 저장한 파일이 정리되지 않고 남는다. 업로드 경로에서 `fileStorage.store()` 직후 `registerSynchronization()` 호출을 try/catch로 감싸, 등록 자체가 실패하면 `catch` 블록에서 즉시 `fileStorage.delete(key)`를 호출하고 원래 예외를 다시 던진다(등록 실패는 곧 트랜잭션 상태를 신뢰할 수 없다는 뜻이므로, 이 경로는 성공으로 진행하지 않는다).
- **[v4 추가 — 리뷰 2차 지적 #5]** 위 결정들은 Mockito 기반 서비스 단위 테스트만으로는 "실제 커밋/롤백 시점에 콜백이 정확히 호출되는가"까지 증명하지 못한다(`TransactionSynchronizationManager`는 실제 트랜잭션 컨텍스트가 있어야 동작). 따라서 실제 트랜잭션을 사용하는 **통합 테스트 `NoticeAttachmentTransactionIntegrationTest`**(`@SpringBootTest` 또는 `@Transactional` 롤백에 의존하지 않는 실제 커밋 기반 테스트 — 파일 정리는 커밋 이후에만 실행되므로 `@Transactional` 테스트의 자동 롤백과는 검증 목적이 다르다)를 신규 추가해 다음을 검증한다: 업로드 커밋→파일 유지, 업로드 실패(강제 롤백)→파일 삭제, 첨부 삭제 커밋→파일 삭제, 첨부 삭제 실패(강제 롤백)→파일 유지.

### 쟁점 8 — multipart 전역 설정값
- **발견된 갭**: `application.yml`/`application-dev.yml` 어디에도 `spring.servlet.multipart.*` 설정이 없어 Spring Boot 기본값(파일 1MB)에 의존 중이었다 — 기존 프로필 이미지 2MB 검증과도 이미 불일치했던 갭.
- **결정**: `spring.servlet.multipart.max-file-size: 10MB`, `max-request-size: 15MB`. 업로드 API가 **요청당 파일 1개**만 받으므로(공지당 5개 상한은 "여러 번 호출"로 채우는 구조, 배치 업로드 아님) `max-request-size`는 파일 5개 합산(50MB)이 아니라 파일 1개 + multipart 오버헤드만 커버하면 된다 — 여유를 둬 15MB.
- **부수 조치**: `GlobalApiExceptionHandler`에 `MaxUploadSizeExceededException` 핸들러를 추가해 400 `INVALID_REQUEST`로 응답한다(현재는 이 예외가 매핑되지 않아 500 `INTERNAL_ERROR`로 떨어지는 기존 갭도 같이 메운다 — 이 예외는 `MultipartException`의 하위 타입이라 `Exception` 폴백 이전에 더 구체적인 핸들러가 필요).

### 쟁점 9 — `FileStorageProperties` 설정 주입 방식
- **선택지 A**: `@ConfigurationProperties(prefix = "app.file-storage")` 신규 도입.
- **선택지 B**: 기존 `PasswordResetService` 패턴(`@Value` + 명시적 생성자, Lombok `@RequiredArgsConstructor`와 `@Value` 병용 불가 문제 회피).
- **결정: A**. 프로젝트에 `@ConfigurationProperties`가 아직 없지만 Spring Boot 표준 관용구이고, 향후 스토리지 설정이 늘어날 가능성(허용 확장자 외부화 등, 이번 범위 밖이지만 확장 여지)을 고려하면 타입 세이프한 프로퍼티 클래스가 유리하다. 단일 프로퍼티라 B도 무방하지만, `@Value` + 수동 생성자는 프로퍼티가 늘 때마다 보일러플레이트가 증가해 A를 택한다.

### 쟁점 10 (v2 신규, v4 재작성) — 저장소 영속성·배포 토폴로지
- **문제 (리뷰 1차 지적 #5)**: `docker-compose.dev.yml`의 `app` 서비스에 볼륨이 전혀 없음을 실측 확인했다(`db`만 `db_data_dev` 볼륨 보유). `make dev-up`으로 앱을 컨테이너에서 실행하면, 컨테이너를 재생성(`docker compose up --build`, `down` 후 `up` 등)할 때 컨테이너 내부에 저장된 첨부파일이 전부 소실된다.
- **[v2 결정, v4에서 결함 발견]** v2는 `docker-compose.dev.yml`의 `app` 서비스에 named volume(`notice_attachments_dev:/app/data/attachments`)을 추가하고, `application-dev.yml`의 `app.file-storage.root` 기본값을 이 컨테이너 경로로 고정하는 방향으로 결정했다. 그런데 **이 프로젝트의 기본 활성 프로파일이 `dev`**(`application.yml` L5, `${SPRING_PROFILES_ACTIVE:dev}`)이므로, `application-dev.yml`에 컨테이너 전용 절대경로(`/app/data/attachments`)를 고정하면 `./gradlew bootRun`으로 로컬에서 직접 띄울 때도 같은 경로를 쓰게 되어 존재하지 않는 `/app/...` 경로에 쓰기를 시도해 실패한다(리뷰 2차 지적 #1) — v2가 같은 문단에서 약속한 "로컬은 `./data/attachments`"와 실제로 양립하지 않는 모순이었다.
- **[v4 결정]** 경로 설정을 프로파일 파일이 아니라 **환경변수로 분리**한다:
  - `application.yml`(공통)에 `app.file-storage.root: ${APP_FILE_STORAGE_ROOT:./data/attachments}` — 기본값은 프로젝트 상대 경로. `./gradlew bootRun`은 환경변수를 안 주므로 자동으로 이 기본값을 쓴다.
  - `docker-compose.dev.yml`의 `app` 서비스 `environment`에 `APP_FILE_STORAGE_ROOT=/app/data/attachments`만 추가한다(volume 마운트 지점과 동일 경로). `application-dev.yml`은 건드리지 않는다.
  - `./gradlew bootRun`으로 로컬 실행할 때 기본 경로(`./data/attachments`)는 `.gitignore`에 추가.
- **[v4 신규 결정 — 리뷰 2차 지적 #1]** Dockerfile을 실측한 결과 `WORKDIR /app` → `RUN useradd -m appuser` → `USER appuser` 순서로, **저장 디렉터리를 미리 만들거나 소유권을 넘기는 단계가 없다**. 빈 named volume의 루트는 기본적으로 root 소유로 마운트되므로, 비root 사용자(`appuser`)로 전환된 애플리케이션 프로세스가 그 안에 디렉터리를 만들거나 파일을 쓰려다 `AccessDeniedException`으로 실패할 수 있다. **`Dockerfile`을 수정해 `USER appuser` 전환 이전에** `RUN mkdir -p data/attachments && chown -R appuser:appuser /app`를 추가한다 — Docker는 named volume이 처음 마운트될 때 이미지 쪽 대상 디렉터리에 내용(및 소유권)이 있으면 그대로 volume에 복사하므로, 이 순서면 volume도 `appuser` 소유로 초기화된다.
- **다중 인스턴스 배포**: 로컬 디스크 기반 구현은 단일 인스턴스 전제다(인스턴스 간 파일 공유 없음). 현재 프로젝트에 prod 프로파일 자체가 없고(로드맵 ⑤ 별건, "prod 프로파일 부활" 시 별도 검토) 수평 확장 계획도 없으므로, 이번 범위에서는 **단일 인스턴스 전제를 설계 제약으로 명문화**하고 별도 대응(S3 등 원격 스토리지, 공유 볼륨)은 로드맵 ⑤ 시점 재평가 항목으로 남긴다.

### 쟁점 11 (v2 신규) — 스키마 세부 명세
- **문제 (리뷰 1차 지적 #9)**: v1에는 컬럼·제약·인덱스의 구체적 정의가 없었다.
- **결정**: `V10__create_notice_attachment.sql`에 다음을 명시한다 (`V8__create_notice.sql` 스타일 미러 — 백틱 식별자, `ENGINE=InnoDB`, `utf8mb4`):
  - `id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`
  - `notice_id BIGINT NOT NULL` + `CONSTRAINT fk_notice_attachment_notice_id FOREIGN KEY (notice_id) REFERENCES notice(id) ON DELETE RESTRICT`
  - `original_filename VARCHAR(255) NOT NULL`
  - `content_type VARCHAR(100) NOT NULL`
  - `file_size BIGINT NOT NULL`
  - `storage_key VARCHAR(255) NOT NULL` + `CONSTRAINT uk_notice_attachment_storage_key UNIQUE (storage_key)` (동일 키 재사용 시 오펀·덮어쓰기를 DB 레벨에서도 차단)
  - `create_date DATETIME(6) DEFAULT NULL` (Notice와 동일하게 nullable — 애플리케이션이 채움)
  - `KEY idx_notice_attachment_notice_id_id (notice_id, id)` — 목록 조회(`findByNoticeIdOrderByIdAsc`) 정렬에 맞춘 복합 인덱스(FK가 자동 생성하는 단일 컬럼 인덱스만으로는 정렬까지 최적화되지 않음)

  **[v4 추가 — 리뷰 2차 지적 #3]** `original_filename VARCHAR(255)`은 정의돼 있었지만 애플리케이션 레벨 길이 검증이 빠져 있었다. 원본 파일명이 255자를 초과하면 서비스가 저장을 시도하기 전에 **400(`InvalidRequestException`)으로 거부**한다 — 그대로 저장을 시도하면 DB가 커밋 시점에 거부하고, 그 예외가 `GlobalApiExceptionHandler.handleDataIntegrityViolation()`을 거쳐 "중복된 데이터..." 409 메시지로 잘못 노출된다(원인은 중복이 아니라 길이 초과인데 메시지가 오도함). `content_type` NOT NULL과 null 정규화는 쟁점 5 [v4 정정] 참조.

### 쟁점 12 (v2 신규, v4 알고리즘 재정의) — `LocalDiskFileStorage` 하드닝
- **문제 (리뷰 1차 지적 #10)**: v1의 `normalize()` + `startsWith(root)` 검사만으로는 심볼릭 링크를 통한 루트 탈출, UUID 충돌 시 기존 파일 덮어쓰기, 쓰기 도중 프로세스 종료로 인한 부분 파일을 방어하지 못한다.
- **결정**:
  - `store()`/`load()`/`delete()` 세 메서드 모두 동일한 루트 검사 로직을 공용 private 메서드로 통일한다(중복 방지).
  - 경로 검증은 `Path.normalize()`가 아니라 **`Path.toRealPath()`**(심볼릭 링크까지 실제로 해석)로 최종 경로를 구하고, 이 결과가 스토리지 루트의 `toRealPath()` 하위인지 확인한다. `store()`는 파일이 아직 없는 시점이라 `toRealPath()`가 예외를 던질 수 있으므로, 상위 디렉터리까지만 `toRealPath()`로 검증하고 파일명은 서버 생성 UUID이므로 추가 검증이 불필요함을 코드 주석으로 남긴다.
  - 다운로드 응답의 `Content-Disposition` 헤더는 수동 문자열 결합 대신 Spring의 `ContentDisposition.builder("attachment").filename(originalFilename, StandardCharsets.UTF_8).build()`를 사용한다(RFC 5987 인코딩을 프레임워크에 위임, 헤더 인젝션 여지 제거).
  - **[v4 재정의 — 리뷰 2차 지적 #2]** v2는 "`store()`가 `CREATE_NEW`로 UUID 충돌 시 덮어쓰기를 막는다"와 "임시 파일에 쓴 뒤 `ATOMIC_MOVE`로 원자적 rename한다"를 같은 문단에 나란히 서술했는데, **`CREATE_NEW`가 임시 경로·최종 경로 중 어디에 적용되는지, 최종 대상이 이미 있을 때 덮어쓰지 않는다는 보장이 어디서 나오는지가 정의되지 않은 모순**이었다(리뷰 2차 지적). v4는 이를 "임시파일 `CREATE_NEW` + 최종 경로 `ATOMIC_MOVE`(REPLACE_EXISTING 미지정)"로 재정의했었다.
  - **[v5 재정의 — 리뷰 3차 지적, 차단]** v4의 재정의도 틀렸다 — Java 17 `Files.move` 공식 문서상 `ATOMIC_MOVE`를 지정하면 **다른 옵션은 전부 무시되며, 대상이 이미 존재할 때 교체할지 실패할지는 구현체(OS/파일시스템) 의존적**이라 "REPLACE_EXISTING 미지정 = 항상 예외"라는 보장이 성립하지 않는다. 확률은 낮지만 UUID가 충돌하면 실행 환경에 따라 기존 정상 첨부가 조용히 덮어써질 수 있어, 계획이 명시적으로 지키려던 데이터 무결성 계약과 정면으로 충돌한다. 임시파일+move 구조를 폐기하고 최종 경로에 직접 `CREATE_NEW`로 쓰는 방식으로 단순화했다(v6에서 정리 로직 보완, 아래 참조).
  - **[v6 보완 — 리뷰 4차 지적, 차단]** v5는 "`store()` 실패 시 파일이 미완성이거나, 완성돼도 DB 행이 없어 무해하다"로 결론지었는데, 이는 **다운로드 접근 불가**(DB 행 없음)만 입증할 뿐 **디스크 용량**까지 안전하다는 뜻은 아니었다 — Java 17 `Files.write`는 파일을 만든 뒤 또는 일부 바이트를 쓴 뒤에도 I/O 오류(디스크 부족 등, 프로세스는 살아있는 정상적 실패)를 던질 수 있어, 정리 없이는 실패한 업로드마다 최대 10MB 잔여 파일이 쌓이고 그 storageKey는 DB에 없어 정상 삭제 경로로도 지울 수 없다. 최종 알고리즘을 다음으로 확정한다:
    1. 날짜 샤딩 디렉터리(`yyyy/MM/dd/`)를 `Files.createDirectories()`로 사전 생성.
    2. `Files.newOutputStream(targetPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)`로 스트림을 연다. **이 호출 자체가 실패하면(대상이 이미 존재 — `FileAlreadyExistsException` 등) 아무것도 삭제하지 않는다** — 이 호출이 만든 파일이 아닐 수 있어서다(`Files.write` 문서상 이 예외 타입은 "optional specific exception"이라 모든 파일시스템 제공자가 정확히 이 하위 타입을 던진다고 단정할 수 없다 — 예외 종류로 분기하지 않고 "스트림 오픈 성공 여부"로만 정리 여부를 판단).
    3. 스트림이 성공적으로 열린 시점부터 `createdByThisCall = true`로 표시하고 바이트를 쓴 뒤 스트림을 닫는다.
    4. 3단계(쓰기 또는 close)에서 예외가 나면, `createdByThisCall`이 true일 때만 `Files.deleteIfExists(targetPath)`로 이 호출이 만든 부분 파일을 즉시 best-effort 정리한다. 정리 자체가 실패하면 storageKey를 포함해 로그만 남기고 원래 예외를 그대로 던진다(무한 재시도 없음).
    5. "무덮어쓰기" 보장은 2단계의 `CREATE_NEW`에서 나온다(대상이 이미 존재하면 항상 실패 — Java API가 크로스플랫폼으로 보장). "디스크 누적 방지"는 4단계의 즉시 정리에서 나온다. 프로세스 강제 종료처럼 애플리케이션이 스스로 정리할 수 없는 경우(4단계 자체가 실행되지 못함)만 기존 "오펀 파일" 리스크(디스크 I/O 실패는 로그로만 확인 가능, 정기 정리 배치는 범위 밖)로 남는다 — 정상적으로 포착 가능한 쓰기 예외는 전부 이 알고리즘이 정리한다.

### 쟁점 13 (v2 신규) — REST 세부 조정
- **문제 (리뷰 1차 지적 #8)**: 다운로드 경로에 동사(`download`)가 들어가 CLAUDE.md RESTful 규칙 위반, 첨부 개수 초과의 상태 코드가 부적절.
- **결정**:
  - `GET /{attachmentId}/download` → **`GET /{attachmentId}/content`**로 변경(자원의 "내용"이라는 명사).
  - 공지당 첨부 5개 초과 시도는 400(`InvalidRequestException`)이 아니라 **409(`ConflictException`, `RESOURCE_CONFLICT`)**로 변경 — "요청 자체의 형식 오류"가 아니라 "현재 리소스 상태(이미 5개 보유)와의 충돌"이라 CLAUDE.md 상태 코드 규칙(409=상태 충돌·중복)에 더 부합한다.
  - 10MB 초과(`MaxUploadSizeExceededException`)는 **400 유지**(기각 사유는 8b 참조 — 프로젝트가 413을 쓰지 않는 기존 컨벤션과의 일관성).
  - 컬렉션 경로는 `NoticeController`와 동일하게 `@RequestMapping("/admin/api/notices/{noticeId}/attachments")` + 메서드 레벨 `@GetMapping`/`@PostMapping`(경로 접미사 없음, trailing slash 없음)으로 통일한다 — v1 문서의 `/` 표기는 실제 코드가 아닌 표기 오류였다.

### 쟁점 14 (v2 신규, v3에서 결정 완료) — 소프트 삭제된 notice의 첨부 오펀 처리

쟁점 3에서 "소프트 삭제된 notice의 첨부는 API로 도달 불가능"으로 결정했는데, 이 상태에서 첨부를 지울 방법도 함께 사라진다(리뷰 1차 지적 #1 — [차단]). 복구 기능도 정리 배치도 이번 범위에 없으므로, 그대로 두면 "보존 정책"이 아니라 "무기한 오펀 정책"이 된다.

**결정 (v3, 사용자 승인)**: **notice 삭제 시 첨부가 남아있으면 409로 차단**한다. `NoticeService.deleteNotice()`에 `NoticeAttachmentRepository`를 생성자 주입으로 추가하고, 기존에 이미 획득한 비관적 락(`findByIdAndDeletedFalseForUpdate`) 트랜잭션 안에서 `softDelete()` 호출 직전에 `noticeAttachmentRepository.countByNoticeId(id) > 0`이면 `ConflictException("첨부파일이 남아있어 삭제할 수 없습니다. 첨부를 먼저 삭제해주세요.")`를 던진다. 관리자는 이번에 만드는 첨부 삭제 UI로 먼저 정리한 뒤에만 notice를 삭제할 수 있어 **오펀이 애초에 발생하지 않는다**. 이 결정으로 v1의 "NoticeService 수정 없음" 선언은 무효화되며, 변경 범위는 생성자 주입 1개 + 카운트 검사 1줄로 작고 명확하다. 기존 `NoticeService`의 다른 로직(생성·수정·조회)은 무변경.

**파급**: `NoticeServiceTest`에 "첨부가 있는 notice 삭제 시도 → 409" 케이스 추가 필요.

---

## 구현 파일

**신규**
- `src/main/java/com/cms/common/storage/FileStorage.java` (인터페이스)
- `src/main/java/com/cms/common/storage/LocalDiskFileStorage.java` (구현, `@Component`)
- `src/main/java/com/cms/common/storage/FileStorageProperties.java` (`@ConfigurationProperties`, `@EnableConfigurationProperties` 등록 필요)
- `src/main/java/com/cms/admin/notice/domain/NoticeAttachment.java`
- `src/main/java/com/cms/admin/notice/repository/NoticeAttachmentRepository.java`
- `src/main/java/com/cms/admin/notice/service/NoticeAttachmentService.java` (+ 정책 상수: 허용 확장자→Content-Type 맵, `MAX_FILE_SIZE`, `MAX_COUNT_PER_NOTICE`)
- `src/main/java/com/cms/admin/notice/controller/NoticeAttachmentController.java`
- `src/main/java/com/cms/admin/notice/dto/response/NoticeAttachmentResponse.java`
- `src/main/resources/db/migration/V10__create_notice_attachment.sql`
- 테스트: `LocalDiskFileStorageTest`, `NoticeAttachmentServiceTest`, `NoticeAttachmentControllerTest`, `NoticeAttachmentTransactionIntegrationTest`(v4 신규 — 실제 트랜잭션 커밋/롤백 기반 파일-DB 정합성 검증, 쟁점 7 참조)

**수정**
- `src/main/java/com/cms/admin/log/constant/AdminActionTypes.java` — `NOTICE_ATTACHMENT_UPLOAD`, `NOTICE_ATTACHMENT_DELETE` 상수 + `ALL` 추가
- `src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java` — `MaxUploadSizeExceededException` 핸들러
- `src/main/resources/application.yml` — multipart 한도, `app.file-storage.root: ${APP_FILE_STORAGE_ROOT:./data/attachments}` (쟁점 10 v4 — `application-dev.yml`은 건드리지 않음)
- `src/main/resources/templates/admin/notice/manage.html` — 첨부 업로드/목록/다운로드/삭제 UI
- `docker-compose.dev.yml` — `app` 서비스에 `notice_attachments_dev` named volume + `APP_FILE_STORAGE_ROOT` 환경변수 추가 (쟁점 10)
- `Dockerfile` — `USER appuser` 전환 전 저장 디렉터리 생성·소유권 이전(`mkdir -p data/attachments && chown -R appuser:appuser /app`) 추가 (쟁점 10, v4 신규 — 리뷰 2차 지적 #1)
- `.gitignore` — 로컬 실행 시 기본 저장 경로(`./data/attachments`) 제외 추가
- `src/main/java/com/cms/admin/notice/repository/NoticeRepository.java` — `findByIdAndDeletedFalseForUpdate` Javadoc 갱신("PATCH·DELETE·첨부 업로드 전용")
- `src/main/java/com/cms/admin/notice/service/NoticeService.java` — `deleteNotice()`에 첨부 존재 시 409 차단 로직 추가(쟁점 14, `NoticeAttachmentRepository` 생성자 주입)
- `src/main/java/com/cms/admin/notice/service/NoticeServiceTest.java` — "첨부가 있는 notice 삭제 시도 → 409" 케이스 추가(쟁점 14)

**수정 없음(확정)**: `SecurityConfig`(인가 정책 무변경), `Notice.java`(엔티티 자체는 FK만 참조, 필드 추가 없음).

### API 설계

`@RestController @RequestMapping("/admin/api/notices/{noticeId}/attachments")`, 전 메서드 `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")`

| HTTP | 경로 | 설명 | 성공 |
|---|---|---|---|
| POST | (기본) | `@RequestPart("file") MultipartFile`, `consumes=MULTIPART_FORM_DATA_VALUE` | 201 + Location + `NoticeAttachmentResponse` |
| GET | (기본) | 첨부 메타 목록 | 200 `List<NoticeAttachmentResponse>` |
| GET | `/{attachmentId}/content` | 파일 바이너리 (v2: `/download` → `/content`, 쟁점 13) | 200 `ResponseEntity<byte[]>` + `Content-Disposition` + `X-Content-Type-Options: nosniff` |
| DELETE | `/{attachmentId}` | 행+파일 삭제, `NoticeAttachmentResponse` 반환(컨트롤러가 버림, 감사로그 targetId 추출용 — 쟁점 12 리뷰 지적 #12) | 204 |

- 다운로드 파일명: Spring `ContentDisposition` 빌더로 생성(쟁점 12).
- **[v4 표기 정정]** 다운로드·삭제는 `NoticeAttachmentRepository.findByIdAndNoticeId(attachmentId, noticeId)` 복합조건으로 조회해, 다른 notice의 attachmentId를 잘못된 부모 URI로 접근하는 것을 차단한다(IDOR 방지, 리뷰 1차 지적 #6). 목록은 attachmentId 자체가 없는 단순 목록 조회라 `findByNoticeIdOrderByIdAsc(noticeId)`를 쓴다(v2 문서의 "목록도 findByIdAndNoticeId" 서술은 표기 오류).
- notice 미존재/삭제 → 전 엔드포인트 404(쟁점 3). notice에 첨부가 남아있으면 notice 자체의 `DELETE`는 409(쟁점 14) — 첨부가 먼저 모두 삭제된 뒤에만 notice soft-delete가 가능해, 이 404 규칙이 가리키는 "삭제된 notice의 첨부"는 애초에 존재하지 않는다.
- 첨부 5개 초과 → 409(쟁점 13). 10MB 초과 → 400(쟁점 13).

---

## 작업 단계 (의존 방향 안쪽부터)

1. Flyway `V10__create_notice_attachment.sql` 작성 (FK 포함, `V8__create_notice.sql` 스타일 미러)
2. `FileStorage`/`LocalDiskFileStorage`/`FileStorageProperties` (도메인 무관 공통 계층)
3. `NoticeAttachment` 엔티티 + `NoticeAttachmentRepository`
4. `AdminActionTypes` 상수 추가
5. `NoticeAttachmentService` (+ `NoticeAttachmentResponse`)
6. `NoticeAttachmentController`
7. `GlobalApiExceptionHandler` 핸들러 추가, `application.yml`/`application-dev.yml` 설정
8. `manage.html` 첨부 UI
9. 테스트 3종 + `NoticeAttachmentTransactionIntegrationTest`(v4 신규) 작성
10. `docker-compose.dev.yml`/`Dockerfile` 볼륨·권한 반영 (쟁점 10)
11. `./gradlew compileJava` 단계별 확인, `./gradlew test` 전체 통과
12. playwright 골든 패스 + 회귀 확인 + `make dev-up` 컨테이너 재생성 후 첨부 유지·쓰기 권한 확인

## 리스크

- **디스크 I/O 실패**(권한·용량)는 애플리케이션 예외로만 처리되고 별도 알림 체계가 없음 — 기존 프로젝트에 파일시스템 모니터링이 없으므로 이번 범위에서 추가하지 않음(로그로만 확인 가능하다는 한계를 인지하고 진행).
- **오펀 파일**: 업로드 트랜잭션이 커밋되지 못하면(쟁점 7의 `afterCompletion` 콜백으로) 방금 쓴 파일을 정리하고, `store()` 자체의 쓰기 실패도 즉시 best-effort 정리한다(쟁점 12 v6). 이 두 정리 로직 자체가 실패하는 경우(디스크 오류) 또는 프로세스 강제 종료처럼 정리 코드가 아예 실행되지 못하는 경우에만 오펀이 남을 수 있음 — best-effort로 명시, 정기 정리 배치는 범위 밖.
- **비관적 락 경합**: 첨부 업로드가 Notice 행 락을 Notice PATCH/DELETE와 공유해 순간적 대기 가능 — 관리자 저빈도 작업이라 실질 영향 낮음으로 판단.
- **약한 파일 검증**(쟁점 5): 매크로 포함 문서·위장 파일이 업로드될 수 있으나 다운로드는 실행되지 않는 형태(`octet-stream`+`attachment`+`nosniff`)로만 제공됨 — 관리자가 로컬에서 직접 실행하는 상황까지는 막지 못함(수용된 위협 모델).
- **byte[] 메모리 사용**(리뷰 1차 지적 #11): 파일당 10MB 상한 × 관리자 전용 저빈도 동시 요청이라는 특성상, 실질적 힙 부담은 "동시 요청 수 × 10MB" 수준으로 낮게 유지될 것으로 판단 — 별도 스트리밍 전환 없이 진행.

## 완료 기준

- `./gradlew test` 전체 통과(`AdminActionTypeSyncTest`, `AdminPageAnnotationConventionTest` 포함)
- 빈 DB `bootRun`(dev) 기동 → Flyway V10 적용, `ddl-auto: validate` 통과
- `make dev-up` 컨테이너 재생성 후에도 첨부파일이 유지되고, `appuser`로 정상 업로드(쓰기 권한) 가능함을 확인(쟁점 10)
- playwright: ADMIN·MANAGER 각각 업로드→목록→다운로드→삭제 골든 패스
- 허용 외 확장자 업로드 시 400 / 10MB 초과 시 400 / 6번째 파일 업로드 시 409 / 255자 초과 파일명 업로드 시 400 확인
- notice에 첨부가 남아있는 상태에서 notice 삭제 시도 → 409 확인(쟁점 14)
- 소프트 삭제된 notice의 첨부 API 접근 정책이 쟁점 3·14 결정대로 동작함을 확인
- 다른 notice의 attachmentId로 접근 시 404(IDOR 차단) 확인
- `NoticeAttachmentTransactionIntegrationTest` 통과(업로드/삭제 커밋·롤백 시 파일 상태 검증, 쟁점 7)
- 감사 로그에 `NOTICE_ATTACHMENT_UPLOAD`/`NOTICE_ATTACHMENT_DELETE` targetId 포함 기록 확인
- 기존 공지 CRUD 화면 회귀 없음

---

## 결정 완료 — 쟁점 14 (v3)

사용자가 **A안(notice 삭제 시 첨부가 남아있으면 409로 차단)**을 승인했다. 상세 내용은 위 "쟁점 14" 섹션 참조. 이 결정으로 `NoticeService.deleteNotice()`가 변경 대상에 포함된다(구현 파일 목록 참조).

---

## 구현·검증 결과 (2026-07-22, feat/notice-attachment)

**Context**: 계획 v6(`/plan-review-loop` 5라운드 — codex no-ship 4회 + ship 1회, 지적 12→5→1→1→0 수렴) 그대로 구현. 정책·스키마·`NoticeService.deleteNotice()` 변경은 plan 모드에서 v4·v5 두 차례 승인 완료.

**핵심 확정 사항**: 계획 v6과 동일 — 설계 이탈 없음. 구현 자체는 계획서의 알고리즘(최종 경로 직접 `CREATE_NEW`, notice 락 재사용, `TransactionSynchronizationManager` 기반 정리)을 그대로 코드화했다.

**구현 파일**:
- 신규: `V10__create_notice_attachment.sql`, `common/storage/{FileStorage, LocalDiskFileStorage, FileStorageProperties}`, `notice/domain/NoticeAttachment`, `notice/repository/NoticeAttachmentRepository`, `notice/service/NoticeAttachmentService`, `notice/controller/NoticeAttachmentController`, `notice/dto/response/{NoticeAttachmentResponse, NoticeAttachmentDownload}`
- 신규 테스트: `LocalDiskFileStorageTest`(7케이스, 심볼릭 링크 1건은 Windows 권한 제약으로 skip), `NoticeAttachmentServiceTest`(19케이스), `NoticeAttachmentControllerTest`(16케이스), `NoticeAttachmentTransactionIntegrationTest`(4케이스, 실제 트랜잭션 커밋/롤백)
- 수정: `NoticeService.deleteNotice()`(첨부 존재 시 409), `NoticeServiceTest`(+1케이스), `NoticeRepository`(Javadoc), `AdminActionTypes`(+2 상수), `admin/log/manage.html`(라벨 맵 동기화 — 계획에 없던 파급, 아래 이슈 1), `GlobalApiExceptionHandler`(`MaxUploadSizeExceededException`), `application.yml`(multipart+file-storage.root), `docker-compose.dev.yml`(볼륨+env), `Dockerfile`(mkdir+chown), `.gitignore`, `admin/notice/manage.html`(첨부 UI)

**검증 결과**:
- `./gradlew test` 전체 통과(신규 46케이스 포함). 빈 DB Flyway V10 적용 + `ddl-auto: validate` 통과.
- Playwright 실기 검증(ADMIN·MANAGER 각각, 실제 로그인 세션): 새 공지 생성 → 첨부 업로드(.txt 성공, .exe 확장자 거부 400) → 다운로드(바이트 일치, `Content-Disposition`/`X-Content-Type-Options: nosniff`/`application/octet-stream` 헤더 확인) → 첨부 5개 채운 뒤 6번째 409 확인 → 첨부 존재 상태에서 notice 삭제 시도 409 확인("첨부파일이 남아있어 삭제할 수 없습니다") → 첨부 전부 삭제 후 notice 삭제 성공 → 디스크(`./data/attachments`)에 잔여 파일 없음(afterCommit 정리 실동작 확인) → 감사 로그에 업로드/삭제 성공·실패 모두 targetId 포함 기록 → MANAGER 역할로 동일 첨부(작성자 admin) 조회·삭제 가능 확인(소유권 없음 정책과 일치) → 기존 화면(대시보드·공지 CRUD) 회귀 없음.
- 테스트용 MANAGER 계정(`manager01`)을 dev DB에 생성해 검증(기존에 ADMIN 계정만 존재).

**이슈**:
1. **계획에 없던 감사 로그 화면 파급**: `AdminActionTypes.ALL`에 상수 2개를 추가하자 `AdminActionTypeLabelSyncTest`가 실패 — `admin/log/manage.html`의 `ACTION_TYPE_LABELS` JS 맵(라벨 단일 출처)에 대응 라벨이 없었던 것. 라벨 2개 추가로 해결. 계획서 작성 시 이 동기화 테스트의 존재를 놓쳤던 것이 원인 — 향후 `AdminActionTypes` 확장 작업의 표준 체크리스트에 추가할 만함.
2. **UI 버그 발견·수정(구현 중)**: 새 공지를 저장한 직후(생성 모드 → 조회 모드 전환) 첨부 섹션이 표시되지 않는 결함을 playwright 검증 중 발견 — `btnSave` 클릭 핸들러가 기존 공지 상세 조회(`openDetailModal`)에만 `showAttachmentSection()`을 걸어두고 신규 생성 저장 성공 경로에는 호출을 빠뜨렸던 것. `wasCreate` 플래그로 수정하고 재검증까지 완료.
3. **devtools 트러블슈팅**: `bootRun` 중 템플릿 소스만 수정하면 devtools가 재시작을 감지하지 못하는 함정을 겪음(`build/resources/main` 미갱신) — `docs/troubleshooting.md`(빌드/의존성)에 기록.

**후속**: 없음. 로드맵 ③(공개 공지 페이지)·⑤(prod 프로파일)는 이 작업과 독립적으로 착수 가능. 프로필 이미지의 `FileStorage` 이관은 여전히 별도 후속(기존 데이터 마이그레이션 수반).
