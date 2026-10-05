package com.cms.admin.message;

import com.cms.support.CmsTestApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * {@code innodb_snapshot_isolation=ON}(MariaDB 11.6+ 기본값 방향) 전용 컨텍스트 — 스냅샷 이후 수정된 행을 잠그려 하면 오류 1020이 나는 설정에서도
 * 쪽지 삭제·발송이 같은 계약을 지키는지 확인한다. 공통 시험은 {@link AbstractMessageSnapshotIsolationTest}.
 */
@SpringBootTest(classes = CmsTestApplication.class,
        properties = "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_snapshot_isolation=ON")
@AutoConfigureMockMvc
@Import(AbstractMessageSnapshotIsolationTest.Instrumentation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminMessageSnapshotIsolationOnIntegrationTest extends AbstractMessageSnapshotIsolationTest {

    @Override
    boolean snapshotIsolationExpected() {
        return true;
    }
}
