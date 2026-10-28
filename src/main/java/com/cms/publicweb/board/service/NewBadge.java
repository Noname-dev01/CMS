package com.cms.publicweb.board.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 공개 목록의 "새 글" 배지 판정(PLAN-board-definition.md PR 1 P1-2 ④). 기준 시각은 {@code createDate}와 {@code publishStart} 중 NULL이 아닌
 * 값의 최댓값(공개 시각)이다 — 예약 게시 글이 작성일 기준으로는 공개되자마자 배지 기간이 지나 있는 일을 막는다. {@code days × 24시간} 이내면
 * 배지이고 경계(정확히 그 시각)는 포함한다. 기간 0은 날짜를 보지 않고 끔, 기준이 둘 다 NULL이면(스키마상 {@code create_date}가 NULL 허용)
 * 배지 없음 — 목록 전체가 NPE로 실패하지 않게 한다.
 */
final class NewBadge {

    private NewBadge() {
    }

    static boolean isNew(LocalDateTime createDate, LocalDateTime publishStart, int days, LocalDateTime now) {
        if (days <= 0) {
            return false;
        }
        LocalDateTime base = createDate;
        if (publishStart != null && (base == null || publishStart.isAfter(base))) {
            base = publishStart;
        }
        if (base == null) {
            return false;
        }
        return !base.isBefore(now.minus(Duration.ofHours(24L * days)));
    }
}
