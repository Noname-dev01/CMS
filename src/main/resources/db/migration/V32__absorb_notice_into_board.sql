-- ============================================================
-- V32: 공지사항을 "공지" 게시판으로 흡수하는 데이터 이관 (2026-10-09)
--
-- adversarial-review/plan/PLAN-notice-to-board.md 쟁점 3~10. 순수 DML — 한 트랜잭션이라 중간 실패는 전부 롤백된다.
-- ⚠️ 되돌리기 어렵다: V32 이후 앱만 이전 버전으로 되돌리는 롤백은 지원하지 않는다(권한·이미지 출처·공지 쓰기가 얽힌다).
--    배포 전 앱 정지 → 정지 상태 정규 백업이 전제이고 복구는 그 백업 복원뿐이다(docs/migration-guide.md "V31~V32").
--
-- 순서: ① 공지 게시판 ② 충돌하는 기존 게시글·첨부 재번호 ③ 공지 → 게시글 ④ 공지 첨부 → 게시글 첨부
--       ⑤ 본문 이미지 출처·참조 이동 ⑥ MANAGER 공지 권한 이동 ⑦ 공지 메뉴 정리.
-- notice·notice_attachment는 지우지 않는다(이관 시점 동결 스냅샷, 후속 PR에서 DROP) — 앱은 더 이상 읽지 않는다.
-- 날짜는 원본을 그대로 옮긴다(DB NOW()와 앱 KST Clock의 시간대 혼용을 피한다).
-- ============================================================

-- ① 공지 게시판(board_key='NOTICE'). 공지가 0건이어도 만든다(/notices가 빈 목록으로 동작). 공개·첨부 허용 — 이전 공지와 같다.
INSERT INTO `board` (`name`, `public_yn`, `attachment_yn`, `deleted`, `board_key`, `create_date`, `update_date`)
SELECT '공지사항', 1, 1, 0, 'NOTICE', NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `board` WHERE `board_key` = 'NOTICE');

SET @nb := (SELECT `id` FROM `board` WHERE `board_key` = 'NOTICE');
SET @max_notice := (SELECT COALESCE(MAX(`id`), 0) FROM `notice`);
SET @post_offset := GREATEST(@max_notice, (SELECT COALESCE(MAX(`id`), 0) FROM `post`));
SET @max_attachment := (SELECT COALESCE(MAX(`id`), 0) FROM `notice_attachment`);
SET @attachment_offset := GREATEST(@max_attachment, (SELECT COALESCE(MAX(`id`), 0) FROM `post_attachment`));

-- ② 공지 ID·첨부 ID를 그대로 쓰려고, 그 범위에 이미 있는 게시글·첨부를 범위 밖으로 옮긴다(+offset이면 어떤 기존 ID와도 겹치지 않는다).
--    충돌이 없으면(일반적) 아무 행도 바뀌지 않는다. 게시글 ID가 바뀌면 그 글의 공개 URL이 바뀐다(감사 로그의 옛 targetId도 어긋난다).
SET FOREIGN_KEY_CHECKS = 0;
UPDATE `content_image_ref` SET `owner_id` = `owner_id` + @post_offset
 WHERE `owner_type` = 'POST' AND `owner_id` <= @max_notice;
UPDATE `post_attachment` SET `post_id` = `post_id` + @post_offset WHERE `post_id` <= @max_notice;
UPDATE `post` SET `id` = `id` + @post_offset WHERE `id` <= @max_notice;
UPDATE `post_attachment` SET `id` = `id` + @attachment_offset WHERE `id` <= @max_attachment;
SET FOREIGN_KEY_CHECKS = 1;

-- ③ 공지 → 게시글(ID·날짜 보존). 이미 같은 ID가 있으면 키 중복으로 실패해 전체가 롤백된다(조용히 건너뛰지 않는다).
INSERT INTO `post` (`id`, `board_id`, `title`, `content`, `use_yn`, `deleted`, `author_id`, `create_date`, `update_date`)
SELECT n.`id`, @nb, n.`title`, n.`content`, n.`use_yn`, n.`deleted`, n.`author_id`, n.`create_date`, n.`update_date`
FROM `notice` n;

-- ④ 공지 첨부 → 게시글 첨부(ID 보존, storage_key는 같은 값 — 파일은 옮기지 않는다)
INSERT INTO `post_attachment` (`id`, `post_id`, `original_filename`, `content_type`, `file_size`, `storage_key`, `create_date`)
SELECT a.`id`, a.`notice_id`, a.`original_filename`, a.`content_type`, a.`file_size`, a.`storage_key`, a.`create_date`
FROM `notice_attachment` a;

