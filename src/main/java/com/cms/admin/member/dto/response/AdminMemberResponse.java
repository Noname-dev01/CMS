package com.cms.admin.member.dto.response;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Date;

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
    private Date createDate;
    private Date updateDate;
    private String profileImageUrl;
}
