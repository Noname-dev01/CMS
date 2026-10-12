package com.cms.admin.inquiry;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V47(문의 테이블)·V48(메뉴 시드) 적용 결과와 시드 멱등성(PLAN-inquiry.md 쟁점 6). 새 DB의 V1부터 전체 적용은 다른 모든 통합 시험의 기동이 이미 증명한다. */
@SpringBootTest(classes = CmsTestApplication.class)
class InquiryMigrationTest extends MariaDbContainerSupport {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V47: inquiry 테이블의 컬럼·NOT NULL·상태 인덱스, 방문자 IP·User-Agent 컬럼은 없다(개인정보 최소 수집)")
    void tableShape() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'inquiry' ORDER BY ordinal_position", String.class);
        assertThat(columns).containsExactly("id", "name", "email", "title", "body", "status", "consent_at", "create_date", "update_date");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'inquiry' AND is_nullable = 'YES'",
                String.class)).as("모든 컬럼 NOT NULL").isEmpty();
        assertThat(jdbc.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'inquiry'",
                String.class)).containsExactlyInAnyOrder("PRIMARY", "idx_inquiry_status_id");
        assertThat(jdbc.queryForObject("SELECT column_type FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'inquiry' AND column_name = 'body'",
                String.class)).isEqualToIgnoringCase("text");
    }

    @Test
    @DisplayName("V48: 문의함 메뉴가 최상위에 한 번 시드돼 있고, 시드를 다시 실행해도 중복되지 않는다 (WHERE NOT EXISTS)")
    void menuSeedIsIdempotent() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V48__seed_inquiry_menu.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/inquiry/manage'", Integer.class);
        assertThat(before).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT up_menu_no IS NULL AND use_yn = 1 FROM menu WHERE menu_url = '/admin/inquiry/manage'", Boolean.class)).isTrue();

        String insert = sql.substring(sql.indexOf("INSERT INTO menu"));
        jdbc.execute(insert);
        jdbc.execute(insert);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu WHERE menu_url = '/admin/inquiry/manage'", Integer.class)).isEqualTo(1);
    }
}
