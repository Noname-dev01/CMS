-- ============================================================
-- V39: 팝업 (2026-10-12)
--
-- 공개 메인(/) 레이어 팝업 관리(adversarial-review/plan/PLAN-popup.md 쟁점 2·4·5·7).
--
-- popup : 공개 메인에 노출되는 팝업. 종류(popup_type)는 IMAGE 또는 HTML이고 생성 후 바꿀 수 없다(서비스가 강제).
--         IMAGE : storage_key·content_type·file_size를 채우고(FileStorage의 'popup' 네임스페이스) content_html은 NULL.
--         HTML  : content_html(정리된 HTML)을 채우고 이미지 컬럼은 NULL. 본문에는 이미지를 넣지 못한다.
--         display_start/display_end는 NULL 허용(NULL = 제한 없음), 구간은 [start, end) — 서버가 분 단위로 절단해 저장한다.
--         use_yn(노출 여부)과 기간은 둘 다 만족해야 노출된다. 하드 삭제라 deleted 컬럼이 없다.
--         revision : 수정할 때마다 서비스가 행 락 아래에서 +1 한다. 방문자 브라우저의 "오늘 하루 보지 않기"가 수정 전 팝업에
--                    대한 것인지 가리는 값이라 수정마다 반드시 달라져야 한다(수정 시각은 초 단위라 같은 초의 수정을 구분하지 못한다).
--         pos_x/pos_y/width/height : 화면 배치(px). height는 HTML형만 쓰고 NULL이면 내용 높이에 맞춘다.
--         가드 행·표시 순서 컬럼은 두지 않는다(배너와 다른 점) — 개수 상한은 서비스의 소프트 상한이고 공개 조회가 최대 5건만 자른다.
--
-- DDL 1문. 실패 복구는 docs/migration-guide.md "V39~V40".
-- ============================================================

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
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_popup_storage_key` (`storage_key`),
  KEY `idx_popup_use_id` (`use_yn`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
