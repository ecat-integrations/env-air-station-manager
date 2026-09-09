package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryQuery;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryResult;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmHistoryQueryMapper;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmIntervalMode;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmStatGranularity;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmUnitPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P2 历史查询 service——入参校验（严格）+ batch 单 SQL + HISTORY 单位读出口换算。
 *
 * <p>granularity 白名单 MINUTE/FIVE_MIN/HOUR；mode 缺省 BACK（国标后闭）；unit 缺省 custom
 * （standard=恒原生直通）。窗口上限 MINUTE|FIVE_MIN≤31 天、HOUR≤400 天（同 SDK 口径，卡死无深 OFFSET）。
 * 换算源单位 = asm_config_unit STORAGE 行 unit（stat 桶 avg-only 无 unit 列，storage 行是桶单位唯一真相源）。</p>
 *
 * <p>@Service 非 @Component：动态 jar 单例注册只认 @RestController/@Service。</p>
 *
 * @author coffee
 */
@Service
@RequiredArgsConstructor
public class AsmHistoryQueryService {

    /** minute/5min 单次窗口上限（31 天）。 */
    static final Duration MAX_WINDOW_MINUTE_LEVEL = Duration.ofDays(31);

    /** hour 单次窗口上限（400 天）。 */
    static final Duration MAX_WINDOW_HOUR = Duration.ofDays(400);

    /** mode 缺省（BACK=国标后闭桶标注）。 */
    static final String DEFAULT_MODE = AsmIntervalMode.BACK.name();

    /** unit 缺省（custom=应用 HISTORY 偏好）。 */
    static final String DEFAULT_UNIT = "custom";

    /** order 缺省（ASC=SDK 机对机「旧→新」口径；DESC 仅历史页网格分页展示用）。 */
    static final String DEFAULT_ORDER = "ASC";

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AsmHistoryQueryMapper historyMapper;
    private final AsmUnitContract unitContract;

    /**
     * 历史查询主入口。
     *
     * @throws IllegalArgumentException granularity/mode/unit 非法、start≥end、窗口超上限、分页非法
     */
    public AsmHistoryResult query(AsmHistoryQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query 不能为 null");
        }
        AsmStatGranularity granularity = parseGranularity(query.getGranularity());
        AsmIntervalMode mode = parseMode(query.getMode());
        boolean applyPref = parseUnit(query.getUnit());
        boolean desc = parseDesc(query.getOrder());
        validateWindow(granularity, query.getStart(), query.getEnd());
        int pageNum = defaultPage(query.getPageNum(), 1, "pageNum");
        int pageSize = defaultPage(query.getPageSize(), DEFAULT_PAGE_SIZE, "pageSize");

        List<AsmHistoryParamKey> params = query.getParams() != null
                ? query.getParams() : Collections.<AsmHistoryParamKey>emptyList();
        if (params.isEmpty()) {
            return AsmHistoryResult.builder()
                    .granularity(granularity.name()).mode(mode.name())
                    .unit(query.getUnit() != null ? query.getUnit() : DEFAULT_UNIT)
                    .pageNum(pageNum).pageSize(pageSize).total(0L)
                    .rows(Collections.<AsmHistoryResult.Row>emptyList())
                    .build();
        }

        // 行集方向按 order 分流到两条独立语句（SDK 升序语句零改动）；过滤/分页参数两条语句完全同构
        List<AsmHistoryBucket> buckets = desc
                ? historyMapper.selectStatRowsDesc(
                        granularity.targetTable(), query.getStart(), query.getEnd(),
                        params, mode.code(), pageSize, (pageNum - 1) * pageSize)
                : historyMapper.selectStatRows(
                        granularity.targetTable(), query.getStart(), query.getEnd(),
                        params, mode.code(), pageSize, (pageNum - 1) * pageSize);
        // total 与行集同 WHERE（mapper 同一 <include> 片段）：前端真分页依据，替代「下一页探测」
        long total = historyMapper.countStatRows(
                granularity.targetTable(), query.getStart(), query.getEnd(),
                params, mode.code());

