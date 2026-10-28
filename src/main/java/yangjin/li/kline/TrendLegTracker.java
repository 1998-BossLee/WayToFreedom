package yangjin.li.kline;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;

/**
 * 按已确认收盘价独立跟踪上涨、下跌两套区域，两个方向允许同时有效。
 *
 * <p>上涨从候选最低收盘价走出启动幅度后确认，下跌从候选最高收盘价走出启动幅度后确认。
 * 每个方向只管理自己的起点、推进极值与回吐结束条件；金叉、死叉及另一方向均不重置它。</p>
 *
 * <p>例：100 涨至 120 后回到 115，上涨仅回吐 25%，但从 120 已下跌 4.17%，两方向可以共存。
 * 再反弹至 117.5，下跌收复了自身跌幅的 50%，仅结束下跌，并以 117.5 作为新候选高点。
 * 上涨仍保持原起点 100。所有时间为北京时间，不使用盘中影线或未来价格。</p>
 */
public final class TrendLegTracker {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final DirectionalTracker up;
    private final DirectionalTracker down;
    private LocalDateTime previousOpenTime;

    /** startMovePct 单位为百分比，例如 2 表示 2%；retraceRatio 为比例，例如 0.5 表示 50%。 */
    public TrendLegTracker(BigDecimal startMovePct, BigDecimal retraceRatio) {
        if (startMovePct == null || startMovePct.signum() <= 0
                || retraceRatio == null || retraceRatio.signum() <= 0
                || retraceRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("启动幅度必须 > 0，回吐比例必须在 (0,1] 内");
        }
        up = new DirectionalTracker(true, startMovePct, retraceRatio);
        down = new DirectionalTracker(false, startMovePct, retraceRatio);
    }

    /** 一个已观测到的收盘价及其所属 K 线开始时间。 */
    public record Point(LocalDateTime openTime, BigDecimal price) {
    }

    /** number 在本方向、本次回放内递增；confirmedTime 是确认根的北京时间收盘时刻。 */
    public record Leg(int number, String direction, Point start, Point extreme, LocalDateTime confirmedTime) {
    }

    /** 本方向在当前根结束；跳过价格门槛时，实际回吐比例可能超过设置值。 */
    public record CompletedLeg(Leg leg, LocalDateTime endedTime, BigDecimal endPrice, BigDecimal retracementRatio) {
    }

    /**
     * 单方向快照：movePct 是相对起点的有符号变化率，maximumMovePct 是最大推进幅度的绝对值。
     * retracementRatio 为比例，打印时再乘 100；无有效段时这三个数值为 null。
     * candidateStart 是本方向的候选起点；结束后只保留结束根价格，不再使用过期极值。
     */
    public record DirectionalSnapshot(Leg leg, BigDecimal movePct, BigDecimal maximumMovePct,
                                      BigDecimal retracementRatio, Point candidateStart,
                                      boolean newlyConfirmed, CompletedLeg completedLeg) {
    }

    /** 两方向互不覆盖，读取者必须明确选择自己的方向，不再提供唯一 leg。 */
    public record Snapshot(DirectionalSnapshot up, DirectionalSnapshot down) {
    }

    public Snapshot update(Kline candle) {
        if (candle == null) {
            throw new IllegalArgumentException("K 线不能为空");
        }
        if (previousOpenTime != null && !candle.openTime().isAfter(previousOpenTime)) {
            throw new IllegalArgumentException("行情段必须按 K 线时间严格递增处理");
        }
        previousOpenTime = candle.openTime();
        // 两个状态只接收同一根数据，彼此不读取状态，调用顺序不影响结果。
        DirectionalSnapshot upSnapshot = up.update(candle);
        DirectionalSnapshot downSnapshot = down.update(candle);
        return new Snapshot(upSnapshot, downSnapshot);
    }

