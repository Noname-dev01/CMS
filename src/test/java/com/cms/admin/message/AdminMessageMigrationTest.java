package com.cms.admin.message;

import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V21(쪽지 테이블 3종)의 적용·제약·실패 복구·롤백 호환(PLAN-admin-message.md §4·§7 ①, R1-12·R1-13).
 * 별도 스키마에서 V20까지 만든 뒤 V21을 적용한다. 컨텍스트가 실제로 뜨는지({@code ddl-auto: validate})는 공용 Testcontainers 구성이
 * V1부터 최신까지 적용한 DB로 모든 통합 테스트를 기동하는 것으로 고정돼 있다.
 */
class AdminMessageMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "upgrade_v21_test";
    private static final List<String> TABLES =
            List.of("admin_message", "admin_message_sender_state", "admin_message_send_log");

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

    private static void freshSchema() throws Exception {
        try (Connection root = DriverManager.getConnection(rootUrl(), "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
    }

    /** V21 파일의 DDL을 문장별로 쪼갠다(주석 제거 후 세미콜론 기준 — 문장 안에 세미콜론이 없다). */
    private static List<String> statementsOf(String file) throws Exception {
        String sql = new String(AdminMessageMigrationTest.class.getResourceAsStream("/db/migration/" + file).readAllBytes(),
                StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
        List<String> statements = new ArrayList<>();
        for (String part : sql.split(";")) {
            if (!part.isBlank()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

    private static long tableCount(Statement st) throws Exception {
        return count(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                + "' AND table_name IN ('admin_message', 'admin_message_sender_state', 'admin_message_send_log')");
    }

    private static long success(Statement st, int success) throws Exception {
        return count(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '21' AND success = " + success);
    }

    private static long insertMember(Statement st, String userId) throws Exception {
        st.execute("INSERT INTO member (user_id, pwd, user_name, email, user_type, status, create_date, update_date, "
                + "password_changed_at, failed_login_count, permission_version) VALUES ('" + userId + "', 'x', '이름', '"
                + userId + "@t.example', 'ROLE_MANAGER', 'ACTIVE', NOW(6), NOW(6), NOW(6), 0, 0)");
        return count(st, "SELECT id FROM member WHERE user_id = '" + userId + "'");
    }

    private static void insertMessage(Statement st, long sender, long recipient) throws SQLException {
        st.execute("INSERT INTO admin_message (sender_id, recipient_id, title, body, create_date) VALUES (" + sender + ", "
                + recipient + ", '제목', '본문', NOW(6))");
    }

    /** migration-guide "V21 실패 복구"의 데이터 확인 질의 — 세 테이블의 행 수 합. */
    private static long residueRows(Statement st) throws Exception {
        return count(st, "SELECT (SELECT COUNT(*) FROM admin_message) + (SELECT COUNT(*) FROM admin_message_sender_state)"
                + " + (SELECT COUNT(*) FROM admin_message_send_log)");
    }

    private static void applyV21DdlWithoutHistory(Statement st) throws Exception {
        for (String ddl : statementsOf("V21__create_admin_message.sql")) {
            st.execute(ddl);
        }
    }

    @Test
    @DisplayName("V20 DB에 V21을 올리면 세 테이블이 만들어지고 FK는 RESTRICT·자기 자신 쪽지는 CHECK로 거부·없는 회원은 FK로 거부된다")
    void upgrade_createsTables_withRestrictAndCheck() throws Exception {
        freshSchema();
        flyway("20").migrate();
        flyway(null).migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(tableCount(st)).isEqualTo(3);
            assertThat(success(st, 1)).isEqualTo(1);

            long a = insertMember(st, "msg-a");
            long b = insertMember(st, "msg-b");

            insertMessage(st, a, b);
            assertThat(count(st, "SELECT COUNT(*) FROM admin_message")).isEqualTo(1);

            // 자기 자신에게 보내는 쪽지는 CHECK 위반
            assertThatThrownBy(() -> insertMessage(st, a, a)).isInstanceOf(SQLException.class);
            // 없는 회원은 FK 위반(보내는 쪽·받는 쪽 모두)
            assertThatThrownBy(() -> insertMessage(st, 999999999L, b)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMessage(st, a, 999999999L)).isInstanceOf(SQLException.class);

            // 상태 행: 회원당 1행(PK), 없는 회원 거부
            st.execute("INSERT INTO admin_message_sender_state (member_id) VALUES (" + a + ")");
            assertThatThrownBy(() -> st.execute("INSERT INTO admin_message_sender_state (member_id) VALUES (" + a + ")"))
                    .isInstanceOf(SQLException.class);
            // ON DUPLICATE KEY UPDATE 형태는 오류 없이 잠금 행으로 쓰인다(발송 트랜잭션의 첫 문장)
            st.execute("INSERT INTO admin_message_sender_state (member_id) VALUES (" + a
                    + ") ON DUPLICATE KEY UPDATE member_id = member_id");
            assertThatThrownBy(() -> st.execute("INSERT INTO admin_message_sender_state (member_id) VALUES (999999999)"))
                    .isInstanceOf(SQLException.class);

            // 발송 이력: 없는 회원 거부
            st.execute("INSERT INTO admin_message_send_log (sender_id, sent_at) VALUES (" + a + ", NOW(6))");
            assertThatThrownBy(() -> st.execute("INSERT INTO admin_message_send_log (sender_id, sent_at) VALUES (999999999, NOW(6))"))
                    .isInstanceOf(SQLException.class);

            // 회원 FK는 RESTRICT — 쪽지가 걸린 회원 행은 지워지지 않는다(상대 보관함의 쪽지가 조용히 사라지지 않는다)
            assertThatThrownBy(() -> st.execute("DELETE FROM member WHERE id = " + a)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("DELETE FROM member WHERE id = " + b)).isInstanceOf(SQLException.class);
            assertThat(count(st, "SELECT COUNT(*) FROM admin_message")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("목록·미읽음·보낸 목록·이력 인덱스가 계획대로 만들어진다(삭제 열 포함)")
    void upgrade_createsPlannedIndexes() throws Exception {
        freshSchema();
        flyway(null).migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(indexColumns(st, "admin_message", "idx_admin_message_inbox"))
                    .isEqualTo("recipient_id,recipient_deleted_at,id");
            assertThat(indexColumns(st, "admin_message", "idx_admin_message_unread"))
                    .isEqualTo("recipient_id,recipient_deleted_at,read_at,id");
            assertThat(indexColumns(st, "admin_message", "idx_admin_message_sent"))
                    .isEqualTo("sender_id,sender_deleted_at,id");
            assertThat(indexColumns(st, "admin_message_send_log", "idx_admin_message_send_log"))
                    .isEqualTo("sender_id,sent_at");
        }
    }

    private static String indexColumns(Statement st, String table, String index) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                + "WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + table + "' AND index_name = '" + index + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("V21 DDL만 적용되고 이력이 없으며 세 테이블이 모두 비어 있으면 그 테이블만 DROP → repair → migrate로 정상 적용된다")
    void emptyResidueWithoutHistory_recoversByDroppingOnlyTheEmptyTables() throws Exception {
        freshSchema();
        flyway("20").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            applyV21DdlWithoutHistory(st);
            assertThat(tableCount(st)).isEqualTo(3);
            assertThat(success(st, 1)).isZero();
            assertThat(residueRows(st)).isZero(); // 복구 절차의 데이터 확인 질의: 비어 있을 때만 DROP
        }

        // 잔여 테이블이 있는 채로 migrate하면 CREATE가 실패한다
        assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(Exception.class);

        try (Connection c = connect(); Statement st = c.createStatement()) {
            // 외래키 의존 순서대로(admin_message_* 모두 member만 참조하므로 순서 무관하지만 명시)
            st.execute("DROP TABLE IF EXISTS admin_message_send_log");
            st.execute("DROP TABLE IF EXISTS admin_message_sender_state");
            st.execute("DROP TABLE IF EXISTS admin_message");
        }
        flyway(null).repair();
        flyway(null).migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(tableCount(st)).isEqualTo(3);
            assertThat(success(st, 1)).isEqualTo(1);
            assertThat(success(st, 0)).isZero();
        }
    }

    @Test
    @DisplayName("성공 이력이 없는데 쪽지 데이터가 든 경우는 데이터 확인 질의가 0이 아니므로 DROP하지 않고, 데이터는 그대로 보존된다")
    void residueWithData_isNotDropped() throws Exception {
        freshSchema();
        flyway("20").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            applyV21DdlWithoutHistory(st);
            long a = insertMember(st, "msg-keep-a");
            long b = insertMember(st, "msg-keep-b");
            insertMessage(st, a, b);

            assertThat(success(st, 1)).isZero();
            // 복구 절차의 데이터 확인 질의가 0이 아니다 → 운영자는 DROP하지 않고 백업 후 별도 분기로 간다
            assertThat(residueRows(st)).isGreaterThan(0);
        }

        // 그대로 migrate하면 실패하지만(CREATE TABLE 충돌) 어떤 데이터도 지워지지 않는다
        assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(Exception.class);

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM admin_message")).isEqualTo(1);
            assertThat(tableCount(st)).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("V21이 success=1이면 repair는 성공한 마이그레이션을 되감지 않아 테이블·데이터가 보존된다 — 성공 이력 테이블을 일괄 DROP하지 않는다")
    void succeededVersion_repairKeepsTablesAndData() throws Exception {
        freshSchema();
        flyway(null).migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            long a = insertMember(st, "msg-ok-a");
            long b = insertMember(st, "msg-ok-b");
            insertMessage(st, a, b);
        }

        flyway(null).repair();
        flyway(null).migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM admin_message")).isEqualTo(1);
            assertThat(success(st, 1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("이전 앱 롤백 호환: V21이 적용된 DB를 V21 파일이 실제로 없는 마이그레이션 위치로 migrate·validate해도 실패하지 않는다(미래 버전은 기본값으로 무시)")
    void rollbackToAppWithoutV21_startsAgainstV21Database() throws Exception {
        freshSchema();
        flyway(null).migrate();

        // target('20') 설정만으로는 V21 파일이 여전히 해석 대상이라 증명이 되지 않는다 — 이전 앱처럼 V20 이하 파일만 둔 디렉터리를 만들어 쓴다.
        // V21만 빼면 V22 이후 파일이 남아 적용된 V21이 future가 아니라 missing으로 분류돼 validate가 실패한다(이전 앱의 실제 조건이 아니다).
        Path withoutV21 = Files.createTempDirectory("migration-without-v21");
        try {
            int copied = 0;
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
                String name = resource.getFilename();
                if (name != null && Integer.parseInt(name.substring(1, name.indexOf("__"))) <= 20) {
                    Files.write(withoutV21.resolve(name), resource.getInputStream().readAllBytes());
                    copied++;
                }
            }
            assertThat(copied).isEqualTo(20);
            assertThat(withoutV21.resolve("V21__create_admin_message.sql")).doesNotExist();

            Flyway previousApp = Flyway.configure()
                    .dataSource(rootUrl() + SCHEMA, "root", MARIA_DB.getPassword())
                    .locations("filesystem:" + withoutV21.toAbsolutePath())
                    .load();

            // 설정을 건드리지 않은 기본값에서 적용 이력에 있고 코드에 없는 V21을 무시하고 기동할 수 있다(Flyway 기본 ignoreMigrationPatterns=*:future)
            previousApp.validate();
            previousApp.migrate();

            try (Connection c = connect(); Statement st = c.createStatement()) {
                assertThat(tableCount(st)).isEqualTo(3); // 데이터·테이블은 남는다(이전 앱은 모를 뿐)
                assertThat(success(st, 1)).isEqualTo(1);
            }
        } finally {
            try (var files = Files.list(withoutV21)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(withoutV21);
        }
    }
}
