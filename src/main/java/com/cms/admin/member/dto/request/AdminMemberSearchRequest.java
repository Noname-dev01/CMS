package com.cms.admin.member.dto.request;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminMemberSearchRequest {

    @Size(max = 50)
    private String userId;

    @Size(max = 100)
    private String userName;

    private Role userType;
    private MemberStatus status;
}
