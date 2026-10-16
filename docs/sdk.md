# ASM 对外 SDK 手册（AirStationSdk）

状态：2026-10-16 ｜ 与 `integration-env-air-station-manager` 4.0.0 api 包同步 ｜ 契约真相源=api 包 javadoc，本文是人读整合手册，两者不一致以代码为准并回改本文

ASM（空气站房管理集成）对外查询/控制能力的**进程内 SDK**：同一 JVM 内的其他集成（如推送类集成）经 `AirStationSdk` 读取站房统计、实时快照、报警数据并下发控制，不经 REST、不带 ruoyi 鉴权语义。

---

## 1. 能力总览

| 方法 | 一句话 | 数据源 |
|---|---|---|
| `queryStat` | batch 查多参数统计桶行（数值均值 / 非数值统计值） | `asm_stat_minute/_5min/_hour` |
| `listStatParams` | 可查统计参数目录 | `asm_config_stat` JOIN `asm_config_unit` |
| `querySnapshot` | 单设备实时快照（ASM 展示导向：STANDARD 单位链 + MONITOR 精度链） | registry live（纯内存零样本查询） |
| `queryCustomSnapshot` | 单设备调用方自定义实时投影（逐 attr 指定单位/精度） | registry live state（纯内存零样本查询） |
| `queryAlarmEntries` | 按报警标识+时间段查报警条目（含持续中） | `asm_alarm_record` |
| `listAlarmTypes` | 合法报警标识目录 | `asm_alarm_rule` |
| `control` | 控制写（`SdkControlRequest`，origin 调用方声明 + unit 换算；落审计，异步终态） | `asm_control_record` |

## 2. 快速开始

**依赖**：消费方 pom 以 `provided` 引本集成 jar（SDK 与消费方同 JVM，由 core 类加载器体系装配，不打包传递）；跨集成类可见性须在消费方 `ecat-config.yml` 声明 `dependencies`（ASM artifactId=`integration-env-air-station-manager`）。

**获取实例**（进程内 registry，不经 Spring bean）：

```java
AirStationSdk sdk = ((EnvAirStationManagerIntegration) core.getIntegrationRegistry()
    .getIntegration("com.ecat:integration-env-air-station-manager"))
    .getAirStationSdk();
```

**import 约束**：只允许 import `api` 包（接口+不可变 DTO）；护栏测试 `AsmApiPackagePurityTest` 强制 api 包零 ruoyi/Spring/ecat-core 依赖，内部实现类不得反向泄漏。

## 3. 方法参考

### 3.1 queryStat — 统计桶行查询

```java
List<SdkStatRow> queryStat(List<AsmParamKey> params, AsmStatGranularity granularity,
                           AsmIntervalMode mode, Instant start, Instant end)
```

- **batch 单 SQL**：N 参数一次往返（tuple IN-list），禁 N+1；结果 dataTime 升序。
- **入参**：`params`=(uid, attrId) 元组非空；`granularity`=MINUTE/FIVE_MIN/HOUR；`mode`=FRONT/BACK（须与物化 scope 一致否则返空）；`start<end`（UTC Instant）。
- **窗口上限**：MINUTE/FIVE_MIN ≤31 天，HOUR ≤400 天（超限抛）。
- **值双槽（互斥，同 raw 层惯例）**：数值 series 桶均值在 `value`（STORAGE 存储单位原值，机对机不做展示换算，按 `unit` 解释）；非数值 series（ALARM/STATE 白名单）`value=null`、统计值在 `valueText`（ALARM: normal/alarm；STATE: 状态串），`unit=""` 显无单位。
- **无数据≠正常**：非数值 series 无样本的桶不写行——查不到行=该时段无数据，非 normal；历史窗口内行稀疏是常态（事件驱动属性）。
- **无缓存**：直连 mapper（毫秒级）；外部轮询分钟级无压力。

### 3.2 listStatParams — 统计参数目录

```java
List<SdkParamMeta> listStatParams()
```