    /** 固定方向状态；包内可见，便于直接验证更新顺序独立性，不作为外部调用入口。 */
    static final class DirectionalTracker {
        private final boolean upward;
        private final String direction;
        private final BigDecimal startMovePct;
        private final BigDecimal retraceRatio;
        private Point candidateStart;
        private Leg activeLeg;
        private int nextNumber;

        DirectionalTracker(boolean upward, BigDecimal startMovePct, BigDecimal retraceRatio) {
            this.upward = upward;
            direction = upward ? TrendDirection.UP : TrendDirection.DOWN;
            this.startMovePct = startMovePct;
            this.retraceRatio = retraceRatio;
        }

        DirectionalSnapshot update(Kline candle) {
            if (!candle.confirmed()) {
                // 未确认区间可能隐藏转折：清除本方向，但已分配编号不复用，不产生结束事件。
                activeLeg = null;
                candidateStart = null;
                return snapshot(candle.close(), false, null);
            }
            Point current = new Point(candle.openTime(), candle.close());
            if (activeLeg != null) {
                boolean newExtreme = upward
                        ? current.price().compareTo(activeLeg.extreme().price()) > 0
                        : current.price().compareTo(activeLeg.extreme().price()) < 0;
                if (newExtreme) {
                    activeLeg = new Leg(activeLeg.number(), direction, activeLeg.start(), current,
                            activeLeg.confirmedTime());
                }
                BigDecimal advance = advance();
                BigDecimal giveback = giveback(current.price());
                // 先比较价差与乘法，恰好 50% 时结束；除法仅用于输出比例。
                if (giveback.compareTo(advance.multiply(retraceRatio)) >= 0) {
                    CompletedLeg completed = new CompletedLeg(activeLeg, candle.closeTime(), current.price(),
                            giveback.divide(advance, MC));
                    activeLeg = null;
                    candidateStart = current;
                    // 本方向本根重新候选，不从旧起点立即复活；另一方向继续独立更新。
                    return snapshot(current.price(), false, completed);
                }
                return snapshot(current.price(), false, null);
            }

            boolean betterCandidate = candidateStart == null || (upward
                    ? current.price().compareTo(candidateStart.price()) < 0
                    : current.price().compareTo(candidateStart.price()) > 0);
            if (betterCandidate) {
                candidateStart = current;
            }
            BigDecimal advance = upward ? current.price().subtract(candidateStart.price())
                    : candidateStart.price().subtract(current.price());
            // 分母取本方向起点：上涨取低点，下跌取高点；启动幅度非零才进入回吐计算。
            if (advance.multiply(HUNDRED).compareTo(candidateStart.price().multiply(startMovePct)) >= 0) {
                activeLeg = new Leg(++nextNumber, direction, candidateStart, current, candle.closeTime());
                return snapshot(current.price(), true, null);
            }
            return snapshot(current.price(), false, null);
        }

        private BigDecimal advance() {
            return activeLeg.extreme().price().subtract(activeLeg.start().price()).abs();
        }

        private BigDecimal giveback(BigDecimal current) {
            return upward ? activeLeg.extreme().price().subtract(current)
                    : current.subtract(activeLeg.extreme().price());
        }

        private DirectionalSnapshot snapshot(BigDecimal current, boolean newlyConfirmed, CompletedLeg completed) {
            if (activeLeg == null) {
                return new DirectionalSnapshot(null, null, null, null, candidateStart, newlyConfirmed, completed);
            }
            BigDecimal movePct = current.subtract(activeLeg.start().price())
                    .divide(activeLeg.start().price(), MC).multiply(HUNDRED);
            BigDecimal maximumMovePct = advance().divide(activeLeg.start().price(), MC).multiply(HUNDRED);
            BigDecimal retracement = giveback(current).divide(advance(), MC);
            return new DirectionalSnapshot(activeLeg, movePct, maximumMovePct, retracement,
                    candidateStart, newlyConfirmed, completed);
        }
    }
}
