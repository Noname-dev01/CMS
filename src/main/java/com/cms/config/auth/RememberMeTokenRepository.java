package com.cms.config.auth;

import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

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

    /** 만료 행 정리 한 번이 로그인 응답을 붙잡는 시간의 예산 — 넘으면 남은 행은 다음 로그인으로 넘긴다. */
    static final Duration PURGE_BUDGET = Duration.ofSeconds(2);

    /**
     * 정리 문장(조회·삭제)의 대기 상한. {@code ContentCounterWriter}와 같은 방식이다.
     * 행 잠금 대기는 락마다 따로 계산돼 합산되지 않으므로 실행 상한을 함께 건다.
     * {@code lock_wait_timeout}(기본 1일)은 메타데이터 잠금 대기다. 다른 연결이 이 표만 {@code LOCK TABLES}로 잡으면,
     * 이 표를 쓰지 않던 remember-me 없는 로그인까지 정리 때문에 멈추므로 같이 묶는다(코드 리뷰 반영).
     */
    private static final String STATEMENT_LIMITS =
            "SET STATEMENT max_statement_time=3, innodb_lock_wait_timeout=1, lock_wait_timeout=1 FOR ";

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

    /**
     * 만료된 행을 지운다 — 기준은 {@code now − 유효 기간}보다 <b>엄격히</b> 이전인 {@code last_used}로, 자동 로그인의 만료 판정
     * ({@code last_used + 유효 기간 < now})과 같다. 모든 회원이 대상이다(PLAN-remember-me-token-purge.md).
     *
     * <ul>
     *   <li>일관 읽기로 대상 시리즈를 고른 뒤 시리즈마다 조건을 다시 확인하며 지운다 — 범위 DELETE의 잠금을 피하고, 조회 뒤 갱신된 행은 남긴다.</li>
     *   <li>로그인 응답 전에 동기로 돌므로 대기를 묶는다: 조회·삭제 문장마다 행·메타데이터 잠금 대기 1초와 실행 3초 상한을 건다.
     *       조회부터 잰 경과가 다음 삭제 전에
     *       {@link #PURGE_BUDGET}을 넘으면 멈춘다. 남은 행은 다음 로그인이 지운다.</li>
     *   <li>실패하면 그 자리에서 예외를 던진다. 앞서 지운 행은 자동 커밋이라 지워진 채 남는다(허용).</li>
     * </ul>
     *
     * @return 지운 행 수
     */
    public int purgeExpired(Instant now) {
        Instant cutoff = now.minusSeconds(AdminRememberMeServices.VALIDITY_SECONDS);
        long started = System.nanoTime();
        List<String> expired = findExpiredSeries(cutoff);
        int deleted = 0;
        for (String series : expired) {
            if (System.nanoTime() - started > PURGE_BUDGET.toNanos()) {
                break;
            }
            if (deleteExpiredSeries(series, cutoff)) {
                deleted++;
            }
        }
        return deleted;
    }

    /** {@code last_used < cutoff}인 시리즈(일관 읽기). 바인딩은 쓰기와 같은 변환(Instant → JVM 시간대 Timestamp)이다. */
    List<String> findExpiredSeries(Instant cutoff) {
        return template().queryForList(
                STATEMENT_LIMITS + "SELECT series FROM persistent_logins WHERE last_used < ?", String.class, Timestamp.from(cutoff));
    }

    /** 시리즈 하나를 아직 만료 상태일 때만 지운다. 지웠으면 {@code true}. */
    boolean deleteExpiredSeries(String series, Instant cutoff) {
        return template().update(STATEMENT_LIMITS + "DELETE FROM persistent_logins WHERE series = ? AND last_used < ?",
                series, Timestamp.from(cutoff)) > 0;
    }

    private org.springframework.jdbc.core.JdbcTemplate template() {
        org.springframework.jdbc.core.JdbcTemplate template = getJdbcTemplate();
        if (template == null) {
            throw new IllegalStateException("DataSource가 설정되지 않았습니다.");
        }
        return template;
    }
}
