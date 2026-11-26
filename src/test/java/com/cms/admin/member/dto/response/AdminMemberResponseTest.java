package com.cms.admin.member.dto.response;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.ProfileImageKind;
import com.cms.admin.member.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원 응답 변환({@link AdminMemberResponse#from})의 프로필 URL 분기 — 내 정보·타 관리자 상세·목록이 각자 다른 형태를 받는다
 * (PLAN-member-profile-image-service.md 결정 1, 원래 쟁점은 PLAN-profile-image-storage.md 쟁점 10).
 */
class AdminMemberResponseTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 1, 9, 0);
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 11, 2, 10, 30);

    @Test
    @DisplayName("SELF는 업로드 이미지를 본인 다운로드 경로로 준다")
    void selfUsesMyDownloadRoute() {
        AdminMemberResponse response = AdminMemberResponse.from(uploadedMember(), ProfileImageVisibility.SELF);

        assertThat(response.getProfileImageUrl()).startsWith("/admin/api/members/me/profile-image?v=");
    }

    @Test
    @DisplayName("OTHER는 업로드 이미지를 회원 ID 다운로드 경로로 준다")
    void otherUsesMemberIdDownloadRoute() {
        AdminMemberResponse response = AdminMemberResponse.from(uploadedMember(), ProfileImageVisibility.OTHER);

        assertThat(response.getProfileImageUrl()).startsWith("/admin/api/members/7/profile-image?v=");
    }

    @Test
    @DisplayName("HIDDEN은 이미지가 있어도 URL을 주지 않고, 나머지 필드는 그대로 옮긴다")
    void hiddenOmitsUrlAndCopiesFields() {
        AdminMemberResponse response = AdminMemberResponse.from(uploadedMember(), ProfileImageVisibility.HIDDEN);

        assertThat(response.getProfileImageUrl()).isNull();
        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getUserId()).isEqualTo("manager07");
        assertThat(response.getUserName()).isEqualTo("김매니저");
        assertThat(response.getEmail()).isEqualTo("m7@test.com");
        assertThat(response.getUserType()).isEqualTo(Role.ROLE_MANAGER);
        assertThat(response.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(response.getCreateDate()).isEqualTo(CREATED);
        assertThat(response.getUpdateDate()).isEqualTo(UPDATED);
    }

    private static Member uploadedMember() {
        return Member.builder()
                .id(7L).userId("manager07").pwd("x").userName("김매니저").email("m7@test.com")
                .userType(Role.ROLE_MANAGER).status(MemberStatus.LOCKED)
                .createDate(CREATED).updateDate(UPDATED)
                .profileImageKind(ProfileImageKind.UPLOADED)
                .profileImageUrl("2026/11/01/uuid.png")
                .profileImageContentType("image/png")
                .build();
    }
}
