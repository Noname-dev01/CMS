package com.cms.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 클라이언트 IP 신뢰 정책 단일 출처.
 * X-Forwarded-For/X-Real-IP 등 전달 헤더는 신뢰 프록시가 없는 한 위조 가능하므로
 * 전혀 읽지 않고 request.getRemoteAddr()만 사용한다(감사 H-03).
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("request가 null이면 null을 반환한다")
    void resolve_nullRequest_returnsNull() {
        assertThat(ClientIpResolver.resolve(null)).isNull();
    }

    @Test
    @DisplayName("getRemoteAddr()가 null이면 null을 반환한다")
    void resolve_nullRemoteAddr_returnsNull() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(null);

        assertThat(ClientIpResolver.resolve(request)).isNull();
    }

    @Test
    @DisplayName("getRemoteAddr() 값을 그대로 반환한다")
    void resolve_returnsRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("X-Forwarded-For·X-Real-IP 헤더가 있어도 무시하고 getRemoteAddr()만 사용한다(위조 차단)")
    void resolve_ignoresForwardedHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");
        when(request.getHeader("X-FORWARDED-FOR")).thenReturn("9.9.9.9");
        when(request.getHeader("X-Real-IP")).thenReturn("8.8.8.8");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
        verify(request, never()).getHeader("X-FORWARDED-FOR");
        verify(request, never()).getHeader("X-Real-IP");
    }

    @Test
    @DisplayName("조작된 X-Forwarded-For: , 헤더가 있어도 예외 없이 getRemoteAddr()를 반환한다")
    void resolve_malformedForwardedFor_doesNotThrow() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");
        when(request.getHeader("X-FORWARDED-FOR")).thenReturn(",");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("getRemoteAddr()가 45자를 초과하면 45자로 절단한다")
    void resolve_truncatesLongAddress() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        String longIp = "1".repeat(60);
        when(request.getRemoteAddr()).thenReturn(longIp);

        String result = ClientIpResolver.resolve(request);

        assertThat(result).hasSize(45);
        assertThat(result).isEqualTo(longIp.substring(0, 45));
    }

    @Test
    @DisplayName("45자 이하 값은 그대로 반환한다")
    void resolve_shortAddress_notTruncated() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("::1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("::1");
    }
}
