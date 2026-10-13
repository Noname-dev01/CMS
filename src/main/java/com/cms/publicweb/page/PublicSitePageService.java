package com.cms.publicweb.page;

import com.cms.admin.page.domain.SiteSlug;
import com.cms.admin.page.repository.PublicSitePageHeaderRow;
import com.cms.admin.page.repository.PublicSitePageRow;
import com.cms.admin.page.repository.SitePageRepository;
import com.cms.admin.page.repository.SitePageRevisionRepository;
import com.cms.common.html.ContentBodyPolicy;
import com.cms.publicweb.page.dto.PublicFooterLink;
import com.cms.publicweb.page.dto.PublicSitePageRevisionItem;
import com.cms.publicweb.page.dto.PublicSitePageRevisions;
import com.cms.publicweb.page.dto.PublicSitePageView;
import com.cms.publicweb.support.PublicSite;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 공개(비로그인) 정적 페이지 조회 전용 서비스(PLAN-static-page.md). 관리 서비스와 별도 클래스로 두어 공개 불변식이 선택적 필터가 아니라 이 클래스가
 * 돌려주는 모든 것의 조건이 되게 한다.
 *
 * <p><b>불변식</b>: 페이지 {@code useYn=true}(쿼리에 고정). 비노출 페이지는 현재 버전·개정 목록·이전 개정이 모두 empty이고 컨트롤러가 같은 404로
 * 흡수한다. 슬러그 형식 위반·없는 슬러그·없는 개정도 전부 empty다(존재 여부 비노출). 본문은 출력 시 한 번 더 이미지 불허 규칙으로 정리한다.
 */
@Service
@RequiredArgsConstructor
public class PublicSitePageService {

    static final int REVISION_PAGE_SIZE = 20;

    private final SitePageRepository pageRepository;
    private final SitePageRevisionRepository revisionRepository;

    @Transactional(readOnly = true)
    public Optional<PublicSitePageView> findCurrent(String slug, Long siteId) {
        PublicSite.requireId(siteId);
        if (!SiteSlug.isValid(slug)) {
            return Optional.empty();
        }
        return pageRepository.findPublicCurrent(slug, siteId).map(PublicSitePageService::toView);
    }

    /** {@code revisionNo}는 컨트롤러가 이미 양의 {@code int}로 파싱한 값이다(없는 번호는 empty). */
    @Transactional(readOnly = true)
    public Optional<PublicSitePageView> findRevision(String slug, int revisionNo, Long siteId) {
        PublicSite.requireId(siteId);
        if (!SiteSlug.isValid(slug) || revisionNo < 1) {
            return Optional.empty();
        }
        return pageRepository.findPublicRevision(slug, revisionNo, siteId).map(PublicSitePageService::toView);
    }

    /**
     * 개정 목록 한 쪽. 쪽 {@code p}(0부터)는 {@code U = 현재 개정 번호 − 20p}일 때 {@code max(1, U−19) ≤ 번호 ≤ U}인 PK 범위다 — OFFSET이 없어
     * 비용이 쪽 번호와 무관하고 전 이력에 도달할 수 있다. 음수이거나 마지막 쪽을 넘으면({@code U < 1}) 첫 쪽으로 보정한다. 산술은 {@code long}.
     */
    @Transactional(readOnly = true)
    public Optional<PublicSitePageRevisions> findRevisions(String slug, int rawPage, Long siteId) {
        PublicSite.requireId(siteId);
        if (!SiteSlug.isValid(slug)) {
            return Optional.empty();
        }
        return pageRepository.findPublicHeader(slug, siteId).map(header -> {
            long current = header.currentRevisionNo();
            int page = rawPage;
            long upper = current - (long) REVISION_PAGE_SIZE * page;
            if (page < 0 || upper < 1) {
                page = 0;
                upper = current;
            }
            long lower = Math.max(1L, upper - (REVISION_PAGE_SIZE - 1));
            List<PublicSitePageRevisionItem> items = revisionRepository
                    .findSummaries(header.pageId(), (int) lower, (int) upper).stream()
                    .map(row -> new PublicSitePageRevisionItem(row.revisionNo(), row.title(), row.createDate(),
                            row.revisionNo() == current))
                    .toList();
            return new PublicSitePageRevisions(header.slug(), header.title(), items, page, page > 0, upper > REVISION_PAGE_SIZE);
        });
    }

    /**
     * 공개 메인 푸터 링크. 선택 섹션이라 호출자({@code HomeController})가 이 호출을 별도로 하고 예외를 격리한다 — 메인 조회 트랜잭션에
     * 넣으면 이 조회의 실패가 트랜잭션을 rollback-only로 만들어 메인까지 깨뜨릴 수 있다(PLAN-static-page.md R1-3).
     */
    @Transactional(readOnly = true)
    public List<PublicFooterLink> findFooterLinks(Long siteId) {
        return pageRepository.findFooterLinks(PublicSite.requireId(siteId)).stream()
                .map((PublicSitePageHeaderRow row) -> new PublicFooterLink(row.slug(), row.title()))
                .toList();
    }

    private static PublicSitePageView toView(PublicSitePageRow row) {
        return new PublicSitePageView(row.slug(), row.title(), ContentBodyPolicy.sanitizeForPageOutput(row.content()),
                row.revisionNo(), row.revisionDate(), row.revisionNo().equals(row.currentRevisionNo()));
    }
}
