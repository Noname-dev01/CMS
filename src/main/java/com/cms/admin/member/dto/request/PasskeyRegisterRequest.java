package com.cms.admin.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 패스키 등록 확정 요청(PLAN-passkey-second-factor.md 결정 5). {@code credential}은 브라우저 {@code navigator.credentials.create} 결과의
 * JSON 객체 — 검증은 전용 매퍼({@code PasskeyJson})가 읽은 뒤 라이브러리가 한다. 이름 길이(코드포인트)·제어 문자는 서비스가 검사한다.
 */
@Schema(description = "패스키 등록 확정 요청")
public record PasskeyRegisterRequest(
        @NotBlank @Size(max = 64) @Schema(description = "등록 대기 발급 때 받은 식별자") String registrationId,
        @NotNull @Schema(description = "브라우저가 만든 공개키 자격(JSON 객체)") JsonNode credential,
        @NotBlank @Size(max = 200) @Schema(description = "패스키 이름(1~50자)", example = "업무용 노트북") String label,
        @NotBlank @Size(max = 200) @Schema(description = "현재 비밀번호") String currentPassword) {

    @Override
    public String toString() {
        return "PasskeyRegisterRequest{registrationId=" + registrationId + "}";
    }
}
