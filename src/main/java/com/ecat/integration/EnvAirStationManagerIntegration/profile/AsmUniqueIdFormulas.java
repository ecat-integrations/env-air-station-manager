package com.ecat.integration.EnvAirStationManagerIntegration.profile;

import java.util.HashMap;
import java.util.Map;

/**
 * ASM 侧 uniqueId 公式集中地（与 ADM 的 X1 实现同构：内置公式 = 子集成 {@code generateUniqueId}
 * 的逐行镜像，字段都从 entryData 取）。供 {@link StationProvisionProfile#buildUniqueId} 判断
 * uniqueId 是否已存在（多引用设备解绑后重绑→自动复用）。
 *
 * <p>只登记 airstation 矩阵实际涉及的厂商坐标（grep logicdevice-airstation DeviceMappings 全量坐标）；
 * 未登记坐标返 null = 无复用探测（每 step 监测自然跳过，flow 自身仍会做 DuplicateUniqueId 校验，
 * 非兜底而是「未知即不预判」）。
 *
 * @author coffee
 */
final class AsmUniqueIdFormulas {

    @FunctionalInterface
    interface Formula { String apply(Map<String, Object> d); }

    private static final Map<String, Formula> BY_COORD = new HashMap<>();
    static {
        // sn-based
        register("com.ecat:integration-sailhero",       d -> sn(d) == null ? null : "sailhero_" + sn(d));
        register("com.ecat:integration-tjtongyangkeji", d -> sn(d) == null ? null : "tjtongyangkeji_" + sn(d));
        // class + sn
        register("com.ecat:integration-saimosen", d -> no(cls(d), sn(d)) ? null : "saimosen_" + cls(d) + "_" + sn(d));
        register("com.ecat:integration-tianhong", d -> no(cls(d), sn(d)) ? null : "tianhong_" + cls(d) + "_" + sn(d));
        // class + model + sn
        register("com.ecat:integration-teledyne-api", d -> no(cls(d), sn(d), mdl(d)) ? null
                : "teledyne-api_" + cls(d) + "_" + mdl(d) + "_" + sn(d));
        // thermofisher（address-based，按 model 分支取 serial_port/ip_address/host）
        register("com.ecat:integration-thermofisher", AsmUniqueIdFormulas::thermo);
    }

    /** 按 coordinate 取公式算 uniqueId；字段未齐或未知 coordinate 返 null（每 step 监测自然跳过）。 */
    static String build(String coordinate, Map<String, Object> entryData) {
        Formula f = BY_COORD.get(coordinate);
        return f == null ? null : f.apply(entryData);
    }

    private static String sn(Map<String, Object> d) { return (String) d.get("sn"); }
    private static String cls(Map<String, Object> d) { return (String) d.get("class"); }
    private static String mdl(Map<String, Object> d) { return (String) d.get("model"); }
    private static boolean no(String... vs) {
        for (String v : vs) {
            if (v == null || v.isEmpty()) return true;
        }
        return false;
    }
    @SuppressWarnings("unchecked")
    private static String thermo(Map<String, Object> d) {
        String model = mdl(d);
        if (model == null) return null;
        Map<String, Object> comm = (Map<String, Object>) d.get("comm_settings");
        if (comm == null) return null;
        String addressKey;
        if ("1405f".equals(model)) {
            Map<String, Object> serial = (Map<String, Object>) comm.get("serial_settings");
            if (serial == null) return null;
            String port = (String) serial.get("serial_port");
            if (port == null) return null;
            addressKey = port.substring(port.lastIndexOf('/') + 1);
        } else if ("5030iq".equals(model)) {
            addressKey = (String) comm.get("ip_address");
        } else {
            addressKey = (String) comm.get("host");
        }
        return addressKey == null ? null : "thermofisher_" + model + "_" + addressKey;
    }
    private static void register(String coord, Formula f) { BY_COORD.put(coord, f); }

    private AsmUniqueIdFormulas() {}
}
