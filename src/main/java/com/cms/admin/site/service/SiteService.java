package com.cms.admin.site.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.banner.repository.BannerRepository;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.faq.repository.FaqCategoryRepository;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.page.repository.SitePageRepository;
import com.cms.admin.popup.repository.PopupRepository;
import com.cms.admin.site.Site;
import com.cms.admin.site.SiteKey;
import com.cms.admin.site.SiteRepository;
import com.cms.admin.site.SiteType;
import com.cms.admin.site.dto.request.SiteCreateRequest;
import com.cms.admin.site.dto.request.SiteUpdateRequest;
import com.cms.admin.site.dto.response.SiteDeleteResult;
import com.cms.admin.site.dto.response.SiteResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사이트 관리(PLAN-site-menu.md D4·D6). <b>쓰기 메서드는 컨트롤러가 호출하는 최상위 트랜잭션 진입점</b>이고 READ_COMMITTED로 실행한다.
 * 수정·삭제는 사이트 행을 FOR UPDATE로 먼저 잠근다 — 그 사이트의 메뉴 쓰기가 모두 같은 행을 먼저 잠그므로(MenuService) 메뉴 존재·외부 링크 검사가
 * 메뉴 쓰기와 경합하지 않는다.
 *
 * <p>삭제 가드의 "내용"은 메뉴 + 사이트 전용 콘텐츠(배너·팝업·FAQ 카테고리·정적 페이지, PR ③; 게시판, PR ④)다. 사이트 소속 컬럼을 가진 새
 * 테이블이 생길 때는 그 FK도 ON DELETE RESTRICT로 두고 {@link #hasContent}에 포함시켜야 한다.
 */
@Service
@RequiredArgsConstructor
public class SiteService {

    private final SiteRepository siteRepository;
    private final MenuRepository menuRepository;
    private final BannerRepository bannerRepository;
    private final PopupRepository popupRepository;
    private final FaqCategoryRepository faqCategoryRepository;
    private final SitePageRepository sitePageRepository;
    private final BoardRepository boardRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SiteResponse> getSites() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : menuRepository.countGroupedBySite()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return siteRepository.findAllByOrderByIdAsc().stream()
                .map(site -> SiteResponse.from(site, counts.getOrDefault(site.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public SiteResponse getSite(Long id) {
        Site site = siteRepository.findById(id).orElseThrow(SiteService::notFound);
        return SiteResponse.from(site, countMenus(site.getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @AdminActionLogged(actionType = AdminActionTypes.SITE_CREATE, targetType = "SITE", targetIdExpression = "id")
    public SiteResponse createSite(SiteCreateRequest request) {
        String siteKey = request.getSiteKey();
        if (!SiteKey.isValid(siteKey)) {
            throw new InvalidRequestException("사이트 키 형식이 올바르지 않습니다.");
        }
        if (SiteKey.isReserved(siteKey)) {
            throw new InvalidRequestException("사용할 수 없는 사이트 키입니다.");
        }
        String siteName = requireNonBlank(request.getSiteName(), "사이트 이름은 공백일 수 없습니다.");
        if (siteRepository.existsBySiteKey(siteKey)) {
            throw new ConflictException("이미 사용 중인 사이트 키입니다.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Site saved = siteRepository.save(Site.builder()
                .siteKey(siteKey)
                .siteName(siteName)
                .siteType(SiteType.PUBLIC)
                .allowExternalLink(Boolean.TRUE.equals(request.getAllowExternalLink()))
                .useYn(true)
                .createDate(now)
                .updateDate(now)
                .build());
        return SiteResponse.from(saved, 0L);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @AdminActionLogged(actionType = AdminActionTypes.SITE_UPDATE, targetType = "SITE", targetIdExpression = "id")
    public SiteResponse updateSite(Long id, SiteUpdateRequest request) {
        if (Boolean.FALSE.equals(request.getDefaultSite())) {
            throw new InvalidRequestException("기본 사이트는 다른 사이트를 기본으로 지정해야만 바뀝니다.");
        }
        boolean makeDefault = Boolean.TRUE.equals(request.getDefaultSite());

        // 기본 사이트 지정은 옛 기본·새 기본 두 행을 함께 바꾸므로 처음부터 모든 site 행을 id 오름차순으로 잠근다(PLAN-site-menu.md P2-5).
        // 대상 행을 먼저 잠근 뒤 확장하면 잠금 순서가 깨져 교착할 수 있다.
        List<Site> allSites = makeDefault ? siteRepository.findAllForUpdate() : List.of();
        Site site = makeDefault
                ? allSites.stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow(SiteService::notFound)
                : siteRepository.findByIdForUpdate(id).orElseThrow(SiteService::notFound);

        String siteName = request.getSiteName() != null
                ? requireNonBlank(request.getSiteName(), "사이트 이름은 공백일 수 없습니다.")
                : site.getSiteName();
        boolean useYn = request.getUseYn() != null ? request.getUseYn() : site.getUseYn();
        if (!useYn && (site.isSeed() || site.isDefaultYn())) {
            throw new ConflictException("기본 사이트는 비활성화할 수 없습니다.");
        }
        if (makeDefault && (site.getSiteType() != SiteType.PUBLIC || !useYn)) {
            // 요청이 만드는 최종 상태 기준 — {defaultSite:true, useYn:false} 같은 복합 요청도 여기서 막는다.
            throw new InvalidRequestException("활성 상태의 공개 사이트만 기본 사이트로 지정할 수 있습니다.");
        }

        boolean allowExternal = site.permitsExternalLink();
        if (request.getAllowExternalLink() != null) {
            boolean requested = request.getAllowExternalLink();
            if (site.getSiteType() == SiteType.ADMIN && requested) {
                throw new InvalidRequestException("관리자 사이트 메뉴에는 외부 링크를 허용할 수 없습니다.");
            }
            allowExternal = requested;
        }
        if (site.permitsExternalLink() && !allowExternal) {
            // 정책 강화 — 사이트 행 잠금 아래에서 검사하므로 메뉴 쓰기와 경합하지 않는다(모든 메뉴 쓰기가 같은 행을 먼저 잠근다).
            long absolute = menuRepository.countAbsoluteUrlMenus(id);
            if (absolute > 0) {
                throw new ConflictException("외부 링크를 사용하는 메뉴가 " + absolute + "개 있어 외부 링크 허용을 끌 수 없습니다. 먼저 메뉴 URL을 내부 경로로 바꾸거나 지워주세요.");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        site.update(siteName, allowExternal, useYn, now);
        if (makeDefault && !site.isDefaultYn()) {
            allSites.stream().filter(Site::isDefaultYn).forEach(previous -> previous.markDefault(false, now));
            site.markDefault(true, now);
        }
        return SiteResponse.from(site, countMenus(id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @AdminActionLogged(actionType = AdminActionTypes.SITE_DELETE, targetType = "SITE",
            targetIdExpression = "id", targetLabelExpression = "auditLabel")
    public SiteDeleteResult deleteSite(Long id) {
        Site site = siteRepository.findByIdForUpdate(id).orElseThrow(SiteService::notFound);
        if (site.isSeed() || site.isDefaultYn()) {
            throw new ConflictException("기본 사이트는 삭제할 수 없습니다.");
        }
        if (hasContent(site)) {
            throw new ConflictException("메뉴나 콘텐츠(배너·팝업·FAQ 카테고리·정적 페이지·게시판)가 있는 사이트는 삭제할 수 없습니다. 비활성화하거나 먼저 삭제해주세요.");
        }
        SiteDeleteResult snapshot = new SiteDeleteResult(site.getId(), site.getSiteKey());
        siteRepository.delete(site);
        return snapshot;
    }

    /**
     * 사이트에 속한 내용이 있는가 — 메뉴, 배너, 팝업, FAQ 카테고리, 정적 페이지(PR ③). 사이트 행 잠금 뒤에 호출한다. 새 {@code site_id} 소유 테이블이
     * 생기면 여기와 FK {@code RESTRICT}를 함께 추가한다.
     */
    private boolean hasContent(Site site) {
        Long id = site.getId();
        return menuRepository.existsBySiteId(id)
                || bannerRepository.existsBySiteId(id)
                || popupRepository.existsBySiteId(id)
                || faqCategoryRepository.existsBySiteId(id)
                || sitePageRepository.existsBySiteId(id)
                || boardRepository.existsBySiteId(id);
    }

    private long countMenus(Long siteId) {
        return menuRepository.countGroupedBySite().stream()
                .filter(row -> siteId.equals(row[0]))
                .mapToLong(row -> (Long) row[1])
                .findFirst()
                .orElse(0L);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("사이트를 찾을 수 없습니다.");
    }

    private static String requireNonBlank(String value, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            throw new InvalidRequestException(message);
        }
        return trimmed;
    }
}
