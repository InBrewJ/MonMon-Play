// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Bank Sync & Monzo Dashboard E2E', () => {

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

  test('Bank Sync dashboard loads navigation pills and Monzo cards', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/bank');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/Monzo Bank Sync|Bank Sync/);
    await expect(page.locator('body')).toBeVisible();

    // Verify presence of cards
    const monzoCard = page.locator('#card-bank-monzo');
    await expect(monzoCard).toBeVisible();

    const accountsCard = page.locator('#card-bank-accounts');
    await expect(accountsCard).toBeVisible();

    const txCard = page.locator('#card-bank-txs');
    await expect(txCard).toBeVisible();

    // Verify presence of headline title
    await expect(page.locator('h1:has-text("Accurate Mode")')).toBeVisible();

    const relevantErrors = consoleErrors.filter(
      e => !e.includes('favicon') && !e.includes('fontawesome-free')
    );
    expect(relevantErrors).toEqual([]);
  });

});
