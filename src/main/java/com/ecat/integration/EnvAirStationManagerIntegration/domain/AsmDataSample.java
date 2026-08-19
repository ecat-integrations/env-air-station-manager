package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * asm_data_sample 一行——raw 明细样本（consumer 攒批后经 batchInsert 落库）。
 *
 * <p>纵表：一行=一设备+一参数+一值+一时刻；valueNum/valueText 互斥（数值量/文本·开关量）。
 * unit 存 UnitInfo.getFullUnitString() key 形式，无单位为 null。</p>
 *
 * <p>故意不继承 ruoyi BaseEntity（raw 明细无审计字段，形状不匹配）——mapper XML 用 FQCN
 * （DynamicJarLoader 只对 domain/ + extends BaseEntity 注册短别名）。</p>
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsmDataSample {

    /** 站房逻辑设备 uniqueId（logicdevice_station.*）。 */
    private String logicDeviceUniqueId;
    /** logic attr id。 */
    private String attrId;
    /** 采样时间。 */
    private Instant dataTime;
    /** 数值业务值；非数值参数 null。 */
    private BigDecimal valueNum;
    /** 文本业务值（状态串/开关量）；数值参数 null。 */
    private String valueText;
    /** 采集原生单位（getFullUnitString key 形式）；无量纲 null。 */
    private String unit;
    /** 数据来源标记；null 落 DDL 默认 'POLL'。 */
    private String source;
}
