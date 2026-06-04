package com.cms.admin.member.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProfileImagePresetRequest {

    @NotBlank(message = "프리셋을 선택해주세요.")
    private String preset;

}
