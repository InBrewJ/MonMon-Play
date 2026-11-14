// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Monzo Dig E2E', () => {

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

  test('Monzo Dig page loads stat cards, controls, and handles Test Sample mode', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/dig');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/Monzo Dig/);
    await expect(page.locator('body')).toBeVisible();

    // Verify presence of stat cards
    await expect(page.locator('#dig-stat-total')).toBeVisible();
    await expect(page.locator('#dig-stat-geotagged')).toBeVisible();
    await expect(page.locator('#dig-stat-category')).toBeVisible();

    // Verify test sample button exists
    const testBtn = page.locator('#btn-dig-test');
    await expect(testBtn).toBeVisible();

    // Click test sample button to load demo locations (London, Edinburgh, Vatican City)
    await testBtn.click();

    // After clicking Test mode, the map card should be visible and spots count populated
    const mapCard = page.locator('#dig-map-card');
    await expect(mapCard).toBeVisible();
    const mapElement = page.locator('#dig-map');
    await expect(mapElement).toBeVisible();

    const relevantErrors = consoleErrors.filter(
      err => !err.includes('favicon') && !err.includes('404') && !err.includes('WebSocket') && !err.includes('tile.openstreetmap.org')
    );
    expect(relevantErrors).toHaveLength(0);
  });
});