返回当前全部可查 series：`(uid, attrId)` + 中文显示名（live attr displayName，设备未绑定降级 attrId 原文）+ STORAGE 单位 + 适用粒度掩码（bit0=分/bit1=5分/bit2=时）。按 uid/attrId 升序；配置未 seed 返空。消费方据此构建自己的参数选择与 `AsmParamKey` 清单。

### 3.3 querySnapshot — 实时快照

```java
List<SdkSnapshotAttr> querySnapshot(String uid)
```

单台站房设备当前全部属性行：live 唯一源，**ASM 展示导向**：单位先走 STANDARD 读出口（`asm_config_unit` STANDARD 行），精度再走 ASM MONITOR `display_precision` → attr def → 默认 2 位链路；不回查样本表。无值参数（命令类/未绑定/未喂数）按无数据处理出 DEF 占位行（`source` 标记 LIVE/DEF，占位行 `value`/`valueText`/`updateTime` 全 null）；`value`/`valueText` 双槽互斥；registry 无该设备返空列表。

### 3.4 queryCustomSnapshot — 调用方自定义实时投影

```java
List<SdkCustomSnapshotAttr> queryCustomSnapshot(SdkCustomSnapshotRequest request)
```

一次查询一台站房设备的指定属性行；行序与 `projections` 完全一致。数值链路固定为：

```text
LD AttrState.value -> state.nativeUnit -> 请求 targetUnitFullName -> 请求 displayPrecision（HALF_EVEN）
```

- **严格契约与错误类型**：调用方契约错误（request/uid/projections/attrId 形状、未知 attr、不支持复杂类型、非法单位、跨单位类、非法精度）同步抛 `IllegalArgumentException`；LD 内部契约缺失/不可达（attr 实例缺失、nativeUnit 在非空目标下缺失、比率 null、state.value 非 Number/NaN/Infinite、非数值 LIVE `displayValue` 缺席）抛 `IllegalStateException`。任一 projection 失败时整个请求不返回部分结果。
- **数值行**：`targetUnitFullName` 必填——空串=明确不换算（dimensionless 数值 `unit=null`；带量纲直通 native unit）；非空=单位 full string（如 `PressureUnit.MPA`，用 core 单位枚举 `getFullUnitString()` 构造，禁止 `MPa` 展示符号）。`displayPrecision` 必须为 0..6。`value` 是最终 Double；`valueText` 是一次 HALF_EVEN 后固定小数串（`123.45679`/3 位 -> `"123.457"`）。
- **展示型非数值行**（Text/Time/Binary/Select/I18nText 白名单）：`targetUnitFullName=""`、`displayPrecision=null`；LIVE `valueText=AttrState.displayValue`，`value/unit/displayPrecision=null`。
- **DEF 行**：attr 定义存在但 state/value 缺席；定义存在但 attr 实例缺失属 LD 内部错误抛 `IllegalStateException`。数值 DEF 保留已验证的 `unit/displayPrecision`（dimensionless DEF `unit=null`），非数值 DEF 两者为 null；值、状态、时间均为 null，`source=DEF`。
- **设备未注册**：与 `querySnapshot` 相同返空列表。

**old vs new（为什么已有 `querySnapshot` 还要本方法）**：

| 维度 | `querySnapshot`（old） | `queryCustomSnapshot`（new） |
|---|---|---|
| 单位/精度选择权 | STANDARD 单位链 + ASM MONITOR 精度链（页面展示导向） | 调用方逐 attr 显式声明 |
| 数值源 | 经 `resolveDisplay` 出口 | 仅 `AttrState.value + nativeUnit` |
| 读取 `AttrState.displayValue` | 用于展示/非数值 | 数值路径禁读；仅展示型非数值路径读取 |
| 单位不可达行为 | 按既有展示语义处理 | 显式失败，不降级源单位 |
| 失败模型 | 查询/映射错误按既有语义 | 契约错误同步抛，不返回部分结果 |

