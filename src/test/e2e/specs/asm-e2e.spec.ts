/**
 * ASM（env-air-station-manager）黑盒验收 G 段 Playwright regression。
 *
 * 覆盖 docs/asm-blackbox-acceptance.md §G：
 *   G1 六页渲染：.app-main innerText 非空 + .asm-page 挂载 + console/pageerror 0（ignoreCache reload）。
 *   G2 核心功能：
 *     - history_data 查询出图出表（echarts canvas + 明细行>0，参数池来自 stat-params 真实候选）。
 *     - monitor SSE 实时推送（/asm-monitor/stream 请求 + ≥1 帧 device.data.update 增量 patch）。
 *     - alarm_rule 列表加载 + 编辑弹窗改 duration 5→6 保存生效 → 还原 5（rule=设备间温度异常，seed 确定存在）。
 *     - control_list 下发 exhaust_fan speed=low → 刷新回查终态 SUCCESS → 还原 off。
 *     - config 页 config-stat 首行 materializationMode BOTH→FRONT 保存生效 → 还原 BOTH。
 *   G3 权限：建无 asm-monitor 权限角色+账号（API），登录后 GET /asm-monitor/snapshot body code=403
 *     （ruoyi HTTP 恒 200 看 body code；API 双证口径，浏览器页面半段断言不渲染数据），测完删角色+账号。
 *
 * 约定：串行（workers=1）；测试数据用后全部还原；keep-alive 缓存 → 断言前点「刷新/查询」。
 */
import { test, expect } from '@playwright/test';
import { AsmBasePage, ASM_PAGES, attachErrorCollectors, toLocalInput } from '../helpers/page-objects/AsmBasePage';

const API = process.env.API_BASE_URL || 'http://localhost:8080';
const ADMIN_USER = process.env.ASM_USER || 'Admin7s9k2G5';
const ADMIN_PASS = process.env.ASM_PASS || '7sK2pG9dR3tQ';

/** 控制下发目标（snapshot 实证存在，speed 属性可写；E1 验收已用同 attr）。 */
const FAN_UID = 'logicdevice_station.exhaust_fan';
const FAN_ATTR = 'speed';

/** ruoyi 登录（API）→ token。失败抛（403 用户密码错/未建都会在此暴露）。 */
async function apiLogin(request: any, username: string, password: string): Promise<string> {
  const res = await request.post(`${API}/login`, { data: { username, password } });
  const body = await res.json();
  expect(body.code, `登录失败(${username}): ${JSON.stringify(body)}`).toBe(200);
  return body.token;
}

