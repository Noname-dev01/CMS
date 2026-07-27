-- ============================================================
-- V10: 공지사항 첨부파일(notice_attachment) 테이블 생성 (2026-07-21)
--
-- 로드맵 ② "파일 스토리지 추상화 + 공지사항 첨부파일"
-- (adversarial-review/plan/PLAN-notice-attachment.md 참조, v6 — 적대적
-- 리뷰 5라운드 ship 판정).
--
-- notice_id는 authorId(논리적 스냅샷)와 달리 진짜 부모-자식 구조적
-- 관계이므로 FK를 건다. notice는 하드 삭제 경로 자체가 없으므로(소프트
-- 삭제만 존재) ON DELETE RESTRICT는 실제로는 발동하지 않는 안전망이다
-- (미래에 실수로 하드 삭제 코드가 추가돼도 첨부가 남아있으면 막아준다).
-- 애플리케이션 레벨에서도 NoticeService.deleteNotice()가 첨부 존재 시
-- 409로 먼저 차단한다(쟁점 14) — 오펀 방지가 이중으로 걸려 있다.
--
-- storage_key UNIQUE: 동일 키 재사용(오펀·덮어쓰기)을 DB 레벨에서도
-- 차단한다. (notice_id, id) 복합 인덱스는 목록 조회
-- (findByNoticeIdOrderByIdAsc) 정렬에 맞춘 것 — FK가 자동 생성하는
-- 단일 컬럼 인덱스만으로는 정렬까지 최적화되지 않는다.
--
-- 컬럼 규약(감사 컬럼 datetime(6), utf8mb4)은 V8__create_notice.sql과
-- 동일. ddl-auto: validate이므로 엔티티 매핑과 1:1 대응해야 한다.
-- ============================================================

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
  KEY `idx_notice_attachment_notice_id_id` (`notice_id`, `id`),
  CONSTRAINT `fk_notice_attachment_notice_id` FOREIGN KEY (`notice_id`) REFERENCES `notice` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
