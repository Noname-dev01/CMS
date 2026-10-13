package com.cms.admin.page.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.page.domain.SitePage;
import com.cms.admin.page.domain.SitePageRevision;
import com.cms.admin.page.domain.SitePageRevisionId;
import com.cms.admin.page.domain.SiteSlug;
import com.cms.admin.page.dto.request.SitePageCreateRequest;
import com.cms.admin.page.dto.request.SitePageUpdateRequest;
import com.cms.admin.page.dto.response.SitePageDeleteResult;
import com.cms.admin.page.dto.response.SitePageDetailResponse;
import com.cms.admin.page.dto.response.SitePageRevisionSummary;
import com.cms.admin.page.dto.response.SitePageSummaryResponse;
import com.cms.admin.page.repository.SitePageRepository;
import com.cms.admin.page.repository.SitePageRevisionRepository;
import com.cms.admin.site.ContentSiteScope;
import com.cms.admin.site.SiteScope;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.html.ContentBodyPolicy;
import com.cms.common.html.SanitizedHtml;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 정적 페이지 관리(PLAN-static-page.md). 권한은 컨트롤러의 {@code @RequirePermission(SITE_PAGE, …)}가 판정하고 서비스는 대상의 존재·상태만 본다.
 *
 * <p><b>잠금·낡은 화면</b>: 수정·삭제는 첫 DB 조회가 페이지 행 {@code FOR UPDATE}이고, 잠근 뒤 요청의 {@code version}과 비교해 다르면 409다
 * (쟁점 10). 본문·노출·푸터 어느 변경이든 {@code version}이 오르므로 메타만 바꾼 저장 뒤의 낡은 화면도 걸러진다. 개정 번호는 같은 잠금 아래에서
 * 매기고 복합 PK가 최종 방어선이다. 생성은 잠글 행이 없어 슬러그 유니크 키가 직렬화 수단이다.
 *
 * <p><b>감사</b>: 실패 행의 {@code errorMessage}는 고정 문구다 — 저장·커밋 예외 메시지에 SQL·입력값(제목·본문)이 섞여 감사 테이블에 사본이
 * 남지 않게 한다(R2-2). 4xx 사유는 HTTP 응답으로만 전달된다.
 */
@Service
@RequiredArgsConstructor
public class SitePageService {

    /** 관리 상세·응답에 싣는 최근 개정 요약 수(R1-4). */
    static final int REVISION_SUMMARY_LIMIT = 20;

    static final String PAGE_NOT_FOUND = "정적 페이지를 찾을 수 없습니다.";
    static final String STALE_VERSION = "다른 관리자가 이 페이지를 먼저 저장했습니다. 화면을 새로 고친 뒤 다시 시도해주세요.";

