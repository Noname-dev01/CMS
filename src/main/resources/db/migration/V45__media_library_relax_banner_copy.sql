-- ============================================================
-- V45: 미디어 라이브러리 — 배너 사본 컬럼 NULL 허용 (2026-10-12)
--
-- adversarial-review/plan/PLAN-media-library.md D4·D11 (리뷰 R1-9).
-- 배너와 이미지의 연결은 content_image_ref 한 곳이다. 새 앱은 banner.storage_key·content_type·file_size를 쓰지도 읽지도 않으므로
-- 새 행은 NULL이다(팝업의 같은 컬럼은 처음부터 NULL 허용). 이관된 기존 행의 값만 화석으로 남고 컬럼 삭제는 후속 PR이다.
-- storage_key UNIQUE는 그대로 둔다 — NULL은 중복을 허용해 새 행과 충돌하지 않는다.
--
-- DDL 1문. V44가 이미 커밋돼 있으므로 이 문이 실패하면 V45만 다시 실행하면 된다 — docs/migration-guide.md "V43~V46".
-- ============================================================

ALTER TABLE `banner`
  MODIFY COLUMN `storage_key` varchar(255) DEFAULT NULL,
  MODIFY COLUMN `content_type` varchar(100) DEFAULT NULL,
  MODIFY COLUMN `file_size` bigint(20) DEFAULT NULL;
