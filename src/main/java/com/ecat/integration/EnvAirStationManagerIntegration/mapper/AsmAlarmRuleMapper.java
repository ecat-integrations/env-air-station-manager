package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmAlarmRule;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 动环报警规则 mapper（{@code asm_alarm_rule}）——CRUD + 启动/热加载全量枚举。
 *
 * @author coffee
 */
public interface AsmAlarmRuleMapper {

    /** 全量规则行（索引加载入口；按 sort, id 稳定序列）。 */
    List<AsmAlarmRule> selectAll();

    /** 按主键取单行；无行返 null。 */
    AsmAlarmRule selectById(@Param("id") Long id);

    /** 新增（alarm_type 库内唯一，冲突由 DB 约束暴露）。 */
    int insert(AsmAlarmRule rule);

    /** 按主键更新（alarm_type/severity/setting_content/sort）。 */
    int update(AsmAlarmRule rule);

    /** 按主键删除。 */
    int deleteById(@Param("id") Long id);
}
