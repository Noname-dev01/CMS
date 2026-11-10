package com.cms.config.auth;

/**
 * remember-me 인증 토큰({@code RememberMeAuthenticationToken})의 내부 서명 키. 기동마다 무작위로 만든다 —
 * 영속 토큰은 DB가 원본이라 재기동해도 쿠키는 유효하다(새 환경변수 없음). {@code SecurityConfig}·서비스·
 * {@code AdminSecurityService.refreshAuthentication}(인증 종류 보존)이 같은 값을 써야 해서 빈으로 공유한다.
 */
public record RememberMeKey(String value) {

    @Override
    public String toString() {
        return "RememberMeKey[****]";
    }
}
