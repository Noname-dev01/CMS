package com.cms.admin.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "2단계 인증 등록 확정 요청")
public record TotpConfirmRequest(
        @NotBlank @Size(max = 64) @Schema(description = "등록 대기 발급 때 받은 식별자") String enrollmentId,
        @NotBlank @Size(max = 40) @Schema(description = "인증 앱의 6자리 코드", example = "123456") String code,
        @NotBlank @Size(max = 200) @Schema(description = "현재 비밀번호") String currentPassword) {

    @Override
    public String toString() {
        return "TotpConfirmRequest{enrollmentId=" + enrollmentId + "}";
    }
}
