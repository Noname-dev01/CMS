package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 동시 로그인 실패의 원자성(벌크 UPDATE)을 실제 MariaDB로 검증한다.
 *
 * <p>자식 스레드가 각자 독립 트랜잭션으로 실행돼야 하므로 {@code @Transactional}을 붙이지
 * 않는다 — @DataJpaTest의 테스트 관리 트랜잭션은 미커밋 준비 데이터를 자식 스레드가
 * 볼 수 없어 vacuous pass가 된다. 생성한 회원·감사 로그는 수동으로 대상 한정 삭제한다.
 *
 * <p>로컬 실행: DB(make dev-db) 기동 + DB_PASS/MAIL_USER/MAIL_PASS 환경변수 설정 필요.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class LoginFailureConcurrencyIntegrationTest {

    @Autowired
    LoginFailureService loginFailureService;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final List<Long> createdMemberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdMemberIds) {
            try {
                // AFTER_COMMIT 리스너가 REQUIRES_NEW로 영구 저장한 감사 로그도 대상 한정 삭제
                jdbcTemplate.update(
                        "DELETE FROM admin_action_log WHERE action_type = 'ACCOUNT_AUTO_LOCK' AND target_id = ?", id);
            } catch (Exception ignored) {
            }
            try {
                memberRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
    }

    private Member createActiveAdmin() {
        String unique = "lockout-cc-" + System.nanoTime();
        Member saved = memberRepository.save(Member.builder()
                .userId(unique.substring(0, Math.min(50, unique.length())))
                .pwd("encoded")
                .userName("동시잠금테스트")
                .email(unique + "@lockout-cc.test")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .passwordChangedAt(LocalDateTime.now())
                .build());
        createdMemberIds.add(saved.getId());
        return saved;
    }

    @Test
    @DisplayName("8개 스레드 동시 실패 — 카운트는 min(N,5)=5에서 멈추고 잠금 전이·감사는 정확히 1회다")
    void concurrentFailures_countCapsAtThreshold_lockExactlyOnce() throws Exception {
        Member member = createActiveAdmin();
        int threads = 8;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        loginFailureService.recordFailure(member.getUserId(), "127.0.0.1", "/admin/login");
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS), "동시 실패 작업이 제한 시간 내에 끝나야 한다");
        } finally {
            executor.shutdownNow();
        }

        assertThat(failures).as("스레드 예외 없음").isEmpty();

        Member found = memberRepository.findById(member.getId()).orElseThrow();
        // 잠금 커밋 후 증가는 ACTIVE 조건에 걸려 0행 — 커밋 전 경합으로 5를 넘을 수는 있으나
        // 직렬화된 행 잠금 특성상 통상 5에서 멈춘다. 계약은 "임계값 이상 + LOCKED 정확히 1회".
        assertThat(found.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(found.getFailedLoginCount()).isEqualTo(5);
        assertThat(found.getLockedAt()).isNotNull();

        Long auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_action_log WHERE action_type = 'ACCOUNT_AUTO_LOCK' AND target_id = ?",
                Long.class, member.getId());
        assertThat(auditCount).as("잠금 전이(=감사 기록)는 정확히 1회").isEqualTo(1);
    }
}
