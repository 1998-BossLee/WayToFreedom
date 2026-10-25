# WayToFreedom

## K 线分析调试

Java 17 + Maven。直接运行 `src/main/java/yangjin/li/kline/KlineAnalysisMain.java` 的 `main` 方法，使用 OkHttp 即时从 OKX 获取 USDT 永续合约 K 线，控制台逐根打印 MA5、变化率和趋平候选提醒。

直接修改 `main` 中的四个参数即可：

```java
String symbol = ContractSymbol.ETH_USDT;
String interval = KlineInterval.HOUR_1;
LocalDateTime start = LocalDateTime.of(2026, 10, 20, 17, 0);
LocalDateTime end = LocalDateTime.of(2026, 10, 23, 7, 0);
```

- `ContractSymbol`：接口常量，预置 BTC、ETH、SOL、BNB 的 USDT 永续合约。
- `KlineInterval`：接口常量，包含分钟、小时、日、周、月周期；`1m` 是一分钟，`1M` 是一个月。
- `Kline`：不可变 K 线实体，价格和成交量使用 `BigDecimal`；开盘、结束时间均为北京时间 `LocalDateTime`。
- `fetchKlines(symbol, interval, start, end)`：返回指定范围内正序、去重的 `List<Kline>`，按开盘时间筛选，首尾包含。
- `analyze(List<Kline>)`：返回 `void`，逐根打印分析结果；数据不足时明确标记。
- `analyze(List<Kline>, displayFrom, flatThresholdPct, slowdownRatio)`：可指定显示起点及阈值。

全部输入、实体、周期边界和输出统一北京时间，不依赖电脑的系统时区。日线从 00:00 开始，周线从周一 00:00 开始，月线从每月 1 日 00:00 开始；不提供 UTC 周期选项。`closeTime` 是结束的排他边界，例如 03:00 小时线的结束时间是 04:00。`confirmed` 对应 OKX 上游确认字段。

`main` 自动多取前面 8 根进行预热，只打印目标区间。8 根保证首根目标的数学计算，但不能恢复更早开始的全部事件状态。

## 运行

IDE 导入根目录 `pom.xml`，项目 JDK 选择 17，运行 `KlineAnalysisMain.main`。默认请求 `https://www.okx.com`；如该地址无法访问，可在 VM options 设置其他可访问的 OKX HTTPS 服务地址。本机此前实测可用的参数为：

```text
-Dokx.baseUrl=https://www.okx.cab
-Dfile.encoding=UTF-8
```

保持 HTTPS 证书验证，不配置交易密钥。

命令行也可以运行：

```sh
mvn package
java -Dfile.encoding=UTF-8 -Dokx.baseUrl=https://www.okx.cab -jar target/WayToFreedom-1.0-SNAPSHOT.jar
```

或传入四个参数：

```sh
java -Dfile.encoding=UTF-8 -Dokx.baseUrl=https://www.okx.cab -jar target/WayToFreedom-1.0-SNAPSHOT.jar BTCUSDT 15m '2026-10-24 23:15' '2026-10-25 14:45'
```

移动运行 jar 时一起保留旁边的 `lib/` 目录。项目原有依赖和复盘资料保留。

## IDEA 重新加载 Maven

Maven 项目名称为 `WayToFreedom`。修改项目配置后，在右侧 Maven 面板点击“重新加载所有 Maven 项目”（双箭头刷新按钮），等待依赖同步完成，再运行 `KlineAnalysisMain.main`。

`src/main/java` 应为正式源码，`src/test/java` 应为测试源码。不要把整个 `src` 标记为正式源码；否则 IDEA 会用正式源码的依赖编译测试文件，导致 `org.junit.jupiter.api` 不存在。JUnit 保持 `test` 作用域，通过 Maven 测试验证；无需修改成正式依赖。

## 当前趋平候选规则

```text
MA5 = 最近五根已确认收盘价的平均值
变化率 = (本根 MA5 - 上根 MA5) / 上根 MA5 × 100%

前三根变化率均为正，且：
当前变化率绝对值 ≤ min(0.05%, 前三根变化率中位数 × 30%)
```

阈值在 `KlineAnalysisMain` 顶部的 `FLAT_THRESHOLD_PCT` 和 `SLOWDOWN_RATIO` 调整。`0.05` 表示 `0.05%`。

进入候选段后固定该段阈值，连续趋平只提醒一次。离开后再次满足完整进入条件，生成新候选段；在预热期已识别开始的候选只标记延续。编号表示本次回放新增的候选段，尚未实现完整一轮上涨中第几次平缓的计数。

未收盘 K 线会打印，但不参与本次收盘回放的均线和提醒计算。“收盘提醒时间”是历史回放中的可触发时间，不代表当时实际发送过消息。分页、缺口和上游错误会明确报错。
