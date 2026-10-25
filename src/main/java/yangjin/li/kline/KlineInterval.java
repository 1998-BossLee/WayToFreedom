package yangjin.li.kline;

/** K 线周期接口常量；日、周、月边界统一使用北京时间。1m 是分钟，1M 是月。 */
public interface KlineInterval {
    String MINUTE_1 = "1m";
    String MINUTE_3 = "3m";
    String MINUTE_5 = "5m";
    String MINUTE_15 = "15m";
    String MINUTE_30 = "30m";

    String HOUR_1 = "1H";
    String HOUR_2 = "2H";
    String HOUR_4 = "4H";
    String HOUR_6 = "6H";
    String HOUR_12 = "12H";

    String DAY_1 = "1D";
    String WEEK_1 = "1W";
    String MONTH_1 = "1M";
}
