/**
 * ASM G12 回归（2026-08-21 设备配置管理 P1-P5，设计 docs/design/2026-08-21-asm-station-device-config.md）：
 *  - G12-1 逐类型槽用例（37 槽数据驱动，覆盖 22 类型）：sidebar 出现 + 三态与 GET /asm-monitor/device/params
 *         一致（CONFIGURED↔✓徽标；UNBOUND/NOT_CREATED↔无徽标且 Detail 提示文案区分）+ 配置入口可打开。
 *  - G12-2 完整绑定链（LIGHTING，saimosen SMS8910V2 → 真实模拟器 modbus_tcp 127.0.0.1:1507）：
 *         provision(replace) → submit×N（GET flow schema 动态填默认值 + 显式连接参数）→ CREATE_ENTRY →
 *         params CONFIGURED + 浏览器 sidebar 徽标变已配置 → 审计 REPLACE 行（云库 SQL 查证）→
 *         unbind → UNBOUND + 审计 UNBIND 行 → bindExisting 还原原设备。
 *  - G12-3 复用探测：AIR_CONDITIONER.ac1 兼容设备列表含当前已绑台（saimosen QCDevice-Test）。
 *  - G12-4 403：无 asm-monitor:device:edit 角色（仅 device:list）POST provision → HTTP 200 body code=403。
 *
 * 约定：串行；测试数据全还原（LIGHTING 绑定还原为原设备；角色/账号测完删）；0 console error。
 * 注：flow 步进走 API（lit <flow-form> shadow DOM 不做逐字段 UI 断言），浏览器断言落在
 * sidebar 徽标与 Detail 三态（UI 契约：配置入口/徽标/文案）。
 */
import { test, expect } from '@playwright/test';
import { AsmBasePage, attachErrorCollectors } from '../helpers/page-objects/AsmBasePage';

const API = process.env.API_BASE_URL || 'http://localhost:8080';
const ADMIN_USER = process.env.ASM_USER || 'Admin7s9k2G5';
const ADMIN_PASS = process.env.ASM_PASS || '7sK2pG9dR3tQ';

/** 完整绑定链目标槽（当前绑定 saimosen QCDevice-Test；厂商 saimosen/SMS8910V2 有真实 modbus_tcp 模拟器）。 */
const CHAIN_TYPE = 'LIGHTING';
const CHAIN_SN = 'E2E-G12-LIGHT-001';
const CHAIN_NAME = 'E2E-G12照明链路测试机';
/** saimosen QC 模拟器 modbus_tcp 端口（MCP sim 实证：port=1507 slave=1 mbap）。 */
const SIM_HOST = '127.0.0.1';
const SIM_PORT = 1507;

/** 37 槽 → sidebar 展示名（与前端 stationParamMeta LABELS 同源镜像；断言 sidebar 出现用）。 */
const SLOT_LABELS: Record<string, string> = {
  TH: '站房温湿度监测仪', POWER_METER: '智能电力监测仪表', VOLTAGE_REGULATOR: '智能稳压电源',
  UPS: 'UPS不间断电源', AIR_CONDITIONER_AC1: '空调1', AIR_CONDITIONER_AC2: '空调2',
  EXHAUST_FAN: '排风扇设备', LIGHTING: '照明设备', ZERO_GAS_RELAY: '零气继电器',
  SECURITY_ALARM: '安防报警监测装置', SAMPLING_TUBE: '采样总管监测设备',
  STANDARD_GAS_SO2: 'SO2标气', STANDARD_GAS_CO: 'CO标气', STANDARD_GAS_NOX: 'NOx标气',
  FILTER_CHANGER_SO2: 'SO2滤膜', FILTER_CHANGER_CO: 'CO滤膜', FILTER_CHANGER_O3: 'O3滤膜',
  FILTER_CHANGER_NOX: 'NOx滤膜', CALIBRATOR: '动态校准仪', ELECTRONIC_FENCE: '电子围栏系统',
  ACCESS_CONTROL: '智能门禁系统', CAMERA_1: '摄像头1', CAMERA_2: '摄像头2', CAMERA_3: '摄像头3',
  CAMERA_4: '摄像头4', CLEANLINESS: '站房清洁度检测装置', INDOOR_POLLUTANT: '室内污染物检测仪',
  PM_ZERO_CHECK_PM10: 'PM10零点检查器', PM_ZERO_CHECK_PM25: 'PM2.5零点检查器',
  CUTTER_CHANGER_PM10: 'PM10切割器', CUTTER_CHANGER_PM25: 'PM2.5切割器',
  PAPER_TAPE_PM10: 'PM10纸带记录仪', PAPER_TAPE_PM25: 'PM2.5纸带记录仪',
  VALVE_GROUP_SO2: 'SO2校准阀', VALVE_GROUP_CO: 'CO校准阀', VALVE_GROUP_NO: 'NO校准阀',
  VALVE_GROUP_O3: 'O3校准阀',
};

