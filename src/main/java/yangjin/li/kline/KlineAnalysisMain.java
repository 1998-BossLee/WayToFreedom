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
import java.math.RoundingMode;
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

    /** 绝对趋平上限：一根 K 线间的 MA5 变化率绝对值最多为 0.05%。数值单位是百分比/根。 */
    public static final BigDecimal FLAT_THRESHOLD_PCT = new BigDecimal("0.05");

    /** 相对减速比例：当前移动速度的绝对值最多为前 3 根典型移动速度的 30%，即至少减速 70%。 */
    public static final BigDecimal SLOWDOWN_RATIO = new BigDecimal("0.30");

    /**
     * 首根目标识别 MA5/MA10 交叉需要前置 10 根，才能同时算出本根和上一根 MA10。
     * 原趋平计算仅需前置 8 根；即使 MA10 不足，也不会阻止原趋平判断。
     * 10 根不保证覆盖最近金叉；找不到时显示未知，可手动扩大查询的历史范围。
     */
    public static final int WARMUP_BARS = 10;

    private KlineAnalysisMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 0 && args.length != 4) {
            throw new IllegalArgumentException("参数：币种 周期 '开始时间' '结束时间'，时间格式 yyyy-MM-dd HH:mm");
        }
        String symbol = args.length == 0 ? ContractSymbol.ETH_USDT : args[0];
        String interval = args.length == 0 ? KlineInterval.HOUR_1 : args[1];
        LocalDateTime start = LocalDateTime.parse(args.length == 0 ? "2026-10-18 00:00" : args[2], TIME);
        LocalDateTime end = LocalDateTime.parse(args.length == 0 ? "2026-10-23 07:00" : args[3], TIME);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }

        // fetchKlines 只返回传入范围；main 多取 10 根，用于均线和交叉状态预热。
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

    /** 逐根打印指标；金叉、死叉、上涨趋平和下跌趋平均会单独打印提醒。 */
    public static void analyze(List<Kline> klines) {
        analyze(klines, LocalDateTime.MIN, FLAT_THRESHOLD_PCT, SLOWDOWN_RATIO);
    }

    /**
     * 按北京时间逐根回放，同时提醒金叉、死叉、上涨趋平和下跌趋平。
     * 只使用当前及之前已确认收盘的数据，不通过后续行情倒推信号。
     *
     * <p>先计算 MA5 及其百分比变化率：</p>
     * <pre>
     * MA5[t] = (C[t-4] + C[t-3] + C[t-2] + C[t-1] + C[t]) / 5
     * s[t] = (MA5[t] - MA5[t-1]) / MA5[t-1] * 100
     * </pre>
     * C 为收盘价，s 为 MA5 的百分比变化率；正数表示均线向上，负数表示均线向下。
     * s 衡量均线每根间隔的移动速度，不是收盘价本身的涨跌幅。
     *
     * <p>进入新的趋平候选段，必须同时满足：</p>
     * <ol>
     *   <li>当前没有尚未结束的趋平候选段；</li>
     *   <li>前三根 s 全部严格大于 0（上涨趋平），或全部严格小于 0（下跌趋平）；</li>
     *   <li>取前三根 s 的绝对值的中位数 B，作为此前的典型移动速度；</li>
     *   <li>|s[t]| 不大于 min(flatThresholdPct, B * slowdownRatio)。</li>
     * </ol>
     * 三根中存在零值、缺失值或正负混合时，不开始新段。
     * 默认参数为 0.05 和 0.30：绝对变化不超过 0.05%，同时不超过此前速度的 30%。
     * 一根已确认收盘的 K 线满足条件就提醒，不要求连续两根确认。
     *
     * <p>方向由进入时前三根的走势决定，不由当前变化率的正负决定。
     * 上涨后轻微下拐仍可算上涨趋平，下跌后轻微上拐仍可算下跌趋平。
     * 进入后固定该段阈值和方向，后续只要绝对变化仍在阈值内就算延续，不重复提醒。
     * 离开阈值结束本段，后续重新满足完整条件才能开始下一段。</p>
     *
     * <p>ETH 小时线示例（全部为北京时间，以开盘时间命名）：</p>
     * <pre>
     * 上涨趋平：2026-10-22 03:00，04:00 收盘确认。
     * 前三根 s 约为 +0.32525%、+0.36796%、+0.19334%。
     * B 约为 0.32525%，阈值 = min(0.05%, B * 30%) = 0.05%。
     * 当前 s 约为 -0.01868%，取绝对值后小于阈值，命中上涨趋平。
     *
     * 下跌趋平：2026-10-19 05:00，06:00 收盘确认。
     * 前三根 s 约为 -0.23309%、-0.32499%、-0.23846%。
     * B 约为 0.23846%，阈值 = min(0.05%, B * 30%) = 0.05%。
     * 当前 s 约为 +0.03647%，取绝对值后小于阈值，命中下跌趋平。
     * </pre>
     * 展示值经过四舍五入，实际判断使用 BigDecimal 计算值；阈值仍为调试参数。
     *
     * <p>MA5/MA10 金叉与死叉按独立的交叉状态判断，在各自确认根单独提醒一次。
     * 均线位置和交叉不会过滤或重置趋平；同一根若同时交叉和趋平，两类都提醒。
     * 金叉/死叉、上涨/下跌趋平分别统计；候选段编号仅统计显示范围内新出现的趋平段，
     * 不是完整一轮行情中的第几次趋平。在预热期已识别开始的段，只显示延续。</p>
     *
     * <p>趋平计算需要预热 8 根；main 为交叉预热 10 根，但不保证恢复更早的全部事件。
     * 未收盘或均线历史不足时不判断，并中断对应候选状态。
     * “收盘提醒时间”是历史回放中的可触发时间，程序仅输出控制台，不发送外部消息。</p>
     *
     * @param klines 同一合约、同一周期的连续 K 线，方法内按开盘时间升序排序
     * @param displayFrom 北京时间；此前的数据只用于预热，不打印提醒或计入提醒统计
     * @param flatThresholdPct 绝对变化率上限，单位为百分比；0.05 表示 0.05%
     * @param slowdownRatio 相对速度比例，0.30 表示前三根绝对变化率中位数的 30%
     */
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
        BigDecimal[] averages = new BigDecimal[data.size()]; // 每根对应的 MA5。
        MaCrossTracker crossTracker = new MaCrossTracker(); // 仅观察交叉，不控制原趋平状态。
        BigDecimal[] slopes = new BigDecimal[data.size()]; // 每根 MA5 相对上一根的百分比变化率。
        BigDecimal activeThreshold = null; // null 表示不在趋平段中；非 null 为当前段固定的阈值。
        String activeFlatDirection = null; // 本段进入时确定的上涨趋平/下跌趋平，延续期间不改变。
        int candidateNumber = 0; // 只累计显示范围内新开始的候选段。
        int goldenReminderCount = 0;
        int deadReminderCount = 0;
        int upwardFlatCount = 0;
        int downwardFlatCount = 0;
        boolean startedInWarmup = false; // 当前段若在预热时开始，显示时只标记延续。
        System.out.println("开盘时间（北京时间）    收盘价          MA5          MA10          MA5变化率"
                + "    均线位置 / 本根交叉 / 价格较金叉涨幅 / MA5较金叉涨幅 / 趋平状态");
        for (int i = 0; i < data.size(); i++) {
            Kline k = data.get(i);
            // MA5 使用包括本根在内的最近 5 个已确认收盘价。
            averages[i] = movingAverage(data, i, 5);
            BigDecimal ma10 = movingAverage(data, i, 10);
            MaCrossSnapshot cross = crossTracker.update(k, averages[i], ma10);
            slopes[i] = i == 0 || averages[i] == null || averages[i - 1] == null ? null
                    : changeRate(averages[i], averages[i - 1]);
            boolean visible = !k.openTime().isBefore(displayFrom);
            String status;
            boolean newCandidate = false;
            BigDecimal baseline = null;
            if (slopes[i] == null) {
                // 数据不足或窗口含未收盘记录时，无法可靠计算变化率，本根不触发提醒。
                activeThreshold = null;
                activeFlatDirection = null;
                status = !k.confirmed() ? "未收盘：本次不判断" : "MA5/变化率预热不足";
            } else {
                // 平缓看移动幅度，因此使用绝对值：小幅上涨、小幅下跌都允许。
                BigDecimal absoluteSlope = slopes[i].abs();
                // 离开固定阈值才结束本段；轻微正负摆动仍属于同一个趋平段。
                if (activeThreshold != null && absoluteSlope.compareTo(activeThreshold) > 0) {
                    activeThreshold = null;
                    activeFlatDirection = null;
                }
                if (activeThreshold == null && i >= 3) {
                    boolean previouslyRising = positive(slopes[i - 3]) && positive(slopes[i - 2])
                            && positive(slopes[i - 1]);
                    boolean previouslyFalling = negative(slopes[i - 3]) && negative(slopes[i - 2])
                            && negative(slopes[i - 1]);
                    if (previouslyRising || previouslyFalling) {
                        // 上涨、下跌统一比较速度大小，基准取三个变化率绝对值的中位数。
                        baseline = median(slopes[i - 3].abs(), slopes[i - 2].abs(), slopes[i - 1].abs());
                        BigDecimal threshold = flatThresholdPct.min(baseline.multiply(slowdownRatio));
                        if (absoluteSlope.compareTo(threshold) <= 0) {
                            activeThreshold = threshold;
                            activeFlatDirection = previouslyRising
                                    ? MaCrossState.FlatDirection.UP : MaCrossState.FlatDirection.DOWN;
                            newCandidate = true;
                            startedInWarmup = !visible;
                            if (visible) {
                                candidateNumber++;
                                if (MaCrossState.FlatDirection.UP.equals(activeFlatDirection)) {
                                    upwardFlatCount++;
                                } else {
                                    downwardFlatCount++;
                                }
                            }
                        }
                    }
                }
                status = activeThreshold == null ? (i < 8 ? "前三根变化率预热不足" : "未命中")
                        : startedInWarmup ? "预热区间已开始的趋平，延续（" + activeFlatDirection + "）"
                        : "候选段 #" + candidateNumber + (newCandidate ? " 开始" : " 延续")
                                + "（" + activeFlatDirection + "）";
            }
            if (visible) {
                System.out.printf(Locale.ROOT,
                        "%s  %12s  %12s  %12s  %12s  均线位置=%s；本根交叉=%s；"
                                + "价格较金叉涨幅=%s；MA5较金叉涨幅=%s；%s%n",
                        format(k.openTime()), k.close().toPlainString(), number(averages[i]), number(ma10),
                        percent(slopes[i]), cross.position(), cross.event(),
                        gain(cross.priceGainPct()), gain(cross.ma5GainPct()), status);
                // 交叉提醒独立于趋平；不因同根另有趋平提醒而跳过。
                if (MaCrossState.Event.GOLDEN.equals(cross.event())
                        || MaCrossState.Event.DEAD.equals(cross.event())) {
                    if (MaCrossState.Event.GOLDEN.equals(cross.event())) {
                        goldenReminderCount++;
                    } else {
                        deadReminderCount++;
                    }
                    System.out.printf(Locale.ROOT,
                            "[提醒] %s %s；提醒类型=%s；所属K线=%s；收盘提醒时间=%s；"
                                    + "MA5=%s；MA10=%s；均线位置=%s%n",
                            k.symbol(), k.interval(), cross.event(), format(k.openTime()), format(k.closeTime()),
                            number(averages[i]), number(ma10), cross.position());
                }
                // 仅在显示范围中新开始一段时提醒；延续行不重复提醒。
                if (newCandidate) {
                    // 03:00 小时线覆盖 [03:00, 04:00)，所以用其结束时间 04:00 标记可提醒时刻。
                    GoldenCross goldenCross = cross.goldenCross();
                    System.out.printf(Locale.ROOT,
                            "[提醒] %s %s 候选段 #%d；提醒类型=%s；所属K线=%s；收盘提醒时间=%s；"
                                    + "MA5=%s；变化率=%s；前3根绝对值中位数=%s；本段阈值=%s；"
                                    + "MA10=%s；均线位置=%s；本根交叉=%s；最近金叉所属K线=%s；"
                                    + "金叉确认时间=%s；金叉交点估算值=%s；价格较金叉涨幅=%s；MA5较金叉涨幅=%s%n",
                            k.symbol(), k.interval(), candidateNumber, activeFlatDirection,
                            format(k.openTime()), format(k.closeTime()),
                            number(averages[i]), percent(slopes[i]), percent(baseline), percent(activeThreshold),
                            number(ma10), cross.position(), cross.event(),
                            goldenCross == null ? "未知" : format(goldenCross.openTime()),
                            goldenCross == null ? "未知" : format(goldenCross.confirmedTime()),
                            goldenCross == null ? "未知" : estimatedNumber(goldenCross.value()),
                            gain(cross.priceGainPct()), gain(cross.ma5GainPct()));
                }
            }
        }
        System.out.printf("分析完成：输出 %d 根，本范围新增趋平候选段 %d 个。候选段不是完整上涨轮次计数。%n",
                data.stream().filter(k -> !k.openTime().isBefore(displayFrom)).count(), candidateNumber);
        System.out.printf("提醒统计：金叉 %d 次，死叉 %d 次，上涨趋平 %d 次，下跌趋平 %d 次。%n",
                goldenReminderCount, deadReminderCount, upwardFlatCount, downwardFlatCount);
    }

    /** 一次已确认金叉的 K 线开始/结束时间，以及两条均线折线交点的估算数值。 */
    record GoldenCross(LocalDateTime openTime, LocalDateTime confirmedTime, BigDecimal value) {
    }

    /** 本根观察结果；涨幅的基准统一为最近金叉的交点估算值，未知时为 null。 */
    record MaCrossSnapshot(String position, String event, GoldenCross goldenCross,
                           BigDecimal priceGainPct, BigDecimal ma5GainPct) {
    }

    /**
     * 逐根跟踪 MA5/MA10；这是独立的观察状态，不参与趋平提醒条件。
     *
     * <p>先看 D = MA5 - MA10 的符号。D > 0 表示 MA5 在上方，D < 0 表示在下方。
     * 只有有效方向由负变正才记金叉，由正变负才记死叉。第一根已经在上方时，
     * 只知道位置，不能假设这一根发生过金叉。D = 0 时先记重合并保留之前的方向，
     * 因此“上方 -> 重合 -> 上方”不会被重复记成一次新金叉。</p>
     *
     * <p>金叉处两条均线的数值采用相邻两个已收盘采样点的线性插值：</p>
     * <pre>
     * q = -D[上一根] / (D[本根] - D[上一根])
     * 交点值 = MA5[上一根] + q * (MA5[本根] - MA5[上一根])
     * </pre>
     * 若上一根两线相等，q = 0，使用该等值点。若连续多根相等，使用紧邻确认根的最后等值点。
     * 这是均线折线交点的估算值，不是实际成交价格，也不代表能知道盘中的真实交叉时刻。
     * 金叉仍在本根收盘后确认，openTime/confirmedTime 记录的都是北京时间。
     *
     * <p>价格涨幅 = (当前收盘价 / 交点值 - 1) * 100；
     * MA5 涨幅 = (当前 MA5 / 交点值 - 1) * 100，两者分别打印，允许负值。
     * 死叉不清空最近金叉的历史参照，新金叉会替换参照；不据此推定“第几轮上涨”。
     * 若均线历史不足或遇到未收盘记录，则重置交叉历史，避免跨过未知区间猜测金叉。</p>
     */
    static final class MaCrossTracker {
        private BigDecimal previousMa5;
        private BigDecimal previousDifference;
        private int lastNonZeroDirection;
        private GoldenCross lastGoldenCross;

        MaCrossSnapshot update(Kline kline, BigDecimal ma5, BigDecimal ma10) {
            if (!kline.confirmed() || ma5 == null || ma10 == null) {
                previousMa5 = null;
                previousDifference = null;
                lastNonZeroDirection = 0;
                lastGoldenCross = null;
                return new MaCrossSnapshot(MaCrossState.Position.UNKNOWN, MaCrossState.Event.UNKNOWN,
                        null, null, null);
            }
            BigDecimal difference = ma5.subtract(ma10);
            int direction = difference.signum();
            String position;
            if (direction > 0) {
                position = MaCrossState.Position.ABOVE;
            } else if (direction < 0) {
                position = MaCrossState.Position.BELOW;
            } else {
                position = MaCrossState.Position.EQUAL;
            }
            String event = MaCrossState.Event.NONE;
            if (direction != 0) {
                if (lastNonZeroDirection != 0 && direction != lastNonZeroDirection
                        && previousDifference != null) {
                    if (direction > 0) {
                        event = MaCrossState.Event.GOLDEN;
                        BigDecimal fraction = previousDifference.negate()
                                .divide(difference.subtract(previousDifference), MC);
                        BigDecimal crossValue = previousMa5.add(
                                ma5.subtract(previousMa5).multiply(fraction, MC), MC);
                        lastGoldenCross = new GoldenCross(kline.openTime(), kline.closeTime(), crossValue);
                    } else {
                        event = MaCrossState.Event.DEAD;
                    }
                }
                lastNonZeroDirection = direction;
            }
            // 包括重合点在内，每根都更新采样值，确保插值只使用紧邻的两根数据。
            previousMa5 = ma5;
            previousDifference = difference;
            BigDecimal priceGain = null;
            BigDecimal ma5Gain = null;
            if (lastGoldenCross != null) {
                priceGain = changeRate(kline.close(), lastGoldenCross.value());
                ma5Gain = changeRate(ma5, lastGoldenCross.value());
            }
            return new MaCrossSnapshot(position, event, lastGoldenCross, priceGain, ma5Gain);
        }
    }

    /** 计算截至 index（含本根）的 period 根收盘价均值；数量不足或包含未收盘记录时返回 null。 */
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

    /**
     * 均线变化率 = (本根均线 - 上根均线) / 上根均线 * 100。
     * 返回数值的单位为百分比/根，例如返回 0.05 表示 +0.05%；负数表示均线向下。
     */
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

    private static boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    /** 三个变化率从小到大排序，返回中间那个；本方法不对它们求平均。 */
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

    /** 交点显示保留至多 8 位小数；实际涨幅计算仍使用未舍入的交点值。 */
    private static String estimatedNumber(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String gain(BigDecimal value) {
        return value == null ? "未知" : percent(value);
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