**存量精度边界**：本方法不会恢复上游设备、物理映射或 LD state 构建时已经丢失的小数位；它能消除的是 ASM 读出口再次应用展示单位/精度造成的额外损失。上游只保留两位时，请求 6 位只能得到尾随零，不是凭空补出的物理精度。

**调用示例**（uid/attrId 必须使用 LD 常量，单位 full string 必须由单位枚举生成）：

```java
import com.ecat.core.State.Unit.PressureUnit;
import com.ecat.integration.logicdevice.Meta.AttrIdStation.StandardGas;
import com.ecat.integration.logicdevice.Meta.DeviceId;

List<SdkCustomSnapshotAttr> rows = sdk.queryCustomSnapshot(SdkCustomSnapshotRequest.builder()
    .uid(DeviceId.Station.STANDARD_GAS_SO2)
    .projections(Collections.singletonList(SdkCustomSnapshotProjection.builder()
        .attrId(StandardGas.GAS_PRESSURE_REMAINING)
        .targetUnitFullName(PressureUnit.MPA.getFullUnitString())
        .displayPrecision(3)
        .build()))
    .build());
```

### 3.5 queryAlarmEntries — 报警条目查询（按标识+时间段）

```java
List<SdkAlarmEntry> queryAlarmEntries(String alarmType, Instant start, Instant end, int limit)
```

- **窗口语义 = episode 区间重叠，查询窗左开右闭 `(start, end]`**：`start_time <= end AND (end_time IS NULL OR end_time > start)`。边界两原则：end 时刻触发的报警**算本期**（右闭防漏）；恰在 start 时刻闭单的**归上期**（左开防重）——连续分窗（上窗 end=下窗 start）每条报警恰好命中一次。
- **持续中天然可查**：ACTIVE 行（end_time=null）episode 开区间到 +∞，窗前开始至今未恢复的报警对任意覆盖窗口命中。
- **行形状 = 前端报警表格列** + alarmType 回显（见 §4 SdkAlarmEntry）；`deviceLabel`/`attrLabel` 解析不到=null（回退标识字段归消费方）；`durationMs` 持续中=null（不虚构时长）。
- 排序 triggerTime 降序；`limit` 1..1000。

### 3.6 listAlarmTypes — 报警标识目录

```java
List<SdkAlarmTypeMeta> listAlarmTypes()
```

`asm_alarm_rule` 全量三列投影（alarmType/ruleName/severity），按 alarmType 升序；坏配置行隔离跳过（同规则索引语义）。报警标识为**语义化 snake_case**（2026-09-09 起替代数字标识），seed 16 类：

`room_temp_abnormal` 设备间温度异常 ｜ `room_humidity_abnormal` 湿度异常 ｜ `water_leak` 漏水 ｜ `supply_voltage_abnormal` 供电波动 ｜ `regulated_voltage_abnormal` 稳压波动 ｜ `current_abnormal` 电流波动 ｜ `gas_leak` 标气泄漏 ｜ `gas_cylinder_low` 标气更换 ｜ `sampling_tube_temp_abnormal` 采样管温异常 ｜ `device_monitor_alarm` 设备监测 ｜ `power_loss` 断电恢复 ｜ `intrusion` 异常进入 ｜ `interference` 干扰 ｜ `access_control_abnormal` 门禁异常 ｜ `indoor_pm_abnormal` 洁净度 ｜ `range_exceeded` 超范围

（另有用户新建规则的自定义标识；查询前先 `listAlarmTypes()` 枚举合法值。）

### 3.7 control — 控制写

```java
SdkControlResult control(SdkControlRequest request)
```

- **请求对象**（`api/SdkControlRequest`，@Value/@Builder）：origin/unit/caller 全部由调用方显式声明，集成不代调用方做任何隐式决定（origin 决定权归 SDK 调用方）。
- **参数约束**（下表任一不满足=提交受理前同步 fail-fast，抛 `IllegalArgumentException`/`IllegalStateException` 且不落审计行；执行期失败走异步终态 PENDING→SUCCESS/FAILED/TIMEOUT，两类失败不混）：

