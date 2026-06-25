package com.cms.admin.log.service;

import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.dto.request.AdminActionLogSearchRequest;
import com.cms.admin.log.dto.response.AdminActionLogPageResponse;
import com.cms.admin.log.dto.response.AdminActionLogResponse;
import com.cms.admin.log.repository.AdminActionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminActionLogQueryService {

    /** 페이지 크기 상한 — 이 도메인에만 한정 적용해 타 도메인에 영향을 주지 않는다. */
    private static final int MAX_PAGE_SIZE = 100;

    /** from/to 한쪽만 지정됐을 때 반대편에 보정하는 일수 (포함 30일 → 29일 차이). */
    private static final int DEFAULT_RANGE_DAYS = 29;

    private final AdminActionLogRepository adminActionLogRepository;

    /**
     * 활동 로그 목록을 조회한다.
     * - 기간 기본값 주입(미입력 → 최근 30일, 한쪽만 → 반대편 보정)
     * - size clamp(100 초과 시 100으로 제한)
     * 기간 역전·3개월 초과 검증은 DTO @AssertTrue가 이미 통과시켰으므로 Service는 예외를 던지지 않는다.
     */
    public AdminActionLogPageResponse searchActionLogs(AdminActionLogSearchRequest req, Pageable pageable) {
        applyDateDefaults(req);

        Pageable effectivePageable = pageable;
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            effectivePageable = PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }

        Page<AdminActionLog> page = adminActionLogRepository.searchActionLogs(req, effectivePageable);

        return AdminActionLogPageResponse.builder()
                .content(page.getContent().stream().map(this::toResponse).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    /**
     * 기간 기본값 주입:
     * 1. from/to 둘 다 null → to=오늘, from=오늘-29일 (최근 30일)
     * 2. to만 null → to=from+29일
     * 3. from만 null → from=to-29일
     * 4. 둘 다 입력 → 그대로 사용 (DTO에서 이미 역전·3개월 검증됨)
     */
    private void applyDateDefaults(AdminActionLogSearchRequest req) {
        LocalDate today = LocalDate.now();

        if (req.getFrom() == null && req.getTo() == null) {
            req.setTo(today);
            req.setFrom(today.minusDays(DEFAULT_RANGE_DAYS));
        } else if (req.getTo() == null) {
            req.setTo(req.getFrom().plusDays(DEFAULT_RANGE_DAYS));
        } else if (req.getFrom() == null) {
            req.setFrom(req.getTo().minusDays(DEFAULT_RANGE_DAYS));
        }
    }

    private AdminActionLogResponse toResponse(AdminActionLog log) {
        return AdminActionLogResponse.builder()
                .id(log.getId())
                .actionId(log.getActionId())
                .actionUserId(log.getActionUserId())
                .actionType(log.getActionType())
                .actionResult(log.getActionResult())
                .targetType(log.getTargetType())
                .targetId(log.getTargetId())
                .requestIp(log.getRequestIp())
                .requestUri(log.getRequestUri())
                .requestMethod(log.getRequestMethod())
                .errorMessage(log.getErrorMessage())
                .createAt(log.getCreateAt())
                .build();
    }
}
