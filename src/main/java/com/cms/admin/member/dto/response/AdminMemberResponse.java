package com.cms.admin.member.dto.response;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.ProfileImageUrls;
import com.cms.admin.member.domain.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AdminMemberResponse {

    private Long id;
    private String userId;
    private String userName;
    private String email;
    private Role userType;
    private MemberStatus status;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;
    private String profileImageUrl;

    /** 회원 응답 변환 — 프로필 URL만 {@code visibility}에 따라 본인 경로·회원 ID 경로·생략으로 갈린다. */
    public static AdminMemberResponse from(Member member, ProfileImageVisibility visibility) {
        String profileImageUrl = switch (visibility) {
            case HIDDEN -> null;
            case SELF -> ProfileImageUrls.resolveSelfUrl(member);
            case OTHER -> ProfileImageUrls.resolveTargetUrl(member.getId(), member);
        };
        return AdminMemberResponse.builder()
                .id(member.getId())
                .userId(member.getUserId())
                .userName(member.getUserName())
                .email(member.getEmail())
                .userType(member.getUserType())
                .status(member.getStatus())
                .createDate(member.getCreateDate())
                .updateDate(member.getUpdateDate())
                .profileImageUrl(profileImageUrl)
                .build();
    }
}
