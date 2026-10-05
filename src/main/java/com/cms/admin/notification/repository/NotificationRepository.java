package com.cms.admin.notification.repository;

import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long>, NotificationRepositoryCustom {

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

    /**
     * E3(비밀번호 만료 임박) 조건부 중복 방지 저장. 회원의 현재 {@code password_changed_at}이 재확인 스냅샷과 같을 때만 1행을 넣고,
     * 같은 {@code (member_id, dedupe_key)}가 이미 있으면 예외 없이 무시한다.
     * {@code ON DUPLICATE KEY UPDATE id = notification.id}는 {@code member.id}와의 모호성을 피하려고 대상 테이블로 한정한 것이다(v4 R-14).
     * 반환값은 호출자가 의존하지 않는다 — 중복(no-op 갱신)일 때의 값은 드라이버의 found-rows·affected-rows 설정에 따라 달라질 수 있어
     * 생성 여부는 행 수로 확인한다(시험이 최초 생성·중복 무시·스냅샷 불일치를 행 수로 고정한다).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO notification (member_id, type, message, link_url, dedupe_key, create_date) "
            + "SELECT m.id, :type, :message, :linkUrl, :dedupeKey, :now FROM member m "
            + "WHERE m.id = :memberId AND m.password_changed_at = :passwordChangedAt "
            + "ON DUPLICATE KEY UPDATE id = notification.id", nativeQuery = true)
    int insertPasswordExpiryIfCurrent(@Param("memberId") Long memberId, @Param("type") String type,
                                      @Param("message") String message, @Param("linkUrl") String linkUrl,
                                      @Param("dedupeKey") String dedupeKey, @Param("now") LocalDateTime now,
                                      @Param("passwordChangedAt") LocalDateTime passwordChangedAt);
}
