-- ============================================================
-- V1: 기준 스키마 — 테이블·인덱스·제약 (2026-10-21)
--
-- 실배포 전에 V1~V58(2026-07-10~2026-10-16)을 하나로 합친 것이다(adversarial-review/plan/PLAN-migration-squash.md).
-- 옛 체인으로 만든 DB와 컬럼·인덱스·FK·CHECK·AUTO_INCREMENT·시드 행까지 같음을 검증했다. 옛 파일과 각 변경의 이유는
-- git 이력(합치기 직전 커밋 96baec2)에 있다.
--
-- 규약
--  * 모든 테이블은 InnoDB + utf8mb4 / utf8mb4_general_ci를 명시한다(DB 기본값에 기대지 않는다).
--  * 감사 시각 컬럼은 datetime(6)이다. 앱은 KST Clock으로 값을 넣는다(루트 CLAUDE.md "시각 원천은 KST Clock 하나").
--  * 상태·종류 값 중 enum 컬럼은 회원·감사 로그의 초기 정의에서 온 것이다. 그 뒤 생긴 기능·동작·출처 값은 VARCHAR다 —
--    카탈로그에 값을 추가할 때 ALTER가 필요 없고, 코드에서 지운 값의 행이 남아도 앱이 무시한다.
--  * 앱은 회원을 하드 삭제하지 않는다(DELETED 상태 전이). 회원 FK는 RESTRICT가 기본이고, 회원 없이 의미가 없는 알림만 CASCADE다.
--  * 시드 데이터는 V2__baseline_seed.sql에 있다. MariaDB DDL은 문마다 암묵 커밋이라 DDL과 DML을 한 파일에 섞지 않는다.
-- ============================================================

-- ------------------------------------------------------------
-- 사이트: 관리자 사이트 1개(ADMIN)와 공개 사이트 N개(PUBLIC). 시드는 id=1 관리자, id=2 기본 공개 사이트('main')이고
-- 코드가 이 번호에 의존한다(menu.site_id DEFAULT 1, 사이드바 상수).
--  * site_key: 유일. 형식·예약어는 앱(SiteKey)이 검증하고 생성 후 바뀌지 않는다.
--  * allow_external_link: 공개 사이트 메뉴에 외부 http(s) 링크를 허용하는가(서비스가 판정).
--  * default_yn: 접두어 없는 `/`가 이동할 기본 공개 사이트. 정확히 1개라는 불변식은 DB 제약이 아니라
--    서비스(모든 site 행 FOR UPDATE)와 시험이 지킨다.
-- ------------------------------------------------------------
CREATE TABLE `site` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `site_key` varchar(30) NOT NULL,
  `site_name` varchar(100) NOT NULL,
  `site_type` varchar(10) NOT NULL,
  `allow_external_link` bit(1) NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  `default_yn` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_site_site_key` (`site_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 관리자 계정. 로그인 실패 잠금(failed_login_count·locked_at), 비밀번호 90일 만료(password_changed_at),
-- 프로필 이미지(profile_image_kind — UPLOADED면 profile_image_url이 FileStorage 'profile' 네임스페이스의 키,
-- LEGACY_INLINE은 이관하지 못한 옛 Base64), 권한 캐시 무효화용 permission_version.
-- 상세는 com.cms.admin.member·com.cms.admin.permission의 CLAUDE.md.
-- ------------------------------------------------------------
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
  `profile_image_kind` enum('NONE','PRESET','UPLOADED','LEGACY_INLINE') NOT NULL DEFAULT 'NONE',
  `profile_image_content_type` varchar(100) DEFAULT NULL,
  `reset_token_expiry_at` datetime(6) DEFAULT NULL,
  `failed_login_count` int(11) NOT NULL DEFAULT 0,
  `locked_at` datetime(6) DEFAULT NULL,
  `password_changed_at` datetime(6) NOT NULL,
  `permission_version` bigint(20) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_user_id` (`user_id`),
  UNIQUE KEY `uk_member_email` (`email`),
  KEY `idx_member_profile_image_kind` (`profile_image_kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 사이드바·공개 사이트 메뉴(사이트별 트리). up_menu_no가 부모, ord가 형제 사이 순서다. 사이드바는 3단까지만 그린다.
