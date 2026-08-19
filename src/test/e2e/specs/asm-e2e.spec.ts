/**
 * ASM（env-air-station-manager）黑盒验收 G 段 Playwright regression。
 *
 * 覆盖 docs/asm-blackbox-acceptance.md §G：
 *   G1 六页渲染：.app-main innerText 非空 + .asm-page 挂载 + console/pageerror 0（ignoreCache reload）。
 *   G2 核心功能：
 *     - history_data 查询出图出表（echarts canvas + 明细行>0，参数池来自 stat-params 真实候选）。
 *     - monitor 30s 轮询（35s 窗内 snapshot 请求 ≥2）。
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
    await page.locator('.asm-filter input[type="radio"][value="MINUTE"]').check();
    // 时间窗 = 近 30min（壁钟，前端直接传后端）
    const now = new Date();
    const times = page.locator('.asm-filter input[type="datetime-local"]');
    await times.nth(0).fill(toLocalInput(new Date(now.getTime() - 30 * 60 * 1000)));
    await times.nth(1).fill(toLocalInput(new Date(now.getTime() + 60 * 1000)));
    // 勾选参数池首个候选（stat-params 实际返回，mask 过滤后首个必可物化 MINUTE）
    const firstCheck = page.locator('.asm-params input[type="checkbox"]').first();
    await expect(firstCheck).toBeAttached();
    await firstCheck.check();
    await page.getByRole('button', { name: '查询', exact: true }).click();

    // 明细表出行 + echarts canvas 出图
    await expect(page.locator('.asm-page > .asm-table tbody tr').first()).toBeVisible({ timeout: 20_000 });
    const rowCount = await page.locator('.asm-page > .asm-table tbody tr').count();
    expect(rowCount, '历史明细行应 >0').toBeGreaterThan(0);
    await expect(page.locator('.asm-chart canvas')).toBeVisible();
    // 分页器出现（series 有数据才渲染）
    await expect(page.locator('.asm-pager')).toBeVisible();
    expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
  });

  test('G2-monitor 30s 轮询更新（mount 后 30s tick 再发 snapshot） @g2', async ({ page }) => {
    // 监听器必须在 goto 前挂（goto 含 ignoreCache reload，前后共 2 次 mount 请求都计入）。
    const stamps: number[] = [];
    page.on('request', (r) => { if (r.url().includes('/asm-monitor/snapshot')) stamps.push(Date.now()); });

    const mp = new AsmBasePage(page, 'monitor');
    await mp.goto();
    await expect(page.locator('.asm-card').first()).toBeVisible();

    // 30s 轮询属被测节奏本身：≥3 次（2 mount + ≥1 tick）且首末间隔 ≥25s（证明是定时 tick 非重复 mount）。
    await expect
      .poll(() => stamps.length, { timeout: 60_000, message: '60s 内应有 ≥3 次 snapshot 请求（2 mount+1 tick）' })
      .toBeGreaterThanOrEqual(3);
    expect(stamps[stamps.length - 1] - stamps[0], '首末请求间隔应 ≥25s（定时轮询证据）').toBeGreaterThanOrEqual(25_000);
    // 手动刷新按钮可用（轮询之外的兜底交互）
    await page.getByRole('button', { name: '手动刷新' }).click();
    await expect(page.locator('.asm-card').first()).toBeVisible();
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
      const input = dialog.locator('.el-form-item', { hasText: 'duration' }).locator('.el-input-number input');
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

  test('G2-control 下发排风扇 speed=low 回查终态 SUCCESS 并还原 off @g2', async ({ page }) => {
    const cp = new AsmBasePage(page, 'control_list');
    await cp.goto();

    await page.locator('.asm-filter select').nth(0).selectOption(FAN_UID);
    await page.locator('.asm-filter select').nth(1).selectOption(FAN_ATTR);
    await page.locator('.asm-filter input[placeholder*="写值"]').fill('low');
    await page.getByRole('button', { name: '执行控制' }).click();

    // 轮询终态（PENDING→异步写回，秒级）。列序：0 id /1 时刻 /2 来源 /3 调用方 /4 设备 /5 参数
    //   /6 动作 /7 执行前 /8 请求值 /9 执行后 /10 结果
    const refreshBtn = page.getByRole('button', { name: /刷新（PENDING 终态回查）/ });
    await expect
      .poll(async () => {
        await refreshBtn.click();
        const row = page.locator('.el-table__row', { hasText: FAN_ATTR }).first();
        if (!(await row.count())) return 'MISSING';
        return ((await row.locator('td').nth(10).innerText()) || '').trim(); // 结果列
      }, { timeout: 60_000, message: '60s 内 speed 行应到 SUCCESS 终态' })
      .toBe('SUCCESS');
    // 终态断言：SUCCESS 且 after 非空（E1 语义：终态 SUCCESS 时 after=执行后值）
    const row = page.locator('.el-table__row', { hasText: FAN_ATTR }).first();
    const cells = row.locator('td');
    expect(((await cells.nth(9).innerText()) || '').trim(), 'SUCCESS 行 after 应非空').not.toBe('');
    expect(((await cells.nth(2).innerText()) || '').trim(), '来源应 REMOTE').toBe('REMOTE');

    // 还原 off
    await page.locator('.asm-filter input[placeholder*="写值"]').fill('off');
    await page.getByRole('button', { name: '执行控制' }).click();
    // 低风/还原两行 created_at 同秒，列表排序并列时首行不稳定 → 扫全行找「请求=off 且 SUCCESS」
    await expect
      .poll(async () => {
        await refreshBtn.click();
        const rows = page.locator('.el-table__row', { hasText: FAN_ATTR });
        const n = await rows.count();
        for (let i = 0; i < n; i++) {
          const tds = rows.nth(i).locator('td');
          const req = ((await tds.nth(8).innerText()) || '').trim();
          const res = ((await tds.nth(10).innerText()) || '').trim();
          if (req === 'off' && res === 'SUCCESS') return 'off-success';
        }
        return `not-found(${n} rows)`;
      }, { timeout: 60_000, message: '还原 off 行应出现且终态 SUCCESS' })
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
      await page.goto('/#/login');
      await page.waitForLoadState('networkidle');
      await mp.login(userName, userPass);
      await page.goto(mp.route);
      await page.waitForLoadState('networkidle');
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