    private final SitePageRepository pageRepository;
    private final SitePageRevisionRepository revisionRepository;
    private final ContentSiteScope contentSiteScope;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SitePageSummaryResponse> getPages() {
        return pageRepository.findAllSummaries().stream().map(SitePageSummaryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public SitePageDetailResponse getPage(Long id) {
        SitePage page = pageRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(PAGE_NOT_FOUND));
        return detailOf(page, requireCurrentRevision(page), null);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.SITE_PAGE_CREATE, targetType = "SITE_PAGE", targetIdExpression = "id",
            targetLabelExpression = "auditLabel", safeErrorMessage = "정적 페이지 등록에 실패했습니다.")
    public SitePageDetailResponse createPage(SitePageCreateRequest request) {
        // 잠금·쓰기 전에 끝낼 수 있는 검증을 모두 마친다
        String slug = requireSlug(request.getSlug());
        String title = requireTitle(request.getTitle());
        SanitizedHtml body = ContentBodyPolicy.sanitizePage(request.getContent(), request.getContentFormat());
        boolean useYn = request.getUseYn() == null || request.getUseYn();
        boolean footerYn = request.getFooterYn() != null && request.getFooterYn();
        Long siteId = SiteScope.parse(request.getSiteScope());

        if (pageRepository.existsBySlug(slug)) {
            throw new ConflictException("이미 사용 중인 슬러그입니다.");
        }
        contentSiteScope.requireAssignable(siteId);
        LocalDateTime now = LocalDateTime.now(clock);
        SitePage page;
        try {
            // 동시 등록의 패자는 유니크 키 위반으로 여기서 드러나게 즉시 flush한다
            page = pageRepository.saveAndFlush(SitePage.builder()
                    .slug(slug)
                    .currentRevisionNo(1)
                    .version(1)
                    .useYn(useYn)
                    .footerYn(footerYn)
                    .siteId(siteId)
                    .createDate(now)
                    .updateDate(now)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 사용 중인 슬러그입니다.");
        }
        SitePageRevision revision = revisionRepository.save(new SitePageRevision(page.getId(), 1, title, body.html(), now));
        return detailOf(page, revision, "rev=1");
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.SITE_PAGE_UPDATE, targetType = "SITE_PAGE", targetIdExpression = "id",
            targetLabelExpression = "auditLabel", safeErrorMessage = "정적 페이지 수정에 실패했습니다.")
    public SitePageDetailResponse updatePage(Long id, SitePageUpdateRequest request) {
        String title = requireTitle(request.getTitle());
        SanitizedHtml body = ContentBodyPolicy.sanitizePage(request.getContent(), request.getContentFormat());
        Long siteId = SiteScope.parseRequired(request.getSiteScope());

        SitePage page = pageRepository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(PAGE_NOT_FOUND));
        requireFreshVersion(page, request.getVersion());
        contentSiteScope.requireAssignable(siteId);

        SitePageRevision current = requireCurrentRevision(page);
        boolean contentChanged = !current.getTitle().equals(title) || !current.getContent().equals(body.html());
        boolean flagsChanged = page.changeFlags(request.getUseYn(), request.getFooterYn(), siteId);
        if (!contentChanged && !flagsChanged) {
            // 아무것도 바뀌지 않은 저장은 개정도 버전도 올리지 않는다(재저장·더블클릭이 이력을 부풀리지 않게)
            return detailOf(page, current, "rev=" + page.getCurrentRevisionNo() + " 변경 없음");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        SitePageRevision effective = current;
        String label = "rev=" + page.getCurrentRevisionNo() + " 유지";
        if (contentChanged) {
            int revisionNo = page.advanceRevision();
            effective = revisionRepository.save(new SitePageRevision(page.getId(), revisionNo, title, body.html(), now));
            label = "rev=" + revisionNo;
        }
        page.markChanged(now);
        return detailOf(page, effective, label);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.SITE_PAGE_DELETE, targetType = "SITE_PAGE", targetIdExpression = "id",
            targetLabelExpression = "auditLabel", safeErrorMessage = "정적 페이지 삭제에 실패했습니다.")
    public SitePageDeleteResult deletePage(Long id, Integer version) {
        if (version == null || version < 1) {
            throw new InvalidRequestException("version이 필요합니다.");
        }
        SitePage page = pageRepository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(PAGE_NOT_FOUND));
        requireFreshVersion(page, version);

        // 행이 사라진 뒤에는 값을 읽을 수 없으므로 삭제 전에 스냅샷을 만든다. 개정을 먼저 지워야 FK(RESTRICT)를 지킨다.
        SitePageDeleteResult snapshot = new SitePageDeleteResult(page.getId(), "rev=" + page.getCurrentRevisionNo());
        revisionRepository.deleteByPageId(page.getId());
        pageRepository.delete(page);
        return snapshot;
    }

    private SitePageDetailResponse detailOf(SitePage page, SitePageRevision current, String auditLabel) {
        int high = page.getCurrentRevisionNo();
        int low = Math.max(1, high - REVISION_SUMMARY_LIMIT + 1);
        List<SitePageRevisionSummary> revisions = revisionRepository.findSummaries(page.getId(), low, high).stream()
                .map(SitePageRevisionSummary::from)
                .toList();
        return SitePageDetailResponse.of(page, current, revisions, auditLabel);
    }

    private SitePageRevision requireCurrentRevision(SitePage page) {
        return revisionRepository.findById(new SitePageRevisionId(page.getId(), page.getCurrentRevisionNo()))
                .orElseThrow(() -> new IllegalStateException("현재 개정 행이 없습니다: pageId=" + page.getId()));
    }

    private static void requireFreshVersion(SitePage page, Integer requested) {
        if (!page.getVersion().equals(requested)) {
            throw new ConflictException(STALE_VERSION);
        }
    }

    private static String requireSlug(String slug) {
        if (!SiteSlug.isValid(slug)) {
            throw new InvalidRequestException("슬러그는 소문자·숫자·하이픈 2~50자여야 합니다(처음·끝·연속 하이픈 불가).");
        }
        if (SiteSlug.isReserved(slug)) {
            throw new InvalidRequestException("사용할 수 없는 슬러그입니다.");
        }
        return slug;
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidRequestException("제목은 공백일 수 없습니다.");
        }
        return title.strip();
    }
}
