package com.cms.admin.notification.repository;

import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long>, NotificationRepositoryCustom {

    /** 후보 회원 중 이미 미읽음 알림(해당 종류)을 가진 회원 ID — 새 문의 알림을 병합(중복 생성 안 함)하기 위한 잠금 없는 SELECT. */
    @Query("select distinct n.memberId from Notification n where n.type = :type and n.readAt is null and n.memberId in :memberIds")
    List<Long> findMemberIdsWithUnread(@Param("type") NotificationType type, @Param("memberIds") Collection<Long> memberIds);

    /** 소유 확인용 단건 조회 — 남의 알림이면 비어 있다(호출자가 404로 숨긴다). */
    Optional<Notification> findByIdAndMemberId(Long id, Long memberId);

    /**
     * 단건 읽음 — {@code read_at IS NULL}일 때만 갱신하는 원자적 UPDATE라 경합해도 최초 읽음 시각이 보존된다.
     * {@code adminOnlyType}은 비ADMIN 요청에서 ADMIN 전용 종류를 제외하기 위한 값이다(D11, {@code includeAdminOnly=false}일 때 적용).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :now "
            + "where n.id = :id and n.memberId = :memberId and n.readAt is null "
            + "and (:includeAdminOnly = true or n.type <> :adminOnlyType)")
    int markRead(@Param("id") Long id, @Param("memberId") Long memberId, @Param("now") LocalDateTime now,
                 @Param("includeAdminOnly") boolean includeAdminOnly,
                 @Param("adminOnlyType") NotificationType adminOnlyType);

    /** 전체 읽음 — 본인 미읽음 중 보이는 행만(비ADMIN의 숨겨진 ADMIN 전용 알림은 미읽음으로 남는다). 갱신 건수를 돌려준다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :now "
            + "where n.memberId = :memberId and n.readAt is null "
            + "and (:includeAdminOnly = true or n.type <> :adminOnlyType)")
    int markAllRead(@Param("memberId") Long memberId, @Param("now") LocalDateTime now,
                    @Param("includeAdminOnly") boolean includeAdminOnly,
                    @Param("adminOnlyType") NotificationType adminOnlyType);

    /** 읽은 지 오래된 본인 알림 삭제(D7·D8) — 미읽음({@code read_at IS NULL})은 지우지 않는다. 삭제 건수를 돌려준다. */
    @Modifying
    @Query("delete from Notification n where n.memberId = :memberId and n.readAt is not null and n.readAt < :cutoff")
    int deleteReadBefore(@Param("memberId") Long memberId, @Param("cutoff") LocalDateTime cutoff);
}
