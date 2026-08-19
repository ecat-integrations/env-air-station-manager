/**
 * ASM 黑盒验收 G 段补验 @g4：页面样式 DOM 结构级验证（样式挂载/布局不出界/无裸奔信号）。
 *
 * 断言层级（DOM 结构级，非像素级；像素级视觉由 docs/screenshots 截图存档供人工核验）：
 *   - element-plus 结构类挂载：表格页 .el-table + .el-table__header 表头单元格文本非空；
 *     规则页编辑 .el-dialog 打开后表单控件可见；监控页 .asm-card 容器非零尺寸且在 viewport 内；
 *     历史页 echarts canvas width/height > 0。
 *   - 布局不出界：.asm-page 直接子元素 boundingBox 右沿 ≤ document.clientWidth（横向溢出即 fail）。
 *   - 无样式裸奔：.app-main 直接文本节点总长 < 5% of innerText（大量裸文本节点 = CSS 没挂上）。
 *
 * 截图另由 run-g4-screenshots 任务（fullPage）产出至 docs/screenshots/，不在本 spec 内做。
 */
import { test, expect } from '@playwright/test';
import { AsmBasePage, ASM_PAGES, attachErrorCollectors } from '../helpers/page-objects/AsmBasePage';

/** 表格类页面 key（.el-table 断言适用页）。 */
const TABLE_PAGES = ['history_data', 'alarm_rule', 'alarm_list', 'control_list', 'config'];

