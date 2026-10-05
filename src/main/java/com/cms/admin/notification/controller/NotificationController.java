package com.cms.admin.notification.controller;

import com.cms.admin.member.domain.Role;
import com.cms.admin.notification.dto.NotificationPageResponse;
import com.cms.admin.notification.dto.NotificationReadRequest;
import com.cms.admin.notification.dto.NotificationReadResponse;
import com.cms.admin.notification.dto.NotificationUnreadCountResponse;
import com.cms.admin.notification.service.NotificationService;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 본인 알림 API. 경로가 {@code /admin/api/members/me/**}라 기존 상시 허용(MY_INFO) 게이트 안에 있다 — 카탈로그·{@code SecurityConfig} 변경이 없다.
 * 회원 ID는 세션 principal에서만 읽고 경로·본문으로 받지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/members/me/notifications")
@Tag(name = "Admin Notification", description = "내 알림 API")
public class NotificationController {

    private final NotificationService notificationService;
    private final AdminSecurityService adminSecurityService;

    @Operation(summary = "내 알림 목록 조회",
            description = "최신순(id 내림차순) 커서 방식. 다음 페이지는 마지막 항목의 id를 beforeId로 보낸다. size 기본 10·상한 50. "
                    + "ADMIN 전용 알림(다른 계정 자동 잠금)은 요청 시점에 ADMIN인 사용자에게만 보인다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<NotificationPageResponse> list(
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(notificationService.list(requireCurrentAdminId(), isAdmin(), beforeId, size));
    }

    @Operation(summary = "내 미읽음 알림 수", description = "상단바 배지용.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/unread-count")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<NotificationUnreadCountResponse> unreadCount() {
        return ResponseEntity.ok(new NotificationUnreadCountResponse(
                notificationService.unreadCount(requireCurrentAdminId(), isAdmin())));
    }

    @Operation(summary = "알림 읽음 처리(단건)", description = "본문은 {\"read\": true}만 허용한다(읽음 취소 미지원). 이미 읽은 알림은 기존 읽음 시각을 유지한다(멱등).")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiResponse(responseCode = "400", description = "read가 true가 아님")
    @ApiResponse(responseCode = "404", description = "알림 없음·남의 알림·열람 권한 없는 알림(존재를 숨긴다)")
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<NotificationReadResponse> markRead(@PathVariable Long id,
                                                             @Valid @RequestBody NotificationReadRequest request) {
        requireReadTrue(request);
        return ResponseEntity.ok(notificationService.markRead(requireCurrentAdminId(), isAdmin(), id));
    }

    @Operation(summary = "알림 전체 읽음 처리", description = "본인에게 보이는 미읽음 알림을 모두 읽음 처리한다. 본문은 {\"read\": true}만 허용한다.")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiResponse(responseCode = "400", description = "read가 true가 아님")
    @PatchMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<NotificationReadResponse> markAllRead(@Valid @RequestBody NotificationReadRequest request) {
        requireReadTrue(request);
        return ResponseEntity.ok(notificationService.markAllRead(requireCurrentAdminId(), isAdmin()));
    }

    private static void requireReadTrue(NotificationReadRequest request) {
        if (!Boolean.TRUE.equals(request.getRead())) {
            throw new InvalidRequestException("읽음 취소는 지원하지 않습니다. read는 true만 허용됩니다.");
        }
    }

    private Long requireCurrentAdminId() {
        Long adminId = adminSecurityService.getCurrentAdminId();
        if (adminId == null) {
            throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
        }
        return adminId;
    }

    /** 요청 시점의 세션 권한으로 ADMIN 여부를 판정한다(D11 — 강등 직후 다음 요청부터 반영된다). */
    private static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(a -> Role.ROLE_ADMIN.name().equals(a.getAuthority()));
    }
}
