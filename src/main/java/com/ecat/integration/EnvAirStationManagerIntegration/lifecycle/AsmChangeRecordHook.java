package com.ecat.integration.EnvAirStationManagerIntegration.lifecycle;

import com.ecat.integration.EnvAirStationManagerIntegration.domain.AsmDeviceChangeRecord;
import com.ecat.integration.EnvAirStationManagerIntegration.mapper.AsmDeviceChangeRecordMapper;

import java.time.Instant;
import java.util.List;

/**
 * ASM 站房设备变更追溯钩子（复刻 ADM AdmChangeRecordHook）。5 事件
 * （FIRST_BIND/REBIND/REPLACE/UNBIND/RECONFIGURE），attr 级粒度，append-only 落
 * {@code asm_device_change_record}。由装配层注入 {@code StationDeviceBindingService}。
 *
 * @author coffee
 */
public class AsmChangeRecordHook {

    private final AsmDeviceChangeRecordMapper recordMapper;

    public AsmChangeRecordHook(AsmDeviceChangeRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    /** 首次绑定新物理设备。 */
    public void onFirstBind(String logicDeviceUniqueId, List<String> attrIds,
                            String physicalDeviceUniqueId, String vendor, String model,
                            String serialNumber, String changeSummary, String operator) {
        recordPerAttr(logicDeviceUniqueId, attrIds, "FIRST_BIND",
                physicalDeviceUniqueId, null, vendor, model, serialNumber, changeSummary, operator);
    }

    /** 复用已存在物理设备重绑。 */
    public void onRebind(String logicDeviceUniqueId, List<String> attrIds,
                         String physicalDeviceUniqueId, String vendor, String model,
                         String serialNumber, String changeSummary, String operator) {
        recordPerAttr(logicDeviceUniqueId, attrIds, "REBIND",
                physicalDeviceUniqueId, null, vendor, model, serialNumber, changeSummary, operator);
    }

    /** 用新设备替换前设备。 */
    public void onReplace(String logicDeviceUniqueId, List<String> attrIds,
                          String physicalDeviceUniqueId, String prevPhysicalDeviceUniqueId,
                          String vendor, String model, String serialNumber,
                          String changeSummary, String operator) {
        recordPerAttr(logicDeviceUniqueId, attrIds, "REPLACE",
                physicalDeviceUniqueId, prevPhysicalDeviceUniqueId,
                vendor, model, serialNumber, changeSummary, operator);
    }

    /** 解绑。 */
    public void onUnbind(String logicDeviceUniqueId, List<String> attrIds,
                         String physicalDeviceUniqueId, String vendor, String model,
                         String serialNumber, String changeSummary, String operator) {
        recordPerAttr(logicDeviceUniqueId, attrIds, "UNBIND",
                null, null, vendor, model, serialNumber, changeSummary, operator);
    }

    /** 改连接。 */
    public void onReconfigure(String logicDeviceUniqueId, List<String> attrIds,
                              String physicalDeviceUniqueId, String vendor, String model,
                              String serialNumber, String changeSummary, String operator) {
        recordPerAttr(logicDeviceUniqueId, attrIds, "RECONFIGURE",
                physicalDeviceUniqueId, null, vendor, model, serialNumber, changeSummary, operator);
    }

    private void recordPerAttr(String logicDeviceUniqueId, List<String> attrIds, String eventType,
                               String physicalDeviceUniqueId, String prevPhysicalDeviceUniqueId,
                               String vendor, String model, String serialNumber,
                               String changeSummary, String operator) {
        if (attrIds == null || attrIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (String attrId : attrIds) {
            recordMapper.insert(AsmDeviceChangeRecord.builder()
                    .logicDeviceUniqueId(logicDeviceUniqueId)
                    .attrId(attrId)
                    .eventType(eventType)
                    .physicalDeviceUniqueId(physicalDeviceUniqueId)
                    .prevPhysicalDeviceUniqueId(prevPhysicalDeviceUniqueId)
                    .vendor(vendor)
                    .model(model)
                    .serialNumber(serialNumber)
                    .changeSummary(changeSummary)
                    .operator(operator)
                    .occurredAt(now)
                    .build());
        }
    }
}
