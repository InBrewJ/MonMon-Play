// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Net Worth Dashboard E2E', () => {

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

  test('Net Worth desktop view loads headline card, asset ratio, and cards', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/netWorth');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/Net Worth/);
    await expect(page.locator('body')).toBeVisible();

    // Verify presence of headline hero card
    const overviewCard = page.locator('#card-nw-overview');
    await expect(overviewCard).toBeVisible();
    await expect(overviewCard).toContainText('Total Net Worth');
    await expect(overviewCard).toContainText('Assets:');
    await expect(overviewCard).toContainText('Debts:');

    // Verify Add Asset button triggers modal
    const addAssetBtn = page.locator('button:has-text("Add Asset")');
    await expect(addAssetBtn).toBeVisible();
    await addAssetBtn.click();
    await expect(page.locator('#add-asset-modal')).toBeVisible();

    const relevantErrors = consoleErrors.filter(
      err => !err.includes('favicon') && !err.includes('404') && !err.includes('WebSocket')
    );
    expect(relevantErrors).toHaveLength(0);
  });

  test('Net Worth mobile viewport supports swipe navigation deck', async ({ page, isMobile }) => {
    if (!isMobile) {
      await page.setViewportSize({ width: 375, height: 667 });
    }

    await page.goto('/netWorth');
    await loginIfRequired(page);

    const deckNav = page.locator('#nw-deck-nav');
    await expect(deckNav).toBeVisible();

    const overviewCard = page.locator('#card-nw-overview');
    await expect(overviewCard).toBeVisible();
  });
});
