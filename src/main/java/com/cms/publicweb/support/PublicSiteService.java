package com.cms.publicweb.support;

import com.cms.admin.site.Site;
import com.cms.admin.site.SiteKey;
import com.cms.admin.site.SiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 공개 라우팅의 사이트 해석(PLAN-site-menu.md PR ② P2-1·P2-5). 형식 → 예약어 → DB 순으로 판정해 어느 하나라도 실패하면 비어 있는 결과를 낸다 —
 * 호출자는 사유를 구분하지 않고 같은 404로 응답한다(존재 여부·비활성 여부를 드러내지 않는다). 형식·예약어 검사는 DB 조회 전에 끝나므로 무작위 키 요청이
 * 대부분 DB에 닿지 않는다. 캐시는 두지 않는다(사이트 비활성화·기본 사이트 변경이 즉시 반영돼야 하고, 레이트리밋이 요청 수를 제한한다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicSiteService {

    private final SiteRepository siteRepository;

    @Transactional(readOnly = true)
    public Optional<PublicSite> findActive(String key) {
        if (!SiteKey.isValid(key) || SiteKey.isReserved(key)) {
            return Optional.empty();
        }
        return siteRepository.findActivePublicByKey(key).map(PublicSiteService::toPublicSite);
    }

    /**
     * 접두어 없는 {@code /}가 이동할 기본 사이트. 표시된 행이 정확히 1개이고 활성 공개 사이트일 때만 돌려준다 — 0개·2개 이상·비활성이면 데이터 이상이므로
     * 오류를 남기고 비어 있는 결과(fail-closed, 호출자는 404)로 답한다.
     */
    @Transactional(readOnly = true)
    public Optional<PublicSite> findDefault() {
        List<Site> defaults = siteRepository.findByDefaultYnTrue();
        if (defaults.size() != 1) {
            log.error("기본 공개 사이트 표시가 정확히 1개가 아닙니다: {}개", defaults.size());
            return Optional.empty();
        }
        Site site = defaults.get(0);
        if (!Boolean.TRUE.equals(site.getUseYn()) || !SiteKey.isValid(site.getSiteKey()) || SiteKey.isReserved(site.getSiteKey())) {
            log.error("기본 공개 사이트가 비활성이거나 사이트 키가 유효하지 않습니다: id={}", site.getId());
            return Optional.empty();
        }
        return Optional.of(toPublicSite(site));
    }

    private static PublicSite toPublicSite(Site site) {
        return new PublicSite(site.getId(), site.getSiteKey(), site.getSiteName());
    }
}
