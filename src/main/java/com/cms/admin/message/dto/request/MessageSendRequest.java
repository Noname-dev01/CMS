package com.cms.admin.message.dto.request;

import com.cms.admin.message.service.MessageTextPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 쪽지 보내기 요청. 제목·본문은 여기서 <b>원문 크기만</b> 방어하고(서비스 진입 전 자원 방어), 서로게이트·제어문자·정규화·최종 길이는
 * 서비스가 {@link MessageTextPolicy}로 검증한다(PLAN-admin-message.md §5-B 파이프라인). 수신자는 검색 결과의 회원 ID다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "쪽지 보내기 요청")
public class MessageSendRequest {

    @NotNull
    @Schema(description = "수신자 회원 ID(수신자 검색 결과)", example = "12")
    private Long recipientId;

    @NotNull
    @Size(max = MessageTextPolicy.TITLE_RAW_MAX)
    @Schema(description = "제목. 한 줄, trim 후 1~100자", example = "공지 검토 요청")
    private String title;

    @NotNull
    @Size(max = MessageTextPolicy.BODY_RAW_MAX)
    @Schema(description = "본문. 개행 정규화 후 1~2000자(평문 — 서버는 원문 그대로 저장하고 화면이 textContent로만 표시)")
    private String body;
}
