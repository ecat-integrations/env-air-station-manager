package com.ecat.integration.EnvAirStationManagerIntegration.controller.dto;

import java.util.List;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * snapshot 单属性行——live 实时态；无值 attr=DEF 占位行（无数据语义，前端显 '-'）。
 *
 * @author coffee
 */
@Value
@Builder
public class AsmSnapshotAttrDto {

    /** logic attr id。 */
    String attrId;

    /** 参数中文名（attr def displayName，strings.json 中文；缺省回退 attrId）。 */
    String displayName;

    /** 数值业务值（已按 MONITOR 读出口换算；非数值/text 属性为 null）。 */
    Double value;

    /** 非数值展示串（开关/文本态属性；数值属性为 null）。 */
    String valueText;

    /** value 实际单位符号（UnitInfo.getName，如 °C/V；null=无量纲。总览页精修定案 4：不再输出 full key 全串）。 */
    String unit;

    /** 该值时刻（LIVE=state.lastUpdated / RAW=样本 data_time）。 */
    Instant updateTime;

    /** 主状态中文名（AttributeStatus.getDescription；DEF 占位行为 null）。 */
    String statusName;

    /** 状态枚举 key（AttributeStatus.name()，如 NORMAL/ALARM；前端着色按本 key 映射，不按中文串）。 */
    String status;

    /** 值来源：LIVE（总线实时态）/ DEF（仅有定义无任何值，无数据占位行）。 */
    String source;

    /**
     * 抽屉分组序键（{@code AsmSnapshotService#attrGroup}：0=状态类 / 1=命令类(attrId _command 结尾) /
     * 2=数值类(def 数值型)）。后端排序权威，行内自带分组键让前端 drawerAttrs 用<b>同一 key</b> 镜像排序——
     * 值缺席的 DEF 数值行前端无法从值推断，必须由后端供给；SSE 新增行无本字段，前端按 attrId/值启发兜底
     * （SSE 帧必有值：命令行 attrId 命中、数值行 value 为数、状态行 text，启发不漂）。
     */
    Integer attrGroup;

    /**
     * 值实际单位 full key（如 TemperatureUnit.CELSIUS；数值行才有）。单位设置抽屉单位下拉回显/高亮用
     * ——unit 字段是符号（°C）不可反推 key。null=无量纲/显原生。
     */
    String unitKey;

    /** 监控页生效修约精度（三级链解析结果；数值行才有）——抽屉小数位输入框 placeholder。 */
    Integer displayPrecision;

    /**
     * 单位候选分组（数值行才有；同类组在前+气态跨类组）：单位设置抽屉单位下拉数据源。
     * null=源单位缺席/脏 key（无候选，只可改小数位）。
     */
    List<AsmUnitOptionGroup> unitOptions;
}
