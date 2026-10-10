package com.cms.admin.banner.dto.request;

import com.cms.common.web.validation.SafeLinkUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/** 배너 등록 요청 — {@code multipart/form-data}({@code @ModelAttribute} 바인딩, 이미지 파일은 서비스가 검증한다). */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "배너 등록 요청 (multipart/form-data)")
public class BannerCreateRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "제목(관리용 이름이자 이미지 대체 텍스트)", example = "가을 맞이 행사")
    private String title;

    @Size(max = 500)
    @SafeLinkUrl
    @Schema(description = "클릭 시 이동할 링크 — /로 시작하는 경로 또는 http(s):// 주소. 비우면 링크 없음", example = "/boards/2")
    private String linkUrl;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "노출 시작 시각(포함, 분 단위로 절단). 비우면 시작 제한 없음", example = "2026-10-10T09:00")
    private LocalDateTime displayStart;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "노출 종료 시각(미포함, 분 단위로 절단). 비우면 종료 제한 없음", example = "2026-10-20T00:00")
    private LocalDateTime displayEnd;

    @Schema(description = "노출 여부. 누락 시 true", example = "true")
    private Boolean useYn;

    @Schema(description = "배너 이미지(png·jpeg·gif, 2MB 이하, 한 변 2560px 이하, 애니메이션 불가)")
    private MultipartFile image;
}