async function apiLogin(request: any, username: string, password: string): Promise<string> {
  const res = await request.post(`${API}/login`, { data: { username, password } });
  const body = await res.json();
  expect(body.code, `登录失败(${username}): ${JSON.stringify(body)}`).toBe(200);
  return body.token;
}

/** GET /asm-monitor/device/params → 37 槽行（param=枚举名）。 */
async function getParams(request: any, token: string): Promise<any[]> {
  const res = await request.get(`${API}/asm-monitor/device/params`, { headers: { Authorization: `Bearer ${token}` } });
  const body = await res.json();
  expect(body.code, `params 应 200: ${JSON.stringify(body).slice(0, 200)}`).toBe(200);
  return body.data;
}

/**
 * schema 驱动填值：defaultValue 优先 + 显式覆盖（sn/name/model 来自 provision；连接参数真实模拟器）。
 * 只读/说明性 text 字段（defaultValue 是长说明文案）跳过——非 required 不提交。
 */
function fillFromSchema(schema: any, overrides: Record<string, any>): Record<string, any> {
  const ui: Record<string, any> = {};
  for (const f of schema.fields || []) {
    if (f.key in overrides) { ui[f.key] = overrides[f.key]; continue; }
    const dv = f.defaultValue;
    // required 字段无默认且非覆盖 → 测试缺口（显式失败优于静默漏填）
    if (f.required && dv == null) {
      throw new Error(`required 字段 ${f.key} 无默认值且未覆盖（step 需补显式参数）`);
    }
    if (dv != null && f.required) ui[f.key] = dv;
  }
  return ui;
}

