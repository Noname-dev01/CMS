package com.cms.admin.message.service;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.message.config.MessageProperties;
import com.cms.admin.message.domain.AdminMessage;
import com.cms.admin.message.domain.MessageBox;
import com.cms.admin.message.dto.request.MessageSendRequest;
import com.cms.admin.message.dto.response.MessageDetailResponse;
import com.cms.admin.message.dto.response.MessageListResponse;
import com.cms.admin.message.dto.response.MessageReadResponse;
import com.cms.admin.message.dto.response.MessageSendResponse;
import com.cms.admin.message.repository.AdminMessageRepository;
import com.cms.admin.message.repository.MessageRow;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.RateLimitedException;
import com.cms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 쪽지 서비스(PLAN-admin-message.md §5-D·§5-F·§5-G) — 보내기·목록·단건·읽음·미읽음 수·삭제.
 *
 * <p><b>보내기의 트랜잭션 계약</b>(R1-2·R2-3·R2-4): 진입점은 {@code REQUIRES_NEW + READ_COMMITTED}다. REQUIRES_NEW는 호출자에게 외부
 * 트랜잭션·일관 읽기 스냅샷이 있어도 항상 새 물리 트랜잭션으로 실행하고(REQUIRED는 참여하며 격리 수준도 무시한다), READ COMMITTED는
 * 일관 읽기가 문장마다 최신 커밋을 보게 해 낡은 스냅샷과 {@code innodb_snapshot_isolation=ON}의 오류 1020, 갭·넥스트키 잠금을 없앤다.
 * 감사 Aspect({@code LOWEST_PRECEDENCE - 1})가 이 트랜잭션 어드바이저보다 바깥이라 커밋까지 끝난 뒤 SUCCESS/FAIL을 기록한다.
 *
 * <p>컨트롤러가 서비스 호출 <b>전에</b> 인가 → 현재 자격 가드({@link MessageActorGuard}, 조회 종료) → 시도 버킷({@link MessageRateLimiter})을
 * 처리한다 — 가드 거부와 트랜잭션 전 시도 제한 429는 이 서비스의 감사에 남지 않는다(서비스에 진입하지 않음).
 */
@Service
@RequiredArgsConstructor
public class AdminMessageService {

    /** 고정 문구 — 상태별로 나누면 계정 상태 탐지 통로가 된다. 사용자 입력을 담지 않는다. */
    static final String INELIGIBLE_RECIPIENT_MESSAGE = "쪽지를 받을 수 없는 수신자입니다.";
    static final String SEND_LIMIT_MESSAGE = "쪽지 발송 한도를 초과했습니다. 잠시 후 다시 시도해주세요.";

    private static final Duration MINUTE_WINDOW = Duration.ofMinutes(1);
    private static final Duration DAY_WINDOW = Duration.ofHours(24);

    private final AdminMessageRepository adminMessageRepository;
    private final MessageProperties properties;
    private final Clock clock;

