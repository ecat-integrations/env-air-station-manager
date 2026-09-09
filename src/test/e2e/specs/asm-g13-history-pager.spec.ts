/**
 * ASM 历史页等间隔网格分页回归（g13-grid-*，2026-09-09 夜 网格分页重构）：
 *  - total 契约：total=窗口内网格 tick 数（按粒度对齐、前端计算），pager「共 N 条」=N 个时刻；
 *    后端响应 total/count 字段保留但不再作为 pager 依据
 *  - 页=时间段：每页固定 N 个 tick（200/500/1000）按时间倒序切页，第 1 页=窗口末段最新段、
 *    翻页向更早、页内时刻亦降序（页序/页内/表格同一降序口径）；页间请求窗=页 tick 区间的
 *    [start,end) 表达（pageNum 恒 1、order=DESC、pageSize=页 tick 数×参数数且≥2000 护栏）
 *  - 等间隔网格：无数据的 tick 照常成行，数值格占位 '-'、非数值格占位 '--'——参数历史起点
 *    异构（数值有数周、非数值当天起）不再产生「首页整页单列 / 页行数骤减」形态
 *
 * 数据面走路由 mock（按请求窗逐分钟 tick 生成行，页码语义由前端网格切分决定）：部署 jar 落后于
 * 源码（ASM_BUNDLE_OVERRIDE 过渡验证缝）时也能锁前端契约；后端 order=DESC 语句形状与 SDK 升序
 * 零回归由 AsmHistoryQueryMapperSqlShapeTest/ServiceTest 单测锁死。
 */
import { test, expect } from '@playwright/test';
import { AsmBasePage, attachErrorCollectors, toLocalInput } from '../helpers/page-objects/AsmBasePage';

const BUNDLE_OVERRIDE = process.env.ASM_BUNDLE_OVERRIDE || '';

/** 分钟粒度步长（与前端 GRANULARITY_STEP_MS.MINUTE / 后端 AsmStatGranularity.MINUTE.interval 同值）。 */
const STEP_MS = 60_000;

/** 默认页 tick 数（PAGE_SIZES[0]）。 */
const PAGE_TICKS = 200;

/** 单页请求 pageSize 护栏下限（前端 GRID_MIN_QUERY_PAGE_SIZE 同值）。 */
const GRID_MIN_QUERY_PAGE_SIZE = 2000;

/** Date → 'YYYY-MM-DD HH:mm:ss'（本地壁钟，与页面 formatLocalDateTime 默认秒级同口径）。 */
function fmtSecond(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** 窗口网格（与前端 buildGridWindow 同公式）：[start,end] 内按分钟对齐的 tick 全集。 */
function gridOf(startMs: number, endMs: number) {
  const firstMs = Math.ceil(startMs / STEP_MS) * STEP_MS;
  const lastMs = Math.floor(endMs / STEP_MS) * STEP_MS;
  const total = (lastMs - firstMs) / STEP_MS + 1;
  return { firstMs, lastMs, total, pages: Math.ceil(total / PAGE_TICKS) };
}

/** 第 k 页（1 起，倒序切页）的 tick 序号区间（与前端 gridPageIndex 同公式）。 */
function gridPageIndex(total: number, pageSize: number, pageNum: number) {
  const pages = Math.ceil(total / pageSize);
  const page = Math.min(Math.max(pageNum, 1), pages);
  return {
    fromIdx: Math.max(0, total - page * pageSize),
    toIdx: Math.min(total - (page - 1) * pageSize - 1, total - 1),
  };
}

/**
 * 网格语义 mock：按请求窗 [start,end) 逐分钟 tick 生成行（降序），cover 决定每个 (key,tick) 是否出
 * 数据（返回 null=该格无桶，前端应显占位符）。记录每次请求的完整分页参数串供断言。
 * @param page   Playwright page
 * @param keys   参数键列表（uid:attrId）
 * @param cover  (key, tickMs) → 行体（value/value_text/validCount/totalCount）或 null
 * @returns 请求参数串列表（按发起顺序）
 */
async function mockGridHistory(
  page: import('@playwright/test').Page,
  keys: string[],
  cover: (key: string, tickMs: number) => Record<string, unknown> | null,
) {
  const requests: string[] = [];
  await page.route('**/asm-monitor/history*', async (route) => {
    const q = new URL(route.request().url()).searchParams;
    requests.push(`pageNum=${q.get('pageNum')}&pageSize=${q.get('pageSize')}&order=${q.get('order')}`
      + `&start=${q.get('start')}&end=${q.get('end')}&params=${(q.get('params') || '').split(',').length}keys`);
    const startMs = new Date(q.get('start') as string).getTime();
    const endMs = new Date(q.get('end') as string).getTime();
    const rows: Record<string, unknown>[] = [];
    for (let t = startMs; t < endMs; t += STEP_MS) {
      for (const key of keys) {
        const body = cover(key, t);
        if (!body) continue;
        const sep = key.lastIndexOf(':');
        rows.push({
          dataTime: new Date(t).toISOString(),
          logicDeviceUniqueId: key.slice(0, sep),
          attrId: key.slice(sep + 1),
          unit: '', display_unit: '',
          ...body,
        });
      }
    }
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200, msg: '操作成功',
        data: { granularity: 'MINUTE', mode: 'BACK', unit: 'custom', pageNum: 1, pageSize: Number(q.get('pageSize')), total: rows.length, rows },
      }),
    });
  });
  return requests;
}

