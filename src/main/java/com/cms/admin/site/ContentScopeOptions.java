package com.cms.admin.site;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 콘텐츠 관리 화면(배너·팝업·FAQ·정적 페이지)의 "표시 대상" 선택지 — 공개 사이트 목록과 기본 공개 사이트의 키(PLAN-site-menu.md PR ③ S7).
 * 위임받은 MANAGER도 이 화면을 쓰는데 사이트 목록 API({@code /admin/api/sites})는 ADMIN 전용이므로, 새 API나 인가 변경 없이 <b>서버가 페이지
 * 모델로 렌더링</b>한다. 이름·키는 관리자가 입력한 값이라 템플릿은 {@code th:text}로만 출력한다.
 */
@Component
@RequiredArgsConstructor
public class ContentScopeOptions {

    private final SiteRepository siteRepository;

    /** 선택지 한 건. {@code active}가 거짓이면 화면이 "(비활성)"을 붙인다(비활성 사이트도 지정은 가능하다). */
    public record Option(Long id, String key, String name, boolean active) {
    }

    /** @param defaultKey 기본 공개 사이트의 키(활성 공개 사이트가 아니면 null) — 공통 페이지의 공개 주소 접두어 */
    public record Options(List<Option> sites, String defaultKey) {
    }

    @Transactional(readOnly = true)
    public Options load() {
        List<Site> publicSites = siteRepository.findAllByOrderByIdAsc().stream()
                .filter(site -> site.getSiteType() == SiteType.PUBLIC)
                .toList();
        List<Option> options = publicSites.stream()
                .map(site -> new Option(site.getId(), site.getSiteKey(), site.getSiteName(), Boolean.TRUE.equals(site.getUseYn())))
                .toList();
        String defaultKey = publicSites.stream()
                .filter(site -> site.isDefaultYn() && Boolean.TRUE.equals(site.getUseYn()))
                .map(Site::getSiteKey)
                .findFirst()
                .orElse(null);
        return new Options(options, defaultKey);
    }
}