        List<AsmHistoryResult.Row> rows = new ArrayList<>(buckets != null ? buckets.size() : 0);
        if (buckets != null) {
            for (AsmHistoryBucket b : buckets) {
                rows.add(toRow(b, applyPref));
            }
        }
        return AsmHistoryResult.builder()
                .granularity(granularity.name()).mode(mode.name())
                .unit(query.getUnit() != null ? query.getUnit() : DEFAULT_UNIT)
                .pageNum(pageNum).pageSize(pageSize).total(total)
                .rows(rows)
                .build();
    }

    /**
     * 桶行 → 结果行（unit=custom 时经 HISTORY 读出口换算，源=STORAGE 行 unit）。
     * display_unit 在换算完成的出口处取 {@code display.getUnit()}（=value 实际单位，custom 换算后
     * 可能≠storageUnit；standard 未换算路径=storageUnit 同源）转 UnitInfo.getDisplayName 显示串。
     * 非数值桶（avgValue=null、valueText 非空）：value 恒 null 直通（resolveDisplay 对 null 值
     * 原样返），valueText 原样透传；unit=seed 空串占位（非数值 attr 无 nativeUnit）=显无单位。
     */
    private AsmHistoryResult.Row toRow(AsmHistoryBucket bucket, boolean applyPref) {
        String storageUnit = unitContract.resolveUnit(
                AsmUnitPurpose.STORAGE, bucket.getLogicDeviceUniqueId(), bucket.getAttrId());
        AsmDisplayValue display = applyPref
                ? unitContract.resolveDisplay(AsmUnitPurpose.HISTORY,
                        bucket.getLogicDeviceUniqueId(), bucket.getAttrId(),
                        bucket.getAvgValue(), storageUnit)
                : AsmDisplayValue.of(bucket.getAvgValue(), storageUnit, false);
        return AsmHistoryResult.Row.builder()
                .dataTime(bucket.getDataTime())
                .logicDeviceUniqueId(bucket.getLogicDeviceUniqueId())
                .attrId(bucket.getAttrId())
                .value(display.getValue())
                .valueText(bucket.getValueText())
                .unit(display.getUnit())
                .displayUnit(AsmUnitContract.unitDisplayName(display.getUnit()))
                .validCount(bucket.getValidCount())
                .totalCount(bucket.getTotalCount())
                .build();
    }

    /** granularity 白名单解析（MINUTE/FIVE_MIN/HOUR；null/未知名抛）。 */
    private static AsmStatGranularity parseGranularity(String name) {
        if (name != null) {
            for (AsmStatGranularity g : AsmStatGranularity.values()) {
                if (g.name().equals(name)) {
                    return g;
                }
            }
        }
        throw new IllegalArgumentException("非法 granularity: " + name + "（合法: MINUTE/FIVE_MIN/HOUR）");
    }

    /** mode 解析（null→缺省 BACK；FRONT/BACK 之外的值抛）。 */
    private static AsmIntervalMode parseMode(String mode) {
        return AsmIntervalMode.of(mode != null ? mode : DEFAULT_MODE);
    }

    /** unit 解析：true=custom（应用偏好）；standard 恒原生；其他值抛。 */
    private static boolean parseUnit(String unit) {
        String u = unit != null ? unit : DEFAULT_UNIT;
        if ("custom".equals(u)) {
            return true;
        }
        if ("standard".equals(u)) {
            return false;
        }
        throw new IllegalArgumentException("非法 unit: " + unit + "（合法: standard/custom）");
    }

    /** order 解析：null/空→缺省 ASC；ASC/DESC 之外的值抛（严格模式不猜）。 */
    private static boolean parseDesc(String order) {
        String o = order != null ? order.trim() : DEFAULT_ORDER;
        if (o.isEmpty()) {
            return false;
        }
        if (DEFAULT_ORDER.equals(o)) {
            return false;
        }
        if ("DESC".equals(o)) {
            return true;
        }
        throw new IllegalArgumentException("非法 order: " + order + "（合法: ASC/DESC）");
    }

    private static void validateWindow(AsmStatGranularity granularity, Instant start, Instant end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start/end 不能为 null");
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start 须 < end（start=" + start + " end=" + end + "）");
        }
        Duration window = Duration.between(start, end);
        Duration limit = granularity == AsmStatGranularity.HOUR ? MAX_WINDOW_HOUR : MAX_WINDOW_MINUTE_LEVEL;
        if (window.compareTo(limit) > 0) {
            throw new IllegalArgumentException("时间窗口超限：粒度 " + granularity
                    + " 单次窗口上限 " + limit.toDays() + " 天（start=" + start + " end=" + end + " 实际=" + window + "）");
        }
    }

    /** 分页缺省/合法性（null→default；<=0 抛）。 */
    private static int defaultPage(Integer value, int def, String field) {
        int v = value != null ? value : def;
        if (v <= 0) {
            throw new IllegalArgumentException("非法 " + field + ": " + value + "（须 >= 1）");
        }
        return v;
    }
}
