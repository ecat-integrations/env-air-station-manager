package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDeviceChangeRecord;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * ASM 站房设备变更追溯 mapper（append-only：insert + selectList，无 update/delete）。
 * 不加 {@code @Mapper}（DynamicJarLoader 路径注册）。
 */
public interface AsmDeviceChangeRecordMapper {

    /** 插入一条变更流水；id 由 mybatis 回填。 */
    int insert(AsmDeviceChangeRecord record);

    /** 列表查询：按 (logic_device, attr) + occurred_at 时间段过滤（全可选）。分页由 controller startPage 触发。 */
    List<AsmDeviceChangeRecord> selectList(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                                           @Param("attrId") String attrId,
                                           @Param("beginTime") Instant beginTime,
                                           @Param("endTime") Instant endTime);
}
