package com.cms.admin.permission.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.MemberPermission;
import com.cms.admin.permission.MemberPermissionId;
import com.cms.admin.permission.MemberPermissionRepository;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionChangedEvent;
import com.cms.admin.permission.dto.request.MemberPermissionUpdateRequest;
import com.cms.admin.permission.dto.response.MemberPermissionMatrixResponse;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MemberPermissionServiceTest {

    static final long MEMBER_ID = 10L;

    @Mock MemberRepository memberRepository;
    @Mock MemberPermissionRepository memberPermissionRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks
    MemberPermissionService service;

    private static Member member(Role role, MemberStatus status, long version) {
        return Member.builder().id(MEMBER_ID).userId("manager10").userName("매니저10").email("m10@example.com")
                .userType(role).status(status).permissionVersion(version).build();
    }

    private static Member manager(long version) {
        return member(Role.ROLE_MANAGER, MemberStatus.ACTIVE, version);
    }

    private static MemberPermission row(String feature, String action) {
        return new MemberPermission(MEMBER_ID, feature, action);
    }

    private static MemberPermissionUpdateRequest request(long version, PermissionAction... noticeActions) {
        return MemberPermissionUpdateRequest.builder()
                .version(version)
                .grants(Arrays.stream(noticeActions)
                        .map(a -> new MemberPermissionUpdateRequest.Grant(AdminFeature.NOTICE, a)).toList())
                .build();
    }

    private static MemberPermissionUpdateRequest requestOf(long version, AdminFeature feature, PermissionAction action) {
        return MemberPermissionUpdateRequest.builder().version(version)
                .grants(List.of(new MemberPermissionUpdateRequest.Grant(feature, action))).build();
    }

    private void seedRows(String... featureAction) {
        given(memberPermissionRepository.findByMemberId(MEMBER_ID)).willReturn(
                IntStream.range(0, featureAction.length / 2)
                        .mapToObj(i -> row(featureAction[2 * i], featureAction[2 * i + 1])).toList());
    }

    // ── 400: DB 접근 전 거부 ─────────────────────────────

    @Test
    @DisplayName("위임 불가 기능(상시 허용·관리자 전용)·중복·READ 없는 쓰기는 400이고 회원 행 잠금 전에 거부된다")
    void invalidGrants_rejectedBeforeLock() {
        assertThatThrownBy(() -> service.replace(MEMBER_ID, requestOf(0, AdminFeature.DASHBOARD, PermissionAction.READ)))
                .isInstanceOf(InvalidRequestException.class).hasMessage("위임할 수 없는 기능입니다: 대시보드");
        assertThatThrownBy(() -> service.replace(MEMBER_ID, requestOf(0, AdminFeature.MY_INFO, PermissionAction.UPDATE)))
                .isInstanceOf(InvalidRequestException.class).hasMessage("위임할 수 없는 기능입니다: 내 정보");
        assertThatThrownBy(() -> service.replace(MEMBER_ID, requestOf(0, AdminFeature.PERMISSION, PermissionAction.READ)))
                .isInstanceOf(InvalidRequestException.class).hasMessage("위임할 수 없는 기능입니다: 권한관리");
        assertThatThrownBy(() -> service.replace(MEMBER_ID, requestOf(0, AdminFeature.MENU, PermissionAction.READ)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(0, PermissionAction.READ, PermissionAction.READ)))
                .isInstanceOf(InvalidRequestException.class).hasMessage("중복된 권한 항목이 있습니다.");
        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(0, PermissionAction.CREATE)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("조회 권한과 함께");
        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(0, PermissionAction.DELETE, PermissionAction.UPDATE)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("조회 권한과 함께");

        verifyNoInteractions(memberRepository, memberPermissionRepository, eventPublisher);
    }

    // ── 대상: 404 / 400 / 409 ───────────────────────────────

    @Test
    @DisplayName("회원이 없으면 404 — 조회·저장 공통")
    void missingMember_notFound() {
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMatrix(MEMBER_ID)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(0, PermissionAction.READ)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(memberPermissionRepository, eventPublisher);
    }

    @Test
    @DisplayName("ROLE_USER 대상은 존재를 숨기는 404이고 쓰기·이벤트가 없다")
    void roleUserTarget_hiddenAsNotFound() {
        Member user = member(Role.ROLE_USER, MemberStatus.ACTIVE, 0);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(user));
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> service.getMatrix(MEMBER_ID)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(0, PermissionAction.READ)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(memberPermissionRepository, eventPublisher);
    }

    @Test
    @DisplayName("ADMIN 대상은 코드로 고정되어 400이고 쓰기·이벤트가 없다 — 조회·저장 공통")
    void adminTarget_rejected() {
        Member admin = member(Role.ROLE_ADMIN, MemberStatus.ACTIVE, 0);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(admin));
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.getMatrix(MEMBER_ID))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("코드로 고정");
        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(0, PermissionAction.READ)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("코드로 고정");
        verifyNoInteractions(memberPermissionRepository, eventPublisher);
    }

    @Test
    @DisplayName("삭제된 계정은 저장 409(쓰기·이벤트 없음)이지만 조회는 허용한다")
    void deletedTarget_writeConflict_readAllowed() {
        Member deleted = member(Role.ROLE_MANAGER, MemberStatus.DELETED, 2);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(deleted));
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(deleted));
        given(memberPermissionRepository.findByMemberId(MEMBER_ID)).willReturn(List.of());

        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(2, PermissionAction.READ)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("삭제된 계정");
        assertThat(service.getMatrix(MEMBER_ID).getStatus()).isEqualTo(MemberStatus.DELETED);

        verify(memberPermissionRepository, never()).saveAll(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("버전이 다르면 409이고 쓰기·이벤트가 없다")
    void staleVersion_conflict() {
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(manager(4)));

        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(3, PermissionAction.READ)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("역할이 바뀌었습니다");

        verify(memberPermissionRepository, never()).saveAll(any());
        verify(memberPermissionRepository, never()).deleteAll(any(Iterable.class));
        verifyNoInteractions(eventPublisher);
    }

    // ── 교체 ───────────────────────────────────────────────

    @Test
    @DisplayName("변경이 있으면 추가·삭제 행만 저장하고 version+1·이벤트 1회, 라벨은 추가(+) 먼저이며 결과가 대상 회원 ID를 노출한다(감사 targetId)")
    void replace_appliesDiff() {
        Member target = manager(3);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(target));
        seedRows("NOTICE", "READ", "NOTICE", "DELETE");
        given(memberPermissionRepository.existsById(any(MemberPermissionId.class))).willReturn(false);

        MemberPermissionUpdateResult result = service.replace(MEMBER_ID,
                request(3, PermissionAction.READ, PermissionAction.CREATE));

        assertThat(result.getMemberId()).as("감사 Aspect가 getMemberId()에서 targetId를 추출한다").isEqualTo(MEMBER_ID);
        assertThat(result.getAuditLabel()).isEqualTo("v3→v4: +공지사항.생성, -공지사항.삭제");
        assertThat(target.getPermissionVersion()).isEqualTo(4L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<MemberPermission>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(memberPermissionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(MemberPermission::getAction).containsExactly("CREATE");
        assertThat(saved.getValue()).extracting(MemberPermission::getMemberId).containsOnly(MEMBER_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<MemberPermission>> deleted = ArgumentCaptor.forClass(Iterable.class);
        verify(memberPermissionRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).extracting(MemberPermission::getAction).containsExactly("DELETE");
        verify(eventPublisher).publishEvent(new PermissionChangedEvent(MEMBER_ID));

        MemberPermissionMatrixResponse.FeatureRow notice = result.getResponse().getFeatures().stream()
                .filter(f -> f.getFeature() == AdminFeature.NOTICE).findFirst().orElseThrow();
        assertThat(notice.getGrantedActions()).containsExactly(PermissionAction.READ, PermissionAction.CREATE);
        assertThat(result.getResponse().getVersion()).isEqualTo(4L);
        assertThat(result.getResponse().getMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    @DisplayName("빈 grants는 전부 회수 — 삭제만 저장하고 라벨은 삭제 항목만")
    void replace_emptyRevokesAll() {
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(manager(0)));
        seedRows("NOTICE", "READ", "NOTICE", "CREATE", "NOTICE", "UPDATE", "NOTICE", "DELETE");

        MemberPermissionUpdateResult result = service.replace(MEMBER_ID, request(0));

        assertThat(result.getAuditLabel()).isEqualTo("v0→v1: -공지사항.조회, -공지사항.생성, -공지사항.수정, -공지사항.삭제");
        verify(eventPublisher).publishEvent(new PermissionChangedEvent(MEMBER_ID));
    }

    @Test
    @DisplayName("변경이 없으면 버전·쓰기·이벤트가 없고 라벨은 '변경 없음'이며 결과는 여전히 대상 회원 ID를 노출한다")
    void replace_noChange() {
        Member target = manager(3);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(target));
        seedRows("NOTICE", "READ");

        MemberPermissionUpdateResult result = service.replace(MEMBER_ID, request(3, PermissionAction.READ));

        assertThat(result.getAuditLabel()).isEqualTo("v3: 변경 없음");
        assertThat(result.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(target.getPermissionVersion()).isEqualTo(3L);
        verify(memberPermissionRepository, never()).saveAll(any());
        verify(memberPermissionRepository, never()).deleteAll(any(Iterable.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("모르는 기능·동작·위임 불가 기능 행과 다른 회원의 행은 diff에서 제외되고 삭제되지 않는다")
    void replace_ignoresUnknownAndForeignRows() {
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(manager(1)));
        given(memberPermissionRepository.findByMemberId(MEMBER_ID)).willReturn(List.of(
                row("NOTICE", "READ"),
                row("GONE_FEATURE", "READ"),
                row("NOTICE", "EXPORT"),
                row("MENU", "READ"),
                row("notice", "DELETE"),                              // 대소문자 변형 행 — 판정기가 무시한다
                new MemberPermission(MEMBER_ID + 1, "NOTICE", "DELETE")));

        MemberPermissionUpdateResult result = service.replace(MEMBER_ID, request(1, PermissionAction.READ));

        assertThat(result.getAuditLabel()).isEqualTo("v1: 변경 없음");
        verify(memberPermissionRepository, never()).deleteAll(any(Iterable.class));
    }

    @Test
    @DisplayName("추가할 키가 DB PK로는 이미 존재하면(대소문자·공백 변형 행) 409이고 쓰기·이벤트가 없다")
    void replace_variantRowCollision_conflict() {
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(manager(1)));
        given(memberPermissionRepository.findByMemberId(MEMBER_ID)).willReturn(List.of(row("NOTICE", "read")));
        given(memberPermissionRepository.existsById(new MemberPermissionId(MEMBER_ID, "NOTICE", "READ"))).willReturn(true);

        assertThatThrownBy(() -> service.replace(MEMBER_ID, request(1, PermissionAction.READ)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("형식이 올바르지 않은 행");

        verify(memberPermissionRepository, never()).saveAll(any());
        verifyNoInteractions(eventPublisher);
    }

    // ── 조회 ───────────────────────────────────────────────

    @Test
    @DisplayName("조회: 회원 식별 정보·버전 + 상시 허용=지원 동작 전부, 관리자 전용=빈 배열, 위임 가능=DB 행 중 유효한 것(READ 없는 쓰기는 제외)")
    void getMatrix_effectiveGrants() {
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(manager(7)));
        seedRows("NOTICE", "CREATE", "NOTICE", "DELETE"); // READ 없음 → 둘 다 무효

        MemberPermissionMatrixResponse matrix = service.getMatrix(MEMBER_ID);

        assertThat(matrix.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(matrix.getUserId()).isEqualTo("manager10");
        assertThat(matrix.getUserName()).isEqualTo("매니저10");
        assertThat(matrix.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(matrix.getVersion()).isEqualTo(7L);
        assertThat(matrix.getActions()).extracting(MemberPermissionMatrixResponse.ActionColumn::getLabel)
                .containsExactly("조회", "생성", "수정", "삭제");
        assertThat(matrix.getFeatures()).extracting(MemberPermissionMatrixResponse.FeatureRow::getFeature)
                .containsExactly(AdminFeature.values());
        var byFeature = matrix.getFeatures().stream()
                .collect(Collectors.toMap(MemberPermissionMatrixResponse.FeatureRow::getFeature, f -> f));
        assertThat(byFeature.get(AdminFeature.DASHBOARD).getGrantedActions()).containsExactly(PermissionAction.READ);
        assertThat(byFeature.get(AdminFeature.MY_INFO).getGrantedActions())
                .containsExactly(PermissionAction.READ, PermissionAction.UPDATE);
        assertThat(byFeature.get(AdminFeature.MENU).getGrantedActions()).isEmpty();
        assertThat(byFeature.get(AdminFeature.PERMISSION).getSupportedActions()).isEmpty();
        assertThat(byFeature.get(AdminFeature.NOTICE).getGrantedActions()).isEmpty();
    }
}
