package com.cms.admin.notice.service;

import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.dto.request.NoticeCreateRequest;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
import com.cms.admin.notice.dto.request.NoticeUpdateRequest;
import com.cms.admin.notice.dto.response.NoticePageResponse;
import com.cms.admin.notice.dto.response.NoticeResponse;
import com.cms.admin.notice.dto.response.NoticeSummaryResponse;
import com.cms.admin.notice.repository.NoticeAttachmentRepository;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.html.HtmlContentSanitizer;
import com.cms.common.html.SanitizedHtml;
import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NoticeService {

    /** 목록 페이지 크기 상한 — AdminActionLogQueryService.MAX_PAGE_SIZE 패턴 미러. */
    private static final int MAX_PAGE_SIZE = 100;

    static final String CONTENT_FORMAT_HTML = "HTML";

    /**
     * 보이는 텍스트 상한. 기존 "본문 10,000자"에 마지막 문단 종료 1자를 더한 값 — 기존 평문 N자는 HTML 변환 후 N+1자 이하가
     * 되므로(V25), 경계의 기존 공지도 다시 저장할 수 있다(PLAN-html-editor.md 쟁점 5·R3-1).
     */
    static final int MAX_CONTENT_TEXT_LENGTH = 10_001;

    /** 정리된 HTML 저장 상한(컬럼은 MEDIUMTEXT — V23). 기존 평문 변환본의 최악값 109,983바이트를 넉넉히 넘는다. */
    static final int MAX_CONTENT_BYTES = 200_000;

    private final NoticeRepository noticeRepository;
    private final NoticeAttachmentRepository noticeAttachmentRepository;
    private final ContentImageService contentImageService;
    private final AdminSecurityService adminSecurityService;
    private final Clock clock;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.NOTICE_CREATE, targetType = "NOTICE", targetIdExpression = "id")
    public NoticeResponse createNotice(NoticeCreateRequest request) {
        String authorId = requireCurrentAdminUserId();
        String title = requireNonBlank(request.getTitle(), "제목은 공백일 수 없습니다.");
        SanitizedHtml content = sanitizeContent(request.getContent(), request.getContentFormat());
        boolean useYn = request.getUseYn() == null || request.getUseYn();

        LocalDateTime now = LocalDateTime.now(clock);
        Notice saved = noticeRepository.save(
                Notice.builder()
                        .title(title)
                        .content(content.html())
                        .useYn(useYn)
                        .deleted(false)
                        .authorId(authorId)
                        .createDate(now)
                        .updateDate(now)
                        .build()
        );
        contentImageService.replaceRefs(ContentImageService.OWNER_NOTICE, saved.getId(),
                ContentImageService.SCOPE_NOTICE, null, content.imageIds());

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
        SanitizedHtml content = request.getContent() != null
                ? sanitizeContent(request.getContent(), request.getContentFormat())
                : null;

        target.update(title, content != null ? content.html() : null, request.getUseYn(), LocalDateTime.now(clock));
        if (content != null) {
            // 공지 행 잠금(findByIdAndDeletedFalseForUpdate) 안에서 본문과 참조를 함께 바꾼다 — 같은 공지의 동시 저장이 직렬화된다
            contentImageService.replaceRefs(ContentImageService.OWNER_NOTICE, target.getId(),
                    ContentImageService.SCOPE_NOTICE, null, content.imageIds());
        }

        return NoticeResponse.from(target);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.NOTICE_DELETE, targetType = "NOTICE", targetIdExpression = "id")
    public NoticeResponse deleteNotice(Long id) {
        Notice target = noticeRepository.findByIdAndDeletedFalseForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("공지사항을 찾을 수 없습니다."));

        // 첨부가 남아있으면 삭제를 차단한다 — notice 소프트 삭제는 API로 도달 불가능해지므로,
        // 그 상태에서 첨부만 남으면 영구 오펀이 된다(PLAN-notice-attachment.md 쟁점 14).
        // 관리자가 첨부를 먼저 모두 삭제해야 notice를 삭제할 수 있다.
        if (noticeAttachmentRepository.countByNoticeId(id) > 0) {
            throw new ConflictException("첨부파일이 남아있어 삭제할 수 없습니다. 첨부를 먼저 삭제해주세요.");
        }

        target.softDelete(LocalDateTime.now(clock));

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

    /**
     * 본문 HTML 정리·검증(PLAN-html-editor.md 쟁점 5). 형식 표식이 "HTML"이 아니면 배포 전에 열어 둔 평문 편집 화면의 요청이라
     * 거부한다(R2-2) — 평문을 HTML로 해석하면 문자 그대로 쓴 태그가 서식으로 바뀐다.
     */
    private SanitizedHtml sanitizeContent(String rawContent, String contentFormat) {
        if (!CONTENT_FORMAT_HTML.equals(contentFormat)) {
            throw new InvalidRequestException("편집 화면이 오래되었습니다. 새로고침 후 다시 저장해 주세요.");
        }
        SanitizedHtml sanitized = HtmlContentSanitizer.sanitize(rawContent);
        if (sanitized.blank()) {
            throw new InvalidRequestException("본문은 공백일 수 없습니다.");
        }
        if (sanitized.textLength() > MAX_CONTENT_TEXT_LENGTH) {
            throw new InvalidRequestException("본문은 10,000자 이하로 입력해주세요.");
        }
        if (sanitized.html().getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new InvalidRequestException("본문 서식이 너무 많습니다. 내용을 줄여주세요.");
        }
        return sanitized;
    }

    private String requireNonBlank(String value, String message) {
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new InvalidRequestException(message);
        }
        return trimmed;
    }
}
