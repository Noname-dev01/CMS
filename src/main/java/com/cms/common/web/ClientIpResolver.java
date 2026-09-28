package com.cms.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 클라이언트 IP를 단일 소스에서 해석한다.
 *
 * <p>이 프로젝트에는 현재 실제 리버스 프록시가 없다(Gate H NOT RUN) — {@code X-Forwarded-For}·
 * {@code X-Real-IP} 등 전달 헤더는 클라이언트가 임의로 보낼 수 있어 신뢰하지 않는다.
 * {@code request.getRemoteAddr()}만 사용한다(레이트리밋({@code com.cms.config.ratelimit})과 동일 정책).
 * 실제 ingress가 도입되면 이 클래스를 포함해 IP 해석 로직 전체를 재검토해야 한다.
 */
public final class ClientIpResolver {

    /** admin_action_log·visit_log의 request_ip 컬럼 길이 */
    private static final int MAX_LENGTH = 45;

    private ClientIpResolver() {
    }

    /**
     * @return {@code request}가 {@code null}이거나 {@code getRemoteAddr()}가 {@code null}이면 {@code null}.
     *         그 외에는 45자로 절단된 {@code getRemoteAddr()} 값.
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String ip = request.getRemoteAddr();
        if (ip == null) {
            return null;
        }

        return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
    }
}
