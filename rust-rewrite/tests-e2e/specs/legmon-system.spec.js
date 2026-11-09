// @ts-check
const { test, expect } = require('@playwright/test');
const path = require('path');
const fs = require('fs');

test.describe('LegMon System Test: Document Upload, Polling, Error & Review UI Parity', () => {

  async function loginWithRauthy(page, targetBaseUrl) {
    const demoEmail = process.env.DEMO_EMAIL || 'demo@urawizard.com';
    const demoPassword = process.env.DEMO_PASSWORD || 'demo';

    await page.goto(`${targetBaseUrl}/login`, { waitUntil: 'networkidle' });

    if (page.url().includes('/auth/v1/oidc/authorize') || page.url().includes('identity.dryark.uk')) {
      const emailInput = page.locator('input[type="email"]');
      await expect(emailInput).toBeVisible({ timeout: 15000 });
      await emailInput.fill(demoEmail);
      await page.click('button:has-text("Login")');

      const pwdInput = page.locator('input[type="password"]');
      await expect(pwdInput).toBeVisible({ timeout: 15000 });
      await pwdInput.fill(demoPassword);
      await page.click('button:has-text("Login")');

      await page.waitForURL('**/spog', { timeout: 25000 });
    }
  }

  test('Document Upload -> "processing" state -> Polling -> Verbatim Review UI & Preview Modal', async ({ page }) => {
    test.setTimeout(90000);
    const targetBaseUrl = process.env.BASE_URL || 'https://monmon.dryark.uk';
    const fixturePath = path.resolve(__dirname, '../../../legmon/test_fixtures/sample_natwest_statement.pdf');
    expect(fs.existsSync(fixturePath)).toBeTruthy();

    // 1. Authenticate and navigate to /bank
    await loginWithRauthy(page, targetBaseUrl);
    await page.goto(`${targetBaseUrl}/bank`, { waitUntil: 'networkidle' });
    await expect(page).toHaveTitle(/Bank Sync/);

    // 2. Locate LegMon Statement card and upload form
    const uploadForm = page.locator('#legmon-upload-form');
    await expect(uploadForm).toBeVisible({ timeout: 10000 });

    const fileInput = uploadForm.locator('input[type="file"][name="pdfFile"]');
    await expect(fileInput).toBeVisible();

    // 3. Upload statement document
    console.log(`Uploading test fixture: ${fixturePath}`);
    await fileInput.setInputFiles(fixturePath);

    // Click upload button
    const uploadBtn = page.locator('#legmon-upload-btn');
    await expect(uploadBtn).toBeEnabled();
    await uploadBtn.click();

    // 4. Assert: UI immediately enters "processing" state
    console.log('Asserting "processing" state in UI...');
    const processingContainer = page.locator('#legmon-processing-container');
    await expect(processingContainer).toBeVisible({ timeout: 5000 });
    await expect(processingContainer).toContainText(/Processing Statement/i);
    await expect(uploadBtn).toBeDisabled();

    // 5. Assert: UI polls for progress and reveals Success & Review UI verbatim from Java version
    console.log('Waiting for polling to detect extracted statement...');
    const successBanner = page.locator('#legmon-success-banner');
    await expect(successBanner).toBeVisible({ timeout: 45000 });
    await expect(successBanner).toContainText(/Statement Processed Successfully/i);

    // Processing container should now be hidden
    await expect(processingContainer).toBeHidden();

    // 6. Assert: Review UI card rendered verbatim from Java version
    console.log('Verifying Review UI card structure verbatim from Java Play version...');
    const pendingCard = page.locator('[data-has-pending-statement="true"]').first();
    await expect(pendingCard).toBeVisible({ timeout: 10000 });

    // Bank name headline
    const bankHeadline = pendingCard.locator('h3');
    await expect(bankHeadline).toContainText(/Extracted Bank:/i);

    // Period and transaction count
    await expect(pendingCard).toContainText(/Period:/i);
    await expect(pendingCard).toContainText(/Transactions:/i);

    // Match to MonMon Account select dropdown
    const matchSelect = pendingCard.locator('select[name="accountId"]');
    await expect(matchSelect).toBeVisible();
    const optionCount = await matchSelect.locator('option').count();
    expect(optionCount).toBeGreaterThan(1);

    // Review & Import action button
    const reviewBtn = pendingCard.locator('button:has-text("Dangerously Overwrite ABL"), button:has-text("Review & Reconstruct")');
    await expect(reviewBtn).toBeVisible();

    // Discard statement action
    const discardBtn = pendingCard.locator('button:has-text("Discard Statement")');
    await expect(discardBtn).toBeVisible();

    // 7. Verify opening the Visual Verification Modal (Canvas preview)
    console.log('Opening Visual Verification: Balance Import Preview Modal...');
    // Ensure an account option is selected in the match dropdown
    const selectedVal = await matchSelect.inputValue();
    if (!selectedVal) {
      // Select the first real account option (index 1)
      await matchSelect.selectOption({ index: 1 });
    }

    await reviewBtn.click();

    // Assert preview modal opens
    const previewModal = page.locator('#statement-preview-modal');
    await expect(previewModal).toBeVisible({ timeout: 10000 });
    await expect(previewModal).toContainText(/Visual Verification: Balance Import Preview/i);

    // Assert key preview metrics appear
    await expect(page.locator('#preview-account-name')).not.toHaveText('-');
    await expect(page.locator('#preview-modal-content')).toBeVisible({ timeout: 10000 });

    // Assert trajectory canvas exists
    const canvas = page.locator('#statement-preview-canvas');
    await expect(canvas).toBeVisible();

    // Close preview modal
    await page.locator('#statement-preview-modal button:has-text("Cancel"), #statement-preview-modal button:has-text("×")').first().click();
    await expect(previewModal).toBeHidden();

    console.log('LegMon system test completed successfully!');
  });

  test('Error Handling: Uploading invalid non-PDF file shows dynamic error banner', async ({ page }) => {
    test.setTimeout(45000);
    const targetBaseUrl = process.env.BASE_URL || 'https://monmon.dryark.uk';

    await loginWithRauthy(page, targetBaseUrl);
    await page.goto(`${targetBaseUrl}/bank`, { waitUntil: 'networkidle' });

    // Create a temporary non-PDF corrupted file
    const tempCorruptedFile = path.resolve(__dirname, 'temp_corrupted_statement.pdf');
    fs.writeFileSync(tempCorruptedFile, 'CORRUPTED_NOT_A_REAL_PDF_HEADER');

    try {
      const uploadForm = page.locator('#legmon-upload-form');
      const fileInput = uploadForm.locator('input[type="file"][name="pdfFile"]');
      await fileInput.setInputFiles(tempCorruptedFile);

      const uploadBtn = page.locator('#legmon-upload-btn');
      await uploadBtn.click();

      // UI enters processing
      const processingContainer = page.locator('#legmon-processing-container');
      await expect(processingContainer).toBeVisible({ timeout: 5000 });

      // Error banner should appear when OCR / extraction fails
      const errorBanner = page.locator('#legmon-error-banner');
      await expect(errorBanner).toBeVisible({ timeout: 35000 });
      await expect(errorBanner).toContainText(/Error/i);

      // Processing banner should be dismissed
      await expect(processingContainer).toBeHidden();
      // Button re-enabled
      await expect(uploadBtn).toBeEnabled();
    } finally {
      if (fs.existsSync(tempCorruptedFile)) {
        fs.unlinkSync(tempCorruptedFile);
      }
    }
  });
});