-- ⑤ 본문 이미지: 공지 출처 → 공지 게시판 출처, 공지 참조 → 게시글 참조로 "이동"한다(구 NOTICE 참조를 남기면 이관된 글에서
--    이미지를 지워도 수동 회수가 사용 중으로 판정해 업로드 상한을 영구 점유한다).
UPDATE `content_image` SET `scope_type` = 'BOARD', `scope_id` = @nb WHERE `scope_type` = 'NOTICE';
INSERT INTO `content_image_ref` (`owner_type`, `owner_id`, `image_id`)
SELECT 'POST', `owner_id`, `image_id` FROM `content_image_ref` WHERE `owner_type` = 'NOTICE';
DELETE FROM `content_image_ref` WHERE `owner_type` = 'NOTICE';

-- ⑥ MANAGER 공지 권한 → 공지 게시판 권한(이동). V19와 같은 기준 — 정확 일치(BINARY)·READ가 있는 회원만·DELETED 제외.
--    해당 회원의 permission_version을 올려 낡은 권한 화면의 PUT이 이관된 권한을 조용히 회수하지 못하게 한다(409).
--    NOTICE 행은 변형·READ 없는 쓰기 행까지 전부 지운다(기능을 카탈로그에서 제거하는 마이그레이션의 규약; 남기면 롤백 시 회수 권한이 되살아난다).
INSERT INTO `member_board_permission` (`member_id`, `board_id`, `action`)
SELECT mp.`member_id`, @nb, mp.`action`
FROM `member_permission` mp
JOIN `member` m ON m.`id` = mp.`member_id`
WHERE m.`user_type` = 'ROLE_MANAGER'
  AND m.`status` <> 'DELETED'
  AND BINARY mp.`feature` = 'NOTICE'
  AND BINARY mp.`action` IN ('READ', 'CREATE', 'UPDATE', 'DELETE')
  AND EXISTS (SELECT 1 FROM `member_permission` r
              WHERE r.`member_id` = mp.`member_id` AND BINARY r.`feature` = 'NOTICE' AND BINARY r.`action` = 'READ');
UPDATE `member` SET `permission_version` = `permission_version` + 1
 WHERE `id` IN (SELECT DISTINCT `member_id` FROM `member_permission` WHERE `feature` = 'NOTICE');
DELETE FROM `member_permission` WHERE `feature` = 'NOTICE';

-- ⑦ 공지 메뉴(N = '/admin/notice/manage') 정리. "깨끗한" 게시글 관리 행 = '/admin/board/posts'(대소문자 정확 일치) 활성·자식 없음·상위 체인(최대 2단계) 활성이고 3단 이내(맨 위가 루트).
--    있으면: N이 리프면 삭제(중복 제거), 자식이 있으면 menu_url만 비운다(행·계층 보존 — MANAGER는 대체 행으로 진입).
--    없으면: 자식 유무와 무관하게 N을 게시글 관리 링크로 전환한다(위치·활성 상태·계층 유지 — 렌더러가 보이는 자식이 있으면 그룹,
--    없으면 부모 URL 링크로 처리하므로 기존 진입 경로가 보존된다).
SET @clean_posts_menu := (
  SELECT COUNT(*) FROM `menu` p
  WHERE BINARY p.`menu_url` = '/admin/board/posts' AND p.`use_yn` = 1
    AND NOT EXISTS (SELECT 1 FROM `menu` c WHERE c.`up_menu_no` = p.`menu_no`)
    AND (p.`up_menu_no` IS NULL
         OR EXISTS (SELECT 1 FROM `menu` a WHERE a.`menu_no` = p.`up_menu_no` AND a.`use_yn` = 1
                    AND (a.`up_menu_no` IS NULL
                         OR EXISTS (SELECT 1 FROM `menu` g WHERE g.`menu_no` = a.`up_menu_no` AND g.`use_yn` = 1
                                    AND g.`up_menu_no` IS NULL))))
);

DELETE n FROM `menu` n
LEFT JOIN (SELECT DISTINCT `up_menu_no` FROM `menu` WHERE `up_menu_no` IS NOT NULL) ch ON ch.`up_menu_no` = n.`menu_no`
WHERE n.`menu_url` = '/admin/notice/manage' AND @clean_posts_menu > 0 AND ch.`up_menu_no` IS NULL;

UPDATE `menu` n
JOIN (SELECT DISTINCT `up_menu_no` FROM `menu` WHERE `up_menu_no` IS NOT NULL) ch ON ch.`up_menu_no` = n.`menu_no`
SET n.`menu_url` = NULL
WHERE n.`menu_url` = '/admin/notice/manage' AND @clean_posts_menu > 0;

UPDATE `menu` SET `menu_url` = '/admin/board/posts', `menu_name` = '게시글 관리'
WHERE `menu_url` = '/admin/notice/manage' AND @clean_posts_menu = 0;
