-- ============================================================
-- V44: 미디어 라이브러리 — 기존 배너·팝업 이미지 이관 (멱등 DML) (2026-10-12)
--
-- adversarial-review/plan/PLAN-media-library.md D2·D11 (리뷰 R3-3·R4-3).
-- 파일은 옮기지 않는다 — 제자리(banner/·popup/)에 두고 content_image 행으로 등록하고 content_image_ref(BANNER|POPUP)로 소유자와 잇는다.
-- 세 단계는 서로의 완료 여부를 가정하지 않는다: ① 이미지 행은 (namespace, storage_key)가 같은 행이 없을 때만, ② 참조 행은 소유자의
-- storage_key로 그 이미지 행을 찾아 참조가 없을 때만 만든다 → 어느 지점에서 끊겨도 재실행으로 수렴한다.
-- ③ 카운터는 덧셈이 아니라 content_image 원본에서 재계산한다(빈 집합에서 SUM이 NULL이므로 COALESCE 필수).
--
-- DDL 금지(순수 DML만): V3·V9·V14·V15·V28·V30·V34·V35·V38·V40·V42와 같은 규칙.
-- ============================================================

-- ① 이미지 행
INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, scope_id, create_date, namespace, original_name)
SELECT b.storage_key, b.content_type, b.file_size, 'system', 'BANNER', NULL, b.create_date, 'banner', LEFT(b.title, 200)
FROM banner b
WHERE b.storage_key IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM content_image i WHERE i.namespace = 'banner' AND i.storage_key = b.storage_key);

INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, scope_id, create_date, namespace, original_name)
SELECT p.storage_key, p.content_type, p.file_size, 'system', 'POPUP', NULL, p.create_date, 'popup', LEFT(p.title, 200)
FROM popup p
WHERE p.popup_type = 'IMAGE' AND p.storage_key IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM content_image i WHERE i.namespace = 'popup' AND i.storage_key = p.storage_key);

-- ② 참조 행
INSERT INTO content_image_ref (owner_type, owner_id, image_id)
SELECT 'BANNER', b.id, i.id
FROM banner b
JOIN content_image i ON i.namespace = 'banner' AND i.storage_key = b.storage_key
WHERE NOT EXISTS (SELECT 1 FROM content_image_ref r WHERE r.owner_type = 'BANNER' AND r.owner_id = b.id);

INSERT INTO content_image_ref (owner_type, owner_id, image_id)
SELECT 'POPUP', p.id, i.id
FROM popup p
JOIN content_image i ON i.namespace = 'popup' AND i.storage_key = p.storage_key
WHERE p.popup_type = 'IMAGE'
  AND NOT EXISTS (SELECT 1 FROM content_image_ref r WHERE r.owner_type = 'POPUP' AND r.owner_id = p.id);

-- ③ 카운터 원본 재계산
UPDATE content_image_usage
SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),
    total_count = (SELECT COUNT(*) FROM content_image)
WHERE id = 1;
