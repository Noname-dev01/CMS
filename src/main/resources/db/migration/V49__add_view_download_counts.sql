-- ============================================================
-- V49: 게시글 조회수·첨부 다운로드 수 누적 카운터 (2026-10-13)
--
-- adversarial-review/plan/PLAN-view-download-count.md 쟁점 1.
--
-- post.view_count / post_attachment.download_count : 공개 경로에서 (IP, 대상)당 30분에 1회 센 누적값. 최선 노력 지표다.
--               기존 행은 0으로 시작한다. 인덱스는 추가하지 않는다 — 대시보드 상위 N 정렬이 느려지면 실측 뒤 새 마이그레이션으로 추가한다.
-- 롤백 주의 없음: 이전 앱은 이 컬럼을 모르지만 DEFAULT 0이라 INSERT가 호환되고, 롤백 중에는 카운트만 멈추며 값은 보존된다.
--
-- DDL 2문 — 암묵 커밋. 실패 복구는 docs/migration-guide.md "V49".
-- ============================================================

ALTER TABLE `post`
  ADD COLUMN `view_count` bigint NOT NULL DEFAULT 0;

ALTER TABLE `post_attachment`
  ADD COLUMN `download_count` bigint NOT NULL DEFAULT 0;
