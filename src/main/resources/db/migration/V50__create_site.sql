-- ============================================================
-- V50: site 테이블 신설 (2026-10-13, PLAN-site-menu.md D1, PR ①)
--
-- 다중 사이트의 소속 단위. 관리자 사이트 1개(ADMIN)와 공개 사이트 N개(PUBLIC)를 담는다.
--  * site_key : 유일. 형식·예약어는 앱(SiteKey)이 검증하고 생성 후 바뀌지 않는다.
--  * site_type: ADMIN | PUBLIC (앱 enum). 관리자 사이트의 링크 정책은 항상 내부 경로만이다.
--  * allow_external_link: 공개 사이트 메뉴에 외부 http(s) 링크를 허용하는가(서비스 계층이 판정).
-- 시드는 V51, menu.site_id는 V52. DDL 단독 파일(MariaDB DDL은 암묵 커밋 — DML과 섞지 않는다, V13·V16과 같은 규칙).
-- ============================================================

CREATE TABLE `site` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `site_key` varchar(30) NOT NULL,
  `site_name` varchar(100) NOT NULL,
  `site_type` varchar(10) NOT NULL,
  `allow_external_link` bit(1) NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_site_site_key` (`site_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
