package com.cms.admin.inquiry.service;

import com.cms.admin.inquiry.domain.Inquiry;
import com.cms.admin.inquiry.domain.InquiryStatus;
import com.cms.admin.inquiry.dto.response.InquiryPageResponse;
import com.cms.admin.inquiry.dto.response.InquiryResponse;
import com.cms.admin.inquiry.dto.response.InquirySummaryResponse;
import com.cms.admin.inquiry.repository.InquiryRepository;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 문의 접수(공개 폼)와 관리(문의함). 접수는 <b>트랜잭션 하나에 INSERT 한 문장</b>이고, 알림은 이 트랜잭션이 끝난 <b>뒤에</b> 트랜잭션 밖에서 따로 만든다
 * ({@link InquiryNotifier} — 요청당 커넥션 2개 금지, PLAN-inquiry.md R4-1). 상태 변경·삭제는 문의 행을 {@code FOR UPDATE}로 잡아 서로를 직렬화한다.
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

    /** 미처리(답변 완료가 아닌) 문의가 이 값 이상이면 새 문의를 받지 않는다. 소프트 상한 — 동시 접수가 몰리면 조금 넘을 수 있다. */
    public static final long MAX_UNRESOLVED = 1000;

    static final String NOT_FOUND = "문의를 찾을 수 없습니다.";
    private static final int MAX_PAGE_SIZE = 100;

    private final InquiryRepository inquiryRepository;
    private final Clock clock;

    /** 공개 폼 접수. 정책 검증을 마친 값만 받는다. 상한이면 {@link InquiryClosedException}. 새 문의 ID를 돌려준다. */
    @Transactional
    public Long submit(NormalizedInquiry input) {
        if (inquiryRepository.countByStatusNot(InquiryStatus.ANSWERED) >= MAX_UNRESOLVED) {
            throw new InquiryClosedException();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Inquiry saved = inquiryRepository.save(Inquiry.builder()
                .name(input.name())
                .email(input.email())
                .title(input.title())
                .body(input.body())
                .status(InquiryStatus.RECEIVED)
                .consentAt(now)
                .createDate(now)
                .updateDate(now)
                .build());
        return saved.getId();
    }

    /** 공개 폼이 상한 도달 여부를 미리 알아 안내할 때 쓴다(저장하지 않는다). */
    @Transactional(readOnly = true)
    public boolean isClosed() {
        return inquiryRepository.countByStatusNot(InquiryStatus.ANSWERED) >= MAX_UNRESOLVED;
    }

    @Transactional(readOnly = true)
    public InquiryPageResponse getInquiries(InquiryStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<InquirySummaryResponse> result = status == null
                ? inquiryRepository.findSummaries(pageable)
                : inquiryRepository.findSummariesByStatus(status, pageable);
        return new InquiryPageResponse(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), inquiryRepository.countByStatusNot(InquiryStatus.ANSWERED));
    }

    @Transactional(readOnly = true)
    public InquiryResponse getInquiry(Long id) {
        return InquiryResponse.of(inquiryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND)));
    }

    /** 상태 변경. 같은 값이면 쓰지 않고 감사 행도 성공으로 남는다(멱등 200). 행 락으로 같은 문의의 삭제와 직렬화된다. */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.INQUIRY_STATUS, targetType = "INQUIRY", targetIdExpression = "id",
            safeErrorMessage = "문의 상태 변경에 실패했습니다.")
    public InquiryResponse changeStatus(Long id, InquiryStatus status) {
        Inquiry target = inquiryRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        target.changeStatus(status, LocalDateTime.now(clock));
        return InquiryResponse.of(target);
    }

    /** 삭제(하드). 행이 사라진 뒤에는 응답을 만들 수 없으므로 삭제 전에 스냅샷을 만든다(감사 targetId용). */
    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.INQUIRY_DELETE, targetType = "INQUIRY", targetIdExpression = "id",
            safeErrorMessage = "문의 삭제에 실패했습니다.")
    public InquiryResponse deleteInquiry(Long id) {
        Inquiry target = inquiryRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        InquiryResponse snapshot = InquiryResponse.of(target);
        inquiryRepository.delete(target);
        return snapshot;
    }
}
