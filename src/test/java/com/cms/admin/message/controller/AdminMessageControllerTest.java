package com.cms.admin.message.controller;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.message.dto.request.MessageSendRequest;
import com.cms.admin.message.dto.response.MessageDetailResponse;
import com.cms.admin.message.dto.response.MessageListResponse;
import com.cms.admin.message.dto.response.MessageRecipientResponse;
import com.cms.admin.message.dto.response.MessageSendResponse;
import com.cms.admin.message.domain.AdminMessage;
import com.cms.admin.message.service.AdminMessageService;
import com.cms.admin.message.service.MessageActorGuard;
import com.cms.admin.message.service.MessageRateLimiter;
import com.cms.admin.message.service.MessageRecipientService;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.RateLimitedException;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.auth.AdminSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 쪽지 보내기·수신자 검색 API의 인가·검증·호출 순서·오류 형식(PLAN-admin-message.md §5-D·§5-G·§7 ④). */
@WebMvcTest(controllers = AdminMessageController.class)
@Import({
        AdminMessageControllerTest.MockConfig.class,
        MethodSecurityTestConfig.class,
        GlobalApiExceptionHandler.class
})
class AdminMessageControllerTest {

    private static final long ME = 7L;
    private static final String SEND = "/admin/api/members/me/messages";
    private static final String SEARCH = "/admin/api/members/me/message-recipients";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminMessageService adminMessageService;
    @Autowired MessageRecipientService messageRecipientService;
    @Autowired MessageActorGuard actorGuard;
    @Autowired MessageRateLimiter rateLimiter;
    @Autowired AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        reset(adminMessageService, messageRecipientService, actorGuard, rateLimiter, adminSecurityService);
        given(adminSecurityService.getCurrentAdminId()).willReturn(ME);
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean AdminMessageService adminMessageService() { return Mockito.mock(AdminMessageService.class); }
        @Bean MessageRecipientService messageRecipientService() { return Mockito.mock(MessageRecipientService.class); }
        @Bean MessageActorGuard messageActorGuard() { return Mockito.mock(MessageActorGuard.class); }
        @Bean MessageRateLimiter messageRateLimiter() { return Mockito.mock(MessageRateLimiter.class); }
        @Bean AdminSecurityService adminSecurityService() { return Mockito.mock(AdminSecurityService.class); }
        @Bean MenuService menuService() { return Mockito.mock(MenuService.class); }
    }

    private static MessageSendResponse sent(long id) {
        return MessageSendResponse.from(AdminMessage.builder().id(id).senderId(ME).recipientId(12L)
                .title("제목").body("본문").createDate(LocalDateTime.of(2026, 10, 5, 12, 0)).build());
    }

    private String json(long recipientId, String title, String body) throws Exception {
        return objectMapper.writeValueAsString(MessageSendRequest.builder().recipientId(recipientId).title(title).body(body).build());
    }

    // ===================== 보내기 =====================

    @Test
    @DisplayName("ADMIN은 보내기에 201 + Location + 본문 없는 결과를 받고, 호출 순서는 가드 → 시도 버킷 → 서비스다")
    @WithMockUser(roles = "ADMIN")
    void admin_send_created_inGuardLimiterServiceOrder() throws Exception {
        given(adminMessageService.send(eq(ME), any())).willReturn(sent(55));

        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(12, "제목", "본문")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith(SEND + "/55")))
                .andExpect(jsonPath("$.id").value(55))
                .andExpect(jsonPath("$.recipientId").value(12))
                .andExpect(jsonPath("$.title").value("제목"))
                .andExpect(jsonPath("$.body").doesNotExist());

        InOrder order = inOrder(actorGuard, rateLimiter, adminMessageService);
        order.verify(actorGuard).requireActive(ME);
        order.verify(rateLimiter).consumeAttempt(ME);
        order.verify(adminMessageService).send(eq(ME), any(MessageSendRequest.class));
    }

    @Test
    @DisplayName("MANAGER도 보낼 수 있다(상시 허용 경로 — 쪽지는 ADMIN·MANAGER 모두 사용한다)")
    @WithMockUser(roles = "MANAGER")
    void manager_send_created() throws Exception {
        given(adminMessageService.send(eq(ME), any())).willReturn(sent(56));

        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(12, "제목", "본문")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("ROLE_USER는 403이고 가드·버킷·서비스를 호출하지 않는다")
    @WithMockUser(roles = "USER")
    void user_send_forbidden() throws Exception {
        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(12, "제목", "본문")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(actorGuard, rateLimiter, adminMessageService);
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 403이다(POST)")
    @WithMockUser(roles = "ADMIN")
    void send_withoutCsrf_forbidden() throws Exception {
        mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON).content(json(12, "제목", "본문")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(actorGuard, rateLimiter, adminMessageService);
    }

    @Test
    @DisplayName("비로그인은 접근할 수 없다(401 또는 로그인 리다이렉트) — 서비스를 호출하지 않는다")
    void unauthenticated_send_isRejected() throws Exception {
        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(12, "제목", "본문")))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 401 && status != 302) {
                        throw new AssertionError("비로그인 응답은 401 또는 302여야 한다: " + status);
                    }
                });

        verifyNoInteractions(actorGuard, rateLimiter, adminMessageService);
    }

    @Test
    @DisplayName("수신자 ID·제목·본문 누락은 400 VALIDATION_ERROR이고 아무 것도 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void send_missingFields_validationError() throws Exception {
        for (String body : new String[]{
                "{\"title\":\"제목\",\"body\":\"본문\"}",
                "{\"recipientId\":12,\"body\":\"본문\"}",
                "{\"recipientId\":12,\"title\":\"제목\"}"}) {
            mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        verifyNoInteractions(actorGuard, rateLimiter, adminMessageService);
    }

    @Test
    @DisplayName("원문 크기 상한(제목 200·본문 4000 UTF-16 단위)을 넘으면 서비스 진입 전에 400이다")
    @WithMockUser(roles = "ADMIN")
    void send_rawSizeCapExceeded_validationError() throws Exception {
        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(12, "가".repeat(201), "본문")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(12, "제목", "가".repeat(4001))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminMessageService);
    }

    @Test
    @DisplayName("가드가 거부하면 403(고정 문구)이고 시도 버킷·서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void guardDenied_forbidden_noLimiterNoService() throws Exception {
        doThrow(new AccessDeniedException("쪽지를 사용할 수 없는 계정입니다.")).when(actorGuard).requireActive(ME);

        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(12, "제목", "본문")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(rateLimiter, adminMessageService);
    }

    @Test
    @DisplayName("시도 버킷이 거부하면 429 RATE_LIMITED + Retry-After이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void attemptLimited_429_noService() throws Exception {
        doThrow(new RateLimitedException("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", 9)).when(rateLimiter).consumeAttempt(ME);

        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(12, "제목", "본문")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "9"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        verifyNoInteractions(adminMessageService);
    }

    @Test
    @DisplayName("서비스의 발송 한도 429·수신자 거부 400·교착 409가 각 형식으로 응답된다")
    @WithMockUser(roles = "ADMIN")
    void serviceErrors_mapToStatusContract() throws Exception {
        given(adminMessageService.send(anyLong(), any()))
                .willThrow(new RateLimitedException("쪽지 발송 한도를 초과했습니다.", 30))
                .willThrow(new InvalidRequestException("쪽지를 받을 수 없는 수신자입니다."))
                .willThrow(new CannotAcquireLockException("deadlock"));
        String request = json(12, "제목", "본문");

        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("쪽지를 받을 수 없는 수신자입니다."));
        mockMvc.perform(post(SEND).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    // ===================== 수신자 검색 =====================

    @Test
    @DisplayName("검색은 가드 → 검색 버킷 → 서비스 순이고 검색어 원문(앞 공백 포함)을 그대로 전달한다")
    @WithMockUser(roles = "MANAGER")
    void search_ok_passesRawKeywordInOrder() throws Exception {
        given(messageRecipientService.search(ME, " a"))
                .willReturn(new MessageRecipientResponse(List.of(new MessageRecipientResponse.Item(3L, " a", "나")), false));

        mockMvc.perform(get(SEARCH).param("keyword", " a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(3))
                .andExpect(jsonPath("$.content[0].userId").value(" a"))
                .andExpect(jsonPath("$.content[0].userName").value("나"))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.truncated").value(false));

        InOrder order = inOrder(actorGuard, rateLimiter, messageRecipientService);
        order.verify(actorGuard).requireActive(ME);
        order.verify(rateLimiter).consumeSearch(ME);
        order.verify(messageRecipientService).search(ME, " a");
    }

    @Test
    @DisplayName("검색 한도 초과는 429, 가드 거부는 403이고 서비스를 호출하지 않는다")
    @WithMockUser(roles = "ADMIN")
    void search_limitedAndDenied() throws Exception {
        doThrow(new RateLimitedException("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", 3)).when(rateLimiter).consumeSearch(ME);
        mockMvc.perform(get(SEARCH).param("keyword", "ab"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "3"));

        reset(rateLimiter);
        doThrow(new AccessDeniedException("쪽지를 사용할 수 없는 계정입니다.")).when(actorGuard).requireActive(ME);
        mockMvc.perform(get(SEARCH).param("keyword", "ab")).andExpect(status().isForbidden());

        verifyNoInteractions(messageRecipientService);
    }

    @Test
    @DisplayName("ROLE_USER는 검색도 403이다")
    @WithMockUser(roles = "USER")
    void search_userForbidden() throws Exception {
        mockMvc.perform(get(SEARCH).param("keyword", "ab")).andExpect(status().isForbidden());

        verifyNoInteractions(actorGuard, rateLimiter, messageRecipientService);
    }

    @Test
    @DisplayName("회원 ID는 세션에서만 읽는다 — 요청 파라미터의 memberId·senderId는 무시된다")
    @WithMockUser(roles = "ADMIN")
    void search_ignoresMemberIdParameters() throws Exception {
        given(messageRecipientService.search(eq(ME), any())).willReturn(new MessageRecipientResponse(List.of(), false));

        mockMvc.perform(get(SEARCH).param("keyword", "ab").param("memberId", "999").param("senderId", "999"))
                .andExpect(status().isOk());

        verify(messageRecipientService).search(ME, "ab");
        verify(actorGuard).requireActive(ME);
    }

    // ===================== 목록·단건·읽음·미읽음 수·삭제 =====================

    private static final String BASE = "/admin/api/members/me/messages";

    @Test
    @DisplayName("목록: 가드 후 본인 ID·box·beforeId·size가 서비스에 전달되고 본문 없는 항목을 돌려준다")
    @WithMockUser(roles = "MANAGER")
    void list_passesPrincipalBoxCursorAndSize() throws Exception {
        given(adminMessageService.list(ME, "sent", 55L, 10)).willReturn(new MessageListResponse(List.of(
                new MessageListResponse.Item(54L, new MessageListResponse.Counterpart(12L, "bob", "밥"), "제목", true,
                        LocalDateTime.of(2026, 10, 5, 13, 0), LocalDateTime.of(2026, 10, 5, 12, 0))), true));

        mockMvc.perform(get(BASE).param("box", "sent").param("beforeId", "55").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(54))
                .andExpect(jsonPath("$.content[0].counterpart.userId").value("bob"))
                .andExpect(jsonPath("$.content[0].counterpart.email").doesNotExist())
                .andExpect(jsonPath("$.content[0].read").value(true))
                .andExpect(jsonPath("$.content[0].body").doesNotExist())
                .andExpect(jsonPath("$.hasMore").value(true));

        InOrder order = inOrder(actorGuard, adminMessageService);
        order.verify(actorGuard).requireActive(ME);
        order.verify(adminMessageService).list(ME, "sent", 55L, 10);
    }

    @Test
    @DisplayName("목록: box 누락·이상 값은 서비스가 400으로 거부한다")
    @WithMockUser(roles = "ADMIN")
    void list_invalidBoxIs400() throws Exception {
        given(adminMessageService.list(eq(ME), any(), any(), any()))
                .willThrow(new InvalidRequestException("box는 inbox 또는 sent여야 합니다."));

        mockMvc.perform(get(BASE).param("box", "trash"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("미읽음 수: 가드 후 서비스 값을 돌려준다(/messages/unread-count는 {id} 경로와 충돌하지 않는다)")
    @WithMockUser(roles = "MANAGER")
    void unreadCount_ok() throws Exception {
        given(adminMessageService.unreadCount(ME)).willReturn(4L);

        mockMvc.perform(get(BASE + "/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(4));

        verify(actorGuard).requireActive(ME);
    }

    @Test
    @DisplayName("단건: 본문 전체와 direction·counterpart를 돌려주고, 없거나 남의 것이면 404 RESOURCE_NOT_FOUND다")
    @WithMockUser(roles = "ADMIN")
    void get_okAndNotFound() throws Exception {
        given(adminMessageService.get(ME, 5L)).willReturn(new MessageDetailResponse(5L, MessageDetailResponse.Direction.RECEIVED,
                new MessageListResponse.Counterpart(12L, "bob", "밥"), "제목", "본문 전체", null, LocalDateTime.of(2026, 10, 5, 12, 0)));
        given(adminMessageService.get(ME, 6L)).willThrow(new com.cms.common.exception.ResourceNotFoundException("쪽지를 찾을 수 없습니다."));

        mockMvc.perform(get(BASE + "/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("본문 전체"))
                .andExpect(jsonPath("$.direction").value("RECEIVED"))
                .andExpect(jsonPath("$.read").value(false));
        mockMvc.perform(get(BASE + "/6"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("읽음: {read:true}를 받아 처리하고 미읽음 수를 돌려준다 — 가드를 먼저 통과한다")
    @WithMockUser(roles = "MANAGER")
    void markRead_ok() throws Exception {
        given(adminMessageService.markRead(ME, 5L)).willReturn(new com.cms.admin.message.dto.response.MessageReadResponse(5L, 2));

        mockMvc.perform(patch(BASE + "/5").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.unreadCount").value(2));

        InOrder order = inOrder(actorGuard, adminMessageService);
        order.verify(actorGuard).requireActive(ME);
        order.verify(adminMessageService).markRead(ME, 5L);
    }

    @Test
    @DisplayName("읽음: read:false(읽음 취소)·누락은 400이고 서비스를 호출하지 않으며, 대상이 없으면 404다")
    @WithMockUser(roles = "ADMIN")
    void markRead_invalidBodyAndNotFound() throws Exception {
        mockMvc.perform(patch(BASE + "/5").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"read\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(patch(BASE + "/5").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(adminMessageService);

        given(adminMessageService.markRead(ME, 9L)).willThrow(new com.cms.common.exception.ResourceNotFoundException("쪽지를 찾을 수 없습니다."));
        mockMvc.perform(patch(BASE + "/9").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("삭제: 204이고 가드 후 본인 ID로만 삭제한다. 없거나 남의 것·이미 지운 것은 404, 교착은 409다")
    @WithMockUser(roles = "ADMIN")
    void delete_okNotFoundConflict() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/5").with(csrf()))
                .andExpect(status().isNoContent());
        verify(actorGuard).requireActive(ME);
        verify(adminMessageService).delete(ME, 5L);

        doThrow(new com.cms.common.exception.ResourceNotFoundException("쪽지를 찾을 수 없습니다.")).when(adminMessageService).delete(ME, 6L);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/6").with(csrf()))
                .andExpect(status().isNotFound());

        doThrow(new CannotAcquireLockException("deadlock")).when(adminMessageService).delete(ME, 7L);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/7").with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("CSRF 토큰이 없는 PATCH·DELETE는 403이고, ROLE_USER는 모든 조회·변경 엔드포인트에서 403이다")
    void csrfAndRoleGates() throws Exception {
        mockMvc.perform(patch(BASE + "/5").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .user("a").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/5")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("a").roles("ADMIN")))
                .andExpect(status().isForbidden());

        var user = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("u").roles("USER");
        mockMvc.perform(get(BASE).param("box", "inbox").with(user)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/unread-count").with(user)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/5").with(user)).andExpect(status().isForbidden());
        mockMvc.perform(patch(BASE + "/5").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/5").with(user).with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(actorGuard, adminMessageService);
    }

    @Test
    @DisplayName("회원 ID는 세션에서만 읽는다 — 목록·단건·삭제에서 memberId 파라미터는 무시된다")
    @WithMockUser(roles = "ADMIN")
    void ignoresMemberIdParameters() throws Exception {
        given(adminMessageService.list(eq(ME), any(), any(), any())).willReturn(new MessageListResponse(List.of(), false));

        mockMvc.perform(get(BASE).param("box", "inbox").param("memberId", "999").param("senderId", "999"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/5").param("memberId", "999").with(csrf()))
                .andExpect(status().isNoContent());

        verify(adminMessageService).list(ME, "inbox", null, null);
        verify(adminMessageService).delete(ME, 5L);
    }
}