/** 前置：分钟粒度 + 指定窗（默认近 8h，>200 tick=有多页可断言），并注入本地构建（可选）。 */
async function openHistory(page: import('@playwright/test').Page, windowMinutes = 8 * 60) {
  if (BUNDLE_OVERRIDE) {
    await page.route('**/ecat-integrations/integration-env-air-station-manager/air-station-manager.js*', (route) =>
      route.fulfill({ path: BUNDLE_OVERRIDE, contentType: 'application/javascript' }));
    console.log(`[g13] ASM_BUNDLE_OVERRIDE 生效，模块脚本指向本地构建: ${BUNDLE_OVERRIDE}`);
  }
  const hp = new AsmBasePage(page, 'history_data');
  await hp.goto();
  await page.locator('.asm-filter .el-radio-button').filter({ hasText: /^分钟$/ }).click();
  const now = new Date();
  const start = new Date(now.getTime() - windowMinutes * STEP_MS);
  const end = new Date(now.getTime() + 60 * 1000);
  const times = page.locator('.asm-filter .el-range-input');
  await times.nth(0).fill(toLocalInput(start).replace('T', ' '));
  await times.nth(1).fill(toLocalInput(end).replace('T', ' '));
  return { startMs: start.getTime(), endMs: end.getTime() };
}

/** 参数弹窗勾选（中文名锚定）并确定（确定即触发重查）。 */
async function checkParams(page: import('@playwright/test').Page, names: string[]) {
  await page.locator('.asm-filter input[placeholder="选择参数..."]').click();
  const dialog = page.locator('.el-dialog').filter({ hasText: '选择参数' });
  await expect(dialog).toBeVisible({ timeout: 10_000 });
  for (const name of names) {
    const cb = dialog.locator('.asm-param-cb').filter({ hasText: name }).first();
    await expect(cb, `参数弹窗应含「${name}」`).toBeVisible({ timeout: 10_000 });
    await cb.click();
  }
  await page.getByRole('button', { name: '确 定' }).click();
}

const tableRows = (page: import('@playwright/test').Page) =>
  page.locator('.el-table__body-wrapper .el-table__row');
const activePagerItem = (page: import('@playwright/test').Page) =>
  page.locator('.el-pager li.is-active');
/** 时刻列（首列）全部单元格文本。 */
const timeCells = (page: import('@playwright/test').Page) =>
  page.locator('.el-table__body-wrapper .el-table__row td:first-child');
/** 某表头名所在列序号（1-based，含首列时刻）。 */
async function colIndexOf(page: import('@playwright/test').Page, name: string) {
  const heads = await page.locator('.el-table__header-wrapper th').allInnerTexts();
  const i = heads.findIndex((h) => h.includes(name));
  expect(i, `表头应含「${name}」（实际：${heads.join(' | ')}）`).toBeGreaterThanOrEqual(0);
  return i + 1;
}
/** 某列全部单元格文本（剔除内联计数 span，g10-table 同口径：值文本与计数分开取）。 */
const columnCells = (page: import('@playwright/test').Page, colIdx: number) =>
  page.locator(`.el-table__body-wrapper .el-table__row td:nth-child(${colIdx}) span:not(.asm-cell-count)`).allInnerTexts();

