-- ============================================================
-- V33: 배너 + 배너 동시성 가드 테이블 (2026-10-09)
--
-- 공개 메인 + 배너 관리(adversarial-review/plan/PLAN-public-home-banner.md 쟁점 2·5).
--
-- banner      : 공개 메인(/)에 노출되는 배너. 이미지는 FileStorage의 'banner' 네임스페이스에 두고 storage_key만 저장한다.
--               display_start/display_end는 NULL 허용(NULL = 제한 없음), 구간은 [start, end) — 서버가 분 단위로 절단해 저장한다.
--               use_yn(노출 여부)과 기간은 둘 다 만족해야 노출된다. 하드 삭제라 deleted 컬럼이 없다(다른 곳에서 참조하지 않음).
--               ord는 표시 순서(작을수록 앞). 공개 조회가 (use_yn, ord, id) 순으로 읽는다.
-- banner_lock : 생성·삭제·순서 저장을 직렬화하는 단일 가드 행(id=1, V34가 시드). 배너 행이 하나도 없어도 잠글 행이 있어
--               갭 잠금·격리 수준에 기대지 않고 개수 상한과 ord 계산이 직렬화된다(리뷰 R1-1).
--
-- DDL 2문 — 문마다 암묵 커밋. 실패 복구는 docs/migration-guide.md "V33~V34".
-- ============================================================

CREATE TABLE `banner` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL,
  `link_url` varchar(500) DEFAULT NULL,
  `storage_key` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint(20) NOT NULL,
  `display_start` datetime(6) DEFAULT NULL,
  `display_end` datetime(6) DEFAULT NULL,
  `use_yn` bit(1) NOT NULL,
  `ord` int(11) NOT NULL,
  `create_date` datetime(6) DEFAULT NULL,
  `update_date` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_banner_storage_key` (`storage_key`),
  KEY `idx_banner_use_ord_id` (`use_yn`, `ord`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `banner_lock` (
  `id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
