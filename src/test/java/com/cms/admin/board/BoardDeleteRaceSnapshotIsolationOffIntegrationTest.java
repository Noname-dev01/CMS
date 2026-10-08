package com.cms.admin.board;

import com.cms.support.CmsTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/** {@code innodb_snapshot_isolation=OFF} 전용 컨텍스트. 공통 시험은 {@link AbstractBoardDeleteRaceTest}. */
@SpringBootTest(classes = CmsTestApplication.class,
        properties = "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_snapshot_isolation=OFF")
@AutoConfigureMockMvc
@Import(AbstractBoardDeleteRaceTest.Instrumentation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BoardDeleteRaceSnapshotIsolationOffIntegrationTest extends AbstractBoardDeleteRaceTest {

    @Override
    boolean snapshotIsolationExpected() {
        return false;
    }
}
