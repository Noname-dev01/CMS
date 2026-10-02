package com.cms.admin.permission.service;

import com.cms.admin.member.domain.Role;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionChangedEvent;
import com.cms.admin.permission.PermissionRole;
import com.cms.admin.permission.PermissionRoleRepository;
import com.cms.admin.permission.RolePermission;
import com.cms.admin.permission.RolePermissionId;
import com.cms.admin.permission.RolePermissionRepository;
import com.cms.admin.permission.dto.request.RolePermissionUpdateRequest;
import com.cms.admin.permission.dto.response.RolePermissionMatrixResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RolePermissionServiceTest {

    /** UTC 2026-09-29 15:00 = KST 2026-09-30 00:00 — 시스템 시각·기본 시간대와 무관하게 저장 시각을 단언한다. */
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 30, 0, 0);

    @Mock PermissionRoleRepository permissionRoleRepository;
    @Mock RolePermissionRepository rolePermissionRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    RolePermissionService service;

    private static PermissionRole base(long version) {
        PermissionRole role = newInstance(PermissionRole.class);
        ReflectionTestUtils.setField(role, "role", "ROLE_MANAGER");
        ReflectionTestUtils.setField(role, "version", version);
        return role;
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RolePermission row(String feature, String action) {
        return new RolePermission("ROLE_MANAGER", feature, action);
    }

    private static RolePermissionUpdateRequest request(long version, PermissionAction... noticeActions) {
        return RolePermissionUpdateRequest.builder()
                .version(version)
                .grants(java.util.Arrays.stream(noticeActions)
                        .map(a -> new RolePermissionUpdateRequest.Grant(AdminFeature.NOTICE, a)).toList())
                .build();
    }

    private static RolePermissionUpdateRequest requestOf(long version, AdminFeature feature, PermissionAction action) {
        return RolePermissionUpdateRequest.builder().version(version)
                .grants(List.of(new RolePermissionUpdateRequest.Grant(feature, action))).build();
    }

    private void seedRows(String... featureAction) {
        given(rolePermissionRepository.findByRole("ROLE_MANAGER")).willReturn(
                java.util.stream.IntStream.range(0, featureAction.length / 2)
                        .mapToObj(i -> row(featureAction[2 * i], featureAction[2 * i + 1])).toList());
    }

    // ── 400: DB 접근 전 거부 ─────────────────────────────

    @Test
    @DisplayName("관리할 수 없는 역할(ADMIN·USER)은 400이고 DB를 보지 않는다 — GET·PUT 공통")
    void unmanageableRoles_rejectedBeforeDb() {
        assertThatThrownBy(() -> service.getMatrix(Role.ROLE_ADMIN))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("코드로 고정");
        assertThatThrownBy(() -> service.replace(Role.ROLE_ADMIN, request(0)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("코드로 고정");
        assertThatThrownBy(() -> service.getMatrix(Role.ROLE_USER))
                .isInstanceOf(InvalidRequestException.class).hasMessage("권한을 관리할 수 없는 역할입니다.");
        assertThatThrownBy(() -> service.replace(Role.ROLE_USER, request(0)))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(permissionRoleRepository, rolePermissionRepository, eventPublisher);
    }

    @Test
    @DisplayName("위임 불가 기능(상시 허용·관리자 전용)·중복·READ 없는 쓰기는 400이고 잠금 조회 전에 거부된다")
    void invalidGrants_rejectedBeforeLock() {
        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, requestOf(0, AdminFeature.DASHBOARD, PermissionAction.READ)))
                .isInstanceOf(InvalidRequestException.class).hasMessage("위임할 수 없는 기능입니다: 대시보드");
        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, requestOf(0, AdminFeature.MY_INFO, PermissionAction.UPDATE)))
                .isInstanceOf(InvalidRequestException.class).hasMessage("위임할 수 없는 기능입니다: 내 정보");
        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, requestOf(0, AdminFeature.PERMISSION, PermissionAction.READ)))
                .isInstanceOf(InvalidRequestException.class).hasMessage("위임할 수 없는 기능입니다: 권한관리");
        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, requestOf(0, AdminFeature.MENU, PermissionAction.READ)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, request(0, PermissionAction.READ, PermissionAction.READ)))
                .isInstanceOf(InvalidRequestException.class).hasMessage("중복된 권한 항목이 있습니다.");
        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, request(0, PermissionAction.CREATE)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("조회 권한과 함께");
        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, request(0, PermissionAction.DELETE, PermissionAction.UPDATE)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("조회 권한과 함께");

        verifyNoInteractions(permissionRoleRepository, rolePermissionRepository, eventPublisher);
    }

    // ── 404 / 409 ──────────────────────────────────────────

    @Test
    @DisplayName("역할 기준 행이 없으면 404")
    void missingBaseRow_notFound() {
        given(permissionRoleRepository.findByIdForUpdate("ROLE_MANAGER")).willReturn(Optional.empty());
        given(permissionRoleRepository.findById("ROLE_MANAGER")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, request(0, PermissionAction.READ)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getMatrix(Role.ROLE_MANAGER)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("버전이 다르면 409이고 쓰기·이벤트가 없다")
    void staleVersion_conflict() {
        given(permissionRoleRepository.findByIdForUpdate("ROLE_MANAGER")).willReturn(Optional.of(base(4)));

        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, request(3, PermissionAction.READ)))
                .isInstanceOf(ConflictException.class);

        verify(rolePermissionRepository, never()).saveAll(any());
        verify(rolePermissionRepository, never()).deleteAll(any(Iterable.class));
        verifyNoInteractions(eventPublisher);
    }

    // ── 교체 ───────────────────────────────────────────────

    @Test
    @DisplayName("변경이 있으면 추가·삭제 행만 저장하고 version+1·updateDate=주입 Clock·이벤트 1회, 라벨은 추가(+) 먼저")
    void replace_appliesDiff() {
        PermissionRole base = base(3);
        given(permissionRoleRepository.findByIdForUpdate("ROLE_MANAGER")).willReturn(Optional.of(base));
        seedRows("NOTICE", "READ", "NOTICE", "DELETE");
        given(rolePermissionRepository.existsById(any(RolePermissionId.class))).willReturn(false);

        RolePermissionUpdateResult result = service.replace(Role.ROLE_MANAGER,
                request(3, PermissionAction.READ, PermissionAction.CREATE));

        assertThat(result.getAuditLabel()).isEqualTo("ROLE_MANAGER v3→v4: +공지사항.생성, -공지사항.삭제");
        assertThat(base.getVersion()).isEqualTo(4L);
        assertThat(base.getUpdateDate()).isEqualTo(FIXED_NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<RolePermission>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(rolePermissionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(RolePermission::getAction).containsExactly("CREATE");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<RolePermission>> deleted = ArgumentCaptor.forClass(Iterable.class);
        verify(rolePermissionRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).extracting(RolePermission::getAction).containsExactly("DELETE");
        verify(eventPublisher).publishEvent(new PermissionChangedEvent("ROLE_MANAGER"));

        RolePermissionMatrixResponse.FeatureRow notice = result.getResponse().getFeatures().stream()
                .filter(f -> f.getFeature() == AdminFeature.NOTICE).findFirst().orElseThrow();
        assertThat(notice.getGrantedActions()).containsExactly(PermissionAction.READ, PermissionAction.CREATE);
        assertThat(result.getResponse().getVersion()).isEqualTo(4L);
    }

    @Test
    @DisplayName("빈 grants는 전부 회수 — 삭제만 저장하고 라벨은 삭제 항목만")
    void replace_emptyRevokesAll() {
        given(permissionRoleRepository.findByIdForUpdate("ROLE_MANAGER")).willReturn(Optional.of(base(0)));
        seedRows("NOTICE", "READ", "NOTICE", "CREATE", "NOTICE", "UPDATE", "NOTICE", "DELETE");

        RolePermissionUpdateResult result = service.replace(Role.ROLE_MANAGER, request(0));

        assertThat(result.getAuditLabel())
                .isEqualTo("ROLE_MANAGER v0→v1: -공지사항.조회, -공지사항.생성, -공지사항.수정, -공지사항.삭제");
        verify(eventPublisher).publishEvent(new PermissionChangedEvent("ROLE_MANAGER"));
    }

    @Test
    @DisplayName("변경이 없으면 버전·쓰기·이벤트가 없고 라벨은 '변경 없음'")
    void replace_noChange() {
        PermissionRole base = base(3);
        given(permissionRoleRepository.findByIdForUpdate("ROLE_MANAGER")).willReturn(Optional.of(base));
        seedRows("NOTICE", "READ");

        RolePermissionUpdateResult result = service.replace(Role.ROLE_MANAGER, request(3, PermissionAction.READ));

        assertThat(result.getAuditLabel()).isEqualTo("ROLE_MANAGER v3: 변경 없음");
        assertThat(base.getVersion()).isEqualTo(3L);
        assertThat(base.getUpdateDate()).isNull();
        verify(rolePermissionRepository, never()).saveAll(any());
        verify(rolePermissionRepository, never()).deleteAll(any(Iterable.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("모르는 기능·동작·위임 불가 기능 행과 역할 변형 행은 diff에서 제외되고 삭제되지 않는다")
    void replace_ignoresUnknownAndForeignRows() {
        given(permissionRoleRepository.findByIdForUpdate("ROLE_MANAGER")).willReturn(Optional.of(base(1)));
        given(rolePermissionRepository.findByRole("ROLE_MANAGER")).willReturn(List.of(
                row("NOTICE", "READ"),
                row("GONE_FEATURE", "READ"),
                row("NOTICE", "EXPORT"),
                row("MENU", "READ"),
                new RolePermission("role_manager", "NOTICE", "DELETE")));

        RolePermissionUpdateResult result = service.replace(Role.ROLE_MANAGER, request(1, PermissionAction.READ));

        assertThat(result.getAuditLabel()).isEqualTo("ROLE_MANAGER v1: 변경 없음");
        verify(rolePermissionRepository, never()).deleteAll(any(Iterable.class));
    }

    @Test
    @DisplayName("추가할 키가 DB PK로는 이미 존재하면(대소문자·공백 변형 행) 409이고 쓰기·이벤트가 없다")
    void replace_variantRowCollision_conflict() {
        given(permissionRoleRepository.findByIdForUpdate("ROLE_MANAGER")).willReturn(Optional.of(base(1)));
        given(rolePermissionRepository.findByRole("ROLE_MANAGER")).willReturn(List.of(row("NOTICE", "read")));
        given(rolePermissionRepository.existsById(new RolePermissionId("ROLE_MANAGER", "NOTICE", "READ"))).willReturn(true);

        assertThatThrownBy(() -> service.replace(Role.ROLE_MANAGER, request(1, PermissionAction.READ)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("형식이 올바르지 않은 행");

        verify(rolePermissionRepository, never()).saveAll(any());
        verifyNoInteractions(eventPublisher);
    }

    // ── 조회 ───────────────────────────────────────────────

    @Test
    @DisplayName("조회: 상시 허용=지원 동작 전부, 관리자 전용=빈 배열, 위임 가능=DB 행 중 유효한 것(READ 없는 쓰기는 제외)")
    void getMatrix_effectiveGrants() {
        given(permissionRoleRepository.findById("ROLE_MANAGER")).willReturn(Optional.of(base(7)));
        seedRows("NOTICE", "CREATE", "NOTICE", "DELETE"); // READ 없음 → 둘 다 무효

        RolePermissionMatrixResponse matrix = service.getMatrix(Role.ROLE_MANAGER);

        assertThat(matrix.getVersion()).isEqualTo(7L);
        assertThat(matrix.getActions()).extracting(RolePermissionMatrixResponse.ActionColumn::getLabel)
                .containsExactly("조회", "생성", "수정", "삭제");
        assertThat(matrix.getFeatures()).extracting(RolePermissionMatrixResponse.FeatureRow::getFeature)
                .containsExactly(AdminFeature.values());
        var byFeature = matrix.getFeatures().stream()
                .collect(java.util.stream.Collectors.toMap(RolePermissionMatrixResponse.FeatureRow::getFeature, f -> f));
        assertThat(byFeature.get(AdminFeature.DASHBOARD).getGrantedActions()).containsExactly(PermissionAction.READ);
        assertThat(byFeature.get(AdminFeature.MY_INFO).getGrantedActions())
                .containsExactly(PermissionAction.READ, PermissionAction.UPDATE);
        assertThat(byFeature.get(AdminFeature.MENU).getGrantedActions()).isEmpty();
        assertThat(byFeature.get(AdminFeature.PERMISSION).getSupportedActions()).isEmpty();
        assertThat(byFeature.get(AdminFeature.NOTICE).getGrantedActions()).isEmpty();
    }
}
