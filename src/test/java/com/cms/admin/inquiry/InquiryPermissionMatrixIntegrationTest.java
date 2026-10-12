package com.cms.admin.inquiry;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 문의함 핸들러 4개 × MANAGER 권한 조합 접근 매트릭스(PLAN-inquiry.md 쟁점 2) — 여섯 번째 기능 단위 위임(DELEGABLE) 기능 {@code INQUIRY}.
 * 성공은 "403이 아님"이 아니라 <b>기대 상태 코드와 실제 저장 결과</b>로, 거부는 403과 <b>변경 없음</b>으로 단언한다. 동작 오표시(삭제를 UPDATE로 표시 등)를 잡으려고
 * 동작별 단일 권한 조합을 각각 돌린다. 다른 기능(FAQ) 권한만 가진 MANAGER는 전부 거부된다(권한 독립).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class InquiryPermissionMatrixIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;

    private Member manager;
    private long inquiryId;

    @BeforeEach
    void setUp() {
        manager = TestMembers.save(memberRepository, "ipm-manager", Role.ROLE_MANAGER);
        resetFixture();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM inquiry");
        TestMembers.delete(jdbc, List.of(manager.getId()));
        cache.invalidate();
    }

    private void resetFixture() {
        jdbc.update("DELETE FROM inquiry");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO inquiry (name, email, title, body, status, consent_at, create_date, update_date) VALUES ('n', 'n@example.com', 't', 'b', 'RECEIVED', ?, ?, ?)",
                now, now, now);
        inquiryId = jdbc.queryForObject("SELECT MAX(id) FROM inquiry", Long.class);
    }

    private void grant(String feature, Set<PermissionAction> actions) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        for (PermissionAction action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, ?, ?)", manager.getId(), feature, action.name());
        }
        cache.invalidate();
    }

    private String statusOfFixture() {
        List<String> values = jdbc.queryForList("SELECT status FROM inquiry WHERE id = ?", String.class, inquiryId);
        return values.isEmpty() ? null : values.get(0);
    }

    private int call(String handler, RequestPostProcessor who) throws Exception {
        return switch (handler) {
            case "목록" -> mockMvc.perform(get("/admin/api/inquiries").with(who)).andReturn().getResponse().getStatus();
            case "상세" -> mockMvc.perform(get("/admin/api/inquiries/{id}", inquiryId).with(who)).andReturn().getResponse().getStatus();
            case "상태 변경" -> mockMvc.perform(patch("/admin/api/inquiries/{id}", inquiryId).with(who).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ANSWERED\"}")).andReturn().getResponse().getStatus();
            case "삭제" -> mockMvc.perform(delete("/admin/api/inquiries/{id}", inquiryId).with(who).with(csrf())).andReturn().getResponse().getStatus();
            default -> throw new IllegalArgumentException(handler);
        };
    }

    private static PermissionAction requiredAction(String handler) {
        return switch (handler) {
            case "목록", "상세" -> PermissionAction.READ;
            case "상태 변경" -> PermissionAction.UPDATE;
            case "삭제" -> PermissionAction.DELETE;
            default -> throw new IllegalArgumentException(handler);
        };
    }

    private static final List<String> HANDLERS = List.of("목록", "상세", "상태 변경", "삭제");

    private static final List<Set<PermissionAction>> COMBOS = List.of(
            EnumSet.of(PermissionAction.READ, PermissionAction.UPDATE, PermissionAction.DELETE),
            EnumSet.noneOf(PermissionAction.class),
            EnumSet.of(PermissionAction.READ),
            EnumSet.of(PermissionAction.READ, PermissionAction.UPDATE),
            EnumSet.of(PermissionAction.READ, PermissionAction.DELETE),
            EnumSet.of(PermissionAction.UPDATE, PermissionAction.DELETE));   // READ 없는 쓰기

    @Test
    @DisplayName("핸들러 4개 × 권한 6조합: 허용은 기대 상태와 저장 결과, 거부는 403과 변경 없음 (READ 없는 쓰기는 의존 규칙상 전부 거부)")
    void matrix() throws Exception {
        for (Set<PermissionAction> granted : COMBOS) {
            for (String handler : HANDLERS) {
                resetFixture();
                grant("INQUIRY", granted);
                PermissionAction required = requiredAction(handler);
                boolean allowed = granted.contains(required) && (required == PermissionAction.READ || granted.contains(PermissionAction.READ));

                int status = call(handler, TestMembers.asMember(manager));

                String label = handler + " / 부여 " + granted;
                if (allowed) {
                    assertThat(status).as(label).isIn(200, 204);
                } else {
                    assertThat(status).as(label).isEqualTo(403);
                }
                if (handler.equals("상태 변경")) {
                    assertThat(statusOfFixture()).as(label).isEqualTo(allowed ? "ANSWERED" : "RECEIVED");
                }
                if (handler.equals("삭제")) {
                    assertThat(statusOfFixture()).as(label).isEqualTo(allowed ? null : "RECEIVED");
                }
            }
        }
    }

    @Test
    @DisplayName("다른 기능(FAQ) 권한을 전부 받아도 문의함은 403 — 권한은 기능별로 독립이고 신규 MANAGER(권한 0개)도 403")
    void otherFeatureDoesNotOpenInquiry() throws Exception {
        grant("FAQ", EnumSet.allOf(PermissionAction.class));
        for (String handler : HANDLERS) {
            resetFixture();
            assertThat(call(handler, TestMembers.asMember(manager))).as(handler).isEqualTo(403);
        }
        assertThat(statusOfFixture()).isEqualTo("RECEIVED");

        grant("INQUIRY", EnumSet.noneOf(PermissionAction.class));
        assertThat(call("목록", TestMembers.asMember(manager))).isEqualTo(403);
    }

    @Test
    @DisplayName("권한 회수는 같은 세션의 다음 요청부터 반영된다 — 부여 중 200, 회수 직후 403")
    void revocationTakesEffectImmediately() throws Exception {
        grant("INQUIRY", EnumSet.of(PermissionAction.READ));
        assertThat(call("목록", TestMembers.asMember(manager))).isEqualTo(200);

        grant("INQUIRY", EnumSet.noneOf(PermissionAction.class));
        assertThat(call("목록", TestMembers.asMember(manager))).isEqualTo(403);
    }
}