    /**
     * 쪽지를 보낸다. 순서(고정): 입력 검증(DB 없음) → ① 발신자 상태 행 잠금(첫 DB 접근) → ② 잠금 후 {@code now} → ③ 이력 기반 한도 →
     * ④ 수신자 검증 → ⑤ 쪽지·이력 저장과 본인 만료 이력 정리 → 커밋. 교착·잠금 충돌은 호출 전체가 롤백돼 쪽지가 저장되지 않으며 409다.
     *
     * <p>감사: 실패 {@code errorMessage}는 {@code safeErrorMessage} 고정 문구다 — 서비스 반환 이후 flush·커밋 예외의 메시지에는 SQL·값이 섞일 수 있어서다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    @AdminActionLogged(
            actionType = AdminActionTypes.MESSAGE_SEND,
            targetType = "MEMBER",
            targetIdExpression = "recipientId",
            safeErrorMessage = "쪽지 발송에 실패했습니다.")
    public MessageSendResponse send(Long senderId, MessageSendRequest request) {
        // DB를 쓰지 않는 검증이 먼저다 — 실패해도 잠금·스냅샷을 만들지 않는다. 실패 메시지는 고정 문구다.
        String title = MessageTextPolicy.normalizeTitle(request.getTitle());
        String body = MessageTextPolicy.normalizeBody(request.getBody());

        // ① 첫 DB 접근: 같은 발신자의 발송만 직렬화한다(회원 행 잠금 아님 — INSERT의 FK 공유 잠금과 맞물려 교착한다)
        adminMessageRepository.lockSenderState(senderId);

        // ② 잠금 후에 산출한다 — 잠금 대기 중 흐른 시간이 한도 창에 반영된다
        LocalDateTime now = LocalDateTime.now(clock);

        // ③ 삭제와 무관한 발송 이력으로 한도를 센다(양쪽이 쪽지를 지워도 24시간 남는다)
        enforceSendLimit(senderId, now, MINUTE_WINDOW, properties.getSendPerMinute());
        enforceSendLimit(senderId, now, DAY_WINDOW, properties.getSendPerDay());

        // ④ 수신자 검증 — 모든 거부 사유가 같은 문구다
        if (!adminMessageRepository.isEligibleRecipient(request.getRecipientId(), senderId)) {
            throw new InvalidRequestException(INELIGIBLE_RECIPIENT_MESSAGE);
        }

        // ⑤ 저장 — 쪽지와 이력은 같은 트랜잭션이라 함께 저장되거나 함께 없다
        AdminMessage saved = adminMessageRepository.save(AdminMessage.builder()
                .senderId(senderId)
                .recipientId(request.getRecipientId())
                .title(title)
                .body(body)
                .createDate(now)
                .build());
        adminMessageRepository.insertSendLog(senderId, now);
        purgeExpiredSendLog(senderId, now);

        return MessageSendResponse.from(saved);
    }

    // ===================== 조회·읽음·삭제 (PLAN-admin-message.md §5-D) =====================

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final String NOT_FOUND_MESSAGE = "쪽지를 찾을 수 없습니다.";

    /**
     * 쪽지함 한 페이지(id 내림차순 {@code beforeId} 커서). 본인 쪽에서 삭제한 쪽지는 제외하고 본문은 담지 않는다.
     * {@code box}는 {@code inbox}·{@code sent}만 허용한다. 회원 ID는 세션 principal에서 온 값이다 — 경로·본문으로 받지 않는다(IDOR 방지).
     */
    @Transactional(readOnly = true)
    public MessageListResponse list(Long memberId, String box, Long beforeId, Integer requestedSize) {
        MessageBox messageBox = MessageBox.parse(box);
        int size = clampSize(requestedSize);

        List<MessageRow> fetched = adminMessageRepository.findPage(memberId, messageBox, beforeId, size + 1);
        boolean hasMore = fetched.size() > size;
        List<MessageListResponse.Item> items = fetched.stream()
                .limit(size)
                .map(row -> new MessageListResponse.Item(row.id(),
                        new MessageListResponse.Counterpart(row.counterpartId(), row.counterpartUserId(), row.counterpartUserName()),
                        row.title(), row.readAt() != null, row.readAt(), row.createDate()))
                .toList();
        return new MessageListResponse(items, hasMore);
    }

