package com.cms.support;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.config.auth.CustomUserDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * 권한 시험용 회원 도구. 권한 판정 키가 회원 ID이므로 위임 기능(공지 등)을 다루는 MANAGER 시험은 <b>회원 ID를 가진 실제 주체</b>
 * ({@link CustomUserDetails})를 써야 한다 — {@code @WithMockUser}는 회원 ID가 없어 위임 기능이 전부 거부된다.
 * DB 시험은 {@code member_permission} FK 때문에 실제 회원 행이 필요하다.
 */
public final class TestMembers {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private TestMembers() {
    }

    /** 실제 회원 행을 저장한다. 아이디·이메일은 호출마다 유일하다. */
    public static Member save(MemberRepository repository, String prefix, Role role) {
        String unique = prefix + "-" + System.nanoTime() + "-" + SEQUENCE.incrementAndGet();
        String userId = unique.substring(0, Math.min(50, unique.length()));
        LocalDateTime now = LocalDateTime.now();
        return repository.save(Member.builder()
                .userId(userId)
                .pwd("{noop}unused-test-password")
                .userName(prefix)
                .email(unique + "@permission-test.example")
                .userType(role)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .build());
    }

    /** 이 회원으로 로그인한 것과 같은 요청 주체(MockMvc). */
    public static RequestPostProcessor asMember(Member member) {
        CustomUserDetails details = new CustomUserDetails(member);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    /** DB에 없는 회원 ID만 가진 주체 — 슬라이스 시험처럼 DB 없이 판정만 볼 때 쓴다. */
    public static CustomUserDetails detached(long id, Role role) {
        Member member = Member.builder().id(id).userId("user" + id).userName("user" + id)
                .email("user" + id + "@example.com").userType(role).status(MemberStatus.ACTIVE).build();
        return new CustomUserDetails(member);
    }

    /**
     * 만든 회원과 그 개별 권한 행·쪽지·발송 이력·발송 상태 행을 지운다(FK가 RESTRICT라 종속 행 먼저).
     * 쪽지는 보낸 쪽·받은 쪽 어느 쪽이든 이 회원이 걸린 행을 모두 지운다(상대 회원 행은 건드리지 않는다).
     */
    public static void delete(JdbcTemplate jdbc, Collection<Long> memberIds) {
        for (Long id : memberIds) {
            jdbc.update("DELETE FROM admin_message WHERE sender_id = ? OR recipient_id = ?", id, id);
            jdbc.update("DELETE FROM admin_message_send_log WHERE sender_id = ?", id);
            jdbc.update("DELETE FROM admin_message_sender_state WHERE member_id = ?", id);
            jdbc.update("DELETE FROM member_permission WHERE member_id = ?", id);
            jdbc.update("DELETE FROM member WHERE id = ?", id);
        }
    }
}
