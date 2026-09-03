package com.ecat.integration.EnvAirStationManagerIntegration.service;

import com.ecat.integration.EnvAirStationManagerIntegration.controller.dto.AsmDeviceLabelsDto;
import com.ecat.integration.EnvAirStationManagerIntegration.profile.StationParamMeta;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicDeviceManager;
import com.ecat.integration.logicdevice.LogicState.LogicAttributeDefine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ASM 设备/参数中文标注服务——REST 出口（alarm-rule list 的 deviceLabels、alarm-record 行的
 * device_label/attr_label、config-stat 行的 device_label/attr_label）的中文 label 唯一来源。
 *
 * <ul>
 *   <li><b>槽中文名</b>：与 {@link StationParamMeta} label 同源（37 槽，多实例槽各带实例名如「CO标气」），
 *       按逻辑设备 uniqueId 精确匹配；非站房槽/未知 uid <b>如实回退 uid 原文</b>（不猜——回退即
 *       「当前无槽元数据」的诚实表达，前端可据此识别配置缺口）。</li>
 *   <li><b>参数中文名</b>：airstation registry 设备 attrDefs 的 displayName（def 是 displayName 唯一
 *       来源，同 snapshot 出口口径）；设备不在 registry 或 attr 无 def 回退 attrId 原文。</li>
 * </ul>
 *
 * <p>registry 访问经构造注入 resolver（生产={@code LogicDeviceManager} 全量扫描精确匹配；测试注入
 * map 桩，同 {@code AsmControlService#stationResolver} 模式）。label 解析是纯读，registry 查不到
 * 设备不是错误（设备存在性=用户驱动运行时状态），逐 attr 回退原文、端点不因此失败。</p>
 *
 * @author coffee
 */
@Service
public class AsmDeviceLabelService {

    /** uid → 逻辑设备（生产=LogicDeviceManager 扫描；测试注入桩）。 */
    private final Function<String, LogicDevice> deviceResolver;

    /** 生产构造：resolver=registry 全量扫描按 uniqueId 精确匹配（同 snapshot/SDK 枚举模式）。 */
    @Autowired
    public AsmDeviceLabelService() {
        this(AsmDeviceLabelService::resolveFromRegistry);
    }

    /** 测试构造：注入 resolver 桩（mock registry；controller 包测试跨包访问故 public，生产装配走上者）。 */
    public AsmDeviceLabelService(Function<String, LogicDevice> deviceResolver) {
        this.deviceResolver = deviceResolver;
    }

    private static LogicDevice resolveFromRegistry(String uid) {
        for (LogicDevice device : LogicDeviceManager.getInstance().getRegisteredDevices()) {
            if (device.getUniqueId().equals(uid)) {
                return device;
            }
        }
        return null;
    }

    /** 槽中文名：StationParamMeta label 同源；未匹配槽回退 uid 原文。 */
    public String slotLabel(String uid) {
        for (StationParamMeta slot : StationParamMeta.values()) {
            if (slot.getUniqueId().equals(uid)) {
                return slot.label;
            }
        }
        return uid;
    }

    /** 参数中文名：registry attrDefs displayName；设备/def 缺席回退 attrId 原文。 */
    public String attrLabel(String uid, String attrId) {
        LogicDevice device = uid == null ? null : deviceResolver.apply(uid);
        String displayName = device == null ? null : displayNameOf(device, attrId);
        return displayName != null ? displayName : attrId;
    }

    /** alarm-rule 行 deviceLabels：按 device_info uid 顺序逐槽 {槽中文名, [attr 中文名...]}。 */
    public List<AsmDeviceLabelsDto> deviceLabels(Map<String, List<String>> devices) {
        if (devices == null || devices.isEmpty()) {
            return Collections.emptyList();
        }
        List<AsmDeviceLabelsDto> out = new ArrayList<>(devices.size());
        for (Map.Entry<String, List<String>> e : devices.entrySet()) {
            List<String> attrs = new ArrayList<>(e.getValue().size());
            for (String attrId : e.getValue()) {
                attrs.add(attrLabel(e.getKey(), attrId));
            }
            out.add(new AsmDeviceLabelsDto(slotLabel(e.getKey()), attrs));
        }
        return out;
    }

    /** attrId → displayName 反查（无 def / displayName null 返 null——回退判定在调用方）。 */
    private static String displayNameOf(LogicDevice device, String attrId) {
        if (device.getAttrDefs() == null || attrId == null) {
            return null;
        }
        for (LogicAttributeDefine def : device.getAttrDefs()) {
            if (def != null && attrId.equals(def.getAttrId())) {
                return def.getDisplayName();
            }
        }
        return null;
    }
}
