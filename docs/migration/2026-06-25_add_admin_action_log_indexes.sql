-- =====================================================================
-- 마이그레이션: admin_action_log 인덱스 추가
-- 날짜: 2026-06-25
-- 대상: dev · prod 모두 수동 적용
-- =====================================================================
-- ⚠️ 적용 전 반드시 현재 인덱스를 확인하세요:
--   SHOW INDEX FROM admin_action_log;
--
-- ⚠️ 이전에 단일 create_at 인덱스(idx_log_create_at 등)를 수동 생성한 환경이 있다면
--   복합 인덱스 추가 전에 먼저 제거하세요:
--   DROP INDEX idx_log_create_at ON admin_action_log;
-- =====================================================================

-- 기본 정렬 (create_at DESC, id DESC) 및 기간 필터를 함께 커버하는 복합 인덱스
-- ⚠️ prod 운영 중 적용 시 AOP INSERT가 지속되므로 ALGORITHM=INPLACE, LOCK=NONE 사용.
--    MariaDB 버전이 온라인 DDL을 지원하지 않는 경우 점검창에서 적용하세요.
ALTER TABLE admin_action_log
    ADD INDEX idx_log_create_at_id (create_at, id),
    ALGORITHM = INPLACE, LOCK = NONE;

-- 수행자 아이디 검색용 인덱스 (contains는 선두 LIKE라 완전 활용 불가하나 기간 필터 후 후보 제한에 기여)
ALTER TABLE admin_action_log
    ADD INDEX idx_log_action_user_id (action_user_id),
    ALGORITHM = INPLACE, LOCK = NONE;

-- 액션 유형 동등 필터용 인덱스
ALTER TABLE admin_action_log
    ADD INDEX idx_log_action_type (action_type),
    ALGORITHM = INPLACE, LOCK = NONE;

-- =====================================================================
-- 적용 후 확인:
--   SHOW INDEX FROM admin_action_log;
--   → idx_log_create_at_id: Seq_in_index 1=create_at, 2=id 확인
-- =====================================================================
