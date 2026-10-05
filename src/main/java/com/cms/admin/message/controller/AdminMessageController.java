package com.cms.admin.message.controller;

import com.cms.admin.message.dto.request.MessageReadRequest;
import com.cms.admin.message.dto.request.MessageSendRequest;
import com.cms.admin.message.dto.response.MessageDetailResponse;
import com.cms.admin.message.dto.response.MessageListResponse;
import com.cms.admin.message.dto.response.MessageReadResponse;
import com.cms.admin.message.dto.response.MessageUnreadCountResponse;
import com.cms.admin.message.dto.response.MessageRecipientResponse;
import com.cms.admin.message.dto.response.MessageSendResponse;
import com.cms.admin.message.service.AdminMessageService;
import com.cms.admin.message.service.MessageActorGuard;
import com.cms.admin.message.service.MessageRateLimiter;
import com.cms.admin.message.service.MessageRecipientService;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.auth.AdminSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * 내 쪽지 API. 경로가 {@code /admin/api/members/me/**}라 기존 상시 허용(MY_INFO) 게이트 안에 있다 — API 쪽 인가 변경이 없다.
 * 회원 ID는 세션 principal에서만 읽고 경로·본문으로 받지 않는다(IDOR 방지).
 *
 * <p><b>호출 순서(고정, PLAN-admin-message.md §5-G)</b>: 컨트롤러 인가({@code @PreAuthorize}) → 현재 자격 가드({@link MessageActorGuard}, 조회 종료) →
 * 시도 버킷({@link MessageRateLimiter}) → 감사 Aspect → 서비스 트랜잭션. 이 컨트롤러는 트랜잭션이 아니다(가드·서비스가 각자 새 트랜잭션).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/members/me")
@Tag(name = "Admin Message", description = "내 쪽지 API")
public class AdminMessageController {

    private final AdminMessageService adminMessageService;
    private final MessageRecipientService messageRecipientService;
    private final MessageActorGuard actorGuard;
    private final MessageRateLimiter rateLimiter;
    private final AdminSecurityService adminSecurityService;

    @Operation(summary = "쪽지 보내기",
            description = "1:1 쪽지. 수신자는 수신자 검색 결과의 회원 ID. 제목은 한 줄 1~100자, 본문은 1~2000자 평문. "
                    + "한도: 발송 이력 기준 최근 1분 10건·24시간 300건(429), 시도(수신자 확인 포함) 버스트 30 + 평균 분당 30(429).")
    @ApiResponse(responseCode = "201", description = "발송 성공")
    @ApiResponse(responseCode = "400", description = "검증 실패·수신할 수 없는 수신자(사유는 통합 문구)")
    @ApiResponse(responseCode = "403", description = "현재 쪽지를 사용할 수 없는 계정(비ACTIVE·USER 역할)")
    @ApiResponse(responseCode = "409", description = "동시 변경과 충돌(교착) — 쪽지는 저장되지 않았으니 다시 시도")
    @ApiResponse(responseCode = "429", description = "빈도 제한 초과(Retry-After 초)")
    @PostMapping("/messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<MessageSendResponse> send(@Valid @RequestBody MessageSendRequest request) {
        Long senderId = requireCurrentAdminId();
        actorGuard.requireActive(senderId);
        rateLimiter.consumeAttempt(senderId);

        MessageSendResponse result = adminMessageService.send(senderId, request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(result.getId()).toUri();
        return ResponseEntity.created(location).body(result);
    }

    @Operation(summary = "내 쪽지함 목록",
            description = "box=inbox(받은)|sent(보낸), id 내림차순 beforeId 커서(다음 페이지는 마지막 항목의 id). size 기본 20·상한 50. "
                    + "본인 쪽에서 삭제한 쪽지는 제외하고 본문은 담지 않는다. 보낸 쪽지함의 read·readAt이 읽음 확인이다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "box가 inbox·sent가 아님")
    @ApiResponse(responseCode = "403", description = "현재 쪽지를 사용할 수 없는 계정")
    @GetMapping("/messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<MessageListResponse> list(@RequestParam(required = false) String box,
                                                    @RequestParam(required = false) Long beforeId,
                                                    @RequestParam(required = false) Integer size) {
        Long memberId = requireCurrentAdminId();
        actorGuard.requireActive(memberId);

        return ResponseEntity.ok(adminMessageService.list(memberId, box, beforeId, size));
    }

    @Operation(summary = "내 미읽음 쪽지 수", description = "상단바 배지용. 받은 쪽지 중 본인 쪽에서 지우지 않은 미읽음 수.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "403", description = "현재 쪽지를 사용할 수 없는 계정")
    @GetMapping("/messages/unread-count")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<MessageUnreadCountResponse> unreadCount() {
        Long memberId = requireCurrentAdminId();
        actorGuard.requireActive(memberId);

        return ResponseEntity.ok(new MessageUnreadCountResponse(adminMessageService.unreadCount(memberId)));
    }

    @Operation(summary = "쪽지 단건 조회", description = "제목·본문 전체. 읽음을 일으키지 않는다(화면이 별도로 PATCH). "
            + "보낸 사람 또는 받는 사람이고 본인 쪽에서 지우지 않았을 때만 — 그 밖에는 404(존재를 숨긴다).")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "없음·남의 쪽지·본인 쪽에서 이미 삭제")
    @GetMapping("/messages/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<MessageDetailResponse> get(@PathVariable Long id) {
        Long memberId = requireCurrentAdminId();
        actorGuard.requireActive(memberId);

        return ResponseEntity.ok(adminMessageService.get(memberId, id));
    }

    @Operation(summary = "쪽지 읽음 처리", description = "본문은 {\"read\": true}만 허용한다(읽음 취소 미지원). 수신자만 가능하다. "
            + "이미 읽은 쪽지는 기존 읽음 시각을 유지한 채 200(멱등)이고 응답에 서버가 센 미읽음 수를 담는다.")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiResponse(responseCode = "400", description = "read가 true가 아님")
    @ApiResponse(responseCode = "404", description = "없음·남의 쪽지·삭제됨·발신자 본인의 요청(존재를 숨긴다)")
    @PatchMapping("/messages/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<MessageReadResponse> markRead(@PathVariable Long id, @Valid @RequestBody MessageReadRequest request) {
        if (!Boolean.TRUE.equals(request.getRead())) {
            throw new InvalidRequestException("읽음 취소는 지원하지 않습니다. read는 true만 허용됩니다.");
        }
        Long memberId = requireCurrentAdminId();
        actorGuard.requireActive(memberId);

        return ResponseEntity.ok(adminMessageService.markRead(memberId, id));
    }

    @Operation(summary = "쪽지 삭제(내 보관함에서만)",
            description = "본인 쪽 보관함에서만 지운다 — 상대에게는 남고, 양쪽 모두 지우면 물리 삭제한다. 이미 지운 쪽지는 404(존재 숨김). 감사 로그는 남기지 않는다.")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "없음·남의 쪽지·이미 삭제")
    @ApiResponse(responseCode = "409", description = "동시 변경과 충돌 — 다시 시도")
    @DeleteMapping("/messages/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long memberId = requireCurrentAdminId();
        actorGuard.requireActive(memberId);

        adminMessageService.delete(memberId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "쪽지 수신자 검색",
            description = "아이디·이름 부분 일치(trim 후 2~50자)와 원문 아이디 정확 일치(1자 입력은 정확 일치만). "
                    + "수신 가능한 ADMIN·MANAGER(자기 제외)만, id·userId·userName만 노출. 최대 10건, 더 있으면 truncated=true. "
                    + "검색은 버스트 30 + 평균 분당 30으로 제한된다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "403", description = "현재 쪽지를 사용할 수 없는 계정")
    @ApiResponse(responseCode = "429", description = "검색 빈도 제한 초과(Retry-After 초)")
    @GetMapping("/message-recipients")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<MessageRecipientResponse> searchRecipients(@RequestParam(required = false) String keyword) {
        Long senderId = requireCurrentAdminId();
        actorGuard.requireActive(senderId);
        rateLimiter.consumeSearch(senderId);

        return ResponseEntity.ok(messageRecipientService.search(senderId, keyword));
    }

    private Long requireCurrentAdminId() {
        Long adminId = adminSecurityService.getCurrentAdminId();
        if (adminId == null) {
            throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
        }
        return adminId;
    }
}
