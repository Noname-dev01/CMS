package com.cms.admin.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "재인증(현재 비밀번호 확인) 요청")
public class ReauthenticationRequest {

    @NotBlank
    @Schema(description = "현재 비밀번호")
    private String password;
}
