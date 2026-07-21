-- ============================================================
-- V8: 공지사항(notice) 테이블 생성 (2026-07-20)
--
-- 첫 콘텐츠 도메인(adversarial-review/plan/PLAN-notice-board.md 참조).
-- 1차 범위: 제목·내용·노출여부·작성자·작성/수정일의 관리 화면 CRUD.
-- 첨부파일·공개 프론트는 후속 계획(②③)에서 다룬다.
--
-- 컬럼 규약은 V1__init_schema.sql의 member·menu 정의를 그대로 따른다
-- (감사 컬럼 datetime(6), utf8mb4). ddl-auto: validate이므로 엔티티
-- 매핑과 1:1 대응해야 한다.
--
-- title/content/use_yn/deleted/author_id 5개 컬럼 모두 NOT NULL —
-- deleted가 null이면 조회의 deleted=false 필터가 해당 행을 조용히
-- 숨기고, use_yn이 null이면 노출 여부라는 이진 상태가 깨진다.
--
-- use_yn(노출 여부)과 deleted(소프트 삭제)는 별도 컬럼이다 — 메뉴는
-- 둘을 use_yn 하나로 겸했지만, 공지는 "노출 끔" 상태와 "삭제됨"
-- 상태를 구분해야 하므로 분리했다(설계 결정 2).
-- ============================================================

CREATE TABLE `notice` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL,
  `content` TEXT NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `author_id` varchar(100) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
