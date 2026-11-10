# ASM 站房设备配置（现行设计）

状态：现行设计（机制/契约/矩阵）｜ 用法与构建入口见 README「设备配置页」｜ SDK 查询见 `docs/sdk.md`

站房设备的配置管理（bind/replace/unbind/复用）：37 类型槽的配置向导 + 三态纯读 + 变更审计。机制复刻 ADM 设备配置，ASM 适配定案如下。

## 机制

- 类型槽 = 22 站房设备类型（DeviceType.Station），多实例类型（空调/标气/滤膜/摄像头/PM零点/切割器/纸带/阀组）按 `StationDeviceConfigFlow.MULTI_INSTANCE_SUFFIXES` 展开为 37 槽（StationParamMeta）。
- REST：`/asm-monitor/device/*` 12 端点（StationDeviceController；第 12 个是拓扑用的 `GET /comm-briefs` 连接摘要）。权限：读 `asm-monitor:device:list` / 写 `asm-monitor:device:edit`（asm_auth.sql / asm_data.sql 内幂等 seed）。
- 三段式流程：provision（FlowDriver 快进身份步停连接步）→ submit×N（schema 驱动 + SHOW_FORM 复用探测）→ CREATE_ENTRY 原子收口 `finalizeAtomic`（绑 logic station device → REPLACE 引用判定 removeEntry → 审计；失败 removeEntry 回滚）。方法级 synchronized。
- 绑定存 logic entry data `mappings.<attrId>.device_id`（无新表）；三态（未配置/已配置/复用候选）纯读 registry。
- 副作用五件套 ASM 口径：绑 logic device（整台，station 无 attr 级多源）／配置联动（无 aggregation_mode，跳过）／seed STORAGE（series seed 数据驱动=AsmSeedService consumer 首见，绑定不触发）／REPLACE 引用判定／审计 5 事件（FIRST_BIND/REBIND/REPLACE/UNBIND/RECONFIGURE，append-only 落 `asm_device_change_record`）。
- profile 注册表：StationProfileRegistrar 全矩阵注册（48 组合）；**厂商列表唯一来源=注册表，矩阵外不发明**；ELECTRONIC_FENCE/CLEANLINESS 仅 stub mapping → 厂商列表如实为空。全部 USER_FLOW（SimpleUserFlowProfile，identityInputs 空=停首 required 步交用户）。
- FlowDriver/SchemaDtoConverter 自 ADM 移植（改包名）；select_type 步对多实例槽追加提交 device_instance。
- 前端：station_device 页四组件（ConnectionTopology / Detail / ReuseDeviceDialog / ConfigFlowDialog）+ `api/device.js` + `stationParamMeta.js`（37 槽元数据）+ config-flow lit lib 副本（webpack CopyPlugin，见 README 工程约束）；e2e 回归入口 `.claude/skills/ruoyi-e2e-test/skills/env-air-station-manager/e2e/specs/asm-g12-station-device.spec.ts`（workspace 相对）（37 槽数据驱动 + 完整绑定链 + 复用 + 403）。
- 左侧「站房设备 + 连接拓扑」面板镜像 ADM 参数设备页：`components/connection-topology/` 三列二分图（类型槽分组卡 → 链路节点 → 设备卡），几何纯函数（`buildTopology.js`，node 单测 `__tests__/connection-topology.test.mjs`）；设备图标 `glyphs/stationGlyphDefs.js`（数据）+ `glyphs/StationGlyph.vue`（渲染）+ `glyphs/glyphKind.js`（映射）：**设备 class 为 `air.monitor.qc` 时用质控仪图标**（多槽复用的一体化仪表），其余按类型槽取轮廓，22 类设备各一种图标；新增一类只需加 defs 条目 + TYPE_KIND 一行。
- 右侧详情「详细配置 / 原始配置」互斥展开 + 原始配置一键复制（`components/config-display/`，镜像 ADM）：详细配置按连接类型选用模板（serial / modbus-rtu / modbus-tcp / clink-tcp / fallback，`registry.js`），连接分项之外另有「其他配置」区块（sn / 采集间隔等）；原始配置面板保留整份 entry.data JSON。
- **共性连接识别集中在 `components/comm/`**（拓扑与详情共用，唯一来源）：`normalizeComm.js`（键名归一化 + `CONNECTION_KEYS` 共性连接键集合）、`resolveConnectionType.js`（串口/Modbus RTU/Modbus TCP/CLink 判定）、`formatters.js`（摘要 + KV 行 + 枚举翻译）、`labels.js`（字段中文释义）。拓扑链路节点与详情摘要走同一函数，不会出现同一设备两处判型不一致；单测 `__tests__/config-display.test.mjs`。

## 类型×厂商×型号矩阵（真相源=logicdevice-airstation DeviceMappings 枚举 + StationProfileRegistrar 注册表）

| 类型 | 厂商×型号 |
|---|---|
| TH | aogan/AGH3485；saimosen/SMS8910(V2) |
| POWER_METER | chko/DTS8666；saimosen/SMS8910(V2) |
| VOLTAGE_REGULATOR | saimosen/IRP0501B、SMS8910V2 |
| UPS | santak/EDX；zhengxin/HCUPS；saimosen/SMS8910(V2) |
| AIR_CONDITIONER(ac1/ac2) | szzht/RACC2；saimosen/SMS8910(V2) |
| EXHAUST_FAN / LIGHTING / ZERO_GAS_RELAY | saimosen/SMS8910(V2) |
| SECURITY_ALARM | juyingele/DAM0888A；saimosen/SMS8910(V2) |
| SAMPLING_TUBE | saimosen/SMS8910(V2)、SampleTube |
| STANDARD_GAS(so2/co/nox) | thermofisher/146i；saimosen/SMS8600V2、SMS8910(V2) |
| FILTER_CHANGER(so2/co/o3/nox) | cecep/TRAMC500；saimosen/SMS8910(V2) |
| CALIBRATOR | thermofisher/146i；teledyne-api/T700；tianhong/TH2008K/T；tjtongyangkeji/TY-AQMS-45；sailhero/XHCAL2000BV3；saimosen/SMS8600V1/V2 |
| ELECTRONIC_FENCE | （仅 stub，不注册） |
| ACCESS_CONTROL | hikvision/DS-K1T670M；zkteco/TDB08 |
| CAMERA(1..4) | benmai/MBELBH16P |
| CLEANLINESS | saimosen/SMS8910(V2)（stub 不注册） |
| INDOOR_POLLUTANT | rkonfly/CompositeAirQualityDetector；saimosen/SMS8910(V2) |
| PM_ZERO_CHECK(pm10/pm25) | saimosen/SMS8910(V2) |
| CUTTER_CHANGER(pm10/pm25) | qdrgdz/QGQGHZZ |
| PAPER_TAPE(pm10/pm25) | sailhero/XHPM3000EV3、XHPM2000EV2 |
| VALVE_GROUP(so2/co/no/o3) | saimosen/SMS8910(V2) |

矩阵组合 48 个 = profile 注册 48 个（多实例槽共享 deviceType 注册键）。**维护约定**：新增厂商/型号时改 DeviceMappings + StationProfileRegistrar，本表同步更新（本表是注册表的人读镜像，非独立真相源）。
