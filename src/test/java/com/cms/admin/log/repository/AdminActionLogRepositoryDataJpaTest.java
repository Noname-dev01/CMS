package com.cms.admin.log.repository;

import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.dto.request.AdminActionLogSearchRequest;
import com.cms.config.QuerydslConfig;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 활동 로그 QueryDSL 검색({@link AdminActionLogRepositoryImpl#searchActionLogs})을 실제 MariaDB에서 검증한다.
 * 기존 테스트(AdminActionLogRepositoryImplSortTest·AdminActionLogQueryServiceTest)는 JPAQueryFactory·리포지토리를
 * mock해 실제 JPQL 생성·실행(필터·정렬·페이지·count)을 확인하지 못한다 — Hibernate 버전 전환 시 회귀 감지용.
 *
 * <p>컨테이너를 다른 테스트 클래스와 공유하므로 다른 테스트가 커밋한 로그가 섞일 수 있다.
 * 고유 수행자 마커와 과거 고정 기간(2001-01)으로 조회 대상을 격리한다. @DataJpaTest 롤백으로 데이터는 남지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@ActiveProfiles("dev")
class AdminActionLogRepositoryDataJpaTest extends MariaDbContainerSupport {

    private static final LocalDate FROM = LocalDate.of(2001, 1, 1);
    private static final LocalDate TO = LocalDate.of(2001, 1, 31);

    @Autowired
    AdminActionLogRepository adminActionLogRepository;

    private void saveLog(String userId, String actionType, AdminActionResult result, LocalDateTime createAt) {
        adminActionLogRepository.save(AdminActionLog.builder()
                .actionUserId(userId)
                .actionType(actionType)
                .actionResult(result)
                .createAt(createAt)
                .build());
    }

    @Test
    @DisplayName("수행자 contains·액션 유형·결과·기간 필터가 모두 적용되고 count가 필터와 일치한다")
    void search_appliesAllFilters_andCountMatches() {
        String marker = "logq" + System.nanoTime();
        saveLog(marker + "-a", "MENU_CREATE", AdminActionResult.SUCCESS, LocalDateTime.of(2001, 1, 10, 9, 0));
        saveLog(marker + "-b", "MENU_CREATE", AdminActionResult.SUCCESS, LocalDateTime.of(2001, 1, 31, 23, 59));
        saveLog(marker + "-c", "MENU_CREATE", AdminActionResult.FAIL, LocalDateTime.of(2001, 1, 11, 9, 0));
        saveLog(marker + "-d", "MENU_DELETE", AdminActionResult.SUCCESS, LocalDateTime.of(2001, 1, 12, 9, 0));
        // 종료일 다음 날 자정 — 포함되면 안 된다(to+1일 자정 미만)
        saveLog(marker + "-e", "MENU_CREATE", AdminActionResult.SUCCESS, LocalDateTime.of(2001, 2, 1, 0, 0));
        saveLog("other" + System.nanoTime(), "MENU_CREATE", AdminActionResult.SUCCESS, LocalDateTime.of(2001, 1, 10, 9, 0));

        AdminActionLogSearchRequest req = AdminActionLogSearchRequest.builder()
                .actionUserId("  " + marker + "  ")   // 앞뒤 공백은 trim된다
                .actionType("MENU_CREATE")
                .actionResult(AdminActionResult.SUCCESS)
                .from(FROM)
                .to(TO)
                .build();

        Page<AdminActionLog> page = adminActionLogRepository.searchActionLogs(req, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(AdminActionLog::getActionUserId)
                .containsExactly(marker + "-b", marker + "-a");   // 기본 정렬 createAt desc
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("화이트리스트 정렬(actionUserId asc)과 페이지 이동이 적용되고 전체 건수는 페이지와 무관하다")
    void search_sortsAndPages() {
        String marker = "logp" + System.nanoTime();
        for (String suffix : new String[]{"c", "a", "e", "b", "d"}) {
            saveLog(marker + "-" + suffix, "NOTICE_CREATE", AdminActionResult.SUCCESS, LocalDateTime.of(2001, 1, 15, 12, 0));
        }
        AdminActionLogSearchRequest req = AdminActionLogSearchRequest.builder()
                .actionUserId(marker).from(FROM).to(TO).build();

        Page<AdminActionLog> second = adminActionLogRepository.searchActionLogs(
                req, PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "actionUserId")));

        assertThat(second.getContent()).extracting(AdminActionLog::getActionUserId)
                .containsExactly(marker + "-c", marker + "-d");
        assertThat(second.getTotalElements()).isEqualTo(5);
        assertThat(second.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("화이트리스트 밖 정렬 필드는 무시되고 기본 정렬(createAt desc, id desc)로 조회된다")
    void search_ignoresUnknownSortField() {
        String marker = "logs" + System.nanoTime();
        saveLog(marker + "-old", "MENU_CREATE", AdminActionResult.SUCCESS, LocalDateTime.of(2001, 1, 5, 0, 0));
        saveLog(marker + "-new", "MENU_CREATE", AdminActionResult.SUCCESS, LocalDateTime.of(2001, 1, 6, 0, 0));
        AdminActionLogSearchRequest req = AdminActionLogSearchRequest.builder()
                .actionUserId(marker).from(FROM).to(TO).build();

        Page<AdminActionLog> page = adminActionLogRepository.searchActionLogs(
                req, PageRequest.of(0, 20, Sort.by("requestIp")));

        assertThat(page.getContent()).extracting(AdminActionLog::getActionUserId)
                .containsExactly(marker + "-new", marker + "-old");
    }
}
