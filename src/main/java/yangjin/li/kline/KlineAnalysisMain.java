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

    /** 从候选低点反弹/候选高点下跌达到 2%，才确认一轮有效行情。 */
    public static final BigDecimal MIN_TREND_MOVE_PCT = new BigDecimal("2.0");

    /** 回吐本轮累计推进幅度的 50% 时结束，0.50 是比例，不是价格跌幅。 */
    public static final BigDecimal TREND_RETRACE_RATIO = new BigDecimal("0.50");

    /**
     * 首根目标识别 MA5/MA10 交叉需要前置 10 根，才能同时算出本根和上一根 MA10。
     * 几何趋平计算仅需前置 8 根；行情段还取决于已获取的价格历史。
     * 10 根不保证覆盖真实走势起点；历史不同可能得到不同分段，需要时可提前开始时间。
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
        System.out.printf("行情规则：从起点走出 %s 确认启动；回吐本轮幅度 %s 时结束；均线交叉不重置起点。%n",
                percent(MIN_TREND_MOVE_PCT), percent(TREND_RETRACE_RATIO.multiply(HUNDRED)));
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

    /** 使用默认行情分段参数：2% 确认启动，回吐本轮幅度 50% 时结束。 */
    public static void analyze(List<Kline> klines, LocalDateTime displayFrom,
                               BigDecimal flatThresholdPct, BigDecimal slowdownRatio) {
        analyze(klines, displayFrom, flatThresholdPct, slowdownRatio, MIN_TREND_MOVE_PCT, TREND_RETRACE_RATIO);
    }

    /** minimumTrendMovePct = 0 可暂时关闭行情过滤，单独检查原来的几何趋平算法。 */
    public static void analyze(List<Kline> klines, LocalDateTime displayFrom,
                               BigDecimal flatThresholdPct, BigDecimal slowdownRatio, BigDecimal minimumTrendMovePct) {
        analyze(klines, displayFrom, flatThresholdPct, slowdownRatio, minimumTrendMovePct, TREND_RETRACE_RATIO);
    }

    /**
     * 趋平形态和行情段分别计算，只使用当前及此前已经确认收盘的数据。
     *
     * <pre>
     * MA5[t] = 最近五根已确认收盘价的平均值
     * s[t] = (MA5[t] - MA5[t-1]) / MA5[t-1] * 100
     * 前三根 s 全正 -> 上涨趋平；全负 -> 下跌趋平；混合、零或不足则不新进入。
     * B = 前三根 |s| 的中位数
     * 几何趋平 = |s[t]| <= min(flatThresholdPct, B * slowdownRatio)
     * </pre>
     * 进入后固定该段方向、B 和阈值，直到离开阈值或对应行情段结束。
     * 小幅正负切换仍为延续，不重复提醒；同段第一次满足行情过滤条件才提醒。
     *
     * <p>默认先从候选收盘低点/高点走出 2%，确认上涨/下跌段，起点保留在过去的极值处，
     * 确认时间记录在当前 K 线结束时。金叉、死叉不改变这一段的起点或极值。
     * 上涨段从最高收盘价回吐累计涨幅的 50% 才结束；下跌段从最低收盘价反弹收复累计跌幅的 50% 才结束。
     * 计算详见 TrendLegTracker，全部基于收盘价而非盘中最高/最低价。</p>
     *
     * <p>上涨趋平需要当前为已确认上涨段，下跌趋平需要当前为已确认下跌段。
     * 不再要求当前价格相对最新金叉/死叉交点走出 2%，也不在回撤后重复检查当前段涨幅是否仍 >= 2%；
     * 启动时已经确认有效推进，之后由 50% 回吐条件管理该段是否结束。
     * minimumTrendMovePct = 0 关闭行情过滤，行情观察本身仍按默认 2% 计算，方便隔离调试几何条件。</p>
     *
     * <p>金叉和死叉仍各自单独提醒，均线位置不作为过滤条件。
     * 同一根可能结束旧行情、确认新行情，再分别满足交叉与趋平提醒。
     * 原始候选编号包含被行情过滤的形态；四类提醒统计只计显示范围内实际打印的提醒。
     * 预热期间已满足完整条件的趋平进入显示范围不补发；预热未满足、后来首次满足时允许提醒。
     * 未确认 K 线重置对应状态，不跨过未知区间猜测行情；本程序只输出控制台。</p>
     *
     * <p>本版用于手动复盘：尚未单独识别横盘平台、突破或成交量，也没有接入 ATR。
     * 金死叉不重置行情起点，并不等于所有横盘期间的趋平都已被排除。</p>
     *
     * @param minimumTrendMovePct 启动确认幅度，单位百分比；0 仅用于关闭行情过滤
     * @param retraceRatio 结束行情的回吐比例，取值 (0,1]；0.50 表示回吐累计幅度的一半
     */
    public static void analyze(List<Kline> klines, LocalDateTime displayFrom,
                               BigDecimal flatThresholdPct, BigDecimal slowdownRatio,
                               BigDecimal minimumTrendMovePct, BigDecimal retraceRatio) {
        if (klines == null || displayFrom == null || flatThresholdPct == null || slowdownRatio == null
                || flatThresholdPct.signum() <= 0 || slowdownRatio.signum() <= 0
                || slowdownRatio.compareTo(BigDecimal.ONE) > 0 || minimumTrendMovePct == null
                || minimumTrendMovePct.signum() < 0 || retraceRatio == null || retraceRatio.signum() <= 0
                || retraceRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("请提供合法阈值：平缓阈值 > 0，收缩比例在 (0,1] 内，启动幅度 >= 0，回吐比例在 (0,1] 内");
        }
        List<Kline> data = new ArrayList<>(klines);
        data.sort(Comparator.comparing(Kline::openTime));
        validateSequence(data);
        BigDecimal[] averages = new BigDecimal[data.size()]; // 每根对应的 MA5。
        MaCrossTracker crossTracker = new MaCrossTracker(); // 均线交叉单独展示，不决定行情分段。
        boolean trendFilterEnabled = minimumTrendMovePct.signum() > 0;
        BigDecimal effectiveStartMove = trendFilterEnabled ? minimumTrendMovePct : new BigDecimal("2.0");
        TrendLegTracker trendTracker = new TrendLegTracker(effectiveStartMove, retraceRatio);
        BigDecimal[] slopes = new BigDecimal[data.size()]; // 每根 MA5 相对上一根的百分比变化率。
        BigDecimal activeThreshold = null; // null 表示不在趋平段中；非 null 为当前段固定的阈值。
        BigDecimal activeBaseline = null; // 保留进入时的典型速度，延迟达到幅度门槛后仍可解释阈值来源。
        String activeFlatDirection = null; // 本段进入时确定的上涨趋平/下跌趋平，延续期间不改变。
        int candidateNumber = 0; // 记录的几何候选段数量，包含未通过行情过滤的段。
        int activeCandidateNumber = 0;
        boolean activeFlatQualified = false; // 当前段是否已经满足过全部提醒条件。
        int goldenReminderCount = 0;
        int deadReminderCount = 0;
        int upwardFlatCount = 0;
        int downwardFlatCount = 0;
        System.out.println("开盘时间（北京时间）    MA5变化率");
        for (int i = 0; i < data.size(); i++) {
            Kline k = data.get(i);
            // MA5 使用包括本根在内的最近 5 个已确认收盘价。
            averages[i] = movingAverage(data, i, 5);
            BigDecimal ma10 = movingAverage(data, i, 10);
            MaCrossSnapshot cross = crossTracker.update(k, averages[i], ma10);
            TrendLegTracker.Snapshot trend = trendTracker.update(k);
            slopes[i] = i == 0 || averages[i] == null || averages[i - 1] == null ? null
                    : changeRate(averages[i], averages[i - 1]);
            boolean visible = !k.openTime().isBefore(displayFrom);
            if (trendFilterEnabled && trend.completedLeg() != null) {
                // 行情段结束才终止对应形态，均线金死叉本身不会触发这里的重置。
                activeThreshold = null;
                activeBaseline = null;
                activeFlatDirection = null;
                activeCandidateNumber = 0;
                activeFlatQualified = false;
            }
            BigDecimal baseline = null;
            if (slopes[i] == null) {
                // 数据不足或窗口含未收盘记录时，无法可靠计算变化率，本根不触发提醒。
                activeThreshold = null;
                activeBaseline = null;
                activeFlatDirection = null;
                activeCandidateNumber = 0;
                activeFlatQualified = false;
            } else {
                // 平缓看移动幅度，因此使用绝对值：小幅上涨、小幅下跌都允许。
                BigDecimal absoluteSlope = slopes[i].abs();
                // 离开固定阈值才结束本段；轻微正负摆动仍属于同一个趋平段。
                if (activeThreshold != null && absoluteSlope.compareTo(activeThreshold) > 0) {
                    activeThreshold = null;
                    activeBaseline = null;
                    activeFlatDirection = null;
                    activeCandidateNumber = 0;
                    activeFlatQualified = false;
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
                            activeBaseline = baseline;
                            activeFlatDirection = previouslyRising
                                    ? MaCrossState.FlatDirection.UP : MaCrossState.FlatDirection.DOWN;
                            activeFlatQualified = false;
                            if (visible) {
                                candidateNumber++;
                                activeCandidateNumber = candidateNumber;
                            }
                        }
                    }
                }
            }
            boolean flatReminder = false;
            if (activeThreshold != null) {
                // 形态可能先出现，等行情确认且方向相符时，在本段内首次提醒。
                TrendFlatCheck trendCheck = checkTrendForFlat(trend, activeFlatDirection, trendFilterEnabled);
                if (trendCheck.eligible() && !activeFlatQualified) {
                    activeFlatQualified = true;
                    if (visible) {
                        if (activeCandidateNumber == 0) {
                            // 几何段从预热期延续过来，但全部提醒条件到显示范围才首次满足。
                            candidateNumber++;
                            activeCandidateNumber = candidateNumber;
                        }
                        flatReminder = true;
                        if (MaCrossState.FlatDirection.UP.equals(activeFlatDirection)) {
                            upwardFlatCount++;
                        } else {
                            downwardFlatCount++;
                        }
                    }
                }
            }
            if (visible) {
                // 每根只输出时间和变化率；未命中、形态延续和行情切换不额外刷屏。
                // 详细均线与行情背景只放到实际触发的 [提醒] 中。
                System.out.printf(Locale.ROOT, "%s  MA5变化率=%s%n",
                        format(k.openTime()), percent(slopes[i]));
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
                                    + "收盘价=%s；MA5=%s；MA10=%s；MA5变化率=%s；均线位置=%s%n",
                            k.symbol(), k.interval(), cross.event(), format(k.openTime()), format(k.closeTime()),
                            number(k.close()), number(averages[i]), number(ma10), percent(slopes[i]), cross.position());
                }
                // 只在形态与行情方向首次同时满足时提醒，延续行不重复。
                if (flatReminder) {
                    // 03:00 小时线覆盖 [03:00, 04:00)，所以用其结束时间 04:00 标记可提醒时刻。
                    MaCrossPoint goldenCross = cross.goldenCross();
                    TrendLegTracker.Leg trendLeg = trend.leg();
                    System.out.printf(Locale.ROOT,
                            "[提醒] %s %s 候选段 #%d；提醒类型=%s；所属K线=%s；收盘提醒时间=%s；"
                                    + "收盘价=%s；MA5=%s；变化率=%s；前3根绝对值中位数=%s；本段阈值=%s；"
                                    + "MA10=%s；均线位置=%s；本根交叉=%s；最近金叉所属K线=%s；"
                                    + "金叉确认时间=%s；金叉交点估算值=%s；价格较金叉涨幅=%s；MA5较金叉涨幅=%s；"
                                    + "行情方向=%s；行情段编号=%s；行情起点所属K线=%s；行情起点价=%s；"
                                    + "行情确认时间=%s；行情极值所属K线=%s；行情极值价=%s；"
                                    + "本轮价格变化=%s；最大推进幅度=%s；回吐比例=%s；回吐门槛=%s%n",
                            k.symbol(), k.interval(), activeCandidateNumber, activeFlatDirection,
                            format(k.openTime()), format(k.closeTime()),
                            number(k.close()), number(averages[i]), percent(slopes[i]), percent(activeBaseline), percent(activeThreshold),
                            number(ma10), cross.position(), cross.event(),
                            goldenCross == null ? "未知" : format(goldenCross.openTime()),
                            goldenCross == null ? "未知" : format(goldenCross.confirmedTime()),
                            goldenCross == null ? "未知" : estimatedNumber(goldenCross.value()),
                            gain(cross.priceGainPct()), gain(cross.ma5GainPct()),
                            trendLeg == null ? TrendDirection.WAITING : trendLeg.direction(),
                            trendLeg == null ? "未知" : Integer.toString(trendLeg.number()),
                            trendLeg == null ? "未知" : format(trendLeg.start().openTime()),
                            trendLeg == null ? "未知" : number(trendLeg.start().price()),
                            trendLeg == null ? "未知" : format(trendLeg.confirmedTime()),
                            trendLeg == null ? "未知" : format(trendLeg.extreme().openTime()),
                            trendLeg == null ? "未知" : number(trendLeg.extreme().price()),
                            gain(trend.movePct()), gain(trend.maximumMovePct()), ratioPercent(trend.retracementRatio()),
                            ratioPercent(retraceRatio));
                }
            }
        }
        System.out.printf("分析完成：输出 %d 根，本范围新增趋平候选段 %d 个。候选段不是完整上涨轮次计数。%n",
                data.stream().filter(k -> !k.openTime().isBefore(displayFrom)).count(), candidateNumber);
        System.out.printf("提醒统计：金叉 %d 次，死叉 %d 次，上涨趋平 %d 次，下跌趋平 %d 次。%n",
                goldenReminderCount, deadReminderCount, upwardFlatCount, downwardFlatCount);
    }

    /** 一次已确认交叉的 K 线开始/结束时间，以及两条均线折线交点的估算数值。 */
    record MaCrossPoint(LocalDateTime openTime, LocalDateTime confirmedTime, BigDecimal value) {
    }

    /** 分别保留最近金叉和死叉；两组变化率各自使用相应交点为基准，未知时为 null。 */
    record MaCrossSnapshot(String position, String event, MaCrossPoint goldenCross,
                           BigDecimal priceGainPct, BigDecimal ma5GainPct, MaCrossPoint deadCross,
                           BigDecimal priceGainSinceDeadPct, BigDecimal ma5GainSinceDeadPct) {
    }

    /** 形态与行情上下文分别判断；均线交点只展示，不再决定提醒的涨跌幅起点。 */
    record TrendFlatCheck(boolean eligible, String reason) {
    }

    static TrendFlatCheck checkTrendForFlat(TrendLegTracker.Snapshot trend, String flatDirection, boolean enabled) {
        if (trend == null) {
            throw new IllegalArgumentException("行情状态不能为空");
        }
        String expected;
        if (MaCrossState.FlatDirection.UP.equals(flatDirection)) {
            expected = TrendDirection.UP;
        } else if (MaCrossState.FlatDirection.DOWN.equals(flatDirection)) {
            expected = TrendDirection.DOWN;
        } else {
            throw new IllegalArgumentException("未知趋平方向：" + flatDirection);
        }
        if (!enabled) {
            return new TrendFlatCheck(true, "行情过滤已关闭");
        }
        if (trend.leg() == null) {
            return new TrendFlatCheck(false, "行情尚未确认，不提醒");
        }
        if (!expected.equals(trend.leg().direction())) {
            return new TrendFlatCheck(false, "当前为" + trend.leg().direction() + "行情，与趋平方向不符，不提醒");
        }
        return new TrendFlatCheck(true, "已确认" + expected + "行情，行情条件满足");
    }

    private static String ratioPercent(BigDecimal ratio) {
        return ratio == null ? "未知" : percent(ratio.multiply(HUNDRED));
    }

    /**
     * 逐根跟踪 MA5/MA10；它不参与行情分段，交点仅作为独立的观察信息。
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
     * 金叉、死叉分别保留最近一次交点，并各自计算两组变化率，互不覆盖。
     * 这两组交点不再作为趋平提醒的幅度门槛，也不据此推定“第几轮行情”。
     * 若均线历史不足或遇到未收盘记录，则重置交叉历史，避免跨过未知区间猜测金叉。</p>
     */
    static final class MaCrossTracker {
        private BigDecimal previousMa5;
        private BigDecimal previousDifference;
        private int lastNonZeroDirection;
        private MaCrossPoint lastGoldenCross;
        private MaCrossPoint lastDeadCross;

        MaCrossSnapshot update(Kline kline, BigDecimal ma5, BigDecimal ma10) {
            if (!kline.confirmed() || ma5 == null || ma10 == null) {
                previousMa5 = null;
                previousDifference = null;
                lastNonZeroDirection = 0;
                lastGoldenCross = null;
                lastDeadCross = null;
                return new MaCrossSnapshot(MaCrossState.Position.UNKNOWN, MaCrossState.Event.UNKNOWN,
                        null, null, null, null, null, null);
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
                    // 金叉、死叉均用同一插值公式，分别保存交点及确认时间。
                    BigDecimal fraction = previousDifference.negate()
                            .divide(difference.subtract(previousDifference), MC);
                    BigDecimal crossValue = previousMa5.add(
                            ma5.subtract(previousMa5).multiply(fraction, MC), MC);
                    MaCrossPoint point = new MaCrossPoint(kline.openTime(), kline.closeTime(), crossValue);
                    if (direction > 0) {
                        event = MaCrossState.Event.GOLDEN;
                        lastGoldenCross = point;
                    } else {
                        event = MaCrossState.Event.DEAD;
                        lastDeadCross = point;
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
            BigDecimal priceGainSinceDead = null;
            BigDecimal ma5GainSinceDead = null;
            if (lastDeadCross != null) {
                priceGainSinceDead = changeRate(kline.close(), lastDeadCross.value());
                ma5GainSinceDead = changeRate(ma5, lastDeadCross.value());
            }
            return new MaCrossSnapshot(position, event, lastGoldenCross, priceGain, ma5Gain,
                    lastDeadCross, priceGainSinceDead, ma5GainSinceDead);
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
