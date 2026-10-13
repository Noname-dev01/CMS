package com.cms.admin.site.dto.response;

import lombok.Getter;

/**
 * 사이트 삭제의 서비스 내부 결과. 컨트롤러는 응답으로 노출하지 않고(204) 버린다. 감사 Aspect가 반환 객체의 getter에서만
 * targetId·targetLabel을 추출하므로 삭제 전에 만든 스냅샷을 돌려준다(MenuDeleteResult와 같은 이유).
 */
@Getter
public class SiteDeleteResult {

    private final Long id;
    private final String siteKey;

    public SiteDeleteResult(Long id, String siteKey) {
        this.id = id;
        this.siteKey = siteKey;
    }

    /** 감사 로그 targetLabel — 사이트 키. */
    public String getAuditLabel() {
        return siteKey;
    }
}
