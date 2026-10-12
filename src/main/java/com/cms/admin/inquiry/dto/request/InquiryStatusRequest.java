package com.cms.admin.inquiry.dto.request;

import com.cms.admin.inquiry.domain.InquiryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 문의 상태 변경 요청. 같은 값이면 멱등 200이다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "문의 상태 변경 요청")
public class InquiryStatusRequest {

    @NotNull
    @Schema(description = "새 상태", allowableValues = {"RECEIVED", "IN_PROGRESS", "ANSWERED"})
    private InquiryStatus status;
}
