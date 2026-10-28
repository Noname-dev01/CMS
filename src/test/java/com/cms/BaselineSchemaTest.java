package com.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cms.support.MariaDbContainerSupport;

/**
 * 기준 스키마(V1)·시드(V2)와 옛 스키마 정리(V3)·프로필 이미지 URL 축소(V4)를 새로 설치한 결과를 고정한다(PLAN-migration-squash.md 설계 4,
 * PLAN-legacy-schema-removal.md, PLAN-profile-image-url-shrink.md).
 *
 * <p>2026-10-21에 V1~V58을 합치면서 지운 업그레이드 경로 시험 가운데 "새로 설치한 DB가 어떤 모습인가"를 단언하던 부분 — 시드 행,
 * 가드·카운터 행, 유일성·FK·CHECK·기본값 같은 스키마 계약 — 을 여기로 옮겼다. 업그레이드 경로(옛 버전 데이터의 이관·멱등 재실행·
 * 실패 복구·이전 앱 롤백 호환)는 대상 DB가 없어 옮기지 않았다(V3의 업그레이드 경로는 {@code LegacySchemaRemovalUpgradeTest}).
 *
 * <p>공유 스프링 DB가 아니라 별도 스키마에 Flyway로 V1~V4를 적용해 시험한다 — 제약 위반 행을 넣어 보는 시험이 다른 시험의 데이터를
 * 더럽히지 않게 하려는 것이다. 시험마다 자동 커밋을 끈 연결을 쓰고 끝나면 되돌린다(MariaDB는 문장 오류가 트랜잭션을 깨지 않는다).
 */
class BaselineSchemaTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "baseline_schema_test";

    private Connection conn;
    private Statement st;

    private static String rootUrl() {
        return "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
    }

    private static void migrateFresh(String schema) throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + schema);
            s.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        }
        Flyway.configure()
                .dataSource(rootUrl() + schema, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeAll
    static void migrate() throws Exception {
        migrateFresh(SCHEMA);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    @BeforeEach
    void open() throws Exception {
        conn = DriverManager.getConnection(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword());
        conn.setAutoCommit(false);
        st = conn.createStatement();
    }

    @AfterEach
    void close() throws Exception {
        conn.rollback();
        conn.close();
    }

    private long count(String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> rows(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    private long insertMember(String userId) throws Exception {
        st.executeUpdate("INSERT INTO member (email, status, user_id, user_name, user_type, password_changed_at)"
                + " VALUES ('" + userId + "@example.com', 'ACTIVE', '" + userId + "', '" + userId + "', 'ROLE_MANAGER', NOW(6))");
        return count("SELECT id FROM member WHERE user_id = '" + userId + "'");
    }

    @Test
    @DisplayName("시드: 관리자 사이트 id=1·기본 공개 사이트 main id=2만 있고, 기본 사이트는 main 하나다")
    void seed_sites() throws Exception {
        assertThat(rows("SELECT id, site_key, site_type, allow_external_link + 0, use_yn + 0, default_yn + 0, create_date FROM site ORDER BY id"))
                .containsExactly("1|admin|ADMIN|0|1|0|null", "2|main|PUBLIC|1|1|1|null");
    }

    @Test
    @DisplayName("시드 다음 번호: 새 사이트 3·게시판 2·메뉴 22부터 받고, 생략한 site_id·default_yn은 관리자 사이트·0이다")
    void seed_nextIds() throws Exception {
        // 실패한 INSERT도 AUTO_INCREMENT를 소비할 수 있어(다른 시험의 제약 위반) 이 시험만 전용 스키마를 새로 만든다
        String schema = "baseline_next_id_test";
        migrateFresh(schema);
        try (Connection c = DriverManager.getConnection(rootUrl() + schema, "root", MARIA_DB.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn) VALUES ('new', '새 사이트', 'PUBLIC', 0, 1)");
            s.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('새 게시판', 1, 0, 0)");
            s.executeUpdate("INSERT INTO menu (menu_name, use_yn) VALUES ('새 메뉴', 1)");
            try (ResultSet rs = s.executeQuery("SELECT (SELECT CONCAT(id, '|', default_yn + 0) FROM site WHERE site_key = 'new'),"
                    + " (SELECT id FROM board WHERE name = '새 게시판'), (SELECT CONCAT(menu_no, '|', site_id) FROM menu WHERE menu_name = '새 메뉴')")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("3|0");
                assertThat(rs.getLong(2)).isEqualTo(2);
                assertThat(rs.getString(3)).isEqualTo("22|1");
            }
            s.execute("DROP DATABASE " + schema);
        }
    }

    @Test
    @DisplayName("시드: 관리자 사이드바 메뉴 19행 — '사이트 관리' 그룹 아래 정적 페이지·메뉴·사이트 설정, 나머지는 최상위")
    void seed_menus() throws Exception {
        assertThat(rows("SELECT menu_no, menu_name, menu_url, up_menu_no, ord, use_yn + 0, site_id FROM menu ORDER BY menu_no")).containsExactly(
                "1|대시보드|/admin|null|0|1|1",
                "3|회원 관리|null|null|2|1|1",
                "4|관리자 조회|/admin/member/manage|3|0|1|1",
                "5|관리자 추가|/admin/member/new|3|1|1|1",
                "6|내 정보|/admin/member/info|3|2|1|1",
                "7|활동 로그|/admin/log/manage|null|3|1|1",
                "9|권한 관리|/admin/permission/manage|null|5|1|1",
                "10|게시판 관리|/admin/board/manage|null|6|1|1",
                "11|게시글 관리|/admin/board/posts|null|7|1|1",
                "12|배너 관리|/admin/banner/manage|null|8|1|1",
                "13|세션 관리|/admin/session/manage|null|9|1|1",
                "14|정적 페이지 관리|/admin/site-page/manage|19|0|1|1",
                "15|팝업 관리|/admin/popup/manage|null|11|1|1",
                "16|FAQ 관리|/admin/faq/manage|null|12|1|1",
                "17|미디어 라이브러리|/admin/media/manage|null|13|1|1",
                "18|문의함|/admin/inquiry/manage|null|14|1|1",
                "19|사이트 관리|null|null|10|1|1",
                "20|메뉴 관리|/admin/menu/manage|19|1|1|1",
                "21|사이트 설정|/admin/site/manage|19|2|1|1");
    }

    @Test
    @DisplayName("시드: 공통 공지 게시판(board_key NOTICE, 공개·첨부 허용) 1개와 배너·FAQ 가드 행, 이미지 카운터 (0, 0)이 있다")
    void seed_noticeBoardAndGuardRows() throws Exception {
        assertThat(rows("SELECT id, name, public_yn + 0, attachment_yn + 0, deleted + 0, board_key, site_id FROM board"))
                .containsExactly("1|공지사항|1|1|0|NOTICE|null");
        assertThat(rows("SELECT id FROM banner_lock")).containsExactly("1");
        assertThat(rows("SELECT id, version FROM faq_lock")).containsExactly("1|0");
        assertThat(rows("SELECT id, total_bytes, total_count FROM content_image_usage")).containsExactly("1|0|0");
    }

    @Test
    @DisplayName("사이트·메뉴: 사이트 키는 유일하고, 없는 사이트를 가리키는 메뉴는 만들 수 없으며, 메뉴가 있는 사이트는 지울 수 없다(RESTRICT)")
    void siteAndMenuConstraints() throws Exception {
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn) VALUES ('main', '중복', 'PUBLIC', 0, 1)"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO menu (menu_name, use_yn, site_id) VALUES ('고아', 1, 999)"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM site WHERE id = 1")).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("게시판·게시글: board_key 유일(전용 공지 키도 사이트당 하나, NULL인 일반 게시판은 여럿), 게시글·첨부 FK RESTRICT, 첨부 storage_key 유일, 카운터 NOT NULL 기본 0")
    void boardAndPostConstraints() throws Exception {
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_key) VALUES ('중복', 1, 1, 0, 'NOTICE')"))
                .isInstanceOf(SQLException.class);
        st.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('일반 1', 1, 1, 0)");
        st.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('일반 2', 1, 1, 0)");
        st.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_key, site_id) VALUES ('main 공지', 1, 1, 0, 'NOTICE:2', 2)");
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_key, site_id) VALUES ('또', 1, 1, 0, 'NOTICE:2', 2)"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted, site_id) VALUES ('고아', 1, 1, 0, 999)"))
                .as("없는 사이트").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM site WHERE id = 2")).as("소속 게시판이 있는 사이트").isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (999999, '고아', 'c', 1, 0, 'admin')"))
                .as("없는 게시판").isInstanceOf(SQLException.class);
        st.executeUpdate("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (1, '글', '<p>본문</p>', 1, 0, 'admin')");
        long postId = count("SELECT id FROM post WHERE title = '글'");
        assertThat(count("SELECT view_count FROM post WHERE id = " + postId)).isZero();
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key) VALUES (999999, 'a.txt', 'text/plain', 1, 'k0')"))
                .as("없는 게시글").isInstanceOf(SQLException.class);
        st.executeUpdate("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key) VALUES (" + postId + ", 'a.txt', 'text/plain', 1, 'k1')");
        assertThat(count("SELECT download_count FROM post_attachment WHERE storage_key = 'k1'")).isZero();
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key) VALUES (" + postId + ", 'b.txt', 'text/plain', 1, 'k1')"))
                .as("storage_key 유일").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM post WHERE id = " + postId)).as("첨부가 있는 게시글").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM board WHERE id = 1")).as("게시글이 있는 게시판").isInstanceOf(SQLException.class);
        assertThat(rows("SELECT table_name, is_nullable FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                + "' AND column_name IN ('view_count', 'download_count') ORDER BY table_name"))
                .containsExactly("post|NO", "post_attachment|NO");
    }

    @Test
    @DisplayName("게시글 노출 기간: publish_start·publish_end는 NULL 허용 datetime(6)이고 기본값이 없으며, 제품 범위 2000-01-01~2099-12-31이 마이크로초까지 왕복한다")
    void postPublishPeriodColumns() throws Exception {
        assertThat(rows("SELECT column_name, column_type, is_nullable, column_default FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'post' AND column_name IN ('publish_start', 'publish_end') ORDER BY column_name"))
                .as("MariaDB는 DEFAULT NULL을 문자열 NULL로 보고한다")
                .containsExactly("publish_end|datetime(6)|YES|NULL", "publish_start|datetime(6)|YES|NULL");
        st.executeUpdate("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, publish_start, publish_end)"
                + " VALUES (1, '범위', 'c', 1, 0, 'admin', '2000-01-01 00:00:00.000000', '2099-12-31 23:59:59.999999')");
        assertThat(rows("SELECT publish_start, publish_end FROM post WHERE title = '범위'"))
                .containsExactly("2000-01-01 00:00:00.000000|2099-12-31 23:59:59.999999");
    }

    @Test
    @DisplayName("이미지: (namespace, storage_key) 유일 — namespace 기본값은 빈 문자열(루트)이고 루트도 NULL이 아니라 유일하며 다른 네임스페이스의 같은 키는 허용, 참조가 남은 이미지는 지울 수 없다")
    void contentImageConstraints() throws Exception {
        String insert = "INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, namespace) VALUES ('same', 'image/png', 1, 'admin', 'LIBRARY', '%s')";
        st.executeUpdate("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type) VALUES ('same', 'image/png', 1, 'admin', 'LIBRARY')");
        assertThat(rows("SELECT namespace FROM content_image WHERE storage_key = 'same'")).as("생략하면 루트").containsExactly("");
        st.executeUpdate(insert.formatted("banner"));
        assertThatThrownBy(() -> st.executeUpdate(insert.formatted(""))).isInstanceOf(SQLException.class);

        long imageId = count("SELECT id FROM content_image WHERE namespace = ''");
        st.executeUpdate("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('BANNER', 1, " + imageId + ")");
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM content_image WHERE id = " + imageId)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('BANNER', 1, 999999)"))
                .as("없는 이미지").isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("배너·팝업: 기간·링크는 NULL 허용, 필수 컬럼 NOT NULL, 배너 version 기본 1·팝업 revision 기본 0, 소속 사이트 FK RESTRICT")
    void bannerAndPopupConstraints() throws Exception {
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO banner (use_yn, ord) VALUES (1, 0)")).as("title 필수").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO banner (title, use_yn) VALUES ('t', 1)")).as("ord 필수").isInstanceOf(SQLException.class);
        st.executeUpdate("INSERT INTO banner (title, use_yn, ord) VALUES ('a', 1, 0)");
        st.executeUpdate("INSERT INTO banner (title, use_yn, ord, site_id) VALUES ('b', 1, 1, 2)");
        assertThat(rows("SELECT version, link_url, display_start, display_end FROM banner WHERE title = 'a'"))
                .containsExactly("1|null|null|null");
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO banner (title, use_yn, ord, site_id) VALUES ('c', 1, 2, 999)")).isInstanceOf(SQLException.class);

        String popup = "INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn) VALUES ('%s', 'HTML', '<p>x</p>', 0, 0, 300, 1)";
        st.executeUpdate(popup.formatted("p1"));
        st.executeUpdate(popup.formatted("p2"));
        assertThat(rows("SELECT revision, height FROM popup WHERE title = 'p1'")).containsExactly("0|null");
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO popup (title, pos_x, pos_y, width, use_yn) VALUES ('종류 없음', 0, 0, 300, 1)"))
                .as("popup_type 필수").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO popup (popup_type, pos_x, pos_y, width, use_yn) VALUES ('HTML', 0, 0, 300, 1)"))
                .as("title 필수").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO popup (title, popup_type, pos_y, width, use_yn) VALUES ('t', 'HTML', 0, 300, 1)"))
                .as("pos_x 필수").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn, site_id) VALUES ('고아', 'HTML', 'x', 0, 0, 300, 1, 999)"))
                .as("없는 사이트").isInstanceOf(SQLException.class);

        st.executeUpdate("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn) VALUES ('popup-site', 'P', 'PUBLIC', 0, 1)");
        long siteId = count("SELECT id FROM site WHERE site_key = 'popup-site'");
        st.executeUpdate("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn, site_id) VALUES ('전용', 'HTML', 'x', 0, 0, 300, 1, " + siteId + ")");
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM site WHERE id = " + siteId)).as("전용 팝업이 있는 사이트").isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("게시글 최종 수정자(V5): post.updater_id는 NULL 허용·기본값 없는 varchar(100)이고, 생략한 INSERT는 NULL이다")
    void postUpdaterIdColumn() throws Exception {
        assertThat(rows("SELECT CONCAT(column_type, '|', is_nullable, '|', IFNULL(column_default, 'NULL')) FROM information_schema.COLUMNS WHERE table_schema = '"
                + SCHEMA + "' AND table_name = 'post' AND column_name = 'updater_id'")).containsExactly("varchar(100)|YES|NULL");
        st.executeUpdate("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id) VALUES (1, '수정자 없음', 'c', 1, 0, 'admin')");
        assertThat(rows("SELECT IFNULL(updater_id, 'NULL') FROM post WHERE title = '수정자 없음'")).as("이전 앱처럼 컬럼을 모르는 INSERT").containsExactly("NULL");
    }

    @Test
    @DisplayName("게시판 표시 설정(V6): 새 컬럼 7개의 타입·NULL·기본값이 현행 동작이고, 생략한 INSERT(이전 앱)는 그 기본값을 얻는다")
    void boardDisplaySettingsColumns() throws Exception {
        assertThat(rows("SELECT CONCAT(column_name, '|', column_type, '|', is_nullable, '|', IFNULL(column_default, 'NULL')) FROM information_schema.COLUMNS"
                + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'board' AND column_name IN ('description', 'list_page_size',"
                + " 'author_label', 'show_view_count', 'new_badge_days', 'writable_yn', 'version') ORDER BY column_name")).containsExactly(
                "author_label|varchar(50)|YES|NULL",
                "description|varchar(500)|YES|NULL",
                "list_page_size|int(11)|NO|10",
                "new_badge_days|int(11)|NO|0",
                "show_view_count|bit(1)|NO|b'0'",
                "version|int(11)|NO|1",
                "writable_yn|bit(1)|NO|b'1'");
        st.executeUpdate("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('표시설정 기본값', 1, 1, 0)");
        assertThat(rows("SELECT CONCAT(list_page_size, '|', show_view_count + 0, '|', new_badge_days, '|', writable_yn + 0, '|', version) FROM board"
                + " WHERE name = '표시설정 기본값'")).as("이전 앱처럼 새 컬럼을 모르는 INSERT").containsExactly("10|0|0|1|1");
    }

    @Test
    @DisplayName("프로필 이미지 URL 축소(V4): member.profile_image_url은 NULL 허용·기본값 없는 varchar(255)다")
    void profileImageUrlShrunk() throws Exception {
        assertThat(rows("SELECT CONCAT(column_type, '|', is_nullable, '|', IFNULL(column_default, 'NULL')) FROM information_schema.COLUMNS WHERE table_schema = '"
                + SCHEMA + "' AND table_name = 'member' AND column_name = 'profile_image_url'")).containsExactly("varchar(255)|YES|NULL");
    }

    @Test
    @DisplayName("옛 스키마 정리(V3): 동결 공지 테이블·배너/팝업 옛 이미지 컬럼과 유니크·회원 LEGACY_INLINE과 인덱스가 없고, 이미지 출처(scope_type)를 생략한 INSERT는 거부된다")
    void legacySchemaRemoved() throws Exception {
        assertThat(rows("SELECT table_name FROM information_schema.TABLES WHERE table_schema = '" + SCHEMA
                + "' AND table_name IN ('notice', 'notice_attachment')")).isEmpty();
        assertThat(rows("SELECT CONCAT(table_name, '.', column_name) FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                + "' AND table_name IN ('banner', 'popup') AND column_name IN ('storage_key', 'content_type', 'file_size')")).isEmpty();
        assertThat(rows("SELECT index_name FROM information_schema.STATISTICS WHERE table_schema = '" + SCHEMA
                + "' AND index_name IN ('uk_banner_storage_key', 'uk_popup_storage_key', 'idx_member_profile_image_kind')")).isEmpty();
        assertThat(rows("SELECT column_type FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'member' AND column_name = 'profile_image_kind'")).containsExactly("enum('NONE','PRESET','UPLOADED')");
        assertThat(rows("SELECT column_default FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'content_image' AND column_name = 'scope_type'")).as("기본값 없음(NOT NULL 컬럼은 SQL NULL로 보고)").containsExactly("null");
        // 접속 sql_mode와 무관하게 거부되도록 엄격 모드를 명시한다(NOT NULL 컬럼의 기본값 없는 생략은 엄격 모드에서 오류다)
        assertThatThrownBy(() -> st.executeUpdate("SET STATEMENT sql_mode = 'STRICT_ALL_TABLES' FOR INSERT INTO content_image"
                + " (storage_key, content_type, file_size, uploader_id) VALUES ('no-scope', 'image/png', 1, 'admin')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("FAQ: 카테고리 없는 FAQ와 FAQ가 있는 카테고리 삭제는 FK가 거부하고, 필수 컬럼 NOT NULL, 카테고리 version 기본 1·가드 행 version 기본 0")
    void faqConstraints() throws Exception {
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO faq_category (ord, use_yn) VALUES (0, 1)")).as("name 필수").isInstanceOf(SQLException.class);
        st.executeUpdate("INSERT INTO faq_lock (id) VALUES (2)");
        assertThat(count("SELECT version FROM faq_lock WHERE id = 2")).isZero();
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO faq (category_id, question, answer_html, ord, use_yn) VALUES (999, 'q', 'a', 0, 1)"))
                .isInstanceOf(SQLException.class);
        st.executeUpdate("INSERT INTO faq_category (name, ord, use_yn) VALUES ('c', 0, 1)");
        long categoryId = count("SELECT id FROM faq_category WHERE name = 'c'");
        assertThat(count("SELECT version FROM faq_category WHERE id = " + categoryId)).isEqualTo(1);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO faq (category_id, answer_html, ord, use_yn) VALUES (" + categoryId + ", 'a', 0, 1)"))
                .as("question 필수").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO faq (category_id, question, ord, use_yn) VALUES (" + categoryId + ", 'q', 0, 1)"))
                .as("answer_html 필수").isInstanceOf(SQLException.class);
        st.executeUpdate("INSERT INTO faq (category_id, question, answer_html, ord, use_yn) VALUES (" + categoryId + ", 'q', 'a', 0, 1)");
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM faq_category WHERE id = " + categoryId)).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("정적 페이지: 슬러그 유일(general_ci라 대소문자 무시 — 앱은 소문자만 허용), 필수 컬럼 NOT NULL, 없는 사이트·페이지 거부, 개정 (page_id, revision_no) 중복 거부, 개정이 있는 페이지 삭제 거부")
    void sitePageConstraints() throws Exception {
        st.executeUpdate("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('terms', 1, 1, 1, 0)");
        long pageId = count("SELECT id FROM site_page WHERE slug = 'terms'");
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('terms', 1, 1, 1, 0)"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn) VALUES ('TERMS', 1, 1, 1, 0)"))
                .as("대소문자만 다른 슬러그").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO site_page (slug, version, use_yn, footer_yn) VALUES ('no-rev', 1, 1, 0)"))
                .as("current_revision_no 필수").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, site_id) VALUES ('orphan', 1, 1, 1, 0, 999)"))
                .as("없는 사이트").isInstanceOf(SQLException.class);
        String revision = "INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (%d, 1, 't', 'c', NOW(6))";
        st.executeUpdate(revision.formatted(pageId));
        assertThatThrownBy(() -> st.executeUpdate(revision.formatted(pageId))).as("개정 번호 중복").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate(revision.formatted(999999L))).as("없는 페이지").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO site_page_revision (page_id, revision_no, content, create_date) VALUES (" + pageId + ", 2, 'c', NOW(6))"))
                .as("개정 title 필수").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM site_page WHERE id = " + pageId)).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("쪽지·알림: 자기 자신에게 보내는 쪽지는 CHECK가, 없는 회원은 FK가 거부하고, 쪽지가 있는 회원은 지울 수 없다. 알림은 (회원, dedupe_key) 유일·NULL 중복 허용·회원 삭제 시 연쇄 삭제")
    void messageAndNotificationConstraints() throws Exception {
        long a = insertMember("baseline_a");
        long b = insertMember("baseline_b");
        String message = "INSERT INTO admin_message (sender_id, recipient_id, title, body, create_date) VALUES (%d, %d, 't', 'b', NOW(6))";
        assertThatThrownBy(() -> st.executeUpdate(message.formatted(a, a))).as("자기 자신").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate(message.formatted(999999L, b))).as("없는 발신자").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate(message.formatted(a, 999999L))).as("없는 수신자").isInstanceOf(SQLException.class);
        st.executeUpdate(message.formatted(a, b));

        // 발신자 상태 행: 회원당 1행(PK)이고, 발송 트랜잭션의 첫 문장인 ON DUPLICATE KEY UPDATE 형태는 오류 없이 잠금 행으로 쓰인다
        st.executeUpdate("INSERT INTO admin_message_sender_state (member_id) VALUES (" + a + ")");
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO admin_message_sender_state (member_id) VALUES (" + a + ")")).isInstanceOf(SQLException.class);
        st.executeUpdate("INSERT INTO admin_message_sender_state (member_id) VALUES (" + a + ") ON DUPLICATE KEY UPDATE member_id = member_id");
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO admin_message_sender_state (member_id) VALUES (999999)")).as("없는 회원").isInstanceOf(SQLException.class);
        st.executeUpdate("INSERT INTO admin_message_send_log (sender_id, sent_at) VALUES (" + a + ", NOW(6))");
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO admin_message_send_log (sender_id, sent_at) VALUES (999999, NOW(6))")).as("없는 회원").isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM member WHERE id = " + a)).as("쪽지를 보낸 회원(RESTRICT)").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM member WHERE id = " + b)).as("쪽지를 받은 회원(RESTRICT)").isInstanceOf(SQLException.class);

        long c = insertMember("baseline_c");
        String notification = "INSERT INTO notification (member_id, type, message, dedupe_key, create_date) VALUES (" + c + ", 'T', 'm', %s, NOW(6))";
        st.executeUpdate(notification.formatted("'k'"));
        assertThatThrownBy(() -> st.executeUpdate(notification.formatted("'k'"))).isInstanceOf(SQLException.class);
        st.executeUpdate(notification.formatted("NULL"));
        st.executeUpdate(notification.formatted("NULL"));
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO notification (member_id, type, message, create_date) VALUES (999999, 'T', 'm', NOW(6))"))
                .as("없는 회원").isInstanceOf(SQLException.class);
        st.executeUpdate("DELETE FROM member WHERE id = " + c);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE member_id = " + c)).as("CASCADE").isZero();
    }

    @Test
    @DisplayName("쪽지 인덱스: 목록·미읽음·보낸 목록·발송 이력 인덱스가 삭제 열을 포함한 계획된 컬럼 순서다 — 소량 데이터의 기능 시험으로는 누락이 드러나지 않는다")
    void messageIndexes() throws Exception {
        assertThat(rows("SELECT CONCAT(index_name, '=', GROUP_CONCAT(column_name ORDER BY seq_in_index)) FROM information_schema.STATISTICS"
                + " WHERE table_schema = '" + SCHEMA + "' AND table_name IN ('admin_message', 'admin_message_send_log') AND index_name LIKE 'idx_%'"
                + " GROUP BY index_name ORDER BY index_name")).containsExactly(
                "idx_admin_message_inbox=recipient_id,recipient_deleted_at,id",
                "idx_admin_message_send_log=sender_id,sent_at",
                "idx_admin_message_sent=sender_id,sender_deleted_at,id",
                "idx_admin_message_unread=recipient_id,recipient_deleted_at,read_at,id");
    }

    @Test
    @DisplayName("문의: 방문자 IP·User-Agent 컬럼이 없고(개인정보 최소 수집) 모든 컬럼이 NOT NULL이며 body는 text, 인덱스는 PRIMARY와 상태 탭 (status, id)뿐이다")
    void inquiryColumns() throws Exception {
        assertThat(rows("SELECT column_name, is_nullable, column_type FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'inquiry' ORDER BY ordinal_position")).containsExactly(
                "id|NO|bigint(20)", "name|NO|varchar(50)", "email|NO|varchar(100)", "title|NO|varchar(100)", "body|NO|text",
                "status|NO|varchar(20)", "consent_at|NO|datetime(6)", "create_date|NO|datetime(6)", "update_date|NO|datetime(6)");
        assertThat(rows("SELECT CONCAT(index_name, '=', GROUP_CONCAT(column_name ORDER BY seq_in_index)) FROM information_schema.STATISTICS"
                + " WHERE table_schema = '" + SCHEMA + "' AND table_name = 'inquiry' GROUP BY index_name ORDER BY index_name"))
                .containsExactlyInAnyOrder("PRIMARY=id", "idx_inquiry_status_id=status,id");
    }

    @Test
    @DisplayName("감사 로그: target_label은 NULL 허용 varchar(500)이다(엔티티 @Column(length = 500), 앱이 500자로 자른다)")
    void adminActionLogTargetLabel() throws Exception {
        assertThat(rows("SELECT column_type, is_nullable FROM information_schema.COLUMNS WHERE table_schema = '" + SCHEMA
                + "' AND table_name = 'admin_action_log' AND column_name = 'target_label'")).containsExactly("varchar(500)|YES");
    }
}
