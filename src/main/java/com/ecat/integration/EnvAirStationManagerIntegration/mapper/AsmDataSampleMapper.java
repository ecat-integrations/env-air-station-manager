package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDataSample;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * ASM raw 明细 mapper（{@code asm_data_sample} 写入/查询）。
 *
 * <p>应用场景：consumer 攒批后 {@link #batchInsert} 落 raw（P1b 接线）；回放/审计按 series+时间段查。
 * 不加 {@code @Mapper}——DynamicJarLoader 运行时按 {@code mapper/*Mapper.class} 路径子串注册。</p>
 *
 * @author coffee
 */
public interface AsmDataSampleMapper {

    /**
     * 批量 insert（consumer 攒批入参，foreach 多行 VALUES）。
     * 无 PK 无去重语义（raw 幂等由上层窗口控制）；source=null 走 DDL 默认 'POLL'。
     */
    int batchInsert(@Param("list") List<AsmDataSample> samples);

    /** 按 (logic_device, attr, 时间段) 查原始明细（BETWEEN 半闭，data_time 升序）。 */
    List<AsmDataSample> selectByLogicAttrTimeRange(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                                                   @Param("attrId") String attrId,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to);
}
