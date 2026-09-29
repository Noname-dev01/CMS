package com.cms.common.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * {@link FileStorage#open}이 반환하는 열린 파일 스트림과 그 크기. 크기는 스트림을 연 것과 <b>같은
 * 핸들</b>에서 읽은 값이라 Content-Length와 실제 바이트 수가 어긋나지 않는다(파일은 생성 후 불변).
 *
 * <p><b>호출자가 반드시 {@link #close()}해야 한다</b> — 닫지 않으면 파일 핸들이 새어나간다.
 * (adversarial-review/plan/PLAN-public-notice-attachment.md 결정 S1·S3)
 */
public record StoredFileStream(InputStream inputStream, long size) implements Closeable {

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
