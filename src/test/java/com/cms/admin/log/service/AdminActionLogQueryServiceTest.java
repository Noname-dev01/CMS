package com.cms.admin.log.service;

import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.dto.request.AdminActionLogSearchRequest;
import com.cms.admin.log.dto.response.AdminActionLogPageResponse;
import com.cms.admin.log.repository.AdminActionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminActionLogQueryServiceTest {

    private AdminActionLogRepository repository;
    private AdminActionLogQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminActionLogRepository.class);
        service    = new AdminActionLogQueryService(repository);

        // 기본 stub: 빈 페이지 반환
        given(repository.searchActionLogs(any(), any()))
                .willReturn(new PageImpl<>(List.of()));
    }

    // ===== 기간 기본값 주입 =====

    @Test
    @DisplayName("from/to 둘 다 null이면 최근 30일을 주입한다")
    void from_to_둘다_null이면_최근_30일을_주입한다() {
        AdminActionLogSearchRequest req = new AdminActionLogSearchRequest();
        service.searchActionLogs(req, PageRequest.of(0, 20));

        ArgumentCaptor<AdminActionLogSearchRequest> captor =
                ArgumentCaptor.forClass(AdminActionLogSearchRequest.class);
        verify(repository).searchActionLogs(captor.capture(), any());

        AdminActionLogSearchRequest captured = captor.getValue();
        LocalDate today = LocalDate.now();
        assertThat(captured.getTo()).isEqualTo(today);
        assertThat(captured.getFrom()).isEqualTo(today.minusDays(29));
    }

    @Test
    @DisplayName("to만 지정되면 from을 to-29일로 보정한다")
    void to만_지정되면_from을_to_minus29로_보정한다() {
        LocalDate to = LocalDate.of(2026, 3, 1);
        AdminActionLogSearchRequest req = AdminActionLogSearchRequest.builder()
                .to(to)
                .build();

        service.searchActionLogs(req, PageRequest.of(0, 20));

        ArgumentCaptor<AdminActionLogSearchRequest> captor =
                ArgumentCaptor.forClass(AdminActionLogSearchRequest.class);
        verify(repository).searchActionLogs(captor.capture(), any());

        assertThat(captor.getValue().getFrom()).isEqualTo(to.minusDays(29));
        assertThat(captor.getValue().getTo()).isEqualTo(to);
    }

    @Test
    @DisplayName("from만 지정되면 to를 from+29일로 보정한다")
    void from만_지정되면_to를_from_plus29로_보정한다() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        AdminActionLogSearchRequest req = AdminActionLogSearchRequest.builder()
                .from(from)
                .build();

        service.searchActionLogs(req, PageRequest.of(0, 20));

        ArgumentCaptor<AdminActionLogSearchRequest> captor =
                ArgumentCaptor.forClass(AdminActionLogSearchRequest.class);
        verify(repository).searchActionLogs(captor.capture(), any());

        assertThat(captor.getValue().getFrom()).isEqualTo(from);
        assertThat(captor.getValue().getTo()).isEqualTo(from.plusDays(29));
    }

    @Test
    @DisplayName("from/to 둘 다 지정되면 그대로 사용한다")
    void from_to_둘다_지정되면_그대로_사용한다() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to   = LocalDate.of(2026, 3, 1);
        AdminActionLogSearchRequest req = AdminActionLogSearchRequest.builder()
                .from(from)
                .to(to)
                .build();

        service.searchActionLogs(req, PageRequest.of(0, 20));

        ArgumentCaptor<AdminActionLogSearchRequest> captor =
                ArgumentCaptor.forClass(AdminActionLogSearchRequest.class);
        verify(repository).searchActionLogs(captor.capture(), any());

        assertThat(captor.getValue().getFrom()).isEqualTo(from);
        assertThat(captor.getValue().getTo()).isEqualTo(to);
    }

    // ===== size clamp =====

    @Test
    @DisplayName("size 100 초과면 100으로 clamp한다")
    void size_100초과면_100으로_clamp한다() {
        service.searchActionLogs(new AdminActionLogSearchRequest(), PageRequest.of(0, 100000));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).searchActionLogs(any(), pageableCaptor.capture());

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("size 100 이하면 그대로 유지한다")
    void size_100이하면_유지한다() {
        service.searchActionLogs(new AdminActionLogSearchRequest(), PageRequest.of(0, 50));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).searchActionLogs(any(), pageableCaptor.capture());

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    // ===== 엔티티 → DTO 매핑 =====

    @Test
    @DisplayName("엔티티가 응답 DTO로 올바르게 매핑된다 (actionId 포함 전 필드)")
    void 엔티티가_응답DTO로_매핑된다() {
        AdminActionLog log = AdminActionLog.builder()
                .id(1L)
                .actionId(10L)
                .actionUserId("admin01")
                .actionType("ADMIN_CREATE")
                .actionResult(AdminActionResult.SUCCESS)
                .targetType("MEMBER")
                .targetId(5L)
                .requestIp("127.0.0.1")
                .requestUri("/admin/api/members")
                .requestMethod("POST")
                .errorMessage(null)
                .createAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .build();

        Page<AdminActionLog> page = new PageImpl<>(List.of(log), PageRequest.of(0, 20), 1);
        given(repository.searchActionLogs(any(), any())).willReturn(page);

        AdminActionLogPageResponse response =
                service.searchActionLogs(new AdminActionLogSearchRequest(), PageRequest.of(0, 20));

        assertThat(response.getContent()).hasSize(1);
        var item = response.getContent().get(0);
        assertThat(item.getId()).isEqualTo(1L);
        assertThat(item.getActionId()).isEqualTo(10L);
        assertThat(item.getActionUserId()).isEqualTo("admin01");
        assertThat(item.getActionType()).isEqualTo("ADMIN_CREATE");
        assertThat(item.getActionResult()).isEqualTo(AdminActionResult.SUCCESS);
        assertThat(item.getTargetType()).isEqualTo("MEMBER");
        assertThat(item.getTargetId()).isEqualTo(5L);
        assertThat(item.getRequestIp()).isEqualTo("127.0.0.1");
        assertThat(item.getRequestUri()).isEqualTo("/admin/api/members");
        assertThat(item.getRequestMethod()).isEqualTo("POST");
        assertThat(item.getErrorMessage()).isNull();
        assertThat(item.getCreateAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 12, 0));
    }
}
