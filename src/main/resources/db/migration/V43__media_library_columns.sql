-- ============================================================
-- V43: 미디어 라이브러리 — content_image 확장 (2026-10-12)
--
-- adversarial-review/plan/PLAN-media-library.md D1·D11 (적대적 리뷰 5라운드 ship).
--
-- namespace     : 파일이 있는 FileStorage 네임스페이스. 빈 문자열 = 루트(본문 이미지·라이브러리 업로드), 'banner'·'popup' = 이관된
--                 배너·팝업 이미지. NULL이 아니라 빈 문자열인 이유는 MariaDB UNIQUE가 NULL 중복을 허용해 루트 키의 유일성이 깨지기 때문이다.
-- original_name : 검색·표시용 원본 파일명(서버가 정리한 값). 기존 행은 NULL.
-- 유일성        : storage_key 단독 UNIQUE를 (namespace, storage_key)로 교체한다 — 다른 네임스페이스에 같은 키 문자열이 있는 기존 데이터도
--                 이관할 수 있어야 한다. scope_type에는 LIBRARY·BANNER·POPUP 값이 추가로 쓰인다(스키마 변경 없음, varchar).
--
-- DDL 1문(ALTER 하나). V43 이전 앱으로의 롤백은 지원하지 않는다(roll-forward만) — docs/migration-guide.md "V43~V46".
-- ============================================================

ALTER TABLE `content_image`
  ADD COLUMN `namespace` varchar(30) NOT NULL DEFAULT '',
  ADD COLUMN `original_name` varchar(200) DEFAULT NULL,
  DROP INDEX `uk_content_image_storage_key`,
  ADD UNIQUE KEY `uk_content_image_ns_storage_key` (`namespace`, `storage_key`);
