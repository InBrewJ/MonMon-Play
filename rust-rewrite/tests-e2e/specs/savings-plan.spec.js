// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Savings Plan & Priority Queue E2E', () => {

  async function loginIfRequired(page) {
    if (page.url().includes('auth') || page.url().includes('login')) {
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
        // Keycloak / monmon-auth fallback
        await page.fill('#username', 'demo');
        await page.fill('#password', 'demo');
        await Promise.all([
          page.waitForNavigation(),
          page.click('#kc-login')
        ]);
      }
    }
  }

  test('Savings Plan desktop view loads headline metrics and priority slices', async ({ page }) => {
    const consoleErrors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });

    await page.goto('/savingsPlan');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/Savings Plan/);
    await expect(page.locator('body')).toBeVisible();

    // Verify key sections are present
    const slicedCard = page.locator('#sliced-items-card');
    await expect(slicedCard).toBeVisible();

    const addForm = page.locator('#edit-form');
    await expect(addForm).toBeVisible();

    // Verify presence of headline metric boxes
    await expect(page.locator('text=Monthly Income')).toBeVisible();
    await expect(page.locator('text=Savings Target Pot')).toBeVisible();

    const relevantErrors = consoleErrors.filter(
      e => !e.includes('favicon') && !e.includes('fontawesome-free')
    );
    expect(relevantErrors).toEqual([]);
  });

  test('Add item form allows creating a prioritized savings slice', async ({ page }) => {
    await page.goto('/savingsPlan');
    await loginIfRequired(page);

    const nameInput = page.locator('#item-name');
    if (await nameInput.isVisible()) {
      const testName = `E2E Slice ${Date.now()}`;
      await nameInput.fill(testName);

      const targetInput = page.locator('#item-target-amount');
      if (await targetInput.isVisible()) {
        await targetInput.fill('150');
      }

      const monthlyInput = page.locator('#item-monthly-amount');
      if (await monthlyInput.isVisible()) {
        await monthlyInput.fill('25');
      }

      // Submit form
      const submitBtn = page.locator('#edit-form button:has-text("Add Item")');
      await submitBtn.scrollIntoViewIfNeeded();
      await submitBtn.click({ force: true });

      // Verify item appears in the list (matches slice label, table row, or mobile card)
      await expect(page.locator(`text=${testName}`).first()).toBeVisible({ timeout: 10000 });
    }
  });

});
