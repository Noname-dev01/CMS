package com.cms.admin.inquiry.dto.response;

import com.cms.admin.inquiry.domain.Inquiry;
import com.cms.admin.inquiry.domain.InquiryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 문의 상세(본문 포함). 방문자 입력 평문이라 화면이 {@code textContent}로만 출력한다. */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "문의 상세")
public class InquiryResponse {

    private Long id;
    private String name;
    private String email;
    private String title;
    private String body;
    private InquiryStatus status;
    private LocalDateTime consentAt;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static InquiryResponse of(Inquiry inquiry) {
        return new InquiryResponse(inquiry.getId(), inquiry.getName(), inquiry.getEmail(), inquiry.getTitle(),
                inquiry.getBody(), inquiry.getStatus(), inquiry.getConsentAt(), inquiry.getCreateDate(), inquiry.getUpdateDate());
    }
}