-- site_id NOT NULL DEFAULT 1(관리자 사이트). FK RESTRICT — 메뉴가 있는 사이트는 지울 수 없다. 노출은 메뉴가 아니라
-- 권한 판정기가 정한다(MenuVisibility). 상세는 com.cms.admin.menu의 CLAUDE.md.
-- ------------------------------------------------------------
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
  `site_id` bigint(20) NOT NULL DEFAULT 1,
  PRIMARY KEY (`menu_no`),
  KEY `fk_menu_site` (`site_id`),
  CONSTRAINT `fk_menu_site` FOREIGN KEY (`site_id`) REFERENCES `site` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 관리자 행위 감사 로그(@AdminActionLogged, 독립 트랜잭션). target_label은 500자로 자른다.
-- 목록은 (create_at, id) 키셋으로 읽는다.
-- ------------------------------------------------------------
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
  `target_label` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_log_create_at_id` (`create_at`,`id`),
  KEY `idx_log_action_user_id` (`action_user_id`),
  KEY `idx_log_action_type` (`action_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 방문 기록: ADMIN·MANAGER 로그인 성공 1회 = 1건. 대시보드 최근 7일 집계가 visit_at으로 읽는다.
-- ------------------------------------------------------------
CREATE TABLE `visit_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `request_ip` varchar(45) DEFAULT NULL,
  `visit_at` datetime(6) NOT NULL,
  `visitor_user_id` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_visit_at` (`visit_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 옛 공지·공지 첨부. 공지는 공지 게시판(board.board_key='NOTICE')의 게시글로 흡수됐고 앱은 이 두 테이블을 읽지 않는다
-- (엔티티 없음). 흡수 전 데이터의 동결 스냅샷으로 남겨 둔 것이며, 운영 DB가 생기기 전이라 비어 있다.
-- 제거는 별도 마이그레이션으로 한다(PLAN-migration-squash.md "범위 밖").
-- ------------------------------------------------------------
CREATE TABLE `notice` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL,
  `content` mediumtext NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `author_id` varchar(100) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `notice_attachment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `notice_id` bigint(20) NOT NULL,
  `original_filename` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint(20) NOT NULL,
  `storage_key` varchar(255) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notice_attachment_storage_key` (`storage_key`),
  KEY `idx_notice_attachment_notice_id_id` (`notice_id`,`id`),
  CONSTRAINT `fk_notice_attachment_notice_id` FOREIGN KEY (`notice_id`) REFERENCES `notice` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 상단바 알림. 그 회원 본인에게만 보이며 message는 서버가 상수 템플릿과 enum 라벨로 조립한다.
-- dedupe_key는 같은 사건의 중복 생성 방지용이다(NULL이면 유니크 대상이 아니다).
-- member FK는 ON DELETE CASCADE — 회원 행을 직접 지우는 시험·운영 SQL이 알림 때문에 실패하지 않게 한다(알림은 회원 없이 의미가 없다).
-- 인덱스: (member_id, id) = 목록 커서, (member_id, read_at, id) = 미읽음 수·90일 정리.
-- ------------------------------------------------------------
CREATE TABLE `notification` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) NOT NULL,
  `type` varchar(40) NOT NULL,
  `message` varchar(255) NOT NULL,
  `link_url` varchar(255) DEFAULT NULL,
  `dedupe_key` varchar(100) DEFAULT NULL,
  `read_at` datetime(6) DEFAULT NULL,
  `create_date` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_member_dedupe` (`member_id`,`dedupe_key`),
  KEY `idx_notification_member_id` (`member_id`,`id`),
  KEY `idx_notification_member_read` (`member_id`,`read_at`,`id`),
  CONSTRAINT `fk_notification_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 관리자 쪽지(1:1). 보내는 쪽·받는 쪽이 각자 삭제 시각으로 지우고, 양쪽 모두 지우면 물리 삭제한다.
-- 제목·본문은 사용자 입력이라 원문 그대로 저장한다. 회원 FK는 RESTRICT — 회원 행을 직접 지우는 SQL이 상대 보관함의 쪽지를
-- 조용히 지우지 못한다. 인덱스에 삭제 열을 넣어 한쪽만 삭제된 행이 쌓여도 목록·미읽음 수가 대량 스캔하지 않는다.
-- ------------------------------------------------------------
CREATE TABLE `admin_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sender_id` bigint(20) NOT NULL,
  `recipient_id` bigint(20) NOT NULL,
  `title` varchar(100) NOT NULL,
  `body` varchar(2000) NOT NULL,
  `read_at` datetime(6) DEFAULT NULL,
  `sender_deleted_at` datetime(6) DEFAULT NULL,
  `recipient_deleted_at` datetime(6) DEFAULT NULL,
  `create_date` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_message_inbox` (`recipient_id`,`recipient_deleted_at`,`id`),
  KEY `idx_admin_message_unread` (`recipient_id`,`recipient_deleted_at`,`read_at`,`id`),
  KEY `idx_admin_message_sent` (`sender_id`,`sender_deleted_at`,`id`),
  CONSTRAINT `fk_admin_message_recipient` FOREIGN KEY (`recipient_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_admin_message_sender` FOREIGN KEY (`sender_id`) REFERENCES `member` (`id`),
  CONSTRAINT `ck_admin_message_not_self` CHECK (`sender_id` <> `recipient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 발송 이력(발신자·시각만). 쪽지 삭제와 무관하게 24시간 빈도 한도를 계산한다.
CREATE TABLE `admin_message_send_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sender_id` bigint(20) NOT NULL,
  `sent_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_message_send_log` (`sender_id`,`sent_at`),
  CONSTRAINT `fk_admin_message_send_log_member` FOREIGN KEY (`sender_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 발신자별 잠금 전용 행(내용 없음). 발송 트랜잭션의 첫 문장이 이 행을 만들며 잠가 같은 발신자의 발송만 직렬화한다 —
-- 회원 행을 잠그면 INSERT의 FK 공유 잠금과 맞물려 A→B/B→A 동시 발송이 교착한다.
CREATE TABLE `admin_message_sender_state` (
  `member_id` bigint(20) NOT NULL,
  PRIMARY KEY (`member_id`),
  CONSTRAINT `fk_admin_message_state_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- MANAGER 회원의 기능 단위 허용 행(AdminFeature의 DELEGABLE 기능). 행이 있으면 허용이고 거부 행은 없다.
-- ADMIN 행은 두지 않는다(코드 고정). 상세는 com.cms.admin.permission의 CLAUDE.md.
-- ------------------------------------------------------------
CREATE TABLE `member_permission` (
  `member_id` bigint(20) NOT NULL,
  `feature` varchar(50) NOT NULL,
  `action` varchar(20) NOT NULL,
  PRIMARY KEY (`member_id`,`feature`,`action`),
  CONSTRAINT `fk_member_permission_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 게시판 정의. public_yn(공개)·attachment_yn(첨부 허용)은 ADMIN이 정하고, deleted는 소프트 삭제다.
-- board_key: 시스템 게시판 표지 — 공통 공지 게시판 'NOTICE', 사이트 전용 공지 'NOTICE:{siteId}'. UNIQUE가 사이트당 하나를 보장한다.
-- site_id NULL = 모든 공개 사이트 공통. PUBLIC 사이트만 가리킬 수 있다는 규칙은 서비스(ContentSiteScope)가 검증한다.
-- 잠금 규칙(생성 FOR SHARE / 삭제 FOR UPDATE)은 com.cms.admin.board의 CLAUDE.md.
-- ------------------------------------------------------------
CREATE TABLE `board` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `public_yn` bit(1) NOT NULL,
  `attachment_yn` bit(1) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  `board_key` varchar(30) DEFAULT NULL,
  `site_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_board_board_key` (`board_key`),
  KEY `fk_board_site` (`site_id`),
  CONSTRAINT `fk_board_site` FOREIGN KEY (`site_id`) REFERENCES `site` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- MANAGER 회원의 게시판별 허용 동작(member_permission과 분리). 게시판은 소프트 삭제라 board FK가 삭제를 막지 않고,
-- 게시판 삭제가 이 테이블 행을 함께 지운다.
CREATE TABLE `member_board_permission` (
  `member_id` bigint(20) NOT NULL,
  `board_id` bigint(20) NOT NULL,
  `action` varchar(20) NOT NULL,
  PRIMARY KEY (`member_id`,`board_id`,`action`),
  KEY `idx_member_board_permission_board_id` (`board_id`),
  CONSTRAINT `fk_member_board_permission_board` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`),
  CONSTRAINT `fk_member_board_permission_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 게시글. use_yn(노출)과 deleted(소프트 삭제)는 별도 컬럼이고, content는 정리된 HTML, author_id는 작성자 userId 스냅샷이다.
-- 노출 기간 [publish_start, publish_end) — NULL은 제한 없음. view_count는 공개 경로에서 (IP, 글)당 30분에 1회 센 최선 노력 지표다.
-- 여러 게시판이 한 테이블을 나눠 쓰므로 목록·COUNT가 board_id로 시작하는 복합 인덱스를 탄다(board_id FK도 이 인덱스로 충족).
-- 게시판 삭제는 살아 있는 게시글이 없을 때만 허용된다.
-- ------------------------------------------------------------
CREATE TABLE `post` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `board_id` bigint(20) NOT NULL,
  `title` varchar(200) NOT NULL,
  `content` mediumtext NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `author_id` varchar(100) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  `publish_start` datetime(6) DEFAULT NULL,
  `publish_end` datetime(6) DEFAULT NULL,
  `view_count` bigint(20) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_post_board_deleted_use_create` (`board_id`,`deleted`,`use_yn`,`create_date`),
  CONSTRAINT `fk_post_board` FOREIGN KEY (`board_id`) REFERENCES `board` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 게시글 첨부. 파일은 FileStorage 루트에 있다. storage_key UNIQUE로 키 재사용(덮어쓰기)을 DB에서도 막고,
-- (post_id, id) 인덱스는 첨부 목록 정렬에 맞춘 것이다. download_count는 view_count와 같은 최선 노력 지표다.
CREATE TABLE `post_attachment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `post_id` bigint(20) NOT NULL,
  `original_filename` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint(20) NOT NULL,
  `storage_key` varchar(255) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `download_count` bigint(20) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_attachment_storage_key` (`storage_key`),
  KEY `idx_post_attachment_post_id_id` (`post_id`,`id`),
  CONSTRAINT `fk_post_attachment_post_id` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 이미지 원본(편집기 본문 이미지 + 미디어 라이브러리). 새 테이블 없이 이 테이블이 라이브러리 원본이다.
--  * namespace: 파일이 있는 FileStorage 네임스페이스. 빈 문자열 = 루트, 'banner'·'popup' = 배너·팝업 이미지.
--    NULL이 아니라 빈 문자열인 이유는 MariaDB UNIQUE가 NULL 중복을 허용해 루트 키의 유일성이 깨지기 때문이다.
--  * scope_type: 업로드 출처(BOARD·LIBRARY·BANNER·POPUP 등, VARCHAR). 기본값 'NOTICE'는 옛 공지 출처의 흔적이다(앱은 항상 값을 넣는다).
--  * original_name: 검색·표시용 원본 파일명(서버가 정리한 값).
-- 상세는 com.cms.admin.contentimage·com.cms.admin.media의 CLAUDE.md.
-- ------------------------------------------------------------
CREATE TABLE `content_image` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `storage_key` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint(20) NOT NULL,
  `uploader_id` varchar(100) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `scope_type` varchar(30) NOT NULL DEFAULT 'NOTICE',
  `scope_id` bigint(20) DEFAULT NULL,
  `namespace` varchar(30) NOT NULL DEFAULT '',
  `original_name` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_image_ns_storage_key` (`namespace`,`storage_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 어떤 소유자(게시글·배너·팝업)가 어떤 이미지를 참조하는지 — 공개 판정과 사용 중 삭제 차단의 근거다.
-- 소유자 테이블에는 이미지 컬럼이 없고 연결은 여기 한 곳이다. image_id FK는 RESTRICT — 참조가 남은 이미지 행을 지우지 못한다.
CREATE TABLE `content_image_ref` (
  `owner_type` varchar(30) NOT NULL,
  `owner_id` bigint(20) NOT NULL,
  `image_id` bigint(20) NOT NULL,
  PRIMARY KEY (`owner_type`,`owner_id`,`image_id`),
  KEY `idx_content_image_ref_image_id` (`image_id`),
  CONSTRAINT `fk_content_image_ref_image_id` FOREIGN KEY (`image_id`) REFERENCES `content_image` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 전체 바이트·개수 카운터 행 1개(id=1, V2가 시드). 업로드가 이 행을 비관적 락으로 잡아 상한을 정확히 집행한다.
CREATE TABLE `content_image_usage` (
  `id` bigint(20) NOT NULL,
  `total_bytes` bigint(20) NOT NULL,
  `total_count` bigint(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 공개 메인 배너. 노출 기간 [display_start, display_end) — NULL은 제한 없음, 서버가 분 단위로 절단해 저장한다.
-- use_yn과 기간을 둘 다 만족해야 노출된다. 하드 삭제. ord는 표시 순서이고 공개 조회가 (use_yn, ord, id)로 읽는다.
-- 이미지는 content_image_ref로 연결한다 — storage_key·content_type·file_size는 옛 직접 저장의 흔적이라 NULL 허용·미사용이다.
-- site_id NULL = 모든 공개 사이트 공통. version은 수정 요청의 낡은 화면 검사용(수정마다 +1).
-- ------------------------------------------------------------
CREATE TABLE `banner` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL,
  `link_url` varchar(500) DEFAULT NULL,
  `storage_key` varchar(255) DEFAULT NULL,
  `content_type` varchar(100) DEFAULT NULL,
  `file_size` bigint(20) DEFAULT NULL,
  `display_start` datetime(6) DEFAULT NULL,
  `display_end` datetime(6) DEFAULT NULL,
  `use_yn` bit(1) NOT NULL,
  `ord` int(11) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  `site_id` bigint(20) DEFAULT NULL,
  `version` int(11) NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_banner_storage_key` (`storage_key`),
  KEY `idx_banner_use_ord_id` (`use_yn`,`ord`,`id`),
  KEY `fk_banner_site` (`site_id`),
  CONSTRAINT `fk_banner_site` FOREIGN KEY (`site_id`) REFERENCES `site` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 배너 생성·삭제·순서 저장을 직렬화하는 단일 가드 행(id=1, V2가 시드 — 없으면 해당 쓰기가 IllegalStateException).
-- 배너 행이 하나도 없어도 잠글 행이 있어 갭 잠금·격리 수준에 기대지 않고 개수 상한과 ord 계산이 직렬화된다.
CREATE TABLE `banner_lock` (
  `id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 정적 페이지(약관·방침·소개). 슬러그는 유일하고 생성 후 바뀌지 않는다. 본문은 개정 테이블에만 있고 current_revision_no가
-- 현재 개정을 가리킨다. version은 낡은 화면 검출용으로 본문·노출·푸터 중 무엇이든 바뀌면 +1 한다(개정 번호와 다른 값).
-- use_yn=0이면 현재 페이지와 이력 모두 비공개, footer_yn=1이면 공개 메인 푸터에 링크된다. site_id NULL = 공통.
-- ------------------------------------------------------------
CREATE TABLE `site_page` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `slug` varchar(50) NOT NULL,
  `current_revision_no` int(11) NOT NULL,
  `version` int(11) NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `footer_yn` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  `site_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_site_page_slug` (`slug`),
  KEY `fk_site_page_site` (`site_id`),
  CONSTRAINT `fk_site_page_site` FOREIGN KEY (`site_id`) REFERENCES `site` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 저장(본문 변경)마다 쌓이는 개정. (page_id, revision_no) 복합 PK가 개정 번호 중복의 최종 방어선이고, 앱은 site_page 행을
-- FOR UPDATE로 잠근 뒤 번호를 매긴다. 페이지 삭제는 개정을 먼저 지운다(FK RESTRICT).
CREATE TABLE `site_page_revision` (
  `page_id` bigint(20) NOT NULL,
  `revision_no` int(11) NOT NULL,
  `title` varchar(200) NOT NULL,
  `content` mediumtext NOT NULL,
  `create_date` datetime(6) NOT NULL,
  PRIMARY KEY (`page_id`,`revision_no`),
  CONSTRAINT `fk_site_page_revision_page` FOREIGN KEY (`page_id`) REFERENCES `site_page` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 공개 메인 레이어 팝업. popup_type은 IMAGE 또는 HTML이고 생성 후 바꿀 수 없다(서비스가 강제). IMAGE의 이미지는
-- content_image_ref로 연결한다(storage_key·content_type·file_size는 옛 직접 저장의 흔적, NULL 허용·미사용). HTML은
-- content_html(정리된 HTML, 이미지 불허)을 쓴다. 노출 기간은 배너와 같은 [start, end) 분 단위.
-- revision은 수정마다 행 락 아래에서 +1 — 방문자 "오늘 하루 보지 않기"가 수정 전 팝업에 대한 것인지 가리는 값이라
-- 수정마다 반드시 달라야 한다(수정 시각은 같은 초의 수정을 구분하지 못한다). height는 HTML형만 쓰고 NULL이면 내용 높이.
-- 가드 행·표시 순서는 없다(배너와 다른 점) — 개수는 서비스 소프트 상한, 공개 조회는 최대 5건. site_id NULL = 공통.
-- ------------------------------------------------------------
CREATE TABLE `popup` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL,
  `revision` int(11) NOT NULL DEFAULT 0,
  `popup_type` varchar(10) NOT NULL,
  `link_url` varchar(500) DEFAULT NULL,
  `storage_key` varchar(255) DEFAULT NULL,
  `content_type` varchar(100) DEFAULT NULL,
  `file_size` bigint(20) DEFAULT NULL,
  `content_html` mediumtext DEFAULT NULL,
  `pos_x` int(11) NOT NULL,
  `pos_y` int(11) NOT NULL,
  `width` int(11) NOT NULL,
  `height` int(11) DEFAULT NULL,
  `display_start` datetime(6) DEFAULT NULL,
  `display_end` datetime(6) DEFAULT NULL,
  `use_yn` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  `site_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_popup_storage_key` (`storage_key`),
  KEY `idx_popup_use_id` (`use_yn`,`id`),
  KEY `fk_popup_site` (`site_id`),
  CONSTRAINT `fk_popup_site` FOREIGN KEY (`site_id`) REFERENCES `site` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- FAQ 카테고리. ord는 전체 안의 표시 순서(항상 0..n-1), use_yn이 꺼지면 그 안의 FAQ가 공개에서 모두 숨는다.
-- site_id NULL = 공통. version은 수정 요청의 낡은 화면 검사용(수정마다 +1).
-- ------------------------------------------------------------
CREATE TABLE `faq_category` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `ord` int(11) NOT NULL,
  `use_yn` bit(1) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  `site_id` bigint(20) DEFAULT NULL,
  `version` int(11) NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `idx_faq_category_use_ord_id` (`use_yn`,`ord`,`id`),
  KEY `fk_faq_category_site` (`site_id`),
  CONSTRAINT `fk_faq_category_site` FOREIGN KEY (`site_id`) REFERENCES `site` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- FAQ 질문/답변. category FK는 RESTRICT(카테고리는 FAQ가 없을 때만 지운다). answer_html은 정리된 HTML(이미지 불허),
-- ord는 카테고리 안의 표시 순서(항상 0..n-1). 하드 삭제.
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
  KEY `idx_faq_category_ord_id` (`category_id`,`ord`,`id`),
  CONSTRAINT `fk_faq_category` FOREIGN KEY (`category_id`) REFERENCES `faq_category` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 모든 FAQ·카테고리 쓰기를 직렬화하는 단일 가드 행(id=1, V2가 시드). version은 구조(등록·삭제·카테고리 이동·순서)가
-- 바뀔 때만 +1 — 순서 초안을 만든 화면이 낡았는지 검사한다.
CREATE TABLE `faq_lock` (
  `id` bigint(20) NOT NULL,
  `version` bigint(20) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ------------------------------------------------------------
-- 공개 문의 폼 → 관리자 문의함. 개인정보(이름·이메일·본문)를 담고 방문자 IP·User-Agent는 저장하지 않는다.
-- status는 RECEIVED / IN_PROGRESS / ANSWERED 문자열이고 전이는 제한하지 않는다. consent_at은 개인정보 수집·이용 동의 시각.
-- 하드 삭제. idx_inquiry_status_id = 상태 탭 목록과 미처리 건수 상한 COUNT.
-- ------------------------------------------------------------
CREATE TABLE `inquiry` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `email` varchar(100) NOT NULL,
  `title` varchar(100) NOT NULL,
  `body` text NOT NULL,
  `status` varchar(20) NOT NULL,
  `consent_at` datetime(6) NOT NULL,
  `create_date` datetime(6) NOT NULL,
  `update_date` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_inquiry_status_id` (`status`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
