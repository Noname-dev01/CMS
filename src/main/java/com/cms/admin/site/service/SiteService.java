package com.cms.admin.site.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.menu.MenuRepository;
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
 * <p>삭제 가드의 "내용"은 지금은 메뉴 존재뿐이다. 사이트 소속 컬럼을 가진 새 테이블(배너·팝업·FAQ·게시판 등, PR ③④)이 생길 때는 그 FK도
 * ON DELETE RESTRICT로 두고 {@link #hasContent}에 포함시켜야 한다.
 */
@Service
@RequiredArgsConstructor
public class SiteService {

    private final SiteRepository siteRepository;
    private final MenuRepository menuRepository;
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
        Site site = siteRepository.findByIdForUpdate(id).orElseThrow(SiteService::notFound);

        String siteName = request.getSiteName() != null
                ? requireNonBlank(request.getSiteName(), "사이트 이름은 공백일 수 없습니다.")
                : site.getSiteName();
        boolean useYn = request.getUseYn() != null ? request.getUseYn() : site.getUseYn();
        if (!useYn && site.isSeed()) {
            throw new ConflictException("기본 사이트는 비활성화할 수 없습니다.");
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

        site.update(siteName, allowExternal, useYn, LocalDateTime.now(clock));
        return SiteResponse.from(site, countMenus(id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @AdminActionLogged(actionType = AdminActionTypes.SITE_DELETE, targetType = "SITE",
            targetIdExpression = "id", targetLabelExpression = "auditLabel")
    public SiteDeleteResult deleteSite(Long id) {
        Site site = siteRepository.findByIdForUpdate(id).orElseThrow(SiteService::notFound);
        if (site.isSeed()) {
            throw new ConflictException("기본 사이트는 삭제할 수 없습니다.");
        }
        if (hasContent(site)) {
            throw new ConflictException("메뉴가 있는 사이트는 삭제할 수 없습니다. 비활성화하거나 메뉴를 먼저 삭제해주세요.");
        }
        SiteDeleteResult snapshot = new SiteDeleteResult(site.getId(), site.getSiteKey());
        siteRepository.delete(site);
        return snapshot;
    }

    /** 사이트에 속한 내용이 있는가 — 지금은 메뉴뿐이다(클래스 설명 참조). 사이트 행 잠금 뒤에 호출한다. */
    private boolean hasContent(Site site) {
        return menuRepository.existsBySiteId(site.getId());
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
