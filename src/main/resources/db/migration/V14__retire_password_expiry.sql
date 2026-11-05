-- ------------------------------------------------------------
-- 비밀번호 90일 만료 폐지(PLAN-password-expiry-retirement-breach-check.md D8). DML만 있다.
-- ① 이 시점의 PASSWORD_EXPIRED 행은 전부 90일 전이의 결과다(관리자 지정 경로는 이 버전에서 처음 생긴다). 만료를 없애므로 ACTIVE로 되돌린다.
-- ② PASSWORD_EXPIRY 알림(E3)을 지운다 — 앱의 NotificationType에서 값이 사라져 남은 행을 읽으면 알림 조회가 enum 변환으로 실패한다.
-- 다시 실행해도 결과가 같다. password_changed_at 컬럼은 변경 시각 기록과 이전 앱 롤백을 위해 유지한다.
-- 구버전을 모두 멈춘 뒤 신버전을 띄워야 한다 — 구버전이 남아 있으면 이 뒤에 만료 전이와 알림을 다시 만든다(docs/deployment.md).
-- ------------------------------------------------------------
UPDATE `member` SET `status` = 'ACTIVE' WHERE `status` = 'PASSWORD_EXPIRED';

DELETE FROM `notification` WHERE `type` = 'PASSWORD_EXPIRY';
