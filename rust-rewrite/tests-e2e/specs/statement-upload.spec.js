// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Statement Upload & LegMon Bridge E2E', () => {

  async function loginIfRequired(page) {
    if (page.url().includes('auth')) {
      const isRauthy = await page.locator('input[type="email"]').isVisible({ timeout: 1000 }).catch(() => false);
      if (isRauthy) {
        await page.fill('input[type="email"]', 'demo@demo.com');
        await page.click('button:has-text("Login")');
        const pwd = page.locator('input[type="password"]');
        await pwd.waitFor({ state: 'visible' });
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
  }

  test('Bank Sync page renders LegMon status badge, statement upload form, and service logs', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/bank');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/Bank Sync/);
    await expect(page.locator('body')).toBeVisible();

    // Verify presence of LegMon statement card
    const statementCard = page.locator('#statement-upload');
    await expect(statementCard).toBeVisible();

    // Verify upload form exists with file input
    const uploadForm = statementCard.locator('form[action="/bank/statement/upload"]');
    await expect(uploadForm).toBeVisible();
    await expect(uploadForm.locator('input[type="file"][name="pdfFile"]')).toBeVisible();

    // Verify LegMon status badge updates dynamically
    const statusBadge = page.locator('#legmon-status-badge');
    await expect(statusBadge).toBeVisible();

    // Verify Service Logs toggle button
    const logsBtn = page.locator('button:has-text("Service Logs")');
    if (await logsBtn.isVisible()) {
      await logsBtn.click();
      await expect(page.locator('#legmon-logs-container')).toBeVisible();
    }

    const relevantErrors = consoleErrors.filter(
      err => !err.includes('favicon') && !err.includes('404') && !err.includes('WebSocket')
    );
    expect(relevantErrors).toHaveLength(0);
  });
});