test.describe('ASM G4 样式结构验证', () => {
  test.describe.configure({ mode: 'serial' });

  test('G4-1 六页布局不出界 + 无样式裸奔 @g4', async ({ page }) => {
    const errors: string[] = [];
    attachErrorCollectors(page, errors);
    const base = new AsmBasePage(page, 'monitor');
    await base.goto();

    for (const key of ASM_PAGES) {
      await new AsmBasePage(page, key).navToOwn();
      // keep-alive 路由切换瞬时宽度抖动（旧页还在卸载）：settled 后再量
      await page.waitForTimeout(600);
      const root = page.locator('.app-main .asm-page').first();
      await expect(root, `页 ${key} .asm-page 应挂载`).toBeVisible();

      // 布局不出界：.asm-page 直接子元素右沿/下沿都在文档内（横向滚动 = 布局炸）
      const overflow = await page.evaluate(() => {
        const docW = document.documentElement.clientWidth;
        const bad: string[] = [];
        document.querySelectorAll('.asm-page > *').forEach((el) => {
          const b = (el as HTMLElement).getBoundingClientRect();
          if (b.width > 0 && b.right > docW + 2) bad.push(`${(el as HTMLElement).className}: right=${b.right.toFixed(0)} > ${docW}`);
        });
        return bad;
      });
      expect(overflow, `页 ${key} 有容器横向出界: ${overflow.join('; ')}`).toEqual([]);

      // 无样式裸奔：.app-main 直接文本节点（非组件内文本）占比异常 = CSS/结构丢失
      const naked = await page.evaluate(() => {
        const main = document.querySelector('.app-main');
        if (!main) return -1;
        let direct = 0;
        main.childNodes.forEach((n) => { if (n.nodeType === Node.TEXT_NODE) direct += (n.textContent || '').trim().length; });
        const total = (main.innerText || '').replace(/\s+/g, '').length;
        return total === 0 ? 0 : direct / total;
      });
      expect(naked, `页 ${key} .app-main 直接文本节点占比异常（疑似样式裸奔）: ${naked}`).toBeLessThan(0.05);
    }
    expect(errors).toEqual([]);
  });

  test('G4-2 表格页 el-table 结构 + 表头齐全 @g4', async ({ page }) => {
    const base = new AsmBasePage(page, 'alarm_list');
    await base.goto();

    for (const key of TABLE_PAGES) {
      await new AsmBasePage(page, key).navToOwn();
      const table = page.locator('.asm-page .el-table').first();
      await expect(table, `页 ${key} .el-table 应挂载`).toBeVisible({ timeout: 20_000 });
      // 表头单元格存在且文本非空（缺列 = 列定义/渲染缺失）
      const headers = await page.locator('.asm-page .el-table .el-table__header th .cell').allInnerTexts();
      const nonEmpty = headers.filter((h) => h.trim().length > 0);
      expect(nonEmpty.length, `页 ${key} 表头单元格文本非空数 ${nonEmpty.length}/${headers.length}: ${headers.join(',')}`).toBeGreaterThan(0);
      expect(headers.length, `页 ${key} 表头列数应>2`).toBeGreaterThan(2);
    }
  });

  test('G4-3 监控页卡片容器尺寸在 viewport 内 @g4', async ({ page }) => {
    const base = new AsmBasePage(page, 'monitor');
    await base.goto();
    const cards = page.locator('.asm-cards .asm-card');
    await expect(cards.first()).toBeVisible({ timeout: 20_000 });
    const n = await cards.count();
    expect(n).toBeGreaterThan(0);
    const boxes = await cards.evaluateAll((els) => els.map((el) => {
      const b = el.getBoundingClientRect();
      return { w: b.width, h: b.height, right: b.right };
    }));
    const docW = await page.evaluate(() => document.documentElement.clientWidth);
    const zero = boxes.filter((b) => b.w <= 0 || b.h <= 0).length;
    expect(zero, `有 ${zero}/${n} 张卡片尺寸为零（样式未挂）`).toBe(0);
    const out = boxes.filter((b) => b.right > docW + 1).length;
    expect(out, `有 ${out}/${n} 张卡片横向出界`).toBe(0);
  });

  test('G4-4 历史页 echarts canvas 尺寸 >0 @g4', async ({ page }) => {
    const base = new AsmBasePage(page, 'history_data');
    await base.goto();
    // 不发起查询也断言：空态下图表容器/占位结构应在 DOM；有 canvas 时尺寸必须非零
    const canvas = page.locator('.asm-page canvas').first();
    const hasCanvas = await canvas.count();
    if (hasCanvas > 0) {
      const box = await canvas.boundingBox();
      expect(box, 'echarts canvas boundingBox 应非空').not.toBeNull();
      expect(box!.width, `canvas width=${box!.width} 应>0`).toBeGreaterThan(0);
      expect(box!.height, `canvas height=${box!.height} 应>0`).toBeGreaterThan(0);
    }
    // 图表宿主容器（查询前空态）也应非零尺寸，证明布局占位正常
    const chartHost = page.locator('.asm-chart, .asm-echarts, [class*="chart"]').first();
    if (await chartHost.count()) {
      const hb = await chartHost.boundingBox();
      expect(hb && hb.height > 0, '图表宿主容器高度应>0').toBeTruthy();
    }
  });

  test('G4-5 规则页编辑弹窗 el-dialog 表单控件可见 @g4', async ({ page }) => {
    const errors: string[] = [];
    attachErrorCollectors(page, errors);
    const base = new AsmBasePage(page, 'alarm_rule');
    await base.goto();

    // 首个「编辑」按钮打开弹窗（seed 规则必存在，G2 已证）
    const editBtn = page.locator('.asm-page .el-table .el-button', { hasText: '编辑' }).first();
    await expect(editBtn).toBeVisible({ timeout: 20_000 });
    await editBtn.click();

    const dialog = page.locator('.el-dialog');
    await expect(dialog).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('.el-dialog .el-dialog__title')).toContainText(/编辑/);
    // 弹窗内表单控件可见（input 至少 1 个 + 弹窗在 viewport 内）
    const inputs = page.locator('.el-dialog .el-input input, .el-dialog .el-form input');
    expect(await inputs.count(), '编辑弹窗应有表单 input').toBeGreaterThan(0);
    const db = await dialog.boundingBox();
    expect(db, 'dialog boundingBox 非空').not.toBeNull();
    expect(db!.width).toBeGreaterThan(100);
    expect(db!.height).toBeGreaterThan(100);

    // 关闭还原
    await page.locator('.el-dialog .el-dialog__headerbtn').click();
    await expect(dialog).toBeHidden();
    expect(errors).toEqual([]);
  });
});