| 字段 | 约束 |
|---|---|
| uid | 非空白；`logicdevice_station.*` 前缀；设备须已注册 |
| attrId | 非空白；须存在且可写（canValueChange=true，不可写抛 IllegalStateException） |
| value | 非空白：数值属性=数字串；Command/Select/Binary=选项 key（选项非法**不**在提交时校验→异步 FAILED） |
| unit | **null 非法**（区分「漏传」与「明确不指定」）；**空串=不指定单位**（按属性默认单位写入=无换算）；**非空=单位 full string**「枚举类名.枚举常量名」（如 `TemperatureUnit.CELSIUS`，全系统传输口径）；须与属性同量纲（跨量纲换算失败→异步 FAILED，非提交拒绝） |
| origin | `AsmControlOrigin` 二值：**LOCAL=本站/集成自身发起**（本站 web 页面操作、站内报警联动）；**REMOTE=第三方集成代传的远程侧指令** |
| caller | 非空白自由串（不约束格式）：LOCAL=发起方集成坐标（`com.ecat:integration-xxx`）；REMOTE=最终用户标识（`platformA:user123`） |

- **示例**：

```java
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.integration.logicdevice.Meta.AttrIdStation.AirConditioner;
import com.ecat.integration.logicdevice.Meta.DeviceId;

SdkControlResult r = sdk.control(SdkControlRequest.builder()
    .uid(DeviceId.Station.AIR_CONDITIONER_AC1)
    .attrId(AirConditioner.SETPOINT_TEMP)
    .value("26.5")
    .unit(TemperatureUnit.CELSIUS.getFullUnitString()) // 空串=按属性默认单位；禁止 "°C"
    .origin(AsmControlOrigin.LOCAL)           // 第三方代传场景用 REMOTE + 最终用户标识
    .caller("com.ecat:integration-env-push-x")
    .build());
```

- 同步返回 `recordId + origin(回显请求) + result(PENDING)`，执行异步完成回填终态（SUCCESS/FAILED/TIMEOUT），可按 recordId 经 REST `GET /asm-monitor/control/{id}` 回查（SDK 不提供单查方法，终态以回查/自身业务回调为准）。
- **单位口径提示**：全系统传输/留痕一律用单位 full string（`TemperatureUnit.CELSIUS`），`°C`/`mA` 等符号是展示层 `getName()` 输出，**禁止作传输值**；审计行 `requested_value` 带单位时同样记 full string（如 `26.5 TemperatureUnit.CELSIUS`），不带单位记值原文。

## 4. DTO 参考（api 包，@Value/@Builder 不可变）

### SdkStatRow（queryStat 行）
| 字段 | 类型 | 说明 |
|---|---|---|
| logicDeviceUniqueId / attrId | String | series 标识 |
| dataTime | Instant | 桶标注（BACK=右沿 / FRONT=左沿，随入参 mode） |
| value | Double | 数值桶均值（STORAGE 单位）；非数值 series=null |
| valueText | String | 非数值统计值（ALARM: normal/alarm；STATE: 状态串）；数值 series=null |
| validCount / totalCount | Long | 窗口内有效样本数 / 总样本数（非数值=非空文本样本计数） |
| unit | String | STORAGE 单位 full key；null=无量纲；空串=非数值显无单位 |

### SdkAlarmEntry（queryAlarmEntries 行，=前端报警表格列+alarmType）
`alarmType`（查询键回显）/`ruleName`/`logicDeviceUniqueId`+`deviceLabel`（解析不到=null）/`attrId`+`attrLabel`（同）/`severity`（'0'普通 '1'重要 '2'紧急原始码，映射归消费方）/`status`（ACTIVE 持续中｜INACTIVE 已闭单）/`triggerTime`（=start_time 首触发）/`recoverTime`（=end_time；持续中=null）/`durationMs`（recover−trigger；持续中=null）/`description`（原文，uid/attrId 内嵌由消费方按 label 重组）。

