# CLAUDE.md — com.cms.admin.contentimage

이 디렉터리(편집기 본문 이미지) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조. 설계 결정·적대적 리뷰 7라운드 기록은 `adversarial-review/plan/PLAN-html-editor.md`.

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

- **세 테이블(V24)**: `content_image`(파일 메타·업로더 userId 스냅샷), `content_image_ref`(어느 콘텐츠가 어느 이미지를 참조하는지 — `(owner_type, owner_id, image_id)` 복합 키, `image_id` FK RESTRICT), `content_image_usage`(전체 바이트·개수 카운터 행 1개, id=1, V25 시드). 지금 owner는 `NOTICE`뿐이다(`ContentImageService.OWNER_NOTICE`).
- **본문 src 계약**: 본문 이미지는 `/content-images/{id}`(같은 사이트, `Long` 범위)만 허용된다 — 외부 URL·`data:`는 `HtmlContentSanitizer`가 `img`째 지우고, 편집기(`static/js/admin/notice-editor.js`)도 붙여넣기 단계에서 버린다. 경로 상수는 `HtmlContentSanitizer.CONTENT_IMAGE_PATH_PREFIX`와 공개 컨트롤러 매핑이 같아야 한다.
- **업로드**(`POST /admin/api/notices/content-images`): 핸들러 선언은 `@RequirePermission(NOTICE, READ)`이고 **서비스가 `NOTICE` CREATE 또는 UPDATE를 다시 판정**한다(403) — 작성 중(공지 ID 없음)과 수정 중 모두 이미지가 필요한데 `@RequirePermission`은 동작 하나만 받기 때문. 검증은 공용 `ImageFileValidator`(png·jpeg·gif, 5MB, 한 변 4096px·4096² 픽셀, 단일 프레임·APNG·GIF 논리 화면 검사, **전체 디코드 없음** — 힙 보호). 카운터 행을 `findByIdForUpdate`로 잠그고 바이트(`cms.content-image.max-total-bytes`, 기본 1GB)·개수(`max-count`, 기본 10,000) 상한을 정확히 집행한다(초과 409, 업로드가 직렬화된다). 파일은 `FileStorage` **루트(네임스페이스 없음)**에 저장하고 롤백 시 정리(`FileStorageTransactionSupport.deleteOnRollback(storage, key)`) — 카운터 증가도 같은 트랜잭션이라 함께 롤백된다. 감사 로그 `CONTENT_IMAGE_UPLOAD`.
- **카운터 보장 범위는 DB 등록 이미지뿐**: 커밋 전 강제 종료 등으로 남은 "행 없는 파일"은 집계되지 않는다. 미참조·삭제 공지 이미지의 자동 정리는 없다(로드맵 ⑧) — 수동 회수 절차는 `docs/deployment.md` "편집기 본문 이미지".
- **참조 교체**(`replaceRefs`): 벌크 삭제 후 삽입이며 `@Modifying(flushAutomatically = true)`만 쓴다 — `clearAutomatically`를 켜면 호출한 공지 저장 트랜잭션의 `Notice` 더티 체킹이 사라진다. 존재하지 않는 이미지 ID는 참조하지 않는다.
- **공개 다운로드**(`com.cms.publicweb.contentimage`, `GET/HEAD /content-images/{id}`): 공개 공지가 참조하거나 요청자가 `NOTICE` READ 권한자(ADMIN 포함 — 작성 중·비노출 공지 미리보기)면 200, 그 외(비숫자·범위 밖·없음·비공개만 참조·미참조) 동일 404. 저장된 `image/*` 그대로 인라인, `nosniff`·`no-store`, 스트리밍(조회 트랜잭션과 파일 열기 분리).
