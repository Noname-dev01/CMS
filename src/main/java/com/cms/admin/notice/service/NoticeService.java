package com.cms.admin.notice.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.dto.request.NoticeCreateRequest;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
import com.cms.admin.notice.dto.request.NoticeUpdateRequest;
import com.cms.admin.notice.dto.response.NoticePageResponse;
import com.cms.admin.notice.dto.response.NoticeResponse;
import com.cms.admin.notice.dto.response.NoticeSummaryResponse;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NoticeService {

    /** 목록 페이지 크기 상한 — AdminActionLogQueryService.MAX_PAGE_SIZE 패턴 미러. */
    private static final int MAX_PAGE_SIZE = 100;

    private final NoticeRepository noticeRepository;
    private final AdminSecurityService adminSecurityService;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.NOTICE_CREATE, targetType = "NOTICE", targetIdExpression = "id")
    public NoticeResponse createNotice(NoticeCreateRequest request) {
        String authorId = requireCurrentAdminUserId();
        String title = requireNonBlank(request.getTitle(), "제목은 공백일 수 없습니다.");
        String content = requireNonBlank(request.getContent(), "본문은 공백일 수 없습니다.");
        boolean useYn = request.getUseYn() == null || request.getUseYn();

        LocalDateTime now = LocalDateTime.now();
        Notice saved = noticeRepository.save(
                Notice.builder()
                        .title(title)
                        .content(content)
                        .useYn(useYn)
                        .deleted(false)
                        .authorId(authorId)
                        .createDate(now)
                        .updateDate(now)
                        .build()
        );

        return NoticeResponse.from(saved);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.NOTICE_UPDATE, targetType = "NOTICE", targetIdExpression = "id")
    public NoticeResponse updateNotice(Long id, NoticeUpdateRequest request) {
        if (request.getTitle() == null && request.getContent() == null && request.getUseYn() == null) {
            throw new InvalidRequestException("변경할 필드가 없습니다.");
        }

        Notice target = noticeRepository.findByIdAndDeletedFalseForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        String title = request.getTitle() != null
                ? requireNonBlank(request.getTitle(), "제목은 공백일 수 없습니다.")
                : null;
        String content = request.getContent() != null
                ? requireNonBlank(request.getContent(), "본문은 공백일 수 없습니다.")
                : null;

        target.update(title, content, request.getUseYn());

        return NoticeResponse.from(target);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.NOTICE_DELETE, targetType = "NOTICE", targetIdExpression = "id")
    public NoticeResponse deleteNotice(Long id) {
        Notice target = noticeRepository.findByIdAndDeletedFalseForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        target.softDelete();

        return NoticeResponse.from(target);
    }

    @Transactional(readOnly = true)
    public NoticeResponse getNotice(Long id) {
        Notice notice = noticeRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        return NoticeResponse.from(notice);
    }

    @Transactional(readOnly = true)
    public NoticePageResponse getNotices(NoticeSearchRequest request, Pageable pageable) {
        Pageable effectivePageable = pageable.getPageSize() > MAX_PAGE_SIZE
                ? PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort())
                : pageable;

        Page<Notice> page = noticeRepository.searchNotices(request, effectivePageable);

        return NoticePageResponse.builder()
                .content(page.getContent().stream().map(NoticeSummaryResponse::from).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    /**
     * 현재 로그인 관리자의 userId를 반환한다. {@code @PreAuthorize} 통과 후라면 null이
     * 되지 않는 방어 코드지만, null일 경우 AccessDeniedException(→ 403)으로 처리해
     * 인증 연계 버그가 조용히 작성자 없는 데이터로 이어지는 것을 막는다.
     */
    private String requireCurrentAdminUserId() {
        String userId = adminSecurityService.getCurrentAdminUserId();
        if (userId == null) {
            throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
        }
        return userId;
    }

    private String requireNonBlank(String value, String message) {
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new InvalidRequestException(message);
        }
        return trimmed;
    }
}
