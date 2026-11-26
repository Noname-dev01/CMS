package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 때의 principal과 지금 회원 행이 같은 상태인가(2단계 판정·패스키 단독 로그인의 잠금 뒤 재확인 — PLAN-solid-small-cleanup.md F3).
 * 세 축(상태 ACTIVE·역할·비밀번호 해시)을 하나씩 바꿔 각각이 거부 사유가 되는지 본다.
 */
class CustomUserDetailsTest {

    private static Member member(MemberStatus status, Role role, String pwd) {
        return Member.builder()
                .userId("matcher")
                .pwd(pwd)
                .userName("재확인")
                .email("matcher@test.local")
                .userType(role)
                .status(status)
                .build();
    }

    private final CustomUserDetails principal = new CustomUserDetails(member(MemberStatus.ACTIVE, Role.ROLE_ADMIN, "hash-1"), true);

    @Test
    @DisplayName("상태·역할·비밀번호 해시가 로그인 때와 같으면 true")
    void sameState() {
        assertThat(principal.matchesCurrent(member(MemberStatus.ACTIVE, Role.ROLE_ADMIN, "hash-1"))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = MemberStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("지금 상태가 ACTIVE가 아니면 false")
    void notActive(MemberStatus status) {
        assertThat(principal.matchesCurrent(member(status, Role.ROLE_ADMIN, "hash-1"))).isFalse();
    }

    @Test
    @DisplayName("역할이 바뀌었으면 false(강등·승격 모두)")
    void roleChanged() {
        assertThat(principal.matchesCurrent(member(MemberStatus.ACTIVE, Role.ROLE_MANAGER, "hash-1"))).isFalse();
        CustomUserDetails manager = new CustomUserDetails(member(MemberStatus.ACTIVE, Role.ROLE_MANAGER, "hash-1"));
        assertThat(manager.matchesCurrent(member(MemberStatus.ACTIVE, Role.ROLE_ADMIN, "hash-1"))).isFalse();
    }

    @Test
    @DisplayName("비밀번호 해시가 바뀌었거나 비었으면 false")
    void passwordChanged() {
        assertThat(principal.matchesCurrent(member(MemberStatus.ACTIVE, Role.ROLE_ADMIN, "hash-2"))).isFalse();
        assertThat(principal.matchesCurrent(member(MemberStatus.ACTIVE, Role.ROLE_ADMIN, null))).isFalse();
    }
}
