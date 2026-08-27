package com.cms.config.ratelimit;

/** 테스트 전용 {@link Ticker} — 수동으로 전진시킬 수 있는 가짜 구현. */
class FakeTicker implements Ticker {

    private long nanos;

    FakeTicker() {
        this(0L);
    }

    FakeTicker(long initialNanos) {
        this.nanos = initialNanos;
    }

    @Override
    public long nanos() {
        return nanos;
    }

    void advance(long amount, java.util.concurrent.TimeUnit unit) {
        nanos += unit.toNanos(amount);
    }
}
