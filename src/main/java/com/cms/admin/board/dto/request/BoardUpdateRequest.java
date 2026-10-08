package com.cms.admin.board.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 부분 수정 요청. null 필드는 기존값 유지. 이름 공백 거부·전체 null 거부는 서비스가 한다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "게시판 부분 수정 요청")
public class BoardUpdateRequest {

    @Size(max = 100)
    @Schema(description = "게시판 이름 (null이면 기존값 유지)", example = "보도자료")
    private String name;

    @Schema(description = "공개 게시판 여부 (null이면 기존값 유지)", example = "false")
    private Boolean publicYn;

    @Schema(description = "첨부 허용 여부 (null이면 기존값 유지)", example = "false")
    private Boolean attachmentYn;
}
