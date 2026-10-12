package com.cms.admin.inquiry;

import com.cms.admin.inquiry.service.InquiryNotifier;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 새 문의 알림의 수신자 규칙과 대기 상한(PLAN-inquiry.md 쟁점 9, R1-4·R2-2·R3-1·R5-1·R6-1). {@link InquiryNotifier}는 트랜잭션 밖에서 호출되는
 * 진입점이다 — 실제 호출 모양 그대로 시험한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class InquiryNotificationIntegrationTest extends MariaDbContainerSupport {

    @Autowired InquiryNotifier notifier;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;

    private final List<Long> memberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notification WHERE type = 'INQUIRY_RECEIVED'");
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, memberIds);
        jdbc.update("DELETE FROM notification WHERE type = 'INQUIRY_RECEIVED'");
        cache.invalidate();
    }

    private Member member(String prefix, Role role) {
        Member member = TestMembers.save(memberRepository, prefix, role);
        memberIds.add(member.getId());
        return member;
    }

    private void grant(Member member, String feature, String action) {
        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, ?, ?)", member.getId(), feature, action);
        cache.invalidate();
    }

    private int notifications(Member member) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'INQUIRY_RECEIVED'", Integer.class, member.getId());
    }

    @Test
    @DisplayName("수신자: ACTIVE ADMIN과 INQUIRY/READ 행이 있는 ACTIVE MANAGER만 — 권한 없음·다른 기능·UPDATE만·비ACTIVE·변형 행은 제외")
    void recipients() {
        Member admin = member("pn-admin", Role.ROLE_ADMIN);
        Member lockedAdmin = member("pn-locked", Role.ROLE_ADMIN);
        jdbc.update("UPDATE member SET status = ? WHERE id = ?", MemberStatus.LOCKED.name(), lockedAdmin.getId());
        Member granted = member("pn-granted", Role.ROLE_MANAGER);
        grant(granted, "INQUIRY", "READ");
        Member noGrant = member("pn-nogrant", Role.ROLE_MANAGER);
        Member otherFeature = member("pn-other", Role.ROLE_MANAGER);
        grant(otherFeature, "FAQ", "READ");
        Member updateOnly = member("pn-update", Role.ROLE_MANAGER);
        grant(updateOnly, "INQUIRY", "UPDATE");
        Member lockedManager = member("pn-lockedmgr", Role.ROLE_MANAGER);
        grant(lockedManager, "INQUIRY", "READ");
        jdbc.update("UPDATE member SET status = ? WHERE id = ?", MemberStatus.LOCKED.name(), lockedManager.getId());
        // 기본 콜레이션(general_ci, PAD SPACE)이면 일치해 버리는 변형 행 — 판정기는 이 행을 무시하므로 알림도 가면 안 된다 (R1-4·R2-2)
        List<Member> variants = new ArrayList<>();
        for (String[] variant : new String[][]{{"inquiry", "READ"}, {"INQUIRY", "read"}, {"INQUIRY ", "READ"}, {"INQUIRY", "READ "}}) {
            Member m = member("pn-var", Role.ROLE_MANAGER);
            grant(m, variant[0], variant[1]);
            variants.add(m);
        }

        notifier.notifyReceived();

        assertThat(notifications(admin)).isEqualTo(1);
        assertThat(notifications(granted)).isEqualTo(1);
        assertThat(notifications(lockedAdmin)).as("LOCKED ADMIN").isZero();
        assertThat(notifications(noGrant)).isZero();
        assertThat(notifications(otherFeature)).isZero();
        assertThat(notifications(updateOnly)).as("READ 행 없는 UPDATE").isZero();
        assertThat(notifications(lockedManager)).as("LOCKED MANAGER").isZero();
        for (Member variant : variants) {
            assertThat(notifications(variant)).as("변형 행 회원").isZero();
        }
    }

    @Test
    @DisplayName("대기 상한 (R3-1·R5-1·R6-1): 수신자 회원 행을 서로 다른 트랜잭션이 순차 해제(각 대기 약 0.8초 < 락 대기 1초, 누적 4초 > 문장 상한 3초)해도 문장 전체 상한에서 끊기고 알림은 하나도 남지 않으며 예외는 삼켜진다")
    void lockWaits_areBoundedByStatementTimeout() throws Exception {
        List<Member> admins = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            admins.add(member("pn-lock" + i, Role.ROLE_ADMIN));
        }
        CountDownLatch locksHeld = new CountDownLatch(admins.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> holders = new ArrayList<>();
        for (int i = 0; i < admins.size(); i++) {
            long memberId = admins.get(i).getId();
            long holdMillis = 800L * (i + 1);
            Thread holder = new Thread(() -> {
                try (Connection connection = DriverManager.getConnection(MARIA_DB.getJdbcUrl(), MARIA_DB.getUsername(), MARIA_DB.getPassword())) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM member WHERE id = ? FOR UPDATE")) {
                        lock.setLong(1, memberId);
                        lock.executeQuery();
                    }
                    locksHeld.countDown();
                    start.await();
                    Thread.sleep(holdMillis);       // 호출 시작 기준으로 순차 해제
                    connection.commit();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            holder.start();
            holders.add(holder);
        }
        assertThat(locksHeld.await(20, TimeUnit.SECONDS)).isTrue();

        start.countDown();
        long begin = System.nanoTime();
        notifier.notifyReceived();                  // 예외가 새면 시험이 실패한다
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        for (Thread holder : holders) {
            holder.join(10_000);
        }

        assertThat(elapsedMillis).as("문장 전체 상한(약 3초)에서 끊겨야 하고 개별 대기만 제한했다면 약 4초가 걸린다").isLessThan(3_700L);
        for (Member admin : admins) {
            assertThat(notifications(admin)).as("끊긴 문장은 일부 행도 남기지 않는다").isZero();
        }
        // 락이 모두 풀린 뒤에는 정상 생성된다
        notifier.notifyReceived();
        for (Member admin : admins) {
            assertThat(notifications(admin)).isEqualTo(1);
        }
    }
}