### 其余
| DTO | 字段 |
|---|---|
| `AsmParamKey` | logicDeviceUniqueId, attrId |
| `SdkParamMeta` | logicDeviceUniqueId, attrId, paramDisplayName, storageUnit, applicableGranularityMask |
| `SdkSnapshotAttr` | attrId, value, valueText, unit, updateTime, source(LIVE/DEF) |
| `SdkCustomSnapshotRequest` | uid, projections（非空且 attrId 唯一） |
| `SdkCustomSnapshotProjection` | attrId, targetUnitFullName(null 非法/空串=不换算/full string), displayPrecision(数值 0..6/非数值 null) |
| `SdkCustomSnapshotAttr` | attrId, value, valueText, unit(SdkUnit；dimensionless 数值=null), displayPrecision, status(SdkAttributeStatus), updateTime, source(LIVE/DEF) |
| `SdkUnit` | fullName(机对机 full string), symbol(短符号), displayName(i18n 名) |
| `SdkAttributeStatus` | key(稳定枚举名), displayName(i18n 名) |
| `SdkAlarmTypeMeta` | alarmType, ruleName, severity |
| `SdkControlRequest` | uid, attrId, value, unit(null 非法/空串=不指定/full string), origin(LOCAL/REMOTE), caller（约束表见 §3.6） |
| `SdkControlResult` | recordId, origin(回显请求), result, error, durationMs |
| 枚举 | `AsmStatGranularity`(MINUTE/FIVE_MIN/HOUR)、`AsmIntervalMode`(FRONT/BACK)、`AsmControlOrigin`(LOCAL/REMOTE) |

## 5. 横切语义

- **时区**：入参/出参时刻全 UTC Instant；桶 `data_time` 亦 UTC（core 壁钟=Asia/Shanghai 仅影响 REST 层，SDK 层无涉）。
- **前后标**：FRONT `[S,E)` 桶标=左沿；BACK `(L,R]` 桶标=右沿；同桶双 mode 各自成行（PK 含 interval_mode），消费方选定一种 mode 查询即可。
- **单位**：全部 STORAGE 口径（存储单位原值，不做展示偏好换算）；换算需求归消费方。
- **非数值 series 白名单**：16 项 bind attr（ALARM 9 + STATE 7），真相源 `support/AsmStatSeriesKindClassifier` 与 `docs/stat-series-rules.md`；未在白名单的状态属性不进统计（计算属性/共享状态/命令量）。

## 6. 消费方先例

`env-push-neimenggu`（5 分钟统计推送）：`AbstractStatFreqPusher` 以 `queryStat(keys, FIVE_MIN, BACK, start, end)` 批量取桶行推送外部平台——batch 取数、BACK 对齐报表口径、异常 catch 降级的用法可参考。

## 7. 兼容与演进原则

- api 包=稳定契约：**追加字段/追加方法**向后兼容；**删改方法**属破坏性变更，须先实证仓内+workspace 零调用方（先例：`queryAlarmRecords` 2026-09-09 删除，其「漏持续中+raw 投影低契约」由 `queryAlarmEntries` 取代）。
- 2026-09-11 破坏性变更：`control(uid, attrId, value, caller)` 四参删除，改 `control(SdkControlRequest)`（新增 origin 显式声明 + unit 单位换算口径，见 §3.6）；删除前实证仓内+workspace 零调用方。伴随 **origin 语义翻转**：REMOTE 由「本站 web 操作」收窄为「第三方集成代传的远程侧指令」（本站页面操作/站内联动改记 LOCAL，审计 caller=最终用户标识）；存量审计行已一次性迁移 REMOTE→LOCAL，`SdkControlResult.origin` 同步改为回显请求声明值。
- 语义变更（口径/窗口边界）以 api javadoc 为第一真相源并在本文同步。
