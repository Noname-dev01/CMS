package com.cms.publicweb.contentimage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/** 열린 본문 이미지 스트림. 받은 쪽(컨트롤러)이 반드시 {@link #close()}한다. */
public record ContentImageDownload(String contentType, long contentLength, InputStream content) implements Closeable {

    @Override
    public void close() throws IOException {
        content.close();
    }
}