    /** 단건(본문 포함). 소유 조건을 만족하지 않으면 404 — 존재를 숨긴다. 읽음을 일으키지 않는다(GET 부작용 없음). */
    @Transactional(readOnly = true)
    public MessageDetailResponse get(Long memberId, Long id) {
        MessageRow row = adminMessageRepository.findVisible(memberId, id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        MessageDetailResponse.Direction direction = row.senderId().equals(memberId)
                ? MessageDetailResponse.Direction.SENT
                : MessageDetailResponse.Direction.RECEIVED;
        return new MessageDetailResponse(row.id(), direction,
                new MessageListResponse.Counterpart(row.counterpartId(), row.counterpartUserId(), row.counterpartUserName()),
                row.title(), row.body(), row.readAt(), row.createDate());
    }

    /**
     * 읽음 — <b>수신자만</b>. 원자적 조건부 UPDATE(첫 DB 문장)가 1행이면 방금 읽음, 0행이면 존재를 확인해 이미 읽은 수신자의 쪽지면
     * 기존 {@code read_at}을 유지한 채 200(멱등), 없거나 남의 것·삭제됐거나 <b>발신자 본인의 요청</b>이면 404다. 응답에 서버가 센 미읽음 수를 담는다.
     */
    @Transactional
    public MessageReadResponse markRead(Long memberId, Long id) {
        int updated = adminMessageRepository.markRead(id, memberId, LocalDateTime.now(clock));
        if (updated == 0 && !adminMessageRepository.existsVisibleToRecipient(id, memberId)) {
            throw new ResourceNotFoundException(NOT_FOUND_MESSAGE);
        }
        return new MessageReadResponse(id, adminMessageRepository.countUnread(memberId));
    }

    /** 배지용 — 받은 쪽지 중 본인 쪽에서 지우지 않은 미읽음 수. */
    @Transactional(readOnly = true)
    public long unreadCount(Long memberId) {
        return adminMessageRepository.countUnread(memberId);
    }

    /**
     * <b>본인 쪽 보관함에서만 삭제</b>(D8) — 상대에게는 남고, 양쪽 모두 지우면 물리 삭제한다. 알고리즘과 SQL 종류는 고정이다(PLAN §5-D, R1-5):
     * ① 새 트랜잭션·READ COMMITTED의 <b>첫 DB 접근이 조건부 UPDATE</b>(일반 SELECT·엔티티 로딩 선행 금지 — 자격 가드는 서비스 밖에서 먼저 끝난다)
     * ② 발신측 UPDATE가 0행이면 수신측 UPDATE ③ 둘 다 0행이면 404(없음·남의 것·이미 지운 것) ④ 성공하면 <b>네이티브 조건부 DELETE를 직접 실행</b>한다
     * (물리 삭제 여부를 일반 SELECT·엔티티 플래그로 판단하지 않는다). 두 쪽이 동시에 지워도 DELETE 영향 행 합계는 1이다.
     * 삭제는 HTTP 의미상 멱등이다 — 이미 지운 쪽의 반복 요청이 404인 것은 존재 숨김 정책이다. 감사 로그는 남기지 않는다(개인 보관함 정리).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void delete(Long memberId, Long id) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = adminMessageRepository.markDeletedBySender(id, memberId, now);
        if (updated == 0) {
            updated = adminMessageRepository.markDeletedByRecipient(id, memberId, now);
        }
        if (updated == 0) {
            throw new ResourceNotFoundException(NOT_FOUND_MESSAGE);
        }
        adminMessageRepository.deleteIfBothDeleted(id);
    }

    private static int clampSize(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private void enforceSendLimit(Long senderId, LocalDateTime now, Duration window, int limit) {
        LocalDateTime since = now.minus(window);
        if (adminMessageRepository.countSendLogSince(senderId, since) < limit) {
            return;
        }
        LocalDateTime oldest = adminMessageRepository.findOldestSendLogSince(senderId, since);
        long retryAfterSeconds = oldest == null
                ? 1
                : Math.max(1, (Duration.between(now, oldest.plus(window)).toMillis() + 999) / 1000);
        throw new RateLimitedException(SEND_LIMIT_MESSAGE, retryAfterSeconds);
    }

    /**
     * 본인의 24시간 지난 이력을 정리한다(스케줄러 없음). <b>범위 DELETE가 아니라</b> 만료 id를 일관 읽기로 조회한 뒤 PK로 지운다 —
     * {@code (sender_id, sent_at)} 인덱스의 범위 DELETE는 갭·넥스트키 잠금으로 인접한 다른 발신자의 INSERT를 막을 수 있다(R2-2).
     */
    private void purgeExpiredSendLog(Long senderId, LocalDateTime now) {
        List<Long> expired = adminMessageRepository.findExpiredSendLogIds(senderId, now.minus(DAY_WINDOW));
        if (!expired.isEmpty()) {
            adminMessageRepository.deleteSendLogByIds(expired);
        }
    }
}
