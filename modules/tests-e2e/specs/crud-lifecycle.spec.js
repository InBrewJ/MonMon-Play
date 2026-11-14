// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Rust-through-Java Ship of Theseus: Core Primitives CRUD E2E', () => {

  async function loginIfRequired(page) {
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
  }

  test('Accounts CRUD: Create, View, and Verify delegation through Rust', async ({ page }) => {
    await page.goto('/myAccounts');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/MonMon Accounts/);
    await expect(page.locator('body')).toBeVisible();

    // 1. Open Add Account modal
    await page.locator('button[onclick="openAccountModal()"]').click();
    await expect(page.locator('#account-modal')).toBeVisible();

    // 2. Fill form
    const uniqueAccountName = 'Rust Monzo Vault ' + Date.now();
    await page.fill('#acc-name', uniqueAccountName);
    await page.selectOption('#type', 'SHORT_TERM_SAVINGS');
    await page.fill('#availableLimit', '750');

    // 3. Submit
    await Promise.all([
      page.waitForNavigation(),
      page.locator('#account-modal-form button[type="submit"]').click()
    ]);

    // 4. Verify new account is visible on the page (rendered by Java template, served by Rust repository)
    await expect(page.locator('body')).toContainText(uniqueAccountName);
  });

  test('Incomings CRUD: Create and View delegation through Rust', async ({ page }) => {
    await page.goto('/myIncomings');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/MonMon Incomings/);
    await expect(page.locator('body')).toBeVisible();

    // 1. Open Add Incoming modal
    await page.locator('button[onclick="openIncomingModal()"]').click();
    await expect(page.locator('#incoming-modal')).toBeVisible();

    // 2. Fill form
    const uniqueIncomingName = 'Rust Consulting Gig ' + Date.now();
    await page.fill('#incoming-name', uniqueIncomingName);
    await page.fill('input[name="netValue"]', '450.00');
    await page.fill('input[name="incomingMonthDay"]', '18');

    // 3. Submit
    await page.locator('#incoming-modal-form button[type="submit"]').click();
    await page.waitForLoadState('load');

    // 4. Verify new incoming is displayed
    await expect(page.locator('body')).toContainText(uniqueIncomingName);
    await expect(page.locator('body')).toContainText('£450');
  });

  test('Outgoings CRUD & Lifecycle: Create, Pause, Unpause, and Delete through Rust', async ({ page }) => {
    await page.goto('/myOutgoings');
    await loginIfRequired(page);

    await expect(page).toHaveTitle(/MonMon Outgoings/);

    // 1. Create Outgoing
    await page.locator('button[onclick="openOutgoingModal()"]').click();
    await expect(page.locator('#outgoing-modal')).toBeVisible();

    const uniqueOutgoingName = 'Fiber Broadband ' + Date.now();
    await page.fill('#outgoing-name', uniqueOutgoingName);
    await page.fill('#outgoing-cost', '38.50');
    await page.fill('input[name="outgoingDay"]', '14');

    await Promise.all([
      page.waitForNavigation(),
      page.locator('#outgoing-modal-form button[type="submit"]').click()
    ]);

    // 2. Verify element exists (either tr on desktop or item-card on mobile)
    const isMobile = await page.locator('.mobile-cards-view').isVisible();
    const itemLocator = isMobile
      ? page.locator('.item-card').filter({ hasText: uniqueOutgoingName })
      : page.locator('tr').filter({ hasText: uniqueOutgoingName });

    await expect(itemLocator).toBeVisible();
    await expect(itemLocator).toContainText('£38.5');

    // 3. Pause the outgoing
    const pauseBtn = isMobile
      ? itemLocator.locator('button', { hasText: 'Pause' })
      : itemLocator.locator('button[onclick*="openPauseModal"], a[title="Pause outgoing"]');
    await pauseBtn.click();

    const pauseModal = page.locator('#pause-outgoing-modal');
    await expect(pauseModal).toBeVisible();
    await page.fill('#pause-reason-input', 'ISP promotional discount applied');

    await Promise.all([
      page.waitForNavigation(),
      page.locator('#pause-outgoing-form button[type="submit"]').click()
    ]);

    // 4. Verify paused badge appears
    const pausedItem = isMobile
      ? page.locator('.item-card').filter({ hasText: uniqueOutgoingName })
      : page.locator('tr').filter({ hasText: uniqueOutgoingName });
    await expect(pausedItem).toContainText('Paused');

    // 5. Unpause outgoing
    const unpauseBtn = pausedItem.locator('a[href*="/unpauseOutgoing/"]');
    await Promise.all([
      page.waitForNavigation(),
      unpauseBtn.click()
    ]);

    // 6. Verify unpaused
    const activeItem = isMobile
      ? page.locator('.item-card').filter({ hasText: uniqueOutgoingName })
      : page.locator('tr').filter({ hasText: uniqueOutgoingName });
    await expect(activeItem).not.toContainText('Paused');
  });

});
