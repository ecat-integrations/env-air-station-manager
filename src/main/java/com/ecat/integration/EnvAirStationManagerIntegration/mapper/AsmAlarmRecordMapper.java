package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRecord;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * ASM 报警记录 mapper（{@code asm_alarm_record}，D2 自有表）——评估触发 insert + 列表/SDK 查询。
 *
 * @author coffee
 */
public interface AsmAlarmRecordMapper {

    /** 落一条报警记录（触发/恢复）。 */
    int insert(AsmAlarmRecord record);

    /**
     * 时间窗查询（SDK 出口共用）。
     *
     * @param logicDeviceUniqueId 设备过滤（null=全部）
     * @param start               起始时刻（含）
     * @param end                 结束时刻（不含）
     * @param limit               行数上限（调用方已校验 1..1000）
     */
    List<AsmAlarmRecord> selectList(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                                    @Param("start") Instant start,
                                    @Param("end") Instant end,
                                    @Param("limit") int limit);

    /** 同 {@link #selectList} 条件的计数（REST 分页 total）。 */
    long countList(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                   @Param("start") Instant start,
                   @Param("end") Instant end);
}
