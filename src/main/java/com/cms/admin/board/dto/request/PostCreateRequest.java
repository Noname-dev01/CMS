package com.cms.admin.board.dto.request;

import com.cms.admin.member.dto.request.validation.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "게시글 생성 요청")
public class PostCreateRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "제목", example = "2026년 상반기 자료집")
    private String title;

    @NotBlank
    @MaxUtf8Bytes(value = 200_000, message = "본문이 너무 깁니다.")
    @Schema(description = "본문 HTML(편집기 출력). 서버가 허용 목록으로 정리한 뒤 저장한다 — 보이는 글자 10,000자 이하",
            example = "<p>자료집을 공유합니다.</p>")
    private String content;

    @Schema(description = "본문 형식 표식. \"HTML\" 필수 — 배포 전에 열어 둔 평문 편집 화면의 저장을 막는다", example = "HTML")
    private String contentFormat;

    @Schema(description = "노출 여부. 누락 시 true로 기본화", example = "true")
    private Boolean useYn;
}
