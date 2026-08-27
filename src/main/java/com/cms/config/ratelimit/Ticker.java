package com.cms.config.ratelimit;

/**
 * 레이트리밋 경과시간 측정 전용 단조시계. {@code AppConfig}의 {@code Clock}(KST 날짜 계산용
 * wall-clock, NTP 조정에 취약)과는 별개로 둔다 — 리필 계산은 두 시점 사이의 경과시간만 필요하고,
 * 시각이 뒤로 이동하면(NTP 보정 등) 리필이 멈추거나 앞으로 이동하면 버킷이 즉시 가득 차버려
 * 레이트리밋이 순간적으로 무력화될 수 있기 때문이다(PLAN-public-endpoint-rate-limit.md 쟁점 7).
 *
 * <p>{@link SystemTicker}가 운영 구현체({@link System#nanoTime()} 래핑)이며, 테스트에서는
 * 수동으로 전진 가능한 가짜 구현({@code FakeTicker})을 주입한다. {@code System.nanoTime()}은
 * 원점이 임의이고 음수를 반환할 수 있다는 점에 유의해야 한다({@link Bucket}이 lazy 초기화로
 * 처리한다).
 */
public interface Ticker {

    /** 임의의 기준점으로부터 경과한 나노초. 두 호출의 차이만 의미가 있다. */
    long nanos();
}
