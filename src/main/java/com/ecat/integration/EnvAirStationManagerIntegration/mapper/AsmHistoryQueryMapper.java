package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmStatParamMetaRow;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * ASM 历史查询/SDK 读出口 mapper（stat 三级 SELECT + raw 最新值 + 参数清单）。
 *
 * <p>防串台唯一闸：stat 查询必带 {@code interval_mode = #{modeCode}} 过滤（PK 含 interval_mode，
 * BACK/FRONT 同 data_time 双行并存，不过滤会串台——ADM 教训平移）。batch 单 SQL tuple IN-list，
 * 禁 N+1。不加 @Mapper——DynamicJarLoader 按 mapper 路径子串注册。</p>
 *
 * @author coffee
 */
public interface AsmHistoryQueryMapper {

    /**
     * stat 桶行 batch 查询（tuple IN 单 SQL）。
     *
     * @param table      目标表名（service 按 granularity 白名单映射，非用户输入）
     * @param start      窗口起（含）
     * @param end        窗口止（不含）
     * @param seriesList 参数键列表
     * @param modeCode   区间模式码（AsmIntervalMode.code()）
     * @param limit      行数上限（&le;0 = 不限，SDK 全窗路径）
     * @param offset     偏移（分页）
     * @return 桶行列表（data_time 升序）
     */
    List<AsmHistoryBucket> selectStatRows(@Param("table") String table,
                                          @Param("start") Instant start,
                                          @Param("end") Instant end,
                                          @Param("seriesList") List<AsmHistoryParamKey> seriesList,
                                          @Param("modeCode") int modeCode,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    /**
     * 参数清单（SDK listStatParams 读出口）：asm_config_stat JOIN asm_config_unit(STORAGE) 双真相源投影。
     */
    List<AsmStatParamMetaRow> selectStatParamMetas();

    /**
     * raw 最新值（snapshot 回放源）：每 series 取 data_time 最大一行（DISTINCT ON 单 SQL，禁 per-series N+1）。
     *
     * @param uids 站房逻辑设备 uniqueId 列表
     * @return 每 (uid, attrId) 最新 raw 样本行
     */
    List<com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDataSample> selectLatestSamples(@Param("uids") List<String> uids);
}
