package com.cms.admin.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "2단계 인증 본인 해제 요청")
public record TotpDisableRequest(
        @NotBlank @Size(max = 40) @Schema(description = "인증 앱의 6자리 코드 또는 복구 코드") String code,
        @NotBlank @Size(max = 200) @Schema(description = "현재 비밀번호") String currentPassword) {

    @Override
    public String toString() {
        return "TotpDisableRequest{}";
    }
}
