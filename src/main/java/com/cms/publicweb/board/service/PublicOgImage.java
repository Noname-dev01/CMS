package com.cms.publicweb.board.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 공개 상세의 {@code og:image} 절대 URL을 만든다(PLAN-home-cover-og-image.md D3). 호스트 원천은 {@code app.base-url}이다 — 요청의 Host 헤더는
 * 프록시 뒤에서 내부 호스트이거나 위조된 값일 수 있고(이 앱은 forward-headers를 신뢰하지 않는다) 메타 값으로 캐시되기 때문이다.
 * base-url의 형식은 {@code PasswordResetService}가 기동 시 검증한다(http(s)·userinfo/query/fragment 없음). 이 클래스는 끝의 {@code /}만 정리한다.
 */
@Component
public class PublicOgImage {

    private final String baseUrl;

    public PublicOgImage(@Value("${app.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** 대표 이미지의 썸네일 절대 URL. 대표가 없으면 null(메타를 넣지 않는다). */
    public String urlFor(Long coverImageId) {
        if (coverImageId == null) {
            return null;
        }
        return baseUrl + "/content-images/" + coverImageId + "?size=thumb";
    }
}
