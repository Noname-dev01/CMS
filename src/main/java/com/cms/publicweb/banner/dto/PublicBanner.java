package com.cms.publicweb.banner.dto;

import com.cms.admin.banner.domain.Banner;
import com.cms.common.web.SafeUrls;

/**
 * 공개 메인에 그릴 배너. 관리 응답({@code BannerResponse})을 재사용하지 않는다 — 노출 여부·기간·저장 키·업로더 정보가 딸려 나가지 않게
 * 필요한 값만 담는다. 링크는 출력 시 한 번 더 {@link SafeUrls}로 거른다(저장 시 검증을 통과했더라도 DB 직접 수정·과거 값 방어) —
 * 통과하지 못하면 링크 없는 배너로 그린다. 외부 http(s) 링크는 화면이 새 탭·{@code noopener noreferrer}로 연다.
 */
public record PublicBanner(Long id, String title, String linkUrl, boolean externalLink) {

    public static PublicBanner from(Banner banner) {
        String link = banner.getLinkUrl();
        boolean safe = SafeUrls.isSafeMenuUrl(link);
        return new PublicBanner(banner.getId(), banner.getTitle(), safe ? link : null,
                safe && SafeUrls.isExternalHttpUrl(link));
    }
}
