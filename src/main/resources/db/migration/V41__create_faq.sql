-- ============================================================
-- V41: FAQ 카테고리 + FAQ + 동시성 가드 테이블 (2026-10-12)
--
-- FAQ 관리(adversarial-review/plan/PLAN-faq.md 쟁점 1·3).
--
-- faq_category : FAQ 분류. ord는 전체 안의 표시 순서(항상 0..n-1), use_yn이 꺼지면 그 안의 FAQ가 공개에서 모두 숨는다.
-- faq          : 질문/답변. category_id는 faq_category를 가리키는 FK(RESTRICT — 카테고리는 FAQ가 없을 때만 지운다).
--                answer_html은 저장 시 정리된 HTML(출력 시에도 한 번 더 정리, 이미지 불허). ord는 카테고리 안의 표시 순서(항상 0..n-1).
-- faq_lock     : 모든 FAQ·카테고리 쓰기를 직렬화하는 단일 가드 행(id=1, V42가 시드). version은 구조(등록·삭제·카테고리 이동·순서)가
--                바뀔 때마다 +1 — 순서 초안을 만든 화면이 낡았는지 검사한다. 하드 삭제라 deleted 컬럼이 없다.
--
-- DDL 3문 — 문마다 암묵 커밋. 실패 복구는 docs/migration-guide.md "V41~V42".
-- ============================================================

CREATE TABLE `faq_category` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `ord` int(11) NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_faq_category_use_ord_id` (`use_yn`, `ord`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `faq` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `category_id` bigint(20) NOT NULL,
  `question` varchar(300) NOT NULL,
  `answer_html` mediumtext NOT NULL,
  `ord` int(11) NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_faq_category_ord_id` (`category_id`, `ord`, `id`),
  CONSTRAINT `fk_faq_category` FOREIGN KEY (`category_id`) REFERENCES `faq_category` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `faq_lock` (
  `id` bigint(20) NOT NULL,
  `version` bigint(20) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
