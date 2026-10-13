package com.cms.admin.board.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회수·다운로드 수의 원자적 증가(PLAN-view-download-count.md 쟁점 2·5). <b>트랜잭션 경계만 담당</b>하는 별도 빈이다 — 같은 클래스 안의
 * try/catch는 트랜잭션 시작·커밋 실패를 잡지 못하므로, 예외 격리는 이 빈을 부르는 {@link ContentCounterService}(비트랜잭션)가 한다.
 *
 * <p>일반 {@code @Modifying @Query}로는 문장 제한을 걸 수 없어 {@code SET STATEMENT}를 쓴다(관리자가 게시글 행을 {@code FOR UPDATE}로 잡고
 * 있으면 이 UPDATE가 기다리므로 공개 응답이 오래 붙잡히지 않게 한다 — {@code NotificationRecorder.recordInquiryReceived}와 같은 방식).
 * {@code innodb_lock_wait_timeout}은 락마다 새로 계산돼 합산 상한이 아니라서 두 설정을 함께 걸며, 엄밀한 벽시계 상한은 아니다.
 */
@Component
@RequiredArgsConstructor
public class ContentCounterWriter {

    private static final int STATEMENT_MAX_SECONDS = 3;
    private static final int LOCK_WAIT_SECONDS = 1;
    private static final String LIMITS = "SET STATEMENT max_statement_time=" + STATEMENT_MAX_SECONDS
            + ", innodb_lock_wait_timeout=" + LOCK_WAIT_SECONDS + " FOR ";

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void incrementView(Long postId) {
        jdbcTemplate.update(LIMITS + "UPDATE post SET view_count = view_count + 1 WHERE id = ?", postId);
    }

    @Transactional
    public void incrementDownload(Long attachmentId) {
        jdbcTemplate.update(LIMITS + "UPDATE post_attachment SET download_count = download_count + 1 WHERE id = ?", attachmentId);
    }
}
