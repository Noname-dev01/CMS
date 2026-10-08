package com.cms.admin.board.dto.response;

import com.cms.admin.board.domain.Board;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "게시판 응답")
public class BoardResponse {

    private Long id;
    private String name;
    private Boolean publicYn;
    private Boolean attachmentYn;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static BoardResponse from(Board board) {
        return BoardResponse.builder()
                .id(board.getId())
                .name(board.getName())
                .publicYn(board.getPublicYn())
                .attachmentYn(board.getAttachmentYn())
                .createDate(board.getCreateDate())
                .updateDate(board.getUpdateDate())
                .build();
    }
}
