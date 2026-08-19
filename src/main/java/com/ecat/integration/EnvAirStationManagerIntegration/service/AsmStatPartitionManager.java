package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.core.Utils.Log;
import com.ecat.core.Utils.LogFactory;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASM stat 表月度分区管理器——三张 stat 父表（{@code asm_stat_{minute,5min,hour}}，PG 声明式 RANGE 分区）
 * 的月分区 ensure 唯一入口，应用层自管不引 pg_partman（机制照抄 ADM AdmStatPartitionManager，
 * 三级无 day）。
 *
 * <p>应用场景：stat 父表按 {@code data_time} 月度分区且<b>无 DEFAULT 分区</b>（严格模式：缺分区 INSERT
 * 显式报错，暴露 ensure 收口漏洞）。本管理器在写路径前把窗口覆盖的每个月份分区幂等建好
 * （{@code CREATE TABLE IF NOT EXISTS ... PARTITION OF ...}）。</p>
 *
 * <p><b>UTC 月对齐</b>：月迭代与月边界恒按 {@link ZoneOffset.UTC}（stat data_time 是 UTC 网格存储）；
 * 禁用 {@code ZoneId.systemDefault()}（月边界随部署环境漂移，切月瞬间桶会落错分区）。</p>
 *
 * <p><b>已 ensure 月份缓存</b>：调度器每分钟 tick 都会调，缓存使命中路径零 DDL 零开销；未命中才发 DDL。
 * DDL 自带 {@code IF NOT EXISTS} 幂等，防两个写者并发 CREATE 同名分区的竞态。并发 Set：调度/启动并发调。</p>
 *
 * <p>表名单一真相源：父表名从 {@link AsmStatGranularity#targetTable()} 派生；分区名 = 父表名 + {@code _yyyyMM}。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmStatPartitionManager {

    /**
     * 分区边界时间戳字面量格式（DDL FOR VALUES 两端）：固定 {@code uuuu-MM-dd'T'HH:mm:ss'Z'} + UTC
     * ——显式 pattern 而非 {@code Instant.toString()}（后者秒为零时可能省略秒段），DDL 字符串跨 JDK 确定性一致
     * （单测锁精确语句）。
     */
    private static final DateTimeFormatter DDL_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final Log log = LogFactory.getLogger(getClass());

    private final JdbcTemplate jdbcTemplate;

    /** 已 ensure 的 (父表, 月) 集合——命中跳过 DDL，调度 tick 常态零 DDL。并发 Set。 */
    private final Set<String> ensuredPartitions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 确保窗口 [start, end] 覆盖的每个 UTC 月、三张 stat 父表的月分区都存在（幂等）。
     *
     * @param start 窗口起点（UTC instant；含该时刻所在月）
     * @param end   窗口终点（含该时刻所在月）
     * @throws IllegalArgumentException start/end null 或 start 晚于 end（严格模式显式报错）
     */
    public void ensureStatPartitions(Instant start, Instant end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException(
                    "ensureStatPartitions start/end 不可为 null: start=" + start + " end=" + end);
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException(
                    "ensureStatPartitions start 不可晚于 end: start=" + start + " end=" + end);
        }
        YearMonth month = YearMonth.from(start.atZone(ZoneOffset.UTC));
        YearMonth lastMonth = YearMonth.from(end.atZone(ZoneOffset.UTC));
        for (; !month.isAfter(lastMonth); month = month.plusMonths(1)) {
            ensureMonth(month);
        }
    }

    /**
     * 启动预建：当前月 ±1 月（对齐 ADM 启动当月+下月策略，ASM 多回望一个月——站房回补场景近月常见）。
     * 调度/回补写路径仍各自律 ensure（本方法只是启动预热，非唯一时机）。
     */
    public void ensureStartupPartitions(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("ensureStartupPartitions now 不可为 null");
        }
        ensureStatPartitions(now.minus(java.time.Duration.ofDays(31)), now.plus(java.time.Duration.ofDays(31)));
    }

    /** 确保某 UTC 月三张父表的月分区存在：逐粒度查缓存，未命中发幂等 DDL 后入缓存。 */
    private void ensureMonth(YearMonth month) {
        String monthStart = DDL_TIMESTAMP.format(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        String nextMonthStart = DDL_TIMESTAMP.format(
                month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        String monthSuffix = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        for (AsmStatGranularity gran : AsmStatGranularity.values()) {
            String parent = gran.targetTable();
            String cacheKey = parent + "_" + monthSuffix;
            if (ensuredPartitions.contains(cacheKey)) {
                continue;
            }
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + cacheKey
                    + " PARTITION OF " + parent
                    + " FOR VALUES FROM ('" + monthStart + "') TO ('" + nextMonthStart + "')");
            ensuredPartitions.add(cacheKey);
            log.info("[诊断调试] ASM stat 月分区已 ensure: {} ({} ~ {})", cacheKey, monthStart, nextMonthStart);
        }
    }
}
