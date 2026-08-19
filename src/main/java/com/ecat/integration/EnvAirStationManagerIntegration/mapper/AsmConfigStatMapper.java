package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigStat;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ASM 每 series 聚合配置 mapper（{@code asm_config_stat}）。
 *
 * <p>应用场景：seed {@link #insertIfAbsent}（首见 series 落默认行，冲突让路不覆盖人工配置）；
 * 物化引擎 {@link #selectAll} 枚举待算 series；配置端点改后 {@link #upsert} + 缓存失效。</p>
 *
 * @author coffee
 */
public interface AsmConfigStatMapper {

    /** 全部 series 配置行（物化引擎枚举入口；按 uid, attr 排序稳定序列）。 */
    List<AsmConfigStat> selectAll();

    /** 单 series 配置行；无行返 null（seed 判首见用）。 */
    AsmConfigStat selectBySeries(@Param("logicDeviceUniqueId") String logicDeviceUniqueId,
                                 @Param("attrId") String attrId);

    /** 全字段 upsert（配置端点人工改用）；同 series 已存在则覆盖 enabled/mask/mode + 刷 updated_at。 */
    int upsert(AsmConfigStat configStat);

    /** insertIfAbsent：ON CONFLICT DO NOTHING——已存在行（人工配置）原样保留；seed 默认行专用。 */
    int insertIfAbsent(AsmConfigStat configStat);
}
