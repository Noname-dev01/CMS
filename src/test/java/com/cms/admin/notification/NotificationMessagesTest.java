package com.cms.admin.notification;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 알림 문장(PLAN-admin-notification.md §5-B) — 사용자 입력은 E4의 정규 아이디뿐이고 어떤 조합도 255자를 넘지 않는다. */
class NotificationMessagesTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 5, 14, 3, 27);

    @Test
    @DisplayName("상태 변경 문장은 받침에 맞는 조사를 쓰고 활성 복구는 별도 문장이다")
    void statusChangedByAdmin() {
        assertThat(NotificationMessages.statusChangedByAdmin(MemberStatus.LOCKED))
                .isEqualTo("관리자가 내 계정 상태를 '잠금'으로 변경했습니다.");
        assertThat(NotificationMessages.statusChangedByAdmin(MemberStatus.DISABLED))
                .isEqualTo("관리자가 내 계정 상태를 '비활성'으로 변경했습니다.");
        assertThat(NotificationMessages.statusChangedByAdmin(MemberStatus.ACTIVE))
                .isEqualTo("관리자가 내 계정을 다시 활성화했습니다.");
    }

    @Test
    @DisplayName("역할 변경 문장은 이전·이후 역할 라벨을 담고 받침 없는 라벨 뒤에는 '로'를 쓴다")
    void roleChanged() {
        assertThat(NotificationMessages.roleChanged(Role.ROLE_MANAGER, Role.ROLE_ADMIN))
                .isEqualTo("역할이 '매니저'에서 '관리자'로 변경되었습니다.");
    }

    @Test
    @DisplayName("조사 판정: 받침 없음·ㄹ 받침은 '로', 그 밖의 받침은 '으로', 한글이 아니면 '(으)로'")
    void euro() {
        assertThat(NotificationMessages.euro("관리자")).isEqualTo("로");
        assertThat(NotificationMessages.euro("비밀번호 만료")).isEqualTo("로");
        assertThat(NotificationMessages.euro("잠금")).isEqualTo("으로");
        assertThat(NotificationMessages.euro("활성")).isEqualTo("으로");
        assertThat(NotificationMessages.euro("서울")).isEqualTo("로");       // ㄹ 받침
        assertThat(NotificationMessages.euro("ADMIN")).isEqualTo("(으)로");
        assertThat(NotificationMessages.euro("")).isEqualTo("(으)로");
    }

    @Test
    @DisplayName("개별 권한 변경 문장은 건수 → 회수(-) → 추가(+) 순서, 한쪽만 있으면 그쪽만 담는다")
    void permissionChanged() {
        assertThat(NotificationMessages.permissionChanged(List.of("공지사항 조회"), List.of("공지사항 삭제")))
                .isEqualTo("권한이 변경되었습니다(추가 1·회수 1): -공지사항 삭제, +공지사항 조회");
        assertThat(NotificationMessages.permissionChanged(List.of("공지사항 조회", "공지사항 생성"), List.of()))
                .isEqualTo("권한이 변경되었습니다(추가 2·회수 0): +공지사항 조회, +공지사항 생성");
        assertThat(NotificationMessages.permissionChanged(List.of(), List.of("공지사항 조회")))
                .isEqualTo("권한이 변경되었습니다(추가 0·회수 1): -공지사항 조회");
    }

    @Test
    @DisplayName("항목이 많아 255자를 넘으면 잘리지만 건수와 회수 항목이 먼저 남는다(PLAN-board.md 리뷰 R1-7)")
    void permissionChangedKeepsCountsAndRemovalsWhenTruncated() {
        List<String> added = java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i -> "게시판 #" + i + " 조회").toList();
        String message = NotificationMessages.permissionChanged(added, List.of("공지사항 삭제"));

        assertThat(message).hasSize(NotificationMessages.MAX_LENGTH).endsWith("…")
                .startsWith("권한이 변경되었습니다(추가 40·회수 1): -공지사항 삭제, +게시판 #1 조회");
    }

    @Test
    @DisplayName("E3는 '3일 후'가 아니라 절대 만료 시각을 담는다")
    void passwordExpiring_hasAbsoluteDate() {
        assertThat(NotificationMessages.passwordExpiring(LocalDateTime.of(2026, 10, 8, 16, 0)))
                .isEqualTo("비밀번호가 2026-10-08 16:00에 만료됩니다. 내 설정에서 비밀번호를 변경해주세요.");
    }

    @Test
    @DisplayName("자동 잠금 문장은 이벤트가 담은 잠금 시각(분 단위)을 쓴다")
    void autoLock_usesLockedAt() {
        assertThat(NotificationMessages.autoLockedSelf(AT)).contains("2026-10-05 14:03").doesNotContain(":27");
        assertThat(NotificationMessages.adminAccountLocked("mgr01", AT))
                .isEqualTo("관리자 계정 'mgr01'이(가) 로그인 연속 실패로 자동 잠금되었습니다(2026-10-05 14:03).");
    }

    @Test
    @DisplayName("userId 상한(50자)이 가득 차도 메시지는 255자 안이고, 비정상적으로 긴 입력은 말줄임으로 잘려 저장 실패를 막는다")
    void lengthIsAlwaysWithinColumnLimit() {
        String longestUserId = "a".repeat(50);
        assertThat(NotificationMessages.adminAccountLocked(longestUserId, AT).length()).isLessThanOrEqualTo(255);

        String huge = NotificationMessages.adminAccountLocked("a".repeat(1000), AT);
        assertThat(huge.length()).isEqualTo(255);
        assertThat(huge).endsWith("…");
    }
}
