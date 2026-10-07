package com.cms.admin.notice;

import com.cms.common.html.HtmlContentSanitizer;
import com.cms.common.html.SanitizedHtml;
import com.cms.support.MariaDbContainerSupport;
import org.flywaydb.core.Flyway;
import org.jsoup.Jsoup;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V23(MEDIUMTEXT)·V24(본문 이미지 테이블)·V25(평문 → HTML 변환) 업그레이드 경로(PLAN-html-editor.md 쟁점 11).
 *
 * <p>별도 스키마에 V22까지 적용 → 기존 검증(10,000자)을 통과하는 평문 공지를 넣고 → 최신까지 적용한 뒤 확인한다.
 * 변환 결과가 ① 같은 텍스트·줄바꿈으로 읽히고 ② 원래 {@code <}·{@code &}가 마크업으로 해석되지 않으며 ③ 서비스의 저장 규칙
 * (보이는 텍스트 ≤ 10,001, 정리된 HTML ≤ 200,000바이트 — {@code NoticeService})을 만족해 그대로 다시 저장할 수 있는지 본다.
 * 반례(줄바꿈·탭·공백 9,998개)는 TEXT 상한·길이 이중 계산·탭 확장 결함을 잡던 입력이다(리뷰 R1-9·R3-1·R3-2).
 */
class NoticeContentHtmlMigrationTest extends MariaDbContainerSupport {

    private static final String SCHEMA = "upgrade_v25_test";

    @Test
    @DisplayName("V22 평문 공지가 V25 후 같은 텍스트의 HTML이 되고, 반례 포함 전부 재저장 규칙을 만족한다")
    void upgradeFromV22_convertsPlainTextToHtml() throws Exception {
        String rootUrl = "jdbc:mariadb://" + MARIA_DB.getHost() + ":" + MARIA_DB.getFirstMappedPort() + "/";
        String schemaUrl = rootUrl + SCHEMA;
        try (Connection root = DriverManager.getConnection(rootUrl, "root", MARIA_DB.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
        }
        Flyway.configure().dataSource(schemaUrl, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration").target("22").load().migrate();

        Map<String, String> originals = new LinkedHashMap<>();
        originals.put("special", "<b>굵게 아님</b> & \"인용\" > 끝");
        originals.put("crlf", "첫 줄\r\n둘째 줄\r셋째 줄\n넷째 줄");
        originals.put("blankLines", "A\n\n\nB\n");
        originals.put("spaces", "  앞 공백, 가운데   여러 칸, 탭\t사이, 뒤 공백  ");
        originals.put("korean", "한글과 이모지 😀 섞인 본문");
        originals.put("newlines9998", "A" + "\n".repeat(9_998) + "B");
        originals.put("tabs9998", "A" + "\t".repeat(9_998) + "B");
        originals.put("spaces9998", "A" + " ".repeat(9_998) + "B");
        originals.put("lt10000", "<".repeat(10_000));

        try (Connection conn = DriverManager.getConnection(schemaUrl, "root", MARIA_DB.getPassword());
             PreparedStatement insert = conn.prepareStatement("INSERT INTO notice (title, content, use_yn, deleted, author_id,"
                     + " create_date, update_date) VALUES (?, ?, 1, 0, 'admin01', NOW(6), '2026-01-01 00:00:00')")) {
            for (Map.Entry<String, String> entry : originals.entrySet()) {
                insert.setString(1, entry.getKey());
                insert.setString(2, entry.getValue());
                insert.executeUpdate();
            }
        }

        Flyway.configure().dataSource(schemaUrl, "root", MARIA_DB.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (Connection conn = DriverManager.getConnection(schemaUrl, "root", MARIA_DB.getPassword());
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT data_type FROM information_schema.columns WHERE table_schema = '"
                    + SCHEMA + "' AND table_name = 'notice' AND column_name = 'content'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("mediumtext");
            }
            try (ResultSet rs = st.executeQuery("SELECT total_bytes, total_count FROM content_image_usage WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
                assertThat(rs.getLong(2)).isZero();
            }

            Map<String, String> converted = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT title, content, update_date FROM notice ORDER BY id")) {
                while (rs.next()) {
                    converted.put(rs.getString("title"), rs.getString("content"));
                    assertThat(rs.getString("update_date")).as("변환은 사용자 수정이 아니므로 update_date 보존").startsWith("2026-01-01");
                }
            }

            assertThat(converted.get("special")).isEqualTo("<p>&lt;b&gt;굵게 아님&lt;/b&gt; &amp; &quot;인용&quot; &gt; 끝</p>");
            assertThat(converted.get("crlf")).isEqualTo("<p>첫 줄</p><p>둘째 줄</p><p>셋째 줄</p><p>넷째 줄</p>");
            assertThat(converted.get("blankLines")).isEqualTo("<p>A</p><p><br></p><p><br></p><p>B</p><p><br></p>");

            for (Map.Entry<String, String> entry : originals.entrySet()) {
                String html = converted.get(entry.getKey());
                SanitizedHtml sanitized = HtmlContentSanitizer.sanitize(html);
                String expectedText = entry.getValue().replace("\r\n", "\n").replace('\r', '\n');

                assertThat(linesOf(sanitized.html())).as(entry.getKey() + " 텍스트 보존(공백·탭은 1칸 1자)")
                        .isEqualTo(expectedText.replace('\t', ' '));
                assertThat(sanitized.textLength()).as(entry.getKey() + " 보이는 글자 ≤ 원문+1")
                        .isLessThanOrEqualTo(entry.getValue().length() + 1).isLessThanOrEqualTo(10_001);
                assertThat(sanitized.html().getBytes(StandardCharsets.UTF_8).length).as(entry.getKey() + " 저장 바이트 상한")
                        .isLessThanOrEqualTo(200_000);
                assertThat(sanitized.blank()).isFalse();
            }
        }
    }

    /** 문단마다 한 줄로 읽은 텍스트(빈 문단은 빈 줄, U+00A0은 공백). 마크업으로 해석된 태그가 있으면 텍스트가 달라진다. */
    private static String linesOf(String html) {
        // Element.wholeText()는 <br>을 "\n"으로 내보내 빈 문단이 두 줄이 되므로 텍스트 노드만 모은다
        return Jsoup.parseBodyFragment(html).body().children().stream()
                .map(block -> block.textNodes().stream().map(TextNode::getWholeText).collect(Collectors.joining()))
                .map(text -> text.replace(' ', ' '))
                .collect(Collectors.joining("\n"));
    }
}
