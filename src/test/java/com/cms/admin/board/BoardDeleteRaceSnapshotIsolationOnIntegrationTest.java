package com.cms.admin.board;

import com.cms.support.CmsTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * {@code innodb_snapshot_isolation=ON}(MariaDB 11.6+ 기본값 방향) 전용 컨텍스트 — 스냅샷 이후 지워진 행을 잠그려 하면 오류 1020이 나는 설정에서도
 * 게시판 삭제와 회수 PUT이 계약대로(409, 전체 롤백) 동작하는지 확인한다. 공통 시험은 {@link AbstractBoardDeleteRaceTest}.
 */
@SpringBootTest(classes = CmsTestApplication.class,
        properties = "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_snapshot_isolation=ON")
@AutoConfigureMockMvc
@Import(AbstractBoardDeleteRaceTest.Instrumentation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BoardDeleteRaceSnapshotIsolationOnIntegrationTest extends AbstractBoardDeleteRaceTest {

    @Override
    boolean snapshotIsolationExpected() {
        return true;
    }
}
