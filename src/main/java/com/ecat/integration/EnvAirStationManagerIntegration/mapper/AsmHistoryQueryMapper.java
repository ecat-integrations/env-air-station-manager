package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryBucket;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmHistoryParamKey;
import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmStatParamMetaRow;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * ASM 历史查询/SDK 读出口 mapper（stat 三级 SELECT + 参数清单；raw 最新值回放查询已删除——
 * snapshot live 唯一源，无值=无数据，见 AsmSnapshotService 类注释）。
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
     * stat 桶行 batch 查询（降序版，历史页网格分页专用）：过滤段/分页与 {@link #selectStatRows}
     * 完全同构，仅 ORDER BY 翻为 data_time DESC——独立语句而非给升序语句加 order 分支，是为了
     * SDK queryStat 所依赖的升序语句零改动（机对机「旧→新」序列是 SDK javadoc 契约，外部消费方
     * 按升序做增量/差分，语句形状回退即静默破坏契约）。
     *
     * @param table      目标表名（service 按 granularity 白名单映射，非用户输入）
     * @param start      窗口起（含）
     * @param end        窗口止（不含）
     * @param seriesList 参数键列表
     * @param modeCode   区间模式码（AsmIntervalMode.code()）
     * @param limit      行数上限（&le;0 = 不限）
     * @param offset     偏移
     * @return 桶行列表（data_time 降序，新→旧）
     */
    List<AsmHistoryBucket> selectStatRowsDesc(@Param("table") String table,
                                              @Param("start") Instant start,
                                              @Param("end") Instant end,
                                              @Param("seriesList") List<AsmHistoryParamKey> seriesList,
                                              @Param("modeCode") int modeCode,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    /**
     * stat 桶行计数（历史页分页 total）：与 {@link #selectStatRows} 同过滤段（mapper XML 同一
     * &lt;include&gt; 片段，防两处 WHERE 漂移）、去 LIMIT/ORDER。
     *
     * @param table      目标表名（同 selectStatRows 口径）
     * @param start      窗口起（含）
     * @param end        窗口止（不含）
     * @param seriesList 参数键列表
     * @param modeCode   区间模式码（AsmIntervalMode.code()）
     * @return 窗口内该批参数的桶行总数（COUNT(*) 恒非 null）
     */
    long countStatRows(@Param("table") String table,
                       @Param("start") Instant start,
                       @Param("end") Instant end,
                       @Param("seriesList") List<AsmHistoryParamKey> seriesList,
                       @Param("modeCode") int modeCode);

    /**
     * 参数清单（SDK listStatParams 读出口）：asm_config_stat JOIN asm_config_unit(STORAGE) 双真相源投影。
     */
    List<AsmStatParamMetaRow> selectStatParamMetas();
}