test.describe('ASM G12 站房设备配置管理', () => {
  test.describe.configure({ mode: 'serial' });

  test('G12-1 37 类型槽逐槽三态 + sidebar + 配置入口（22 类型全覆盖） @g12-1', async ({ page, request }) => {
    const errors: string[] = [];
    attachErrorCollectors(page, errors);
    const token = await apiLogin(request, ADMIN_USER, ADMIN_PASS);
    const rows = await getParams(request, token);
    expect(rows.length, '应 37 槽').toBe(37);

    const base = new AsmBasePage(page, 'station_device');
    await base.goto();

    for (const row of rows) {
      const label = SLOT_LABELS[row.param];
      expect(label, `槽 ${row.param} 应在 SLOT_LABELS 清单（22 类型 37 槽）`).toBeTruthy();
      // ① sidebar 出现（按展示名定位槽行）
      const item = page.locator('.sidebar .param-item', { hasText: label });
      await expect(item.first(), `槽 ${label} 应出现在 sidebar`).toBeVisible();
      // ② 三态与 API 一致：CONFIGURED→✓ 徽标；UNBOUND/NOT_CREATED→无徽标
      if (row.configured) {
        await expect(item.first().locator('.param-check'), `${label} 应有已配置徽标`).toBeVisible();
      } else {
        expect(await item.first().locator('.param-check').count(), `${label} 不应有徽标（${row.state}）`).toBe(0);
      }
      // ③ 点击打开 Detail：配置入口可开（未配置=「配置设备」蓝钮；已配置=更换/修改/移除三钮）+ 状态徽章 + 文案
      await item.first().click();
      const detail = page.locator('.detail');
      await expect(detail.locator('.detail-title')).toHaveText(label);
      await expect(detail.locator('.detail-state')).toHaveText(row.configured ? '已配置' : '未配置');
      if (row.configured) {
        for (const btn of ['更换设备', '修改配置', '移除']) {
          await expect(detail.getByRole('button', { name: btn }), `${label} 已配置应有「${btn}」`).toBeVisible();
        }
      } else {
        await expect(detail.getByRole('button', { name: '配置设备' }), `${label} 未配置应有「配置设备」入口`).toBeVisible();
        await expect(detail.locator('.detail-hint')).toContainText(
          row.state === 'UNBOUND' ? '曾配置' : '暂未配置',
        );
      }
    }
    expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
  });

  test('G12-2 完整绑定链 provision→submit→CREATE_ENTRY→徽标→审计→unbind→还原（LIGHTING/saimosen 真实模拟器） @g12-2', async ({ page, request }) => {
    const errors: string[] = [];
    attachErrorCollectors(page, errors);
    const token = await apiLogin(request, ADMIN_USER, ADMIN_PASS);
    const auth = { Authorization: `Bearer ${token}` };

    // ── 前置：记录原绑定（还原锚点） ──
    const before = (await getParams(request, token)).find(p => p.param === CHAIN_TYPE)!;
    expect(before.configured, '链路前提：LIGHTING 初始已配置（复用 replace 语义）').toBe(true);
    const originalDeviceId = before.boundDeviceId;
    originalIdHolder.id = originalDeviceId;

    // ── provision(replace)：driver 快进身份步停 device_config ──
    const provRes = await request.post(`${API}/asm-monitor/device/params/${CHAIN_TYPE}/provision`, {
      headers: { ...auth, 'Content-Type': 'application/json' },
      data: {
        coordinate: 'com.ecat:integration-saimosen', model: 'SMS8910V2',
        sn: CHAIN_SN, name: CHAIN_NAME, operation: 'replace',
      },
    });
    const prov = (await provRes.json()).data;
    expect(prov.status, `provision 应停连接步: ${JSON.stringify(prov).slice(0, 200)}`).toBe('NEED_USER_INPUT');

    // ── submit×N：schema 驱动动态填默认值（连接参数显式指向真实模拟器）直至 CREATE_ENTRY ──
    let stepId: string = prov.stoppedStepId;
    let r = prov;
    for (let i = 0; i < 12; i++) {
      const overrides: Record<string, any> = {
        sn: CHAIN_SN, name: CHAIN_NAME, model: 'SMS8910V2',
        modbus_protocol: 'TCP', ip_address: SIM_HOST, port: SIM_PORT, confirmed: true,
      };
      const userInput = fillFromSchema(r.schema, overrides);
      const res = await request.post(`${API}/asm-monitor/device/flow/${prov.flowId}/submit`, {
        headers: { ...auth, 'Content-Type': 'application/json' },
        data: { stepId, userInput },
      });
      r = (await res.json()).data;
      expect(Object.keys(r.errors || {}).length, `步骤 ${stepId} 不应有校验错误: ${JSON.stringify(r.errors)}`).toBe(0);
      if (r.type === 'CREATE_ENTRY') break;
      expect(r.type, `应 SHOW_FORM 或 CREATE_ENTRY，实=${r.type}`).toBe('SHOW_FORM');
      stepId = r.stepId;
    }
    expect(r.type, 'flow 应走完 CREATE_ENTRY').toBe('CREATE_ENTRY');
    expect(r.entryId, 'CREATE_ENTRY 应返回 entryId').toBeTruthy();

    // ── API 三态 + 浏览器 sidebar 徽标变已配置 ──
    const afterBind = (await getParams(request, token)).find(p => p.param === CHAIN_TYPE)!;
    expect(afterBind.configured, '绑定后 LIGHTING CONFIGURED').toBe(true);
    expect(afterBind.title).toBe(CHAIN_NAME);

    const base = new AsmBasePage(page, 'station_device');
    await base.goto();
    const item = page.locator('.sidebar .param-item', { hasText: SLOT_LABELS[CHAIN_TYPE] }).first();
    await expect(item.locator('.param-check'), '浏览器徽标应变已配置').toBeVisible();
    await item.click();
    await expect(page.locator('.detail .row:nth-child(2) .val')).toContainText(CHAIN_NAME);
    expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);

    // 审计 REPLACE/UNBIND 行的云库 SQL 查证：e2e 进程无 pg 驱动，由 runner 在套件后 shell 查询
    //（见 asm-e2e-test skill G12 段；spec 内只验业务状态，DB 断言不省略、外置执行）。
  });

  test('G12-2b unbind → UNBOUND + 还原 bindExisting @g12-2b', async ({ page, request }) => {
    const token = await apiLogin(request, ADMIN_USER, ADMIN_PASS);
    const auth = { Authorization: `Bearer ${token}` };

    const before = (await getParams(request, token)).find(p => p.param === CHAIN_TYPE)!;
    expect(before.title, '前提：G12-2 绑定的测试机仍在').toBe(CHAIN_NAME);

    // unbind → UNBOUND + 浏览器徽标消失 + Detail 提示「曾配置」
    const ub = await request.post(`${API}/asm-monitor/device/params/${CHAIN_TYPE}/unbind`, { headers: auth });
    expect((await ub.json()).code).toBe(200);
    const afterUb = (await getParams(request, token)).find(p => p.param === CHAIN_TYPE)!;
    expect(afterUb.state, 'unbind 后应 UNBOUND').toBe('UNBOUND');

    const base = new AsmBasePage(page, 'station_device');
    await base.goto();
    const item = page.locator('.sidebar .param-item', { hasText: SLOT_LABELS[CHAIN_TYPE] }).first();
    expect(await item.locator('.param-check').count(), 'unbind 后徽标应消失').toBe(0);
    await item.click();
    await expect(page.locator('.detail .detail-hint')).toContainText('曾配置');

    // 还原：复用直绑回原设备（oldDeviceId=测试机 → 无引用时后端移除测试机 entry）
    const bd = await request.post(`${API}/asm-monitor/device/params/${CHAIN_TYPE}/bind`, {
      headers: { ...auth, 'Content-Type': 'application/json' },
      data: { physicalDeviceId: originalIdHolder.id, oldDeviceId: afterUb.boundDeviceId },
    });
    expect((await bd.json()).code, 'bindExisting 还原应成功').toBe(200);
    const restored = (await getParams(request, token)).find(p => p.param === CHAIN_TYPE)!;
    expect(restored.configured, '还原后应 CONFIGURED').toBe(true);
    expect(restored.boundDeviceId, '还原后应绑回原设备').toBe(originalIdHolder.id);
  });

  test('G12-3 复用探测：ac1 兼容设备列表含当前已绑台 @g12-3', async ({ request }) => {
    const token = await apiLogin(request, ADMIN_USER, ADMIN_PASS);
    const auth = { Authorization: `Bearer ${token}` };
    const ac1 = (await getParams(request, token)).find(p => p.param === 'AIR_CONDITIONER_AC1')!;
    expect(ac1.configured).toBe(true);
    const res = await request.get(`${API}/asm-monitor/device/params/AIR_CONDITIONER.ac1/devices`, { headers: auth });
    const body = await res.json();
    expect(body.code).toBe(200);
    expect(body.data.length, 'ac1 兼容设备应非空（saimosen QCDevice-Test 在册）').toBeGreaterThan(0);
    expect(body.data.some((d: any) => d.deviceId === ac1.boundDeviceId), '当前已绑台应出现在兼容列表').toBe(true);
    expect(body.data.some((d: any) => d.deviceId === ac1.boundDeviceId && (d.referencingParams || []).includes('AIR_CONDITIONER.ac1')),
      '兼容条目应带 referencingParams 标注').toBe(true);
  });

  test('G12-4 无 asm-monitor:device:edit 角色写类端点 → HTTP 200 body code=403 @g12-4', async ({ request }) => {
    // ── 准备：admin 建角色（仅 device:list，无 edit）+ 账号，测完删 ──
    const adminToken = await apiLogin(request, ADMIN_USER, ADMIN_PASS);
    const auth = { Authorization: `Bearer ${adminToken}` };
    const stamp = Date.now().toString(36);
    const roleKey = `asm_e2e_g12r_${stamp}`.slice(0, 30);
    const userName = `asm_g12_u_${stamp}`.slice(0, 20);
    const userPass = 'AsmG12@403';

    // 查 device:list 权限的 menuId（asm_auth.sql 注册的按钮权限）
    const perms = (await (await request.get(`${API}/system/menu/list`, { headers: auth })).json()).data || [];
    const listMenu = perms.find((m: any) => m.perms === 'asm-monitor:device:list');
    expect(listMenu, 'asm-monitor:device:list 权限行应存在（asm_auth.sql）').toBeTruthy();

    const roleRes = await request.post(`${API}/system/role`, {
      headers: auth,
      data: { roleName: 'ASM G12 只读角色', roleKey, roleSort: 99, status: '0', menuIds: [listMenu.menuId], deptIds: [], remark: 'G12 e2e 临时' },
    });
    expect((await roleRes.json()).code).toBe(200);
    const roleId = (await (await request.get(`${API}/system/role/list?pageNum=1&pageSize=10&roleKey=${roleKey}`, { headers: auth })).json()).rows[0].roleId;
    const userRes = await request.post(`${API}/system/user`, {
      headers: auth,
      data: { userName, nickName: 'ASM G12 403', password: userPass, status: '0', deptId: 103, roleIds: [roleId], postIds: [] },
    });
    expect((await userRes.json()).code).toBe(200);

    try {
      const userToken = await apiLogin(request, userName, userPass);
      // 读类放行（有 list 权限）
      const listRes = await request.get(`${API}/asm-monitor/device/params`, { headers: { Authorization: `Bearer ${userToken}` } });
      expect((await listRes.json()).code, '只读角色 GET params 应 200').toBe(200);
      // 写类拒绝：HTTP 200 + body code=403（ruoyi 恒 200 看 body code）
      const provRes = await request.post(`${API}/asm-monitor/device/params/LIGHTING/provision`, {
        headers: { Authorization: `Bearer ${userToken}`, 'Content-Type': 'application/json' },
        data: { coordinate: 'com.ecat:integration-saimosen', model: 'SMS8910V2', operation: 'add' },
      });
      expect(provRes.status(), 'ruoyi HTTP 恒 200').toBe(200);
      const body = await provRes.json();
      expect(String(body.code), `应 403 缺权（asm-monitor:device:edit），实=${JSON.stringify(body).slice(0, 200)}`).toBe('403');
    } finally {
      const users = (await (await request.get(`${API}/system/user/list?pageNum=1&pageSize=10&userName=${userName}`, { headers: auth })).json()).rows || [];
      for (const u of users) await request.delete(`${API}/system/user/${u.userId}`, { headers: auth });
      await request.delete(`${API}/system/role/${roleId}`, { headers: auth });
    }
  });
});

/** G12-2 记录的原设备 id（G12-2b 还原用；串行保证跨 test 传递安全）。 */
const originalIdHolder = { id: '' };
