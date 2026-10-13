package com.cms.publicweb.support;

/** 요청 경로의 {@code /{siteKey}}가 가리키는 활성 공개 사이트. 템플릿은 {@link #key()}로 링크 접두어를 만든다. */
public record PublicSite(Long id, String key, String name) {

    /** 링크 접두어 — 예: {@code /main}. 키는 {@code SiteKey} 형식(소문자·숫자·하이픈)이라 인코딩이 필요 없다. */
    public String prefix() {
        return "/" + key;
    }
}
