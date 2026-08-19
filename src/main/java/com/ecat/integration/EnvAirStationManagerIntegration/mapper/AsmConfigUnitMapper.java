package com.ecat.integration.EnvAirStationManagerIntegration.mapper;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmConfigUnit;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ASM 每 series 单位偏好 mapper（{@code asm_config_unit}）。
 *
 * <p>应用场景：seed {@link #insertIfAbsent}（STORAGE 行，unit=airstation attr nativeUnit，冲突让路）；
 * 读出口按 purpose 查（缺行 native 保底）；配置端点改后 {@link #upsert} + 缓存失效。</p>
 *
 * @author coffee
 */
public interface AsmConfigUnitMapper {

    /** 该 logic device 全部 attr 全 purpose 行（per-uid 一次拉，读出口缓存粒度）。 */
    List<AsmConfigUnit> selectByLogicDevice(@Param("logicDeviceUniqueId") String logicDeviceUniqueId);

    /** 按用途滤行（如全 STORAGE 行）；无行返空列表。 */
    List<AsmConfigUnit> selectByPurpose(@Param("purpose") String purpose);

    /** 全字段 upsert（配置端点人工改用）；同 (uid, attr, purpose) 已存在则覆盖 unit + 刷 updated_at。 */
    int upsert(AsmConfigUnit configUnit);

    /** insertIfAbsent：ON CONFLICT DO NOTHING——已存在行（人工精化 unit）原样保留；seed 专用。 */
    int insertIfAbsent(AsmConfigUnit configUnit);
}
