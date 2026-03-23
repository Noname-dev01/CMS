package com.cms.admin.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "내 관리자 정보 수정 요청")
public class AdminMyInfoUpdateRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(description = "이름", example = "홍길동")
    private String userName;

    @NotBlank
    @Email
    @Size(max = 100)
    @Schema(description = "이메일", example = "admin01@test.com")
    private String email;
}
