-- ============================================================
-- V36: 게시글 예약 게시 — 노출 시작·종료 시각 (2026-10-11)
--
-- adversarial-review/plan/PLAN-post-publish-period.md 쟁점 5.
--
-- post.publish_start / publish_end : 공개 노출 기간 [start, end). NULL 허용(NULL = 그쪽 제한 없음) — 기존 행은 둘 다 NULL이라
--               기존 공개 동작이 그대로다. use_yn(노출 여부)과 기간은 둘 다 만족해야 공개된다. 서버가 분 단위로 절단해 저장하고,
--               판정은 조회 조건에서 주입된 KST Clock 시각으로 한다(스케줄러 없음).
--
-- 인덱스는 추가하지 않는다 — 기간 조건이 붙은 쿼리의 실측(PLAN 쟁점 5) 뒤 필요하면 새 마이그레이션으로 추가한다.
-- 롤백 주의: 이전 앱은 이 컬럼을 모르고 use_yn만 보므로 되돌리면 예약·만료 글이 공개된다(docs/deployment.md "게시글 예약 게시 롤백 주의").
--
-- DDL 1문 — 암묵 커밋. 실패 복구는 docs/migration-guide.md "V36".
-- ============================================================

ALTER TABLE `post`
  ADD COLUMN `publish_start` datetime(6) DEFAULT NULL AFTER `update_date`,
  ADD COLUMN `publish_end` datetime(6) DEFAULT NULL AFTER `publish_start`;
