package com.cms.admin.message.repository;

import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.message.domain.AdminMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AdminMessageRepository extends JpaRepository<AdminMessage, Long>, AdminMessageRepositoryCustom {

    /** 가드가 읽는 본인의 현재 상태·역할 스칼라 뷰 — 엔티티를 영속성 컨텍스트에 올리지 않는다. */
    interface ActorView {
        MemberStatus getStatus();

        Role getUserType();
    }

    /**
     * 본인의 <b>현재 DB 값</b>(상태·역할)을 스칼라로 읽는다 — {@code MessageActorGuard}가 새 트랜잭션·READ COMMITTED에서 호출한다.
     * 엔티티 로딩·캐시된 엔티티 기반 판정을 하지 않는다(PLAN-admin-message.md §5-J).
     */
    @Query("select m.status as status, m.userType as userType from Member m where m.id = :memberId")
    Optional<ActorView> findActorView(@Param("memberId") Long memberId);

    /**
     * 수신 가능한 수신자인가(D5) — 존재하고, 자기 자신이 아니며, 역할이 ADMIN·MANAGER이고, 상태가 ACTIVE·LOCKED·PASSWORD_EXPIRED다.
     * 모든 거부 사유(없음·ROLE_USER·자기 자신·DISABLED·DELETED)가 같은 {@code false}로 합쳐져 호출자가 한 문구로 거부한다 —
     * 상태별 문구를 나누면 계정 상태 탐지 통로가 된다. 발송 트랜잭션(READ COMMITTED)의 일관 읽기라 직전 커밋 값을 본다.
     */
    @Query("select count(m) > 0 from Member m where m.id = :recipientId and m.id <> :senderId "
            + "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER) "
            + "and m.status in (com.cms.admin.member.domain.MemberStatus.ACTIVE, com.cms.admin.member.domain.MemberStatus.LOCKED, "
            + "com.cms.admin.member.domain.MemberStatus.PASSWORD_EXPIRED)")
    boolean isEligibleRecipient(@Param("recipientId") Long recipientId, @Param("senderId") Long senderId);

    /**
     * 발신자별 잠금 행을 만들며 동시에 배타 잠금을 얻는다 — <b>발송 트랜잭션의 첫 DB 접근</b>이어야 한다(PLAN-admin-message.md §5-F).
     * 회원 행이 아니라 이 전용 행을 잠가 같은 발신자의 발송만 직렬화한다(회원 행 잠금은 INSERT의 FK 공유 잠금과 맞물려 A→B/B→A 동시 발송이 교착한다).
     * 쓰기 문장이라 일관 읽기 스냅샷을 열지 않는다. 반환값(행 수)에는 의존하지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO admin_message_sender_state (member_id) VALUES (:memberId) "
            + "ON DUPLICATE KEY UPDATE member_id = member_id", nativeQuery = true)
    int lockSenderState(@Param("memberId") Long memberId);

    /**
     * 읽음 — <b>수신자만</b>, 본인 쪽에서 지우지 않았고 아직 읽지 않았을 때만 갱신하는 원자적 UPDATE라 경합해도 최초 읽음 시각이 보존된다.
     * 발신자·타인의 요청은 0행이다. 조회 후 엔티티 수정 방식은 쓰지 않는다(경합 시 최초 읽음 시각이 덮어써진다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AdminMessage m set m.readAt = :now "
            + "where m.id = :id and m.recipientId = :memberId and m.recipientDeletedAt is null and m.readAt is null")
    int markRead(@Param("id") Long id, @Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    /** 읽음 UPDATE가 0행일 때 존재 확인 — 이미 읽은 수신자의 쪽지면 true(멱등 200), 없거나 남의 것·삭제된 것이면 false(404). */
    @Query("select count(m) > 0 from AdminMessage m where m.id = :id and m.recipientId = :memberId and m.recipientDeletedAt is null")
    boolean existsVisibleToRecipient(@Param("id") Long id, @Param("memberId") Long memberId);

    /**
     * 보낸 쪽 보관함에서 삭제 — 본인이 발신자이고 아직 지우지 않았을 때만. 삭제 알고리즘의 <b>첫 DB 접근</b>이다(PLAN-admin-message.md §5-D).
     * 네이티브 DML이라 영속성 컨텍스트·엔티티 플래그를 거치지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE admin_message SET sender_deleted_at = :now "
            + "WHERE id = :id AND sender_id = :memberId AND sender_deleted_at IS NULL", nativeQuery = true)
    int markDeletedBySender(@Param("id") Long id, @Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    /** 받은 쪽 보관함에서 삭제 — 본인이 수신자이고 아직 지우지 않았을 때만. */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE admin_message SET recipient_deleted_at = :now "
            + "WHERE id = :id AND recipient_id = :memberId AND recipient_deleted_at IS NULL", nativeQuery = true)
    int markDeletedByRecipient(@Param("id") Long id, @Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    /**
     * 양쪽 모두 지운 쪽지를 물리 삭제하는 <b>조건부 DELETE</b> — 삭제 UPDATE 성공 직후 같은 트랜잭션에서 직접 실행한다.
     * 물리 삭제 여부를 일반 SELECT·엔티티 플래그로 판단하지 않는다(낡은 값으로 DELETE를 생략할 수 있다). 두 쪽이 동시에 지우면 먼저 UPDATE한 쪽의
     * DELETE는 상대 열이 아직 NULL이라 0행이고, 대기하던 쪽의 DELETE가 현재 커밋된 양쪽 값을 읽어 1행을 지운다 — 둘 다 성공하면 영향 행 합계는 1이다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM admin_message WHERE id = :id AND sender_deleted_at IS NOT NULL AND recipient_deleted_at IS NOT NULL",
            nativeQuery = true)
    int deleteIfBothDeleted(@Param("id") Long id);

    /** 발신자의 {@code since} 이후 발송 이력 건수 — 쪽지 삭제와 무관한 한도의 권위(R1-3). 일관 읽기. */
    @Query(value = "SELECT COUNT(*) FROM admin_message_send_log WHERE sender_id = :senderId AND sent_at > :since",
            nativeQuery = true)
    long countSendLogSince(@Param("senderId") Long senderId, @Param("since") LocalDateTime since);

    /** 창 안에서 가장 오래된 발송 시각 — 429의 Retry-After 계산용. 없으면 null. */
    @Query(value = "SELECT MIN(sent_at) FROM admin_message_send_log WHERE sender_id = :senderId AND sent_at > :since",
            nativeQuery = true)
    LocalDateTime findOldestSendLogSince(@Param("senderId") Long senderId, @Param("since") LocalDateTime since);

    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO admin_message_send_log (sender_id, sent_at) VALUES (:senderId, :sentAt)", nativeQuery = true)
    int insertSendLog(@Param("senderId") Long senderId, @Param("sentAt") LocalDateTime sentAt);

    /** 만료된(24시간 지난) 본인 이력 id — 일관 읽기(잠금 없음). 범위 DELETE가 아니라 이 id를 PK로 지운다(갭 잠금 방지, R2-2). */
    @Query(value = "SELECT id FROM admin_message_send_log WHERE sender_id = :senderId AND sent_at <= :cutoff", nativeQuery = true)
    List<Long> findExpiredSendLogIds(@Param("senderId") Long senderId, @Param("cutoff") LocalDateTime cutoff);

    /** PK 동등 조건 삭제 — 범위가 아니라 존재하는 행의 레코드 잠금만 쓴다. */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM admin_message_send_log WHERE id IN (:ids)", nativeQuery = true)
    int deleteSendLogByIds(@Param("ids") List<Long> ids);
}
