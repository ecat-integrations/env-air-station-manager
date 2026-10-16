# ASM 统计规则与参数分类（需求 + 维护手册）

日期：2026-09-08 ｜ 状态：已落地（2026-09-09 全链实测收口，单测 367 绿+部署库 e2e 双 mode 三粒度全验证，实测记录见执行文档 §7） ｜ 执行设计：根目录 `docs/2026-09-08-asm-nonnumeric-stat-design.md`

本文是 ASM 统计参数的**分类真相源**：哪些参数进统计、按什么规则统计、后期新增参数如何维护。
实现细节（DDL/引擎/seed）不在本文展开，只给入口索引。

## 1. 统计对象判据

- **只统计 bind attr**（`mapable=true`，值来自物理设备映射，如安防的水浸/烟感/红外）。
- **不统计计算属性**（`mapable=false`，由 logic 阈值/聚合派生，如 `temp_alarm` 由温度+阈值算出、
  共享 `alarm_status` 由 bus 聚合）——计算结果可由源数据重算，统计它属冗余。
- 不统计命令量（瞬态触发）、文本/时间量、事件快照量（门禁打卡详情）、暂存配置量。
- 「bind attr 且非数值」**不自动**进统计（反例：门禁 `last_*` 事件快照、`fence_status`/`ai_running`
  用户裁定不统计）——最终准入 = §3 白名单 + 用户裁定。白名单是唯一真相源，判据只作归类参考。

## 2. 统计规则（粒度 × 数据类别）

三类数据、三种聚合语义。前后标沿用引擎既有定义：**FRONT（前标）** `[S,E)` 桶标=左沿；
**BACK（后标）** `(L,R]` 桶标=右沿；级联取子行时 mode 恒对齐（FRONT 子桶→FRONT 父桶）。

| 数据类别 | minute ← raw | 5min ← minute | hour ← minute |
|---|---|---|---|
| **AVG**（数值 bind attr，既有） | 窗口内 `value_num` 算术均值 | 子桶 valid_count 加权均值 | 子桶 valid_count 加权均值 |
| **ALARM**（报警类） | 窗口内任一样本=alarm → **alarm**；全 normal → normal；无样本不写行 | 取**桶标时刻** minute 行的值（后标 10:10 桶 → minute 10:10 行；前标 10:05 桶 → minute 10:05 行）；缺行不写 | 窗口内**任一** minute 行=alarm → **alarm**；全 normal → normal；无 minute 行不写行 |
| **STATE**（状态/控制类） | 取距**桶标时刻**最近的实时样本值（后标 03 分 = (02,03] 内最靠近 03:00 的样本；前标 03 分 = [03,04) 内最靠近 03:00 的样本；等距取时间更新者） | 取桶标时刻 minute 行的值（同 ALARM）；缺行不写 | 取桶标时刻 minute 行的值（即整点 00 分行）；缺行不写 |

要点：

- 非数值统计值落 `asm_stat_*.value_text`（与 raw 层 `asm_data_sample.value_text` 同名同义），
  数值行 `value_text` 为 NULL、非数值行 `avg_value` 为 NULL。
- 计数列语义不漂移：非数值 minute 行 `valid_count/total_count` = 窗口内非空文本样本数 / 窗口总样本数；
  5min/hour **点采样**行继承所取 minute 行的计数；hour **ALARM OR** 行 = 成员 minute 行计数求和。
- 「不写行」与既有引擎「全空桶不写行」一致：无数据 ≠ normal，历史查询能区分「正常」与「无数据」。
- STATE「等距取时间更新者」的可达形态：分钟桶内样本恒在桶标同侧（FRONT 桶标=左沿、BACK=右沿），不同时刻样本距桶标严格单调、不可能等距；等距仅出现在**同一 dataTime 的重复样本**（raw 表 bigserial 主键允许同刻多行）——此时保留先抵达者（同刻同值归并无差；同刻异值属上游重复上报，计数仍计全量）。
- ALARM 与 STATE 口径各自独立明确（2026-09-08 用户裁定）：ALARM 关注「期间是否发生过报警」
  （minute 小窗 OR、hour 全窗 OR、5min 对齐国标报表点采样）；STATE 关注「该时刻设备处于什么状态」（点采样）。

## 3. 参数分类明细

### 3.1 ALARM 类（9 项，值域 normal/alarm）

| 设备类型 | attrId | 中文名 |
|---|---|---|
| SecurityAlarm 安防 | `ir_alarm` | 红外报警 |
| SecurityAlarm 安防 | `water_leak` | 漏水报警 |
| SecurityAlarm 安防 | `temp_alarm_1` / `temp_alarm_2` | 温感1 / 温感2 |
| SecurityAlarm 安防 | `smoke_1` / `smoke_2` | 烟感1 / 烟感2 |
| Camera 摄像头 | `intrusion_alarm` / `interference_alarm` | 入侵报警 / 干扰报警 |
| ElectronicFence 电子围栏 | `intrusion_alarm` | 入侵报警 |