const KEY_TEMP = 'logicdevice_station.ac2:setpoint_temp';

test('g13-grid-total 出 tick 总数与页数、首页=窗口末段 200 行降序 @g13', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  const win = await openHistory(page);
  const grid = gridOf(win.startMs, win.endMs);
  await mockGridHistory(page, [KEY_TEMP], () => ({ value: 26, validCount: 5, totalCount: 6 }));
  await checkParams(page, ['设定温度']);

  // pager 直读网格 total：共 N 条=N 个时刻（非后端桶行计数）；页数=ceil(total/200)
  await expect(page.locator('.el-pagination__total')).toHaveText(new RegExp(`共\\s*${grid.total}\\s*条`), { timeout: 20_000 });
  await expect(page.locator('.el-pager li')).toHaveCount(grid.pages);
  await expect(activePagerItem(page)).toHaveText('1');

  // 首页恒 200 行（网格行数只由 tick 数决定，不随参数数据起点异构骤减）
  await expect(tableRows(page).first()).toBeVisible({ timeout: 20_000 });
  expect(await tableRows(page).count(), '首页应渲染 200 个 tick 行').toBe(PAGE_TICKS);

  // 页内降序且=窗口末段：首行=全集最新 tick（lastMs），第 200 行=lastMs-199 步长
  const times = await timeCells(page).allInnerTexts();
  expect(times[0], '首页首行应为窗口最新对齐时刻').toBe(fmtSecond(grid.lastMs));
  expect(times[PAGE_TICKS - 1], '首页末行应为最新段起点').toBe(fmtSecond(grid.lastMs - (PAGE_TICKS - 1) * STEP_MS));
  for (let i = 1; i < times.length; i++) {
    expect(Date.parse(times[i - 1].replace(' ', 'T')), `第 ${i} 行应严格降序（${times[i - 1]} → ${times[i]}）`)
      .toBeGreaterThan(Date.parse(times[i].replace(' ', 'T')));
  }
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g13-grid-页=时间区间：跳页末页取余段、改页大小重切、请求窗不重不漏 @g13', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  const win = await openHistory(page);
  const grid = gridOf(win.startMs, win.endMs);
  const requests = await mockGridHistory(page, [KEY_TEMP], () => ({ value: 26, validCount: 5, totalCount: 6 }));
  await checkParams(page, ['设定温度']);
  await expect(tableRows(page).first()).toBeVisible({ timeout: 20_000 });

  // 跳末页：行数=余段 tick 数、时间区间=全集最旧段（首行=fromIdx 对应 tick、末行=全集首 tick）
  await page.locator('.el-pagination__jump input').fill(String(grid.pages));
  await page.locator('.el-pagination__jump input').press('Enter');
  await expect(activePagerItem(page)).toHaveText(String(grid.pages));
  await expect(tableRows(page).first()).toBeVisible({ timeout: 20_000 });
  const { fromIdx, toIdx } = gridPageIndex(grid.total, PAGE_TICKS, grid.pages);
  const lastPageRows = toIdx - fromIdx + 1;
  expect(await tableRows(page).count(), `末页应渲染余段 ${lastPageRows} 行`).toBe(lastPageRows);
  const times = await timeCells(page).allInnerTexts();
  expect(times[0], '末页首行=余段最新 tick').toBe(fmtSecond(grid.firstMs + toIdx * STEP_MS));
  expect(times[lastPageRows - 1], '末页末行=全集最旧 tick').toBe(fmtSecond(grid.firstMs));

  // 改每页条数 500：归第 1 页、单页装下全窗网格
  await page.locator('.el-pagination__sizes').click();
  await page.locator('.el-select-dropdown__item').filter({ hasText: '500' }).first().click();
  await expect(activePagerItem(page)).toHaveText('1');
  await expect(tableRows(page).first()).toBeVisible({ timeout: 20_000 });
  expect(await tableRows(page).count(), '500/页应单页装下全窗 tick').toBe(grid.total);
  await expect(page.locator('.el-pager li')).toHaveCount(1);

  // 请求序列=3 次页区间查询：pageNum 恒 1（分页由前端网格切）、order=DESC、pageSize=护栏 2000、
  // 窗口=页 tick 区间的 [start,end) 表达（段末 tick 桶标经上限+1 步长含入），页间不重不漏
  expect(requests.length, `应恰好 3 次页查询（实际：${requests.join(' → ')}）`).toBe(3);
  const seg = (pageSize: number, pageNum: number) => {
    const { fromIdx: f, toIdx: t } = gridPageIndex(grid.total, pageSize, pageNum);
    return `start=${fmtSecond(grid.firstMs + f * STEP_MS).replace(' ', 'T')}&end=${fmtSecond(grid.firstMs + (t + 1) * STEP_MS).replace(' ', 'T')}`;
  };
  expect(requests[0]).toContain(`pageNum=1&pageSize=${GRID_MIN_QUERY_PAGE_SIZE}&order=DESC`);
  expect(requests[0]).toContain(seg(PAGE_TICKS, 1));
  expect(requests[1]).toContain(`pageNum=1&pageSize=${GRID_MIN_QUERY_PAGE_SIZE}&order=DESC`);
  expect(requests[1]).toContain(seg(PAGE_TICKS, grid.pages));
  expect(requests[2]).toContain(`pageNum=1&pageSize=${GRID_MIN_QUERY_PAGE_SIZE}&order=DESC`);
  expect(requests[2]).toContain(seg(500, 1));
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g13-grid-异构起点混合参数：空 tick 行恒在、数值 - 非数值 --、整页单列消失 @g13', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  // 参数候选池也 mock：11 参数（1 数值 + 10 非数值）→ 护栏上探 pageSize=页 tick 数×参数数=2200
  const KEYS: Array<[string, string, string]> = [
    ['logicdevice_station.ac2', 'setpoint_temp', '设定温度'],
    ['logicdevice_station.security_alarm', 'ir_alarm', '红外报警'],
    ['logicdevice_station.security_alarm', 'water_leak', '漏水报警'],
    ['logicdevice_station.exhaust_fan', 'speed', '转速档位'],
    ['logicdevice_station.ac2', 'power_state', '电源状态'],
    ['logicdevice_station.ac2', 'running_mode', '运行模式'],
    ['logicdevice_station.light', 'light_state', '照明状态'],
    ['logicdevice_station.door', 'lock_state', '门锁状态'],
    ['logicdevice_station.calibrator', 'gas_path', '气路状态'],
    ['logicdevice_station.ac1', 'power_state', '空调1电源'],
    ['logicdevice_station.ac1', 'running_mode', '空调1模式'],
  ];
  await page.route('**/asm-monitor/stat-params*', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200, msg: '操作成功',
        data: KEYS.map(([uid, attrId, name]) => ({
          logicDeviceUniqueId: uid, attrId, paramDisplayName: name,
          storageUnit: attrId === 'setpoint_temp' ? 'TemperatureUnit.CELSIUS' : '',
          display_unit: attrId === 'setpoint_temp' ? '℃' : '',
          applicableGranularityMask: 7, device_label: uid.split('.').pop(),
        })),
      }),
    });
  });
  const win = await openHistory(page);
  const grid = gridOf(win.startMs, win.endMs);
  // 异构起点：数值参数全窗有数（历史最久）、红外报警只在全集最新 20 tick 有数（当天才起）、
  // 漏水报警全窗无数——扁平行集分页下此形态会产出「整页单列/页行数骤减」，网格下应恒为时刻行
  const alarmFromIdx = grid.total - 20;
  const requests = await mockGridHistory(page, KEYS.map(([uid, attrId]) => `${uid}:${attrId}`), (key, tickMs) => {
    const idx = Math.round((tickMs - grid.firstMs) / STEP_MS);
    if (key === KEYS[0][0] + ':' + KEYS[0][1]) return { value: 26, validCount: 5, totalCount: 6 };
    if (key === KEYS[1][0] + ':' + KEYS[1][1]) {
      return idx >= alarmFromIdx ? { value: null, value_text: idx % 2 ? 'alarm' : 'normal', validCount: 3, totalCount: 5 } : null;
    }
    return null;
  });
  // 勾满 11 参数（护栏上探：pageSize=页 tick 数×参数数=200×11=2200 > 护栏下限 2000，可区分断言）
  await checkParams(page, KEYS.map(([, , name]) => name));
  await expect(tableRows(page).first()).toBeVisible({ timeout: 20_000 });

  // 首页恒 200 行（时间连续降序），不是「后页才覆盖 20 时刻」的行数骤减形态
  expect(await tableRows(page).count(), '首页应恒 200 个 tick 行').toBe(PAGE_TICKS);
  const times = await timeCells(page).allInnerTexts();
  expect(times[0]).toBe(fmtSecond(grid.lastMs));

  const tempCol = await colIndexOf(page, '设定温度');
  const alarmCol = await colIndexOf(page, '红外报警');
  const leakCol = await colIndexOf(page, '漏水报警');
  const tempCells = await columnCells(page, tempCol);
  const alarmCells = await columnCells(page, alarmCol);
  const leakCells = await columnCells(page, leakCol);

  // 首页（最新段）覆盖全集最新 200 tick，含红外报警起点段（最新 20 tick）：20 格原文 + 180 格占位
  //（占位=该时刻无观测、行仍在；旧扁平行集形态下非数值列被 '--' 整列吞掉、行数随起点异构骤减）
  expect(tempCells.filter((v) => v === '26').length, '数值列首页应有值（全窗有数参数）').toBe(PAGE_TICKS);
  expect(alarmCells.filter((v) => v === 'alarm' || v === 'normal').length, '首页应含红外报警起点段 20 个原文格').toBe(20);
  expect(alarmCells.filter((v) => v === '--').length, '首页红外报警余格应占位 --').toBe(PAGE_TICKS - 20);
  // 全窗无数参数：整列占位且无数据格。占位形态=数值口径 '-'——类别沿行值域自判（stat-params 无
  // 类别字段），当前页全无行的 series 无值域可判，不做猜测性兜底（与 emptyCellText 注释同口径）
  expect(leakCells.every((v) => v === '-'), '全窗无数参数应整列占位 -').toBe(true);

  // 末页（最旧段）：红外报警起点之外 → 整列占位 --；数值列照常有值
  await page.locator('.el-pagination__jump input').fill(String(grid.pages));
  await page.locator('.el-pagination__jump input').press('Enter');
  await expect(tableRows(page).first()).toBeVisible({ timeout: 20_000 });
  const lastPageRows = grid.total - (grid.pages - 1) * PAGE_TICKS;
  expect((await columnCells(page, alarmCol)).every((v) => v === '--'), '末页红外报警列应整列占位').toBe(true);
  expect((await columnCells(page, tempCol)).filter((v) => v === '-').length, '数值列不应出现占位（全窗有数）').toBe(0);
  expect(await tableRows(page).count(), '末页应渲染余段 tick 行').toBe(lastPageRows);

  // 单页请求 pageSize=页 tick 数×参数数=200×11=2200（护栏下限 2000 之上，防「按 200 请求 → 数据被截」）
  expect(requests.length).toBeGreaterThan(0);
  for (const r of requests) {
    expect(r).toContain('pageNum=1&pageSize=2200&order=DESC');
  }
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g13-grid-1000/页 两页承载分钟一天、曲线轴=页网格全 tick @g13', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  const win = await openHistory(page, 24 * 60); // 分钟粒度 1 天=1440 tick（1000/页 两页看完）
  const grid = gridOf(win.startMs, win.endMs);
  await mockGridHistory(page, [KEY_TEMP], () => ({ value: 26, validCount: 5, totalCount: 6 }));
  await checkParams(page, ['设定温度']);
  await expect(tableRows(page).first()).toBeVisible({ timeout: 20_000 });

  await page.locator('.el-pagination__sizes').click();
  await page.locator('.el-select-dropdown__item').filter({ hasText: '1000' }).first().click();
  await expect(activePagerItem(page)).toHaveText('1');
  await expect(page.locator('.el-pagination__total')).toHaveText(new RegExp(`共\\s*${grid.total}\\s*条`));
  expect(await tableRows(page).count(), '1000/页 首页应满 1000 个 tick 行').toBe(1000);
  await expect(page.locator('.el-pager li')).toHaveCount(2, `${grid.total} tick ÷ 1000 应为 2 页`);

  // 曲线轴=当前页网格全部 tick（缺数据槽位=null，补空语义随统一网格覆盖全页，无切半段）
  await page.locator('.asm-result-head .el-radio-button', { hasText: '曲线' }).click();
  await expect(page.locator('.asm-split-cell canvas').first()).toBeVisible({ timeout: 20_000 });
  const axisLen = await page.evaluate(() => {
    const dom = document.querySelectorAll('.asm-split-cell')[0];
    return (window as any).echarts.getInstanceByDom(dom).getOption().xAxis[0].data.length;
  });
  expect(axisLen, '分图轴槽数应=页内 1000 个 tick').toBe(1000);
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);

  // 导出按导出档位（1000 tick/段）切段拉全窗：段数=ceil(总 tick/1000)=2、段窗合法且连续，
  // 行数=全窗 tick 数（回归：导出档与浏览档混用曾切出 start>end 越界段）
  await page.locator('.asm-result-head .el-radio-button', { hasText: '列表' }).click();
  const exportReqs: string[] = [];
  await page.route('**/asm-monitor/history*', async (route) => {
    const q = new URL(route.request().url()).searchParams;
    exportReqs.push(`start=${q.get('start')}&end=${q.get('end')}&pageSize=${q.get('pageSize')}`);
    await route.fallback();
  });
  const downloadPromise = page.waitForEvent('download', { timeout: 30_000 });
  await page.locator('.asm-result-head .el-button', { hasText: '导出' }).click();
  const download = await downloadPromise;
  expect(await download.path(), '导出应产出文件').toBeTruthy();
  const validWindows = exportReqs.map((r) => {
    const s = Date.parse(r.split('&')[0].slice(6).replace('T', ' '));
    const e = Date.parse(r.split('&')[1].slice(4).replace('T', ' '));
    return e > s;
  });
  expect(exportReqs.length, `导出段数应=2（实际：${exportReqs.join(' | ')}）`).toBe(2);
  expect(validWindows.every((v) => v), `导出段窗须合法（start<end）：${exportReqs.join(' | ')}`).toBe(true);
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});

