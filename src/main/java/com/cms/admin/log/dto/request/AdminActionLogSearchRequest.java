package com.cms.admin.log.dto.request;

import com.cms.admin.log.domain.AdminActionResult;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminActionLogSearchRequest {

    @Size(max = 50)
    private String actionUserId;

    @Size(max = 100)
    private String actionType;

    private AdminActionResult actionResult;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    /** from/to가 둘 다 입력됐을 때만 역전 검증. 한쪽이라도 null이면 통과 (Service에서 기본값 보정). */
    @AssertTrue(message = "조회 시작일이 종료일보다 늦을 수 없습니다.")
    public boolean isDateRangeNotReversed() {
        if (from == null || to == null) {
            return true;
        }
        return !from.isAfter(to);
    }

    /** from/to가 둘 다 입력됐을 때만 3개월 초과 검증. 한쪽이라도 null이면 통과 (Service에서 기본값 보정). */
    @AssertTrue(message = "조회 기간은 3개월을 초과할 수 없습니다.")
    public boolean isDateRangeWithin3Months() {
        if (from == null || to == null) {
            return true;
        }
        // 종료일은 Repository에서 포함(inclusive) 조회되므로, from 기준 3개월의 마지막 포함일은 plusMonths(3).minusDays(1)이다.
        return !to.isAfter(from.plusMonths(3).minusDays(1));
    }
}
