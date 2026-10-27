package yangjin.li.kline;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;

/**
 * 按已确认收盘价识别行情段的调试版；不使用盘中最高/最低价或未来数据。
 *
 * <p>待确认时维护候选低点和高点。从候选低点反弹达到启动幅度，确认上涨；
 * 从候选高点下跌达到启动幅度，确认下跌。起点属于过去的 K 线，但只能在确认根收盘后知道。</p>
 *
 * <p>上涨段保留低点，持续更新最高收盘价；回吐达到本轮累计上涨幅度的指定比例时结束。
 * 下跌段对称处理。结束后以刚才的极值作为反方向候选起点，再检查启动幅度；
 * 若该根已经满足反方向启动条件，可在同一根收盘时结束旧段、确认新段。</p>
 *
 * <p>例：100 涨到 110，回落到 105，回吐 (110-105)/(110-100)=50%。
 * 这里的 50% 指回吐本轮涨幅的一半，不是价格从高点下跌 50%。
 * 全部时间均为北京时间，不接收或处理 MA 金叉/死叉，因此均线交叉不会重置本轮起点。</p>
 */
public final class TrendLegTracker {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final BigDecimal startMovePct;
    private final BigDecimal retraceRatio;
    private Point candidateLow;
    private Point candidateHigh;
    private Leg activeLeg;
    private int nextNumber;
    private LocalDateTime previousOpenTime;

    /** startMovePct 单位为百分比，例如 2 表示 2%；retraceRatio 为比例，例如 0.5 表示 50%。 */
    public TrendLegTracker(BigDecimal startMovePct, BigDecimal retraceRatio) {
        if (startMovePct == null || startMovePct.signum() <= 0
                || retraceRatio == null || retraceRatio.signum() <= 0
                || retraceRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("启动幅度必须 > 0，回吐比例必须在 (0,1] 内");
        }
        this.startMovePct = startMovePct;
        this.retraceRatio = retraceRatio;
    }

    /** 一个已观测到的收盘价及其所属 K 线开始时间。 */
    public record Point(LocalDateTime openTime, BigDecimal price) {
    }

    /** confirmedTime 是确认启动根的结束时间，和起点所属 K 线的开始时间是两个概念。 */
    public record Leg(int number, String direction, Point start, Point extreme, LocalDateTime confirmedTime) {
    }

    /** 旧段在本根收盘时结束；回吐比例可能因价格跳过门槛而超过设定值。 */
    public record CompletedLeg(Leg leg, LocalDateTime endedTime, BigDecimal endPrice, BigDecimal retracementRatio) {
    }

    /**
     * movePct 为当前收盘价相对段起点的有符号变化率；maximumMovePct 为最大推进幅度的绝对值。
     * retracementRatio 为比例，打印百分比时再乘 100。无有效行情段时上述三个值为 null。
     * candidateLow/high 用于解释待确认过程，编号只在本次回放中有效。
     */
    public record Snapshot(Leg leg, BigDecimal movePct, BigDecimal maximumMovePct, BigDecimal retracementRatio,
                           Point candidateLow, Point candidateHigh, boolean newlyConfirmed, CompletedLeg completedLeg) {
    }

    public Snapshot update(Kline candle) {
        if (candle == null) {
            throw new IllegalArgumentException("K 线不能为空");
        }
        if (previousOpenTime != null && !candle.openTime().isAfter(previousOpenTime)) {
            throw new IllegalArgumentException("行情段必须按 K 线时间严格递增处理");
        }
        previousOpenTime = candle.openTime();
        if (!candle.confirmed()) {
            // 未确认区间可能隐藏转折，清除历史后重新确认，不跨过未知区间猜测走势。
            activeLeg = null;
            candidateLow = null;
            candidateHigh = null;
            return snapshot(candle.close(), false, null);
        }

        Point current = new Point(candle.openTime(), candle.close());
        CompletedLeg completed = null;
        boolean newlyConfirmed = false;
        if (activeLeg != null) {
            updateExtreme(current);
            BigDecimal advance = advance(activeLeg);
            BigDecimal giveback = giveback(activeLeg, current.price());
            // 比较价格差，避免先除法四舍五入后误判恰好 50% 的边界。
            if (giveback.compareTo(advance.multiply(retraceRatio)) >= 0) {
                completed = new CompletedLeg(activeLeg, candle.closeTime(), current.price(),
                        giveback.divide(advance, MC));
                if (TrendDirection.UP.equals(activeLeg.direction())) {
                    candidateHigh = activeLeg.extreme();
                    candidateLow = current;
                } else {
                    candidateLow = activeLeg.extreme();
                    candidateHigh = current;
                }
                activeLeg = null;
            }
        }
        if (activeLeg == null) {
            updateCandidates(current);
            if (upwardMoveConfirmed(current.price())) {
                activeLeg = new Leg(++nextNumber, TrendDirection.UP, candidateLow, current, candle.closeTime());
                newlyConfirmed = true;
            } else if (downwardMoveConfirmed(current.price())) {
                activeLeg = new Leg(++nextNumber, TrendDirection.DOWN, candidateHigh, current, candle.closeTime());
                newlyConfirmed = true;
            }
        }
        return snapshot(current.price(), newlyConfirmed, completed);
    }

    private void updateCandidates(Point current) {
        if (candidateLow == null || current.price().compareTo(candidateLow.price()) < 0) {
            candidateLow = current;
        }
        if (candidateHigh == null || current.price().compareTo(candidateHigh.price()) > 0) {
            candidateHigh = current;
        }
    }

    private boolean upwardMoveConfirmed(BigDecimal current) {
        return current.subtract(candidateLow.price()).multiply(HUNDRED)
                .compareTo(candidateLow.price().multiply(startMovePct)) >= 0;
    }

    private boolean downwardMoveConfirmed(BigDecimal current) {
        return candidateHigh.price().subtract(current).multiply(HUNDRED)
                .compareTo(candidateHigh.price().multiply(startMovePct)) >= 0;
    }

    private void updateExtreme(Point current) {
        boolean newExtreme = TrendDirection.UP.equals(activeLeg.direction())
                ? current.price().compareTo(activeLeg.extreme().price()) > 0
                : current.price().compareTo(activeLeg.extreme().price()) < 0;
        if (newExtreme) {
            activeLeg = new Leg(activeLeg.number(), activeLeg.direction(), activeLeg.start(), current,
                    activeLeg.confirmedTime());
        }
    }

    private Snapshot snapshot(BigDecimal current, boolean newlyConfirmed, CompletedLeg completed) {
        if (activeLeg == null) {
            return new Snapshot(null, null, null, null, candidateLow, candidateHigh, newlyConfirmed, completed);
        }
        BigDecimal movePct = current.subtract(activeLeg.start().price())
                .divide(activeLeg.start().price(), MC).multiply(HUNDRED);
        BigDecimal maximumMovePct = advance(activeLeg).divide(activeLeg.start().price(), MC).multiply(HUNDRED);
        BigDecimal retracement = giveback(activeLeg, current).divide(advance(activeLeg), MC);
        return new Snapshot(activeLeg, movePct, maximumMovePct, retracement,
                candidateLow, candidateHigh, newlyConfirmed, completed);
    }

    private static BigDecimal advance(Leg leg) {
        return leg.extreme().price().subtract(leg.start().price()).abs();
    }

    private static BigDecimal giveback(Leg leg, BigDecimal current) {
        return TrendDirection.UP.equals(leg.direction())
                ? leg.extreme().price().subtract(current) : current.subtract(leg.extreme().price());
    }
}
