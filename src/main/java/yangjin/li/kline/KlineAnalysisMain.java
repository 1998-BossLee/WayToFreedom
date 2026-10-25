package yangjin.li.kline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** 即时获取 OKX 行情并打印分析结果。直接改 main 中的参数即可调试。 */
public final class KlineAnalysisMain {
    public static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build();

    // 单位：百分比/根。0.05 表示 0.05%，不是 5%。以下仅为候选阈值。
    public static final BigDecimal FLAT_THRESHOLD_PCT = new BigDecimal("0.05");
    public static final BigDecimal SLOWDOWN_RATIO = new BigDecimal("0.30");
    public static final int WARMUP_BARS = 8;

    private KlineAnalysisMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 0 && args.length != 4) {
            throw new IllegalArgumentException("参数：币种 周期 '开始时间' '结束时间'，时间格式 yyyy-MM-dd HH:mm");
        }
        String symbol = args.length == 0 ? ContractSymbol.ETH_USDT : args[0];
        String interval = args.length == 0 ? KlineInterval.HOUR_1 : args[1];
        LocalDateTime start = LocalDateTime.parse(args.length == 0 ? "2026-10-20 17:00" : args[2], TIME);
        LocalDateTime end = LocalDateTime.parse(args.length == 0 ? "2026-10-23 07:00" : args[3], TIME);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }

        // fetchKlines 本身只返回传入范围；main 多取 8 根，让首根目标也能完成判断。
        Bar bar = Bar.parse(interval);
        LocalDateTime warmupStart = bar.shift(start, -WARMUP_BARS);
        System.out.printf("即时读取 %s %s，北京时间 %s 至 %s（按开盘时间，首尾包含）%n",
                normalizeSymbol(symbol), bar.apiValue(), start.format(TIME), end.format(TIME));
        List<Kline> klines = fetchKlines(symbol, interval, warmupStart, end);
        analyze(klines, start, FLAT_THRESHOLD_PCT, SLOWDOWN_RATIO);
    }

    /** 输入北京时间的 K 线开盘范围，首尾包含；返回该范围内去重、正序的原始数据。 */
    public static List<Kline> fetchKlines(String symbol, String interval,
                                          LocalDateTime start, LocalDateTime end)
            throws IOException, InterruptedException {
        String instId = normalizeSymbol(symbol);
        Bar bar = Bar.parse(interval);
        return fetchKlines(instId, bar, start, end, after -> {
            String baseUrl = System.getProperty("okx.baseUrl", "https://www.okx.com");
            URI base = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                    || base.getRawUserInfo() != null || base.getRawQuery() != null
                    || base.getRawFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
                throw new IllegalArgumentException("okx.baseUrl 必须是有效的 HTTPS 服务地址");
            }
            HttpUrl url = HttpUrl.get(base).newBuilder()
                    .encodedPath("/api/v5/market/history-candles")
                    .addQueryParameter("instId", instId)
                    .addQueryParameter("bar", bar.apiValue())
                    .addQueryParameter("limit", "100")
                    .addQueryParameter("after", Long.toString(after))
                    .build();
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = HTTP.newCall(request).execute()) {
                if (response.code() != 200) {
                    throw new IOException("OKX HTTP " + response.code() + "，地址 " + base.getHost()
                            + "；请检查网络或通过 -Dokx.baseUrl 配置可访问的 OKX 地址");
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    throw new IOException("OKX 返回空响应体");
                }
                return parsePage(responseBody.string(), instId, bar);
            }
        });
    }

    @FunctionalInterface
    interface PageFetcher {
        List<Kline> fetch(long after) throws IOException, InterruptedException;
    }

    static List<Kline> fetchKlines(String instId, Bar bar, LocalDateTime start,
                                   LocalDateTime end, PageFetcher fetcher)
            throws IOException, InterruptedException {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
        LocalDateTime from = start;
        LocalDateTime to = end;
        if (!bar.aligned(from) || !bar.aligned(to)) {
            throw new IllegalArgumentException("开始和结束时间必须对齐 " + bar.apiValue() + " 的官方开盘边界");
        }
        TreeMap<LocalDateTime, Kline> result = new TreeMap<>();
        long after = Math.addExact(toEpochMillis(to), 1); // OKX after 为排他上界。
        while (true) {
            List<Kline> page = fetcher.fetch(after);
            if (page.isEmpty()) {
                break;
            }
            long earliest = Long.MAX_VALUE;
            for (Kline k : page) {
                earliest = Math.min(earliest, toEpochMillis(k.openTime()));
                if (!k.openTime().isBefore(from) && !k.openTime().isAfter(to)) {
                    result.putIfAbsent(k.openTime(), k);
                }
            }
            if (earliest >= after) {
                throw new IOException("OKX 分页游标没有前移，停止请求以避免死循环");
            }
            if (earliest <= toEpochMillis(from)) {
                break;
            }
            after = earliest;
            Thread.sleep(150);
        }
        // 缺数据就报错，避免把不连续的 K 线直接用于 MA5。
        LocalDateTime expected = from;
        for (Kline k : result.values()) {
            if (!k.openTime().equals(expected)) {
                throw new IOException("K 线缺失：" + format(expected));
            }
            expected = bar.shift(expected, 1);
        }
        if (!expected.isAfter(to)) {
            throw new IOException("K 线缺失或范围尚不可获取：" + format(expected));
        }
        return new ArrayList<>(result.values());
    }

    static List<Kline> parsePage(String body, String instId, Bar bar) throws IOException {
        JsonNode root = JSON.readTree(body);
        if (root == null || !"0".equals(root.path("code").asText())) {
            throw new IOException("OKX 业务错误：" + (root == null ? "空响应" : root.path("code").asText()
                    + " " + root.path("msg").asText()));
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new IOException("OKX data 不是数组");
        }
        List<Kline> result = new ArrayList<>();
        for (JsonNode row : data) {
            try {
                if (!row.isArray() || row.size() != 9) {
                    throw new IllegalArgumentException("K 线应包含 9 个字段");
                }
                for (JsonNode value : row) {
                    if (!value.isTextual() || value.asText().isBlank()) {
                        throw new IllegalArgumentException("字段必须为非空字符串");
                    }
                }
                LocalDateTime openTime = fromEpochMillis(Long.parseLong(row.get(0).asText()));
                if (!bar.aligned(openTime)) {
                    throw new IllegalArgumentException("K 线开始时间未对齐周期");
                }
                String confirm = row.get(8).asText();
                if (!confirm.equals("0") && !confirm.equals("1")) {
                    throw new IllegalArgumentException("未知确认状态");
                }
                result.add(new Kline(instId, bar.apiValue(), openTime, bar.shift(openTime, 1),
                        decimal(row, 1), decimal(row, 2), decimal(row, 3), decimal(row, 4),
                        decimal(row, 5), decimal(row, 6), decimal(row, 7), confirm.equals("1")));
            } catch (RuntimeException e) {
                throw new IOException("OKX K 线格式不合法：" + row, e);
            }
        }
        return result;
    }

    /** 逐根打印 MA5、变化率和趋平候选；不足的前置数据会明确标注。 */
    public static void analyze(List<Kline> klines) {
        analyze(klines, LocalDateTime.MIN, FLAT_THRESHOLD_PCT, SLOWDOWN_RATIO);
    }

    /** displayFrom 之前的数据只用于预热；阈值可在 main 或调用处调整。 */
    public static void analyze(List<Kline> klines, LocalDateTime displayFrom,
                               BigDecimal flatThresholdPct, BigDecimal slowdownRatio) {
        if (klines == null || displayFrom == null || flatThresholdPct == null || slowdownRatio == null
                || flatThresholdPct.signum() <= 0 || slowdownRatio.signum() <= 0
                || slowdownRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("请提供 K 线及合法阈值：平缓阈值 > 0，收缩比例在 (0,1] 内");
        }
        List<Kline> data = new ArrayList<>(klines);
        data.sort(Comparator.comparing(Kline::openTime));
        validateSequence(data);
        BigDecimal[] averages = new BigDecimal[data.size()];
        BigDecimal[] slopes = new BigDecimal[data.size()];
        BigDecimal activeThreshold = null;
        int candidateNumber = 0;
        boolean startedInWarmup = false;
        System.out.println("开盘时间（北京时间）    收盘价          MA5          MA5变化率       状态");
        for (int i = 0; i < data.size(); i++) {
            Kline k = data.get(i);
            averages[i] = movingAverage(data, i, 5);
            slopes[i] = i == 0 || averages[i] == null || averages[i - 1] == null ? null
                    : changeRate(averages[i], averages[i - 1]);
            boolean visible = !k.openTime().isBefore(displayFrom);
            String status;
            boolean newCandidate = false;
            BigDecimal baseline = null;
            if (slopes[i] == null) {
                activeThreshold = null;
                status = !k.confirmed() ? "未收盘：本次不判断" : "MA5/变化率预热不足";
            } else {
                BigDecimal absoluteSlope = slopes[i].abs();
                if (activeThreshold != null && absoluteSlope.compareTo(activeThreshold) > 0) {
                    activeThreshold = null;
                }
                if (activeThreshold == null && i >= 3 && positive(slopes[i - 3])
                        && positive(slopes[i - 2]) && positive(slopes[i - 1])) {
                    baseline = median(slopes[i - 3], slopes[i - 2], slopes[i - 1]);
                    BigDecimal threshold = flatThresholdPct.min(baseline.multiply(slowdownRatio));
                    if (absoluteSlope.compareTo(threshold) <= 0) {
                        activeThreshold = threshold;
                        newCandidate = true;
                        startedInWarmup = !visible;
                        if (visible) {
                            candidateNumber++;
                        }
                    }
                }
                status = activeThreshold == null ? (i < 8 ? "前三根变化率预热不足" : "未命中")
                        : startedInWarmup ? "预热区间已开始的趋平，延续"
                        : "候选段 #" + candidateNumber + (newCandidate ? " 开始" : " 延续");
            }
            if (visible) {
                System.out.printf(Locale.ROOT, "%s  %12s  %12s  %12s  %s%n", format(k.openTime()),
                        k.close().toPlainString(), number(averages[i]), percent(slopes[i]), status);
                if (newCandidate) {
                    System.out.printf(Locale.ROOT,
                            "[提醒] %s %s 候选段 #%d；所属K线=%s；收盘提醒时间=%s；"
                                    + "MA5=%s；变化率=%s；前3根中位数=%s；本段阈值=%s%n",
                            k.symbol(), k.interval(), candidateNumber, format(k.openTime()), format(k.closeTime()),
                            number(averages[i]), percent(slopes[i]), percent(baseline), percent(activeThreshold));
                }
            }
        }
        System.out.printf("分析完成：输出 %d 根，本范围新增趋平候选段 %d 个。候选段不是完整上涨轮次计数。%n",
                data.stream().filter(k -> !k.openTime().isBefore(displayFrom)).count(), candidateNumber);
    }

    public static BigDecimal movingAverage(List<Kline> data, int index, int period) {
        if (period <= 0 || index < 0 || index >= data.size()) {
            throw new IllegalArgumentException("均线参数不合法");
        }
        if (index + 1 < period) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = index - period + 1; j <= index; j++) {
            if (!data.get(j).confirmed()) {
                return null;
            }
            sum = sum.add(data.get(j).close());
        }
        return sum.divide(BigDecimal.valueOf(period), MC);
    }

    /** 输出单位为百分比/根；调用时应先确保上一根均线为正。 */
    public static BigDecimal changeRate(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() <= 0) {
            throw new IllegalArgumentException("上一根 MA 必须为正数");
        }
        return current.subtract(previous).divide(previous, MC).multiply(HUNDRED);
    }

    private static void validateSequence(List<Kline> data) {
        if (data.isEmpty()) {
            return;
        }
        Kline first = data.get(0);
        Bar bar = Bar.parse(first.interval());
        for (int i = 0; i < data.size(); i++) {
            Kline k = data.get(i);
            if (!k.symbol().equals(first.symbol()) || !k.interval().equals(first.interval())
                    || !bar.aligned(k.openTime()) || !bar.shift(k.openTime(), 1).equals(k.closeTime())
                    || (i > 0 && !data.get(i - 1).closeTime().equals(k.openTime()))) {
                throw new IllegalArgumentException("K 线必须属于同一合约/周期，且连续、不重复：" + format(k.openTime()));
            }
        }
    }

    static String normalizeSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("币种不能为空");
        }
        String value = symbol.trim().toUpperCase(Locale.ROOT);
        if (value.matches("[A-Z0-9]+USDT") && !value.contains("-")) {
            value = value.substring(0, value.length() - 4) + "-USDT-SWAP";
        } else if (value.matches("[A-Z0-9]+-USDT")) {
            value += "-SWAP";
        } else if (value.matches("[A-Z0-9]+") && !value.equals("USDT")) {
            value += "-USDT-SWAP";
        }
        if (!value.matches("[A-Z0-9]+-USDT-SWAP")) {
            throw new IllegalArgumentException("只接受 USDT 永续合约，例如 ETH、ETHUSDT、ETH-USDT-SWAP");
        }
        return value;
    }

    private static BigDecimal decimal(JsonNode row, int index) {
        return new BigDecimal(row.get(index).asText());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal median(BigDecimal a, BigDecimal b, BigDecimal c) {
        List<BigDecimal> sorted = new ArrayList<>(List.of(a, b, c));
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(1);
    }

    private static String number(BigDecimal value) {
        return value == null ? "--" : value.stripTrailingZeros().toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value == null ? "--" : String.format(Locale.ROOT, "%+.5f%%", value);
    }

    private static String format(LocalDateTime value) {
        return TIME.format(value);
    }

    private static long toEpochMillis(LocalDateTime time) {
        return time.atZone(BEIJING_ZONE).toInstant().toEpochMilli();
    }

    private static LocalDateTime fromEpochMillis(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), BEIJING_ZONE);
    }

    /** 周期参数来自 KlineInterval；所有日、周、月边界均使用北京时间。 */
    record Bar(String apiValue, int amount, ChronoUnit unit) {
        static Bar parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("周期不能为空");
            }
            String input = raw.trim().replace("min", "m");
            return switch (input) {
                case KlineInterval.MINUTE_1 -> new Bar(KlineInterval.MINUTE_1, 1, ChronoUnit.MINUTES);
                case KlineInterval.MINUTE_3 -> new Bar(KlineInterval.MINUTE_3, 3, ChronoUnit.MINUTES);
                case KlineInterval.MINUTE_5 -> new Bar(KlineInterval.MINUTE_5, 5, ChronoUnit.MINUTES);
                case KlineInterval.MINUTE_15 -> new Bar(KlineInterval.MINUTE_15, 15, ChronoUnit.MINUTES);
                case KlineInterval.MINUTE_30 -> new Bar(KlineInterval.MINUTE_30, 30, ChronoUnit.MINUTES);
                case KlineInterval.HOUR_1, "1h" -> new Bar(KlineInterval.HOUR_1, 1, ChronoUnit.HOURS);
                case KlineInterval.HOUR_2, "2h" -> new Bar(KlineInterval.HOUR_2, 2, ChronoUnit.HOURS);
                case KlineInterval.HOUR_4, "4h" -> new Bar(KlineInterval.HOUR_4, 4, ChronoUnit.HOURS);
                case KlineInterval.HOUR_6, "6h" -> new Bar(KlineInterval.HOUR_6, 6, ChronoUnit.HOURS);
                case KlineInterval.HOUR_12, "12h" -> new Bar(KlineInterval.HOUR_12, 12, ChronoUnit.HOURS);
                case KlineInterval.DAY_1, "1d" -> new Bar(KlineInterval.DAY_1, 1, ChronoUnit.DAYS);
                case KlineInterval.WEEK_1, "1w" -> new Bar(KlineInterval.WEEK_1, 1, ChronoUnit.WEEKS);
                case KlineInterval.MONTH_1 -> new Bar(KlineInterval.MONTH_1, 1, ChronoUnit.MONTHS);
                default -> throw new IllegalArgumentException("不支持的周期：" + raw
                        + "；请使用 KlineInterval 中的北京时间周期常量");
            };
        }

        LocalDateTime shift(LocalDateTime time, long count) {
            return time.plus(Math.multiplyExact(count, amount), unit);
        }

        boolean aligned(LocalDateTime time) {
            if (time.getSecond() != 0 || time.getNano() != 0) {
                return false;
            }
            if (unit == ChronoUnit.MINUTES) {
                return time.getMinute() % amount == 0;
            }
            if (time.getMinute() != 0) {
                return false;
            }
            if (unit == ChronoUnit.HOURS) {
                return time.getHour() % amount == 0;
            }
            if (time.getHour() != 0) {
                return false;
            }
            if (unit == ChronoUnit.DAYS) {
                return true;
            }
            if (unit == ChronoUnit.WEEKS) {
                return time.getDayOfWeek() == DayOfWeek.MONDAY;
            }
            return unit == ChronoUnit.MONTHS && time.getDayOfMonth() == 1;
        }
    }
}
