package com.cms.member.dto.response;

import com.cms.member.domain.MemberStatus;
import com.cms.member.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.Date;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "관리자 계정 생성 응답")
public class AdminSignupResponse {

    @Schema(example = "12")
    private Long id;

    @Schema(example = "admin01")
    private String userId;

    @Schema(example = "홍길동")
    private String userName;

    @Schema(example = "admin01@test.com")
    private String email;

    @Schema(example = "ADMIN")
    private Role userType;

    @Schema(example = "ACTIVE")
    private MemberStatus status;

    private Date createDate;
}
