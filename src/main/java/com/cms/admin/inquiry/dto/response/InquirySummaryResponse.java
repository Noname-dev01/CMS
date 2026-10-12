package com.cms.admin.inquiry.dto.response;

import com.cms.admin.inquiry.domain.InquiryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 문의 목록 한 줄 — 본문은 싣지 않는다(상세에서만). 이름·이메일·제목은 방문자 입력이라 화면이 텍스트로만 출력한다. */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "문의 목록 항목(본문 제외)")
public class InquirySummaryResponse {

    private Long id;
    private String name;
    private String email;
    private String title;
    private InquiryStatus status;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;
}
