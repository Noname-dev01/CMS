-- 관리자 로그인 "로그인 상태 유지"(remember-me) 영속 토큰 (2026-11-10, 이슈 #222, adversarial-review/plan/PLAN-remember-me-login.md 쟁점 6)
-- 테이블·칼럼 이름은 Spring Security JdbcTokenRepositoryImpl 기본값이다.
-- series·token은 바이너리 비교(ascii_bin) — 기본 utf8mb4_general_ci면 대소문자만 바꾼 시리즈가 같은 행을 찾아 세션 ↔ 기기 연결 비교를 빠져나간다(v6).
-- username은 member.user_id와 같은 길이·콜레이션. FK는 두지 않는다(회원은 삭제가 아니라 상태 전이, 폐기는 앱이 맡는다).
CREATE TABLE IF NOT EXISTS `persistent_logins` (
  `series` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `username` varchar(50) NOT NULL,
  `token` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `last_used` datetime(6) NOT NULL,
  PRIMARY KEY (`series`),
  KEY `idx_persistent_logins_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
