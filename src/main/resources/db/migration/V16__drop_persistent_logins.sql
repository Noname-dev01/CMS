-- 로그인 상태 유지(remember-me) 제거 (2026-11-12, adversarial-review/plan/PLAN-remember-me-removal.md 쟁점 3)
-- V15가 만든 영속 토큰 테이블을 지운다. 토큰은 해시 없이 저장돼 있어 DB·백업에 남기지 않는다.
-- DDL 한 문장, IF EXISTS라 테이블을 이미 손으로 지운 DB에서도 성공한다.
-- 이전 버전 앱으로 되돌리려면 먼저 V15의 CREATE TABLE을 수동 실행해야 한다(docs/migration-guide.md V16 행).
DROP TABLE IF EXISTS `persistent_logins`;
