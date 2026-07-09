-- ============================================================
-- V1: baseline 스키마 (2026-07-10)
--
-- dev DB(cms-db-dev, MariaDB 10.11) 실물에서 SHOW CREATE TABLE로 추출.
-- 이 스키마를 만든 것이 Hibernate(ddl-auto: update) 자신이므로,
-- 이후 ddl-auto: validate 전환이 안전하다.
--
-- 기존 DB는 baseline(version 1)으로 이 파일을 건너뛰고,
-- 빈 DB(CI·신규 환경)만 여기서부터 전체 실행된다.
-- 데이터 보정은 V2 이상에 두어야 기존 DB에 도달한다.
--
-- 인덱스/유니크 제약은 엔티티 @Table 선언 전체와 1:1 대응:
--   member           uk_member_user_id, uk_member_email (유니크)
--   admin_action_log idx_log_create_at_id, idx_log_action_user_id, idx_log_action_type
--   visit_log        idx_visit_at
--   menu             (PRIMARY만)
-- ============================================================

CREATE TABLE `member` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `create_date` datetime(6) DEFAULT NULL,
  `email` varchar(100) NOT NULL,
  `pwd` varchar(255) DEFAULT NULL,
  `reset_token` varchar(255) DEFAULT NULL,
  `status` enum('ACTIVE','DELETED','DISABLED','LOCKED','PASSWORD_EXPIRED') NOT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  `user_id` varchar(50) NOT NULL,
  `user_name` varchar(100) NOT NULL,
  `user_type` enum('ROLE_ADMIN','ROLE_MANAGER','ROLE_USER') NOT NULL,
  `profile_image_url` longtext DEFAULT NULL,
  `reset_token_expiry_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_user_id` (`user_id`),
  UNIQUE KEY `uk_member_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `menu` (
  `menu_no` bigint(20) NOT NULL AUTO_INCREMENT,
  `create_date` datetime(6) DEFAULT NULL,
  `menu_desc` varchar(500) DEFAULT NULL,
  `menu_icon` varchar(100) DEFAULT NULL,
  `menu_name` varchar(100) NOT NULL,
  `menu_url` varchar(255) DEFAULT NULL,
  `ord` int(11) DEFAULT NULL,
  `up_menu_no` bigint(20) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  `use_yn` bit(1) NOT NULL,
  `access_role` enum('ADMIN','ALL') DEFAULT NULL,
  PRIMARY KEY (`menu_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `admin_action_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `action_id` bigint(20) DEFAULT NULL,
  `action_user_id` varchar(255) DEFAULT NULL,
  `request_ip` varchar(45) DEFAULT NULL,
  `request_uri` varchar(255) DEFAULT NULL,
  `target_id` bigint(20) DEFAULT NULL,
  `target_type` varchar(100) DEFAULT NULL,
  `action_type` varchar(100) NOT NULL,
  `create_at` datetime(6) DEFAULT NULL,
  `action_result` enum('FAIL','SUCCESS') NOT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `request_method` varchar(10) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_log_create_at_id` (`create_at`,`id`),
  KEY `idx_log_action_user_id` (`action_user_id`),
  KEY `idx_log_action_type` (`action_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `visit_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `request_ip` varchar(45) DEFAULT NULL,
  `visit_at` datetime(6) NOT NULL,
  `visitor_user_id` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_visit_at` (`visit_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