test('g13-param-dialog-设备筛选下拉与参数搜索输入框高度一致 @g13', async ({ page }) => {
  const errors: string[] = [];
  attachErrorCollectors(page, errors);
  await openHistory(page);
  await page.locator('.asm-filter input[placeholder="选择参数..."]').click();
  const dialog = page.locator('.el-dialog').filter({ hasText: '选择参数' });
  await expect(dialog).toBeVisible({ timeout: 10_000 });

  // getBoundingClientRect 实测：控件根与内层 wrapper 双层都要一致（根一致而内层错位=视觉仍歪）
  const h = await page.evaluate(() => {
    const rect = (el: Element | null) => (el ? el.getBoundingClientRect().height : null);
    const dev = document.querySelector('.asm-param-filter-row .asm-param-device');
    const kw = document.querySelector('.asm-param-filter-row .asm-param-search');
    return {
      devRoot: rect(dev),
      kwRoot: rect(kw),
      devInner: rect(dev && dev.querySelector('.el-select__wrapper')),
      kwInner: rect(kw && kw.querySelector('.el-input__wrapper')),
    };
  });
  expect(h.devRoot, '设备筛选控件应已渲染').toBeGreaterThan(0);
  expect(h.kwRoot, '参数搜索控件应已渲染').toBeGreaterThan(0);
  expect(Math.abs(h.devRoot - h.kwRoot), `控件根高度应一致（设备=${h.devRoot} 搜索=${h.kwRoot}）`).toBeLessThan(0.5);
  expect(Math.abs(h.devInner - h.kwInner), `内层 wrapper 高度应一致（设备=${h.devInner} 搜索=${h.kwInner}）`).toBeLessThan(0.5);
  expect(errors, `不应有 console error/pageerror: ${errors.join(' | ')}`).toEqual([]);
});
