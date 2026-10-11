-- ============================================================
-- V37: 정적 페이지 + 개정 이력 테이블 (2026-10-11)
--
-- 정적 페이지(약관·개인정보처리방침·소개 등, adversarial-review/plan/PLAN-static-page.md 쟁점 2·10).
--
-- site_page          : 페이지 메타. 슬러그는 유일하고 생성 후 바뀌지 않는다. 본문은 개정 테이블에만 있고
--                      current_revision_no가 현재 개정을 가리킨다. version은 낡은 화면 검출용 변경 버전으로
--                      본문·노출·푸터 중 무엇이든 바뀌면 +1 한다(current_revision_no와 다른 값).
--                      use_yn=0이면 현재 페이지와 이력 모두 공개되지 않는다. footer_yn=1이면 공개 메인 푸터에 링크된다.
-- site_page_revision : 저장(본문 변경)마다 쌓이는 개정. (page_id, revision_no) 복합 PK가 개정 번호 중복의 최종 방어선이고,
--                      앱은 site_page 행을 FOR UPDATE로 잠근 뒤 번호를 매긴다. 페이지 삭제는 개정을 먼저 지운다(FK RESTRICT).
--
-- DDL 2문 — 문마다 암묵 커밋. 실패 복구는 docs/migration-guide.md "V37~V38".
-- ============================================================

CREATE TABLE `site_page` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `slug` varchar(50) NOT NULL,
  `current_revision_no` int(11) NOT NULL,
  `version` int(11) NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `footer_yn` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_site_page_slug` (`slug`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `site_page_revision` (
  `page_id` bigint(20) NOT NULL,
  `revision_no` int(11) NOT NULL,
  `title` varchar(200) NOT NULL,
  `content` mediumtext NOT NULL,
  `create_date` datetime(6) NOT NULL,
  PRIMARY KEY (`page_id`, `revision_no`),
  CONSTRAINT `fk_site_page_revision_page` FOREIGN KEY (`page_id`) REFERENCES `site_page` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
