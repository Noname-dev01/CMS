package com.cms.admin.site.dto.request;

import com.cms.admin.site.SiteKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 공개 사이트 생성 요청. 종류는 받지 않는다(새로 만들 수 있는 것은 공개 사이트뿐). 예약어는 서비스가 판정한다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "공개 사이트 생성 요청")
public class SiteCreateRequest {

    @NotBlank
    @Size(min = SiteKey.MIN_LENGTH, max = SiteKey.MAX_LENGTH)
    @Pattern(regexp = SiteKey.PATTERN, message = "사이트 키는 소문자·숫자·하이픈만 쓸 수 있고 처음·끝·연속 하이픈은 불가합니다.")
    @Schema(description = "사이트 키(공개 경로 접두어가 될 값, 생성 후 변경 불가). 소문자·숫자·하이픈 2~30자, 예약어 불가", example = "news")
    private String siteKey;

    @NotBlank
    @Size(max = 100)
    @Schema(description = "사이트 이름", example = "소식 사이트")
    private String siteName;

    @Schema(description = "메뉴에 외부 http(s) 링크를 허용할지. 누락 시 false", example = "true")
    private Boolean allowExternalLink;
}
