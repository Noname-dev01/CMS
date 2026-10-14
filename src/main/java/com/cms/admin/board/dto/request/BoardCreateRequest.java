package com.cms.admin.board.dto.request;

import com.cms.admin.site.SiteScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
@Schema(description = "게시판 생성 요청")
public class BoardCreateRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(description = "게시판 이름(중복 허용 — 화면은 #id를 함께 보인다)", example = "자료실")
    private String name;

    @NotNull
    @Schema(description = "공개 게시판 여부 — true면 공개 사이트 /boards/{id}에 노출(PR B)", example = "true")
    private Boolean publicYn;

    @NotNull
    @Schema(description = "첨부 허용 여부 — false면 새 첨부 업로드만 막는다(PR B)", example = "true")
    private Boolean attachmentYn;

    @Pattern(regexp = SiteScope.PATTERN)
    @Schema(description = "표시 대상 — \"COMMON\"(모든 공개 사이트 공통) 또는 사이트 ID 문자열. 누락 = 공통", example = "COMMON")
    private String siteScope;

    @Schema(description = "true면 siteScope 사이트의 공지 게시판으로 만든다(공개 필수·사이트당 하나, 공통은 불가). 누락 = false", example = "false")
    private Boolean noticeBoard;
}
