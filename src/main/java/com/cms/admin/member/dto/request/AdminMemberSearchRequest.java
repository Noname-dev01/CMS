package com.cms.admin.member.dto.request;

import com.cms.admin.member.domain.MemberStatus;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminMemberSearchRequest {

    private String userId;
    private String userName;
    private MemberStatus status;
}
