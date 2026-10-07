-- ============================================================
-- V25: 본문 이미지 카운터 시드 + 공지 본문 평문 → HTML 변환 (2026-10-07)
--
-- adversarial-review/plan/PLAN-html-editor.md 쟁점 11. DML만(V23·V24 DDL과
-- 분리) — 실패하면 전체가 롤백된다.
--
-- 변환 규칙(순서 중요):
--   1. CRLF·CR → LF
--   2. & → &amp; (반드시 먼저), < → &lt;, > → &gt;, " → &quot;
--   3. 줄마다 <p>…</p>
--   4. 빈 문단 <p></p> → <p><br></p> (빈 줄이 화면에서 높이 0이 되지 않게)
-- 연속 공백·탭의 저장 형식(&nbsp;) 정규화는 여기서 하지 않는다 — 출력 시
-- sanitize(HtmlContentSanitizer)가 같은 규칙으로 처리하고, 다음 저장 때
-- 정규화된 형태로 저장된다(쟁점 11 5단계의 대안 경로).
--
-- 개행 문자는 백슬래시 이스케이프 대신 CHAR(.. USING utf8mb4)로 쓴다
-- (sql_mode의 NO_BACKSLASH_ESCAPES 여부와 무관하게 같은 결과).
-- 삭제된 공지도 변환한다(형식 일관성). update_date는 사용자 수정이 아니므로
-- 건드리지 않는다.
--
-- 롤백 주의: V25 적용 후 구버전 앱으로 되돌리면 구버전은 HTML을 평문으로
-- 보여주고 새로 쓰는 본문은 평문으로 저장한다. 롤백 중에는 공지 쓰기를
-- 동결해야 한다(docs/migration-guide.md "V25 이후 롤백", 쟁점 15).
-- ============================================================

INSERT INTO `content_image_usage` (`id`, `total_bytes`, `total_count`)
SELECT 1, 0, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `content_image_usage` WHERE `id` = 1);

UPDATE `notice`
SET `content` = REPLACE(
        CONCAT(
            '<p>',
            REPLACE(
                REPLACE(REPLACE(REPLACE(REPLACE(
                    REPLACE(REPLACE(`content`, CHAR(13, 10 USING utf8mb4), CHAR(10 USING utf8mb4)),
                            CHAR(13 USING utf8mb4), CHAR(10 USING utf8mb4)),
                    '&', '&amp;'), '<', '&lt;'), '>', '&gt;'), '"', '&quot;'),
                CHAR(10 USING utf8mb4), '</p><p>'),
            '</p>'),
        '<p></p>', '<p><br></p>');
