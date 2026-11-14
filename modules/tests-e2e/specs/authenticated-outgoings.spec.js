// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Authenticated Outgoings & Pause Modal E2E', () => {
  test('User logs in, views seeded outgoings, and opens pause modal on complex titled item', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/myOutgoings');

    // Handle Rauthy / Keycloak login
    if (page.url().includes('auth') || page.url().includes('login')) {
      const isRauthy = await page.locator('input[type="email"]').isVisible({ timeout: 1500 }).catch(() => false);
      if (isRauthy) {
        await page.fill('input[type="email"]', 'demo@demo.com');
        await page.click('button:has-text("Login")');
        const pwd = page.locator('input[type="password"]');
        await pwd.waitFor({ state: 'visible', timeout: 3000 });
        await pwd.fill('demo');
        await Promise.all([
          page.waitForNavigation(),
          page.click('button:has-text("Login")')
        ]);
      } else {
        await page.fill('#username', 'demo');
        await page.fill('#password', 'demo');
        await Promise.all([
          page.waitForNavigation(),
          page.click('#kc-login')
        ]);
      }
    }

    await expect(page).toHaveTitle(/MonMon Outgoings/);
    await expect(page.locator('body')).toBeVisible();

    // Verify seeded outgoings are listed
    const count = await page.locator('.col-name b').count();
    expect(count).toBeGreaterThanOrEqual(6);

    // Locate the exact Ionos VPS row/card containing brackets and apostrophe (supports desktop table and mobile Stacked Action Cards)
    const isMobile = await page.locator('.mobile-cards-view').isVisible();
    let pauseBtn;
    if (isMobile) {
      const vpsCard = page.locator('.item-card').filter({ hasText: 'boom.dryark.uk' });
      await expect(vpsCard).toBeVisible();
      // If it was left paused by a previous test run, unpause it first
      const unpauseBtn = vpsCard.locator('a', { hasText: 'Unpause' });
      if (await unpauseBtn.isVisible()) {
        await unpauseBtn.click();
        await page.waitForLoadState('networkidle');
      }
      pauseBtn = vpsCard.locator('button', { hasText: 'Pause' });
    } else {
      const vpsRow = page.locator('tr').filter({ hasText: 'boom.dryark.uk' });
      await expect(vpsRow).toBeVisible();
      // If it was left paused by a previous test run, unpause it first
      const unpauseBtn = vpsRow.locator('a', { hasText: 'Unpause' });
      if (await unpauseBtn.isVisible()) {
        await unpauseBtn.click();
        await page.waitForLoadState('networkidle');
      }
      pauseBtn = vpsRow.locator('a[title="Pause outgoing"]');
    }

    // Click the pause button
    await pauseBtn.click();

    // Verify the pause modal opened cleanly with exact title and cost
    const modal = page.locator('#pause-outgoing-modal');
    await expect(modal).toBeVisible();

    const modalName = page.locator('#pause-outgoing-name');
    await expect(modalName).toContainText('boom.dryark.uk');
    await expect(modalName).toContainText("what happens if we don't pay?");

    const modalCost = page.locator('#pause-outgoing-cost');
    await expect(modalCost).toHaveText('£10.8');

    // Ensure no JavaScript syntax errors occurred on the application view
    const relevantErrors = consoleErrors.filter(
      e => !e.includes('favicon') && !e.includes('fontawesome-free') && !e.includes('auth/v1') && !e.includes('401') && !e.includes('404')
    );
    expect(relevantErrors).toEqual([]);
  });
});