### 3.2 STATE 类（7 项）

| 设备类型 | attrId | 中文名 | 值域 |
|---|---|---|---|
| AirConditioner 空调 | `running_mode` | 运行模式 | auto/cooling/dehumidifying/fan_only/heating |
| AirConditioner 空调 | `fan_speed` | 风机转速 | auto/low/medium/high |
| AirConditioner 空调 | `power_status` | 电源状态 | on/off |
| Lighting 照明 | `switch_status` | 开关状态 | on/off |
| ExhaustFan 排风扇 | `speed` | 转速档位 | off/low/medium/high |
| AccessControl 门禁 | `lock_status` | 门锁状态 | locked/unlocked/password |
| Calibrator 校准器 | `current_gas_type` | 当前气体类型 | 气体枚举 |

### 3.3 不统计（按原因归类）

| 原因 | 明细 |
|---|---|
| **计算属性（mapable=false），26 项** | TH：`temp_alarm` `humidity_alarm`；IndoorPollutant：`so2/no2/o3/co/pm10/pm25_alarm`；PowerMeter：`voltage_a/b/c_alarm`；VoltageRegulator：`voltage_ch1-4_alarm`；UPS：`power_failure_alarm`；SamplingTube：`retention_time_alarm` `tube_temp_alarm`；FilterChanger：`filter_warning` `condensation_warning`；PaperTape：`tape_alarm` `unregistered_replacement_alarm`；StandardGas：`volume_warning` `pressure_warning` `gas_leak_alarm`；Cleanliness：`cumulative_alarm` |
| **共享状态属性（聚合/派生）** | `alarm_status`（bus 聚合）、`online_status`、`manual_status`（人工标记）、`running_status`（前三者派生）——C 类全不统计（2026-09-08 裁定） |
| **用户裁定剔除的设备控制属性** | VoltageRegulator `relay_ch1-4`、Camera `ai_running`、ElectronicFence `fence_status`、ZeroGasRelay `relay_status`、ValveGroup `valve_status` |
| **standalone 状态（非 bind attr）** | Calibrator `zero_gas` `instrument_status`（系统写入，非物理映射） |
| **事件快照（bind attr 但无统计语义）** | AccessControl `last_person` `last_photo` `event_info` `last_verify_type` `last_access_result` `last_access_time` `event_time` |
| **命令量（瞬态）** | `change_filter` `calibrator_command` `door_control` `apply_manual_command` `reset_command` `reset_unregistered_alarm_command` `reset_dust_command` |
| **文本/时间/暂存** | `cylinder_id` `gas_source` `bound_consumable_id` `last_change_time` `new_zero_gas` |

### 3.4 数值类

其余全部 `LNumericAttribute` bind attr（温湿度/电压电流/流量/设定值等）自始按 AVG 统计，不在本次调整范围。

## 4. 新增参数维护指南

新增站房设备参数（或新设备类型）时按此流程：

1. **归类**：按 §1 判据先归类——计算属性 / 共享状态 / 命令 / 文本 / 事件快照 → 默认不统计，**无需任何改动**。
2. **bind attr 且非数值** → 判断语义：报警类（有报警即记录，ALARM）还是状态类（看时刻状态，STATE），
   **报用户确认**后登记白名单。
3. **登记**（两处，缺一不可）：
   - 代码：`AsmStatSeriesKindClassifier`（ASM 仓 support 包）加一行 `attrId → Kind`；
   - 文档：本文 §3.1/§3.2 明细表同步加行。
4. **验证**：分类清单单测补断言；绑定物理设备后跑一轮数据，确认 `asm_config_stat` 落行、
   `asm_stat_minute/5min/hour` 出桶。
5. 数值 bind attr 无需登记（numeric 自动走 AVG seed）。

attrId 以 `AsmStatSeriesKindClassifier` 白名单为唯一真相源；本文明细表与白名单不一致时以代码为准并回改本文。

## 5. 实现位置索引

| 环节 | 位置 |
|---|---|
| 分类白名单 | `support/AsmStatSeriesKindClassifier` + `AsmStatSeriesKind`（AVG/ALARM/STATE/NONE） |
| seed | `service/AsmSeedService` / `consumer/AsmDataSampleConsumer#seedIfFirstSeen` |
| 聚合引擎 | `service/AsmStatAggregationEngine`（三级物化，桶划分/前后标= `support/AsmStatGridBucketing` / `AsmIntervalMode`） |
| 表结构 | `src/main/resources/sql/asm_data.sql`（asm_stat_minute/5min/hour `value_text` 列） |
| 读出口 | `service/AsmHistoryQueryService` + `mapper/AsmHistoryQueryMapper.xml` |
