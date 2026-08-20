package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmControlRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlOrigin;
import com.ecat.integration.EnvAirStationManagerIntegration.support.AsmControlResult;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 控制审计 mapper：insert 落 PENDING 行（回填生成 id）；updateResult 回填执行终态
 * （result/after_value/error/duration_ms，按 id 定位）；selectList/countList 列表读（时间窗+过滤）。
 *
 * @author coffee
 */
public interface AsmControlRecordMapper {

    /** 落 PENDING 行；useGeneratedKeys 回填 id。 */
    int insert(AsmControlRecord record);

    /** 执行终态回填（result/after_value/error/duration_ms）。 */
    int updateResult(AsmControlRecord record);

    /** 按主键查单条（SSE 重连补偿单查；不存在返 null 由调用方明确拒绝）。 */
    AsmControlRecord selectById(@Param("id") long id);

    /**
     * 控制记录时间窗查询（窗口按 created_at 落窗，created_at 降序——最新在前）。
     *
     * @param logicDeviceUniqueId 设备过滤（null=全部）
     * @param origin              来源过滤（null=全部）
     * @param result              结果过滤（null=全部）
     * @param start               起始时刻（含）
     * @param end                 结束时刻（不含）
     * @param limit               行数上限（调用方已校验 ≤1000）
     */
    List<AsmControlRecord> selectList(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                                      @Param("origin") AsmControlOrigin origin,
                                      @Param("result") AsmControlResult result,
                                      @Param("start") Instant start,
                                      @Param("end") Instant end,
                                      @Param("limit") int limit);

    /** 同 {@link #selectList} 条件的计数（REST 分页 total）。 */
    long countList(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                   @Param("origin") AsmControlOrigin origin,
                   @Param("result") AsmControlResult result,
                   @Param("start") Instant start,
                   @Param("end") Instant end);
}
