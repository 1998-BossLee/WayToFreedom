package yangjin.li.kline;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/** OKX 永续合约原始 K 线；openTime、closeTime 均为北京时间，confirmed 对应接口确认字段。 */
public record Kline(
        String symbol,
        String interval,
        LocalDateTime openTime,
        LocalDateTime closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volumeContracts,
        BigDecimal volumeBase,
        BigDecimal volumeQuote,
        boolean confirmed) {

    public Kline {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(openTime, "openTime");
        Objects.requireNonNull(closeTime, "closeTime");
        for (BigDecimal value : new BigDecimal[]{open, high, low, close}) {
            if (value == null || value.signum() <= 0) {
                throw new IllegalArgumentException("OHLC 必须为正数");
            }
        }
        for (BigDecimal value : new BigDecimal[]{volumeContracts, volumeBase, volumeQuote}) {
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException("成交量和成交额不能为负数");
            }
        }
        if (!closeTime.isAfter(openTime) || high.compareTo(low) < 0
                || high.compareTo(open.max(close)) < 0 || low.compareTo(open.min(close)) > 0) {
            throw new IllegalArgumentException("K 线时间或最高/最低价不合法");
        }
    }
}
