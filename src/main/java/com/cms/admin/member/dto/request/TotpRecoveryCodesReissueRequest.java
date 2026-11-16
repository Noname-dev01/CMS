package com.cms.admin.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 2단계 인증 복구 코드 재발급 요청. 코드 형식(6자리)은 Bean Validation이 아니라 서비스가 판정한다 —
 * 형식 오류도 재인증 실패로 세야 하기 때문이다(PLAN-totp-recovery-code-reissue.md 결정 2).
 */
@Schema(description = "2단계 인증 복구 코드 재발급 요청")
public record TotpRecoveryCodesReissueRequest(
        @NotBlank @Size(max = 40) @Schema(description = "인증 앱의 6자리 코드(복구 코드는 받지 않는다)") String code,
        @NotBlank @Size(max = 200) @Schema(description = "현재 비밀번호") String currentPassword) {

    @Override
    public String toString() {
        return "TotpRecoveryCodesReissueRequest{}";
    }
}
