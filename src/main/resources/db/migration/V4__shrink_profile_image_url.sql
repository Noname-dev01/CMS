-- ------------------------------------------------------------
-- 프로필 이미지 URL 컬럼 축소 (PLAN-profile-image-url-shrink.md). 옛 Base64 data URI를 담던 LONGTEXT를 varchar(255)로 줄인다.
-- 지금 이 컬럼에는 FileStorage 'profile' 네임스페이스의 키(최대 58자)나 정적 프리셋 경로만 들어간다. 순수 DDL 한 문장이다.
--
-- 255자를 넘는 값이 있으면 엄격 모드에서 오류로 멈추고 아무것도 바뀌지 않는다(비엄격 모드의 MODIFY는 값을 조용히 자른다).
-- 서버 기본 sql_mode에 기대지 않으려고 이 문장에만 STRICT_ALL_TABLES를 고정한다.
-- 한계: 잘리는 부분이 공백뿐이면 엄격 모드에서도 오류가 아닐 수 있다. 앱 경로로는 공백이 든 값을 만들 수 없다.
-- ------------------------------------------------------------
SET STATEMENT sql_mode = 'STRICT_ALL_TABLES' FOR
ALTER TABLE `member` MODIFY `profile_image_url` varchar(255) DEFAULT NULL;
