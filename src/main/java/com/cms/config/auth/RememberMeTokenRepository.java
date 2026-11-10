package com.cms.config.auth;

import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;

import java.sql.Timestamp;
import java.util.Date;

/**
 * remember-me 영속 토큰 저장소(테이블 {@code persistent_logins}, V15 — 이슈 #222, PLAN-remember-me-login.md).
 *
 * <p>Spring 기본 구현에 두 가지를 더한다.
 * <ul>
 *   <li>{@link #updateToken}이 0행이면 예외 — 기본 구현은 영향 행 수를 버려, 조회 직후 폐기된 토큰으로도 자동 로그인이 진행됐다.
 *       라이브러리가 이 예외를 {@code RememberMeAuthenticationException}으로 바꿔 익명 처리한다.</li>
 *   <li>기기(시리즈) 단위·회원 단위 폐기 — 라이브러리의 {@code removeUserTokens}는 회원의 모든 기기를 지운다.</li>
 * </ul>
 * 같은 {@code DataSource}의 {@code JdbcTemplate}이라 호출 시점에 열린 스프링 트랜잭션이 있으면 거기에 참여한다
 * (회원 변경 이벤트의 BEFORE_COMMIT 폐기가 이 성질에 기댄다).
 */
public class RememberMeTokenRepository extends JdbcTokenRepositoryImpl {

    @Override
    public void updateToken(String series, String tokenValue, Date lastUsed) {
        int updated = template().update(
                "UPDATE persistent_logins SET token = ?, last_used = ? WHERE series = ?",
                tokenValue, new Timestamp(lastUsed.getTime()), series);
        if (updated == 0) {
            throw new IllegalStateException("remember-me 토큰이 이미 폐기됐습니다.");
        }
    }

    /** 기기 하나(시리즈)를 지운다. 없으면 아무것도 하지 않는다. */
    public void removeSeries(String series) {
        template().update("DELETE FROM persistent_logins WHERE series = ?", series);
    }

    public boolean existsSeries(String series) {
        Integer count = template().queryForObject(
                "SELECT COUNT(*) FROM persistent_logins WHERE series = ?", Integer.class, series);
        return count != null && count > 0;
    }

    /** 회원의 모든 기기를 지운다. */
    public void removeAllForMember(Long memberId) {
        template().update(
                "DELETE FROM persistent_logins WHERE username = (SELECT user_id FROM member WHERE id = ?)", memberId);
    }

    /** 회원의 기기 중 {@code keptSeries}를 뺀 전부를 지운다. {@code keptSeries}가 {@code null}이면 전부. */
    public void removeAllForMemberExcept(Long memberId, String keptSeries) {
        if (keptSeries == null) {
            removeAllForMember(memberId);
            return;
        }
        template().update(
                "DELETE FROM persistent_logins WHERE username = (SELECT user_id FROM member WHERE id = ?) AND series <> ?",
                memberId, keptSeries);
    }

    private org.springframework.jdbc.core.JdbcTemplate template() {
        org.springframework.jdbc.core.JdbcTemplate template = getJdbcTemplate();
        if (template == null) {
            throw new IllegalStateException("DataSource가 설정되지 않았습니다.");
        }
        return template;
    }
}
