package com.cms.publicweb.support;

/** 요청 경로의 {@code /{siteKey}}가 가리키는 활성 공개 사이트. 템플릿은 {@link #key()}로 링크 접두어를 만든다. */
public record PublicSite(Long id, String key, String name) {

    /** 공개 서비스가 받은 {@code siteId}가 비어 있으면(= 사이트 필터 없는 호출) 거부한다 — 필터를 빠뜨린 호출이 조용히 전체를 보여 주지 않게(PLAN-site-menu.md PR ③ S3). */
    public static Long requireId(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("siteId가 필요합니다.");
        }
        return siteId;
    }

    /** 링크 접두어 — 예: {@code /main}. 키는 {@code SiteKey} 형식(소문자·숫자·하이픈)이라 인코딩이 필요 없다. */
    public String prefix() {
        return "/" + key;
    }
}
