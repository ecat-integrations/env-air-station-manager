package com.ecat.integration.EnvAirStationManagerIntegration.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * ASM 站房设备变更追溯记录（append-only 审计流水，复刻 ADM AdmDeviceChangeRecord）。
 *
 * <p>eventType 5 种：FIRST_BIND/REBIND/REPLACE/UNBIND/RECONFIGURE；仅 REPLACE 填
 * {@link #prevPhysicalDeviceUniqueId}。业务键 logicDeviceUniqueId + attrId + occurredAt。
 *
 * @author coffee
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsmDeviceChangeRecord {
    private Long id;
    /** 类型槽 uniqueId（logicdevice_station.*） */
    private String logicDeviceUniqueId;
    /** logic attr id */
    private String attrId;
    /** 事件类型（字符串存储，避 DynamicJarLoader 扫枚举） */
    private String eventType;
    /** 变更后物理设备 uniqueId；UNBIND 为 NULL */
    private String physicalDeviceUniqueId;
    /** 变更前物理设备 uniqueId；仅 REPLACE 有值 */
    private String prevPhysicalDeviceUniqueId;
    /** 厂商 */
    private String vendor;
    /** 型号 */
    private String model;
    /** 序列号 */
    private String serialNumber;
    /** 变更摘要 */
    private String changeSummary;
    /** 操作者 */
    private String operator;
    /** 变更发生时间（业务时间） */
    private Instant occurredAt;
    /** 入库时间（append-only 无 updated_at） */
    private Instant createdAt;
}
