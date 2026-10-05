package com.cms.admin.notification;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 알림 문장(PLAN-admin-notification.md §5-B). 상태·역할·기능·동작은 enum → 고정 한국어 라벨로만 변환하고,
 * 사용자 입력은 E4의 잠긴 계정 아이디(DB의 정규 {@code userId}, 최대 50자) 하나뿐이다.
 */
public final class NotificationMessages {

    /** {@code message} 컬럼 상한. 어떤 조합이든 이 길이를 넘기지 않게 마지막에 자른다. */
    static final int MAX_LENGTH = 255;

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private NotificationMessages() {
    }

    /** E1 — 관리자가 내 계정 상태를 바꿨다. 활성 복구는 별도 문장이다. */
    public static String statusChangedByAdmin(MemberStatus newStatus) {
        if (newStatus == MemberStatus.ACTIVE) {
            return "관리자가 내 계정을 다시 활성화했습니다.";
        }
        String label = statusLabel(newStatus);
        return fit("관리자가 내 계정 상태를 '" + label + "'" + euro(label) + " 변경했습니다.");
    }

    /** E1 — 로그인 연속 실패로 내 계정이 자동 잠금됐다. 시각은 이벤트가 담은 잠금 시각이다. */
    public static String autoLockedSelf(LocalDateTime lockedAt) {
        return fit("로그인 연속 실패로 계정이 자동 잠금되었습니다(" + lockedAt.format(MINUTE) + ").");
    }

    /** E4 — 다른 관리자 계정이 자동 잠금됐다. {@code canonicalUserId}는 DB의 정규 아이디다(로그인 요청 원문이 아니다). */
    public static String adminAccountLocked(String canonicalUserId, LocalDateTime lockedAt) {
        return fit("관리자 계정 '" + canonicalUserId + "'이(가) 로그인 연속 실패로 자동 잠금되었습니다(" + lockedAt.format(MINUTE) + ").");
    }

    /** E2 — 역할이 바뀌었다. */
    public static String roleChanged(Role before, Role after) {
        String afterLabel = roleLabel(after);
        return fit("역할이 '" + roleLabel(before) + "'에서 '" + afterLabel + "'" + euro(afterLabel) + " 변경되었습니다.");
    }

    /** E2 — 개별 권한이 바뀌었다. 항목은 "공지사항 조회"처럼 기능·동작 라벨이다. */
    public static String permissionChanged(Collection<String> added, Collection<String> removed) {
        String addedText = added.stream().map(label -> "+" + label).collect(Collectors.joining(", "));
        String removedText = removed.stream().map(label -> "-" + label).collect(Collectors.joining(", "));
        String detail = java.util.stream.Stream.of(addedText, removedText).filter(s -> !s.isEmpty()).collect(Collectors.joining(", "));
        return fit("권한이 변경되었습니다: " + detail);
    }

    /** E3 — 비밀번호 만료 임박. "3일 후" 대신 절대 만료 시각을 넣어 나중에 읽어도 오해가 없게 한다. */
    public static String passwordExpiring(LocalDateTime expiresAt) {
        return fit("비밀번호가 " + expiresAt.format(MINUTE) + "에 만료됩니다. 내 설정에서 비밀번호를 변경해주세요.");
    }

    /** 한글 라벨 뒤 조사 "으로/로" — 받침이 없거나 ㄹ 받침이면 "로", 그 밖에는 "으로"다. 한글 음절이 아니면 "(으)로"로 둔다. */
    static String euro(String label) {
        if (label == null || label.isEmpty()) {
            return "(으)로";
        }
        char last = label.charAt(label.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "(으)로";
        }
        int jongseong = (last - 0xAC00) % 28;
        return (jongseong == 0 || jongseong == 8) ? "로" : "으로";
    }

    static String statusLabel(MemberStatus status) {
        return switch (status) {
            case ACTIVE -> "활성";
            case LOCKED -> "잠금";
            case DISABLED -> "비활성";
            case PASSWORD_EXPIRED -> "비밀번호 만료";
            case DELETED -> "삭제";
        };
    }

    static String roleLabel(Role role) {
        return switch (role) {
            case ROLE_ADMIN -> "관리자";
            case ROLE_MANAGER -> "매니저";
            case ROLE_USER -> "일반 회원";
        };
    }

    /** 컬럼 상한을 넘으면 말줄임으로 자른다 — 길이 때문에 저장 전체가 실패하지 않게 하는 마지막 방어선이다. */
    static String fit(String message) {
        return message.length() <= MAX_LENGTH ? message : message.substring(0, MAX_LENGTH - 1) + "…";
    }
}