test.describe('ASM 黑盒验收 G 段', () => {
  test.describe.configure({ mode: 'serial' });

  test('G1 六页全部渲染（innerText 非空 + 无空挂载 + console 0 error） @g1', async ({ page }) => {
    const errors: string[] = [];
    attachErrorCollectors(page, errors);
    const base = new AsmBasePage(page, 'monitor');
    // 登录一次（后续同 context 内 hash 导航复用登录态）
    await base.goto();

    for (const key of ASM_PAGES) {
      await new AsmBasePage(page, key).navToOwn();
      const main = page.locator('.app-main');
      await expect(main).toBeVisible();
      const text = (await main.innerText()) || '';
      expect(text.trim().length, `页 ${key} .app-main innerText 应非空`).toBeGreaterThan(50);
      // 页面真实挂载（.asm-page 根容器；空 `<!---->` 挂载异常时不存在）
      await expect(page.locator('.app-main .asm-page').first(), `页 ${key} .asm-page 应挂载`).toBeVisible();
    }
    expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
  });

  test('G2-history 查询出图出表（MINUTE 粒度，明细行>0） @g2', async ({ page }) => {
    const errors: string[] = [];
    attachErrorCollectors(page, errors);
    const hp = new AsmBasePage(page, 'history_data');
    await hp.goto();

    // 粒度=分钟（分钟桶延迟 +10s，近 30min 窗必有数据；默认 HOUR 首桶未闭环会空）
    await page.locator('.asm-filter .el-radio-button').filter({ hasText: /^分钟$/ }).click();  // 正则全匹配：避免「5分钟」子串命中
    // 时间窗 = 近 30min（壁钟，前端直接传后端）
    const now = new Date();
    // el-date-picker datetimerange：两个 .el-range-input，fill 用显示格式（空格分隔，非 T；emit 才归一为线上 T 串）
    const times = page.locator('.asm-filter .el-range-input');
    await times.nth(0).fill(toLocalInput(new Date(now.getTime() - 30 * 60 * 1000)).replace('T', ' '));
    await times.nth(1).fill(toLocalInput(new Date(now.getTime() + 60 * 1000)).replace('T', ' '));
    // 参数选择（2026-09-08 v2：dialog 化）：readonly 触发框开 dialog → 勾参数 → 确定触发查询。
    await page.locator('.asm-filter input[placeholder="选择参数..."]').click();
    const firstParam = page.locator('.asm-params .asm-param-cb').first();
    await expect(firstParam).toBeVisible({ timeout: 10_000 });
    await firstParam.click();
    await page.getByRole('button', { name: '确 定' }).click();

    // 明细表出行（默认视图=列表）
    await expect(page.locator('.el-table__body-wrapper tbody .el-table__row').first()).toBeVisible({ timeout: 20_000 });
    const rowCount = await page.locator('.el-table__body-wrapper tbody .el-table__row').count();
    expect(rowCount, '历史明细行应 >0').toBeGreaterThan(0);
    // 曲线：切到曲线视图后 echarts canvas 出图（默认列表，需显式切换）
    await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();
    await expect(page.locator('.asm-chart canvas')).toBeVisible();
    // 分页器出现（有数据后渲染；wrapper class .asm-pager 保留）
    await expect(page.locator('.asm-pager')).toBeVisible();
    expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
  });

  test('G2-monitor SSE 实时推送（stream 请求 + ≥1 帧 device.data.update patch 瓦片） @g2', async ({ page }) => {
    // 监听器必须在 goto 前挂（goto 含 ignoreCache reload，前后共 2 次连接请求都计入）。
    const streamStamps: number[] = [];
    page.on('request', (r) => { if (r.url().includes('/asm-monitor/stream')) streamStamps.push(Date.now()); });

    const mp = new AsmBasePage(page, 'monitor');
    await mp.goto();
    // 分组瓦片墙挂载（snapshot 全量初始化的渲染证据；37 台一屏瓦片）
    await expect(page.locator('.asm-group').first()).toBeVisible();
    await expect(page.locator('.asm-tile').first()).toBeVisible();

    // SSE 长连接请求 ≥1（fetch-based，?token= query + Authorization header 双通道）
    await expect
      .poll(() => streamStamps.length, { timeout: 15_000, message: '15s 内应有 ≥1 次 /asm-monitor/stream 请求' })
      .toBeGreaterThanOrEqual(1);

    // 收到 ≥1 帧 device.data.update：组件在 handleSseUpdate 暴露 window.__asmSseFrames 计数器
    //（网络面板外的确定性证据；站房设备分钟级采样，60s 窗内必有帧）。
    await expect
      .poll(() => page.evaluate(() => (window as any).__asmSseFrames || 0),
        { timeout: 78_000, message: '78s 内应收到 ≥1 帧 device.data.update（SSE 实时推送证据）' })
      .toBeGreaterThanOrEqual(1);

    // 断连横幅默认不显示（连接健康）
    await expect(page.locator('.asm-banner')).toHaveCount(0);
    // 手动刷新按钮可用（SSE 之外的兜底交互）
    await page.getByRole('button', { name: '手动刷新' }).click();
    await expect(page.locator('.asm-tile').first()).toBeVisible();
  });

  test('G2-alarm_rule 编辑弹窗改 duration 保存生效并还原 @g2', async ({ page }) => {
    const errors: string[] = [];
    attachErrorCollectors(page, errors);
    const rp = new AsmBasePage(page, 'alarm_rule');
    await rp.goto();

    // 列表加载（seed 规则「设备间温度异常」= range+duration 结构，duration=5）
    const row = page.locator('.el-table__row', { hasText: '设备间温度异常' }).first();
    await expect(row).toBeVisible();
    const dialog = page.locator('.el-dialog');

    /** 打开该规则编辑弹窗并返回 duration 输入框。 */
    async function openDurationInput(): Promise<import('@playwright/test').Locator> {
      await row.getByRole('button', { name: '编辑' }).click();
      await expect(dialog).toBeVisible();
      const input = dialog.locator('.el-form-item', { hasText: '持续时长' }).locator('.el-input-number input');
      await expect(input).toBeVisible();
      return input;
    }

    // 改 5→6 保存
    let input = await openDurationInput();
    expect((await input.inputValue()).trim()).toBe('5');
    await input.fill('6');
    await dialog.getByRole('button', { name: '保存' }).click();
    await expect(dialog).toBeHidden({ timeout: 15_000 });
    await expect(row).toBeVisible(); // load() 重取后行仍在

    // 重开校验已生效
    input = await openDurationInput();
    await expect(input).toHaveValue('6');
    // 还原 5
    await input.fill('5');
    await dialog.getByRole('button', { name: '保存' }).click();
    await expect(dialog).toBeHidden({ timeout: 15_000 });
    input = await openDurationInput();
    await expect(input).toHaveValue('5');
    await dialog.getByRole('button', { name: '取消' }).click();
    expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
  });

  test('G2-control 下发排风扇 speed=low 回查终态 成功 并还原 off @g2', async ({ page, request }) => {
    // 2026-09-02 控制记录页重设计：执行控件移除（职责归设备控制页），下发走控制 API（与
    // AsmDeviceControl executor 同端点同 body）；记录页纯查询（「查询」承担重查，刷新按钮已删）。
    //
    // 页序契约（bug-record-20260901-084100）：先挂载页面再下发——复刻真实场景「记录页开着，控制
    // 从别处进来，用户点查询」。若先下发再挂载，时间窗 end 在挂载时刻初始化已晚于记录 created_at，
    // 冻结 end 缺陷被掩盖（09-02 重写曾因此假绿）。断言锁定「本次下发行」：execute 响应 data 含
    // 审计记录 id，以 id 对照查询响应 data.rows 精确匹配，杜绝旧行假绿（历史断言扫「任意
    // 远程/low/成功 行」，08-26 旧行滑出 24h 窗前一直假绿）。UI 表已删记录ID 列，故 id 匹配走
    // 网络响应侧，再以 createdAt 展示串回绑表格行断言渲染。
    const cp = new AsmBasePage(page, 'control_list');
    await cp.goto();

    const token = await apiLogin(request, ADMIN_USER, ADMIN_PASS);
    const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
    /** API 下发并返回审计记录 id（insert 仅回填 id；createdAt 是 DB 生成，execute 响应为 null，
     *  时刻等字段以 list 按 id 回查的行数据为准——mapper 注释「useGeneratedKeys 回填 id」）。 */
    const exec = async (value: string): Promise<number> => {
      const body = await (await request.post(`${API}/asm-monitor/control`, {
        headers: auth, data: { uid: FAN_UID, attrId: FAN_ATTR, value } })).json();
      expect(body.code, `API 下发 ${value} 应受理`).toBe(200);
      expect(body.data && body.data.id, `execute 响应须含审计记录 id: ${JSON.stringify(body)}`).toBeTruthy();
      return body.data.id;
    };
    const lowId = await exec('low');

    // 点「查询」并从本次 list 响应按 id 取行（表格渲染的行集 = 该响应 data.rows；无则 null）
    const queryBtn = page.getByRole('button', { name: '查询', exact: true });
    const rowById = async (recordId: number): Promise<any | null> => {
      const respP = page.waitForResponse(
        (r) => r.url().includes('/asm-monitor/control-record/list') && r.request().method() === 'GET',
        { timeout: 15_000 });
      await queryBtn.click();
      const body = await (await respP).json();
      return (((body || {}).data || {}).rows || []).find((r: any) => r.id === recordId) || null;
    };
    let lowRow: any = null;
    await expect
      .poll(async () => {
        lowRow = await rowById(lowId);
        if (lowRow && lowRow.result === 'SUCCESS') return 'ok';
        return `not-found(id=${lowId}${lowRow ? `,result=${lowRow.result}` : ''})`;
      }, { timeout: 60_000, message: `60s 内本次下发行(id=${lowId})应出现在查询响应且终态 SUCCESS` })
      .toBe('ok');
    // 终态断言（E1 语义：终态成功时 after=执行后值）
    expect(lowRow.afterValue, '成功 行 after 应非空').toBeTruthy();

    // UI 渲染回绑：时刻列展示串 = 该行 createdAt（list 回查携带，ISO UTC）的本地壁钟秒级格式
    // （datetime.js 同口径）。新列序（0 起，记录ID 列已删）：0 时刻 /1 来源 /2 调用方 /3 设备 /4 参数
    // /5 动作 /6 执行前 /7 请求值 /8 执行后 /9 结果 /10 耗时ms（来源/结果已中文化）。
    const pad = (n: number) => String(n).padStart(2, '0');
    const c = new Date(lowRow.createdAt);
    const createdAtLocal = `${c.getFullYear()}-${pad(c.getMonth() + 1)}-${pad(c.getDate())} ` +
      `${pad(c.getHours())}:${pad(c.getMinutes())}:${pad(c.getSeconds())}`;
    const uiRow = page.locator('.el-table__row', { hasText: FAN_ATTR }).filter({ hasText: createdAtLocal })
      .filter({ hasText: 'low' });
    await expect(uiRow, '本次下发行应在表格渲染').toHaveCount(1);
    expect((((await uiRow.locator('td').nth(8).innerText()) || '').trim()), 'UI 行执行后应非空').not.toBe('');

    // 还原 off（API 下发）；同样按本次 off 行 id 锁定（低风/还原可能同秒，旧行扫描不可靠）
    const offId = await exec('off');
    await expect
      .poll(async () => {
        const row = await rowById(offId);
        return row && row.result === 'SUCCESS' ? 'off-success' : `not-found(id=${offId}${row ? `,result=${row.result}` : ''})`;
      }, { timeout: 60_000, message: `还原 off 行(id=${offId})应出现且终态 成功` })
      .toBe('off-success');
  });

  test('G2-config config-stat 行编辑（物化范围 BOTH→FRONT 保存生效）并还原 @g2', async ({ page }) => {
    const errors: string[] = [];
    attachErrorCollectors(page, errors);
    const gp = new AsmBasePage(page, 'config');
    await gp.goto();

    // 首行 config-stat（73 行 seed，首行 ac1 setpoint_temp，默认 BOTH）
    const row = page.locator('.el-table').first().locator('.el-table__row').first();
    await expect(row).toBeVisible();
    const saveBtn = row.getByRole('button', { name: '保存' });
    await expect(saveBtn).toBeDisabled(); // 未改前 _dirty=false

    /** 点开行内第 N 个 el-select 并选指定文案（el-select 下拉 teleport 到 body）。 */
    async function pickMode(mode: string) {
      await row.locator('.el-select').nth(1).click(); // nth0=粒度掩码, nth1=物化范围
      const item = page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: mode }).first();
      await item.click();
      await page.keyboard.press('Escape'); // 收残余下拉
    }

    // BOTH → FRONT 保存
    await pickMode('FRONT');
    await expect(saveBtn).toBeEnabled();
    await saveBtn.click();
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 });
    await expect(saveBtn).toBeDisabled(); // 保存后 _dirty 复位

    // 刷新重取验证已生效
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await expect(row.locator('.el-select').nth(1)).toContainText('FRONT');

    // 还原 BOTH
    await pickMode('BOTH');
    await expect(saveBtn).toBeEnabled();
    await saveBtn.click();
    await expect(page.locator('.el-message--success').first()).toBeVisible({ timeout: 15_000 });
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await expect(row.locator('.el-select').nth(1)).toContainText('BOTH');
    expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
  });

  test('G3 无 asm-monitor 权限账号访问 → API body code=403（HTTP 200）+ 页面不渲染数据 @g3', async ({ page, request }) => {
    // ── 准备：admin 建无权限角色 + 测试账号（幂等键 asm_e2e_*，测完删） ──
    const adminToken = await apiLogin(request, ADMIN_USER, ADMIN_PASS);
    const auth = { Authorization: `Bearer ${adminToken}` };
    const stamp = Date.now().toString(36);   // base36 短 stamp：登录查询超 ~20 字符的用户名查不到（28 字符用户行在库仍报
                                             // 「用户不存在」，实证 20 字符可登录），用户名总长必须 ≤20
    const roleKey = `asm_e2e_r_${stamp}`;
    const userName = `asm_e2e_u_${stamp}`;
    const userPass = 'AsmE2e@403';

    const roleRes = await request.post(`${API}/system/role`, {
      headers: auth,
      data: { roleName: 'ASM e2e 无权限角色', roleKey, roleSort: 99, status: '0', menuIds: [], deptIds: [], remark: 'ASM G3 e2e 临时' },
    });
    expect((await roleRes.json()).code, '建角色应成功').toBe(200);
    const roleId = (await (await request.get(`${API}/system/role/list?pageNum=1&pageSize=10&roleKey=${roleKey}`, { headers: auth })).json()).rows[0].roleId;

    const userRes = await request.post(`${API}/system/user`, {
      headers: auth,
      data: { userName, nickName: 'ASM e2e 403', password: userPass, status: '0', deptId: 103, roleIds: [roleId], postIds: [] },
    });
    expect((await userRes.json()).code, '建用户应成功').toBe(200);

    try {
      // ── 半段 1（API 双证主断言）：无权限 token 调 snapshot → HTTP 200 但 body code=403 ──
      const userToken = await apiLogin(request, userName, userPass);
      const snap = await request.get(`${API}/asm-monitor/snapshot`, { headers: { Authorization: `Bearer ${userToken}` } });
      expect(snap.status(), 'ruoyi HTTP 恒 200').toBe(200);
      const body = await snap.json();
      expect(String(body.code), `应 403 缺权（asm-monitor:monitor:list），实=${JSON.stringify(body)}`).toBe('403');

      // ── 半段 2（浏览器）：该账号登录 8081 导航 monitor → 不渲染任何站房数据卡片 ──
      // 注意不能走 AsmBasePage.goto（等 .asm-page 挂载）：无权限账号集成路由不注册（webintegration 拉取被拒），
      // monitor 路由 404 是**预期**，只断言「数据卡片为 0」（不渲染任何站房数据）。
      const mp = new AsmBasePage(page, 'monitor');
      // storageState 使 context 自带 admin 登录态（ruoyi-vue3 token 存 **Cookie**，非 localStorage——
      // 实证 localStorage 空而守卫仍放行）：切 403 账号须 context 级清 cookie+localStorage 双清。
      await page.context().clearCookies();
      await page.goto('/');
      await page.waitForLoadState('load');
      await page.evaluate(() => localStorage.clear());
      await page.goto('/#/login');
      await page.waitForLoadState('load');
      await mp.login(userName, userPass);
      await page.goto(mp.route);
      await page.waitForLoadState('load');
      await page.waitForTimeout(2000); // 等路由解析与可能的异步 snapshot 拒绝（403）尘埃落定
      expect(await page.locator('.asm-card').count(), '无权限账号不应渲染站房数据卡片').toBe(0);
    } finally {
      // ── 清理：删测试用户 + 角色（幂等，失败也不遮蔽主断言结果） ──
      const users = (await (await request.get(`${API}/system/user/list?pageNum=1&pageSize=10&userName=${userName}`, { headers: auth })).json()).rows || [];
      for (const u of users) await request.delete(`${API}/system/user/${u.userId}`, { headers: auth });
      await request.delete(`${API}/system/role/${roleId}`, { headers: auth });
    }
  });
});
