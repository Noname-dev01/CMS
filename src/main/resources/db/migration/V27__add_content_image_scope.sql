-- ============================================================
-- V27: 본문 이미지 업로드 출처 (2026-10-08)
--
-- 범용 게시판 PR A(adversarial-review/plan/PLAN-board.md 쟁점 9).
--
-- scope_type : 이미지를 올린 콘텐츠 영역. 'NOTICE'(공지 편집기) 또는 'BOARD'(게시판 편집기, PR B).
--              기존 행은 모두 공지 편집기 업로드라 기본값 'NOTICE'로 채워진다. 구버전 앱의 INSERT도
--              이 컬럼을 몰라 기본값이 들어간다.
-- scope_id   : 'BOARD'일 때 게시판 ID, 'NOTICE'는 NULL. FK 없음(기록 용도 — 소프트 삭제 게시판 ID 보존).
--
-- 참조 저장은 콘텐츠 출처와 이미지 출처가 같을 때만 허용되고, 공개 판정·미리보기도 출처로 판정한다.
-- 이 컬럼이 있는 앱(PR A 이상)이 이 기능의 안전한 롤백 하한이다(docs/migration-guide.md "V26~V28").
--
-- DDL 1문(암묵 커밋).
-- ============================================================

ALTER TABLE `content_image`
  ADD COLUMN `scope_type` varchar(30) NOT NULL DEFAULT 'NOTICE',
  ADD COLUMN `scope_id` bigint(20) DEFAULT NULL;
