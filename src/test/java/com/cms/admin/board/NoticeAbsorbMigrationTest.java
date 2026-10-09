package com.cms.admin.board;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V31(board_key)·V32(공지 → 공지 게시판 이관) — adversarial-review/plan/PLAN-notice-to-board.md 쟁점 3~10.
 * V30 DB에 공지·첨부·본문 이미지·MANAGER 권한·메뉴를 채운 뒤 최신까지 올려 이관 정합(건수·ID·플래그·첨부 바이트·이미지·권한·메뉴)과
 * 충돌 재번호, 빈 DB, 메뉴 상태별 규칙을 고정한다.
 */
class NoticeAbsorbMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "notice_absorb_test";

    private static String rootUrl() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String string(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** 빈 V30 DB(이관 전 최신 스키마). V9·V30 메뉴 시드가 들어 있다. */
    private static void v30Database() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        flyway("30").migrate();
    }

    private static void insertMember(Statement st, String userId, String role, String status) throws Exception {
        st.execute("INSERT INTO member (user_id, pwd, user_name, email, user_type, status, password_changed_at) VALUES ('"
                + userId + "', 'x', '" + userId + "', '" + userId + "@example.com', '" + role + "', '" + status + "', NOW())");
    }

    private static long memberId(Statement st, String userId) throws Exception {
        return count(st, "SELECT id FROM member WHERE user_id = '" + userId + "'");
    }

    private static void insertNotice(Statement st, long id, String title, boolean useYn, boolean deleted) throws Exception {
        st.execute("INSERT INTO notice (id, title, content, use_yn, deleted, author_id, create_date, update_date) VALUES ("
                + id + ", '" + title + "', '<p>" + title + "</p>', " + (useYn ? 1 : 0) + ", " + (deleted ? 1 : 0)
                + ", 'writer', '2026-09-0" + id + " 10:00:00.123456', '2026-09-1" + id + " 11:00:00.654321')");
    }

    private static void insertNoticeAttachment(Statement st, long id, long noticeId, String key, long size) throws Exception {
        st.execute("INSERT INTO notice_attachment (id, notice_id, original_filename, content_type, file_size, storage_key, create_date)"
                + " VALUES (" + id + ", " + noticeId + ", 'f" + id + ".pdf', 'application/pdf', " + size + ", '" + key + "', '2026-09-05 12:00:00')");
    }

    private static long noticeBoardId(Statement st) throws Exception {
        return count(st, "SELECT id FROM board WHERE board_key = 'NOTICE'");
    }

    @Test
    @DisplayName("이관: 공지·첨부가 같은 ID·플래그·날짜·바이트로 공지 게시판에 복사되고 동결 테이블은 그대로다")
    void copiesNoticesAndAttachmentsKeepingIds() throws Exception {
        v30Database();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            insertNotice(st, 1, "공개공지", true, false);
            insertNotice(st, 2, "비노출공지", false, false);
            insertNotice(st, 3, "삭제공지", true, true);
            insertNoticeAttachment(st, 1, 1, "2026/09/05/a.bin", 111);
            insertNoticeAttachment(st, 2, 3, "2026/09/05/b.bin", 222);
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long nb = noticeBoardId(st);
            assertThat(string(st, "SELECT name FROM board WHERE id = " + nb)).isEqualTo("공지사항");
            assertThat(count(st, "SELECT public_yn + attachment_yn * 2 + deleted * 4 FROM board WHERE id = " + nb)).isEqualTo(3);

            assertThat(count(st, "SELECT COUNT(*) FROM post WHERE board_id = " + nb)).isEqualTo(3);
            assertThat(count(st, "SELECT COUNT(*) FROM post p JOIN notice n ON n.id = p.id AND n.title = p.title"
                    + " AND n.content = p.content AND n.use_yn = p.use_yn AND n.deleted = p.deleted AND n.author_id = p.author_id"
                    + " AND n.create_date = p.create_date AND n.update_date = p.update_date WHERE p.board_id = " + nb)).isEqualTo(3);
            assertThat(count(st, "SELECT COUNT(*) FROM post WHERE id = 2 AND use_yn = 0 AND deleted = 0")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM post WHERE id = 3 AND deleted = 1")).isEqualTo(1);

            assertThat(count(st, "SELECT COUNT(*) FROM post_attachment pa JOIN notice_attachment na ON na.id = pa.id"
                    + " AND na.notice_id = pa.post_id AND na.storage_key = pa.storage_key AND na.file_size = pa.file_size"
                    + " AND na.original_filename = pa.original_filename AND na.content_type = pa.content_type")).isEqualTo(2);
            assertThat(count(st, "SELECT SUM(file_size) FROM post_attachment")).isEqualTo(333);

            // 동결: 앱은 더 읽지 않지만 DROP 전까지 이관 시점 스냅샷이 그대로 남는다
            assertThat(count(st, "SELECT COUNT(*) FROM notice")).isEqualTo(3);
            assertThat(count(st, "SELECT COUNT(*) FROM notice_attachment")).isEqualTo(2);

            // AUTO_INCREMENT: 이관 뒤 새 게시글은 공지 ID와 겹치지 않는다
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (" + nb + ", 'new', 'x', 1, 0, 'a')");
            assertThat(count(st, "SELECT MAX(id) FROM post")).isGreaterThan(3);
            st.execute("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key)"
                    + " VALUES (1, 'n.pdf', 'application/pdf', 1, 'k-new')");
            assertThat(count(st, "SELECT MAX(id) FROM post_attachment")).isGreaterThan(2);
        }
    }

    @Test
    @DisplayName("이관: 공지가 0건이어도 공지 게시판은 만들어지고 board_key는 유일하다")
    void createsNoticeBoardEvenWithoutNotices() throws Exception {
        v30Database();

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM board WHERE board_key = 'NOTICE'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM post")).isZero();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute(
                    "INSERT INTO board (name, public_yn, attachment_yn, deleted, board_key) VALUES ('dup', 1, 1, 0, 'NOTICE')"))
                    .isInstanceOf(java.sql.SQLException.class);
            // 일반 게시판(board_key NULL)은 여러 개 만들 수 있다
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('a', 1, 1, 0)");
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('b', 1, 1, 0)");
        }
    }

    @Test
    @DisplayName("이관: 공지 ID 범위에 있던 기존 게시글·첨부·이미지 참조는 범위 밖으로 재번호되고 서로 연결이 유지된다")
    void renumbersConflictingPostsAndKeepsLinks() throws Exception {
        v30Database();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            insertNotice(st, 1, "공지1", true, false);
            insertNotice(st, 2, "공지2", true, false);
            insertNoticeAttachment(st, 1, 1, "2026/09/05/n.bin", 10);
            st.execute("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('기존', 1, 1, 0)");
            long other = count(st, "SELECT id FROM board WHERE name = '기존'");
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (" + other + ", '기존글1', 'x', 1, 0, 'a')");
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (" + other + ", '기존글2', 'x', 1, 0, 'a')");
            long existing1 = count(st, "SELECT id FROM post WHERE title = '기존글1'");
            st.execute("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key)"
                    + " VALUES (" + existing1 + ", 'p.pdf', 'application/pdf', 5, 'k-existing')");
            st.execute("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, scope_id)"
                    + " VALUES ('2026/10/08/b.png', 'image/png', 7, 'admin', 'BOARD', " + other + ")");
            long image = count(st, "SELECT id FROM content_image WHERE storage_key = '2026/10/08/b.png'");
            st.execute("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', " + existing1 + ", " + image + ")");
            assertThat(existing1).isLessThanOrEqualTo(2);
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long nb = noticeBoardId(st);
            // 공지는 자기 ID 그대로
            assertThat(count(st, "SELECT COUNT(*) FROM post WHERE board_id = " + nb + " AND id IN (1, 2)")).isEqualTo(2);
            // 기존 글은 범위 밖으로 이동, 첨부·이미지 참조가 같은 새 ID를 따라간다
            long moved = count(st, "SELECT id FROM post WHERE title = '기존글1'");
            assertThat(moved).isGreaterThan(2);
            assertThat(count(st, "SELECT COUNT(*) FROM post_attachment WHERE storage_key = 'k-existing' AND post_id = " + moved)).isEqualTo(1);
            assertThat(count(st, "SELECT id FROM post_attachment WHERE storage_key = 'k-existing'")).isGreaterThan(1);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = " + moved)).isEqualTo(1);
            // 공지 첨부 1번은 그대로 공지 1번 글에
            assertThat(count(st, "SELECT COUNT(*) FROM post_attachment WHERE id = 1 AND post_id = 1 AND storage_key = '2026/09/05/n.bin'")).isEqualTo(1);
            // 다음 INSERT도 충돌하지 않는다
            st.execute("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (" + nb + ", 'new', 'x', 1, 0, 'a')");
            assertThat(count(st, "SELECT COUNT(*) FROM post")).isEqualTo(5);   // 기존 글 2 + 공지 2 + 새 글 1
        }
    }

    @Test
    @DisplayName("이관: 공지 본문 이미지는 공지 게시판 출처로 바뀌고 참조는 NOTICE에서 POST로 이동한다(NOTICE 참조 0건)")
    void movesImageScopeAndReferences() throws Exception {
        v30Database();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            insertNotice(st, 1, "공지1", true, false);
            st.execute("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id) VALUES ('2026/10/08/c.png', 'image/png', 9, 'admin')");
            long image = count(st, "SELECT id FROM content_image WHERE storage_key = '2026/10/08/c.png'");
            st.execute("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('NOTICE', 1, " + image + ")");
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long nb = noticeBoardId(st);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE scope_type = 'BOARD' AND scope_id = " + nb)).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM content_image WHERE scope_type = 'NOTICE'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'NOTICE'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = 1")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("이관: MANAGER 공지 권한은 공지 게시판 권한으로 이동한다 — READ 있는 활성 MANAGER만, 버전 +1, NOTICE 행은 전부 삭제")
    void movesManagerPermissions() throws Exception {
        v30Database();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            insertMember(st, "m_ok", "ROLE_MANAGER", "ACTIVE");
            insertMember(st, "m_writeonly", "ROLE_MANAGER", "ACTIVE");
            insertMember(st, "m_variant", "ROLE_MANAGER", "ACTIVE");
            insertMember(st, "m_deleted", "ROLE_MANAGER", "DELETED");
            insertMember(st, "m_none", "ROLE_MANAGER", "ACTIVE");
            long ok = memberId(st, "m_ok");
            long writeOnly = memberId(st, "m_writeonly");
            long variant = memberId(st, "m_variant");
            long deleted = memberId(st, "m_deleted");
            long none = memberId(st, "m_none");
            for (String action : new String[]{"READ", "CREATE", "UPDATE"}) {
                st.execute("INSERT INTO member_permission (member_id, feature, action) VALUES (" + ok + ", 'NOTICE', '" + action + "')");
            }
            st.execute("INSERT INTO member_permission (member_id, feature, action) VALUES (" + writeOnly + ", 'NOTICE', 'CREATE')");
            st.execute("INSERT INTO member_permission (member_id, feature, action) VALUES (" + variant + ", 'notice', 'READ')");
            st.execute("INSERT INTO member_permission (member_id, feature, action) VALUES (" + deleted + ", 'NOTICE', 'READ')");
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long nb = noticeBoardId(st);
            long ok = memberId(st, "m_ok");
            assertThat(count(st, "SELECT COUNT(*) FROM member_board_permission WHERE board_id = " + nb + " AND member_id = " + ok)).isEqualTo(3);
            assertThat(count(st, "SELECT COUNT(*) FROM member_board_permission WHERE board_id = " + nb + " AND member_id = " + ok
                    + " AND action IN ('READ', 'CREATE', 'UPDATE')")).isEqualTo(3);
            // READ 없는 쓰기 행·변형 행·DELETED 회원은 옮기지 않는다
            assertThat(count(st, "SELECT COUNT(*) FROM member_board_permission")).isEqualTo(3);
            // NOTICE 행은 전부 지워진다(롤백해도 회수한 권한이 되살아나지 않는다)
            assertThat(count(st, "SELECT COUNT(*) FROM member_permission WHERE feature = 'NOTICE'")).isZero();
            // 행이 있던 회원은 모두 버전 +1, 없던 회원은 그대로
            assertThat(count(st, "SELECT permission_version FROM member WHERE user_id = 'm_ok'")).isEqualTo(1);
            assertThat(count(st, "SELECT permission_version FROM member WHERE user_id = 'm_writeonly'")).isEqualTo(1);
            assertThat(count(st, "SELECT permission_version FROM member WHERE user_id = 'm_variant'")).isEqualTo(1);
            assertThat(count(st, "SELECT permission_version FROM member WHERE user_id = 'm_deleted'")).isEqualTo(1);
            assertThat(count(st, "SELECT permission_version FROM member WHERE user_id = 'm_none'")).isZero();
        }
    }

    // ───────── 메뉴 규칙 (쟁점 5⑦) ─────────

    private static long menuNo(Statement st, String where) throws Exception {
        return count(st, "SELECT menu_no FROM menu WHERE " + where);
    }

    /** V30 DB(공지 메뉴 + 게시글 관리 메뉴가 모두 최상위 활성 리프)에서 시작해 시나리오를 만들고 최신으로 올린다. */
    private static void migrateWith(String... setupSql) throws Exception {
        v30Database();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/notice/manage'")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts'")).isEqualTo(1);
            for (String sql : setupSql) {
                st.execute(sql);
            }
        }
        flyway(null).migrate();
    }

    @Test
    @DisplayName("메뉴 ① 깨끗한 게시글 관리 행이 있고 공지 메뉴가 리프면 공지 메뉴만 삭제")
    void menuDefaultDeletesNoticeLeaf() throws Exception {
        migrateWith();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/notice/manage'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("메뉴 ② 게시글 관리 행이 없으면 공지 메뉴가 게시글 관리 링크로 전환된다(위치·활성 유지)")
    void menuConvertsNoticeWhenPostsMenuMissing() throws Exception {
        migrateWith("DELETE FROM menu WHERE menu_url = '/admin/board/posts'");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/notice/manage'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts' AND menu_name = '게시글 관리'"
                    + " AND use_yn = 1 AND up_menu_no IS NULL")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("메뉴 ③ 게시글 관리 행이 비활성이면 공지 메뉴가 전환되고 비활성 행은 그대로다")
    void menuConvertsNoticeWhenPostsMenuInactive() throws Exception {
        migrateWith("UPDATE menu SET use_yn = 0 WHERE menu_url = '/admin/board/posts'");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/notice/manage'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts' AND use_yn = 1")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts' AND use_yn = 0")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("메뉴 ④ 게시글 관리 행에 자식이 있으면(링크가 되지 못함) 공지 메뉴가 전환된다")
    void menuConvertsNoticeWhenPostsMenuHasChildren() throws Exception {
        migrateWith("INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no)"
                + " SELECT '자식', '/x', 1, 0, menu_no FROM menu WHERE menu_url = '/admin/board/posts'");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/notice/manage'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts' AND menu_name = '게시글 관리'")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("메뉴 ⑤ 공지 메뉴에 자식이 있고 깨끗한 대체 행이 있으면 공지 메뉴는 URL만 비우고 자식과 함께 남는다")
    void menuKeepsNoticeGroupWithChildrenWhenReplacementClean() throws Exception {
        migrateWith("INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no)"
                + " SELECT '하위', '/y', 0, 0, menu_no FROM menu WHERE menu_url = '/admin/notice/manage'");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_name = '공지사항 관리' AND menu_url IS NULL")).isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu c JOIN menu p ON c.up_menu_no = p.menu_no WHERE p.menu_name = '공지사항 관리' AND c.menu_name = '하위'"))
                    .isEqualTo(1);
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("메뉴 ⑥ 공지 메뉴에 자식이 있고 대체 행이 없으면 계층을 보존한 채 공지 메뉴를 게시글 관리 링크로 전환한다")
    void menuConvertsNoticeGroupWithChildrenWhenNoReplacement() throws Exception {
        migrateWith("DELETE FROM menu WHERE menu_url = '/admin/board/posts'",
                "INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no)"
                        + " SELECT '하위', '/y', 0, 0, menu_no FROM menu WHERE menu_url = '/admin/notice/manage'");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/notice/manage'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu c JOIN menu p ON c.up_menu_no = p.menu_no"
                    + " WHERE p.menu_url = '/admin/board/posts' AND p.menu_name = '게시글 관리' AND c.menu_name = '하위'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("메뉴 ⑦ 게시글 관리 행의 URL 대소문자가 다르면(앱은 완전 일치로 판정) 대체 행으로 치지 않고 공지 메뉴를 전환한다")
    void menuConvertsNoticeWhenPostsMenuUrlCaseDiffers() throws Exception {
        migrateWith("UPDATE menu SET menu_url = '/admin/BOARD/posts' WHERE menu_url = '/admin/board/posts'");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/notice/manage'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE BINARY menu_url = '/admin/board/posts' AND menu_name = '게시글 관리'"))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("메뉴 ⑧ 게시글 관리 행이 4단에 있으면(사이드바 3단 초과) 대체 행으로 치지 않고 공지 메뉴를 전환한다")
    void menuConvertsNoticeWhenPostsMenuIsTooDeep() throws Exception {
        migrateWith("INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no) VALUES ('R', NULL, 1, 0, NULL)",
                "SET @r = LAST_INSERT_ID()",
                "INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no) VALUES ('G', NULL, 1, 0, @r)",
                "SET @g = LAST_INSERT_ID()",
                "INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no) VALUES ('A', NULL, 1, 0, @g)",
                "SET @a = LAST_INSERT_ID()",
                "UPDATE menu SET up_menu_no = @a WHERE menu_url = '/admin/board/posts'");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/notice/manage'")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/board/posts' AND menu_name = '게시글 관리'"))
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("회수 절차 ready() 가드의 SQL — V31 DB에서는 V32 성공 행이 0건, 최신 DB에서는 1건")
    void flywayHistoryGuardDetectsV32() throws Exception {
        v30Database();
        flyway("31").migrate();
        String guard = "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '32' AND success = 1";
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, guard)).isZero();
        }

        flyway(null).migrate();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertThat(count(st, guard)).isEqualTo(1);
        }
    }
}
