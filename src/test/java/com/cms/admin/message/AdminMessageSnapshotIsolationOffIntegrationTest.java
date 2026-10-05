package com.cms.admin.message;

import com.cms.support.CmsTestApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/** {@code innodb_snapshot_isolation=OFF}(MariaDB 10.11 기본값) 전용 컨텍스트 — 공통 시험은 {@link AbstractMessageSnapshotIsolationTest}. */
@SpringBootTest(classes = CmsTestApplication.class,
        properties = "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_snapshot_isolation=OFF")
@AutoConfigureMockMvc
@Import(AbstractMessageSnapshotIsolationTest.Instrumentation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminMessageSnapshotIsolationOffIntegrationTest extends AbstractMessageSnapshotIsolationTest {

    @Override
    boolean snapshotIsolationExpected() {
        return false;
    }
}
