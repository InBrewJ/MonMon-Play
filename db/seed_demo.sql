-- Seed Data for Demo User (canonical UUID matching modules/monmon-rs/src/auth.rs)
-- Login: username "demo", password "demo" (or DEMO_PASSWORD env var)
DO $$
DECLARE
    u_id VARCHAR(255) := '434268bd-0c8a-41f6-b191-3768a7fe0048';
    now_sec BIGINT := EXTRACT(EPOCH FROM NOW())::BIGINT;
    now_ms  BIGINT := (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT;
    pot_start_sec BIGINT := EXTRACT(EPOCH FROM (NOW() - INTERVAL '45 days'))::BIGINT;
    pot_start_ms  BIGINT := (EXTRACT(EPOCH FROM (NOW() - INTERVAL '45 days')) * 1000)::BIGINT;
    acc_monzo BIGINT;
    acc_credit BIGINT;
    acc_savings BIGINT;
    acc_invest BIGINT;
    monthly_pot_id BIGINT;
BEGIN
    -- 0. Ensure demo user exists and is approved in user_access
    INSERT INTO user_access (id, approvedat, approvedby, createdat, created_at, updated_at, email, status, userid, username)
    VALUES (nextval('hibernate_sequence'), NOW(), 'SEED', NOW(), NOW(), NOW(), 'demo@urawizard.com', 'APPROVED', u_id, 'demo')
    ON CONFLICT (userid) DO UPDATE
        SET status = 'APPROVED', username = 'demo', email = 'demo@urawizard.com', updated_at = NOW();

    -- 1. Clean existing records for demo user (idempotent re-runs)
    DELETE FROM pot_accounts WHERE pot_id IN (
        SELECT id FROM pot WHERE COALESCE(user_id, userid, "userId") = u_id
    );
    DELETE FROM pot WHERE COALESCE(user_id, userid, "userId") = u_id;
    DELETE FROM balance WHERE COALESCE(user_id, userid, "userId") = u_id;
    DELETE FROM outgoing WHERE COALESCE(user_id, userid, "userId") = u_id;
    DELETE FROM incoming WHERE COALESCE(user_id, userid, "userId") = u_id;
    DELETE FROM account WHERE COALESCE(user_id, userid, "userId") = u_id;

    -- 2. Create Accounts
    INSERT INTO account (id, name, type, user_id, userid, "userId", account_limit, interest_rate, ledger_mode, archived)
    VALUES (nextval('hibernate_sequence'), 'Monzo Current Account', 'DEBIT', u_id, u_id, u_id, 500.0, 0.0, 'TBL', FALSE)
    RETURNING id INTO acc_monzo;

    INSERT INTO account (id, name, type, user_id, userid, "userId", account_limit, interest_rate, ledger_mode, archived)
    VALUES (nextval('hibernate_sequence'), 'Barclaycard Platinum', 'CREDIT', u_id, u_id, u_id, 3000.0, 19.9, 'TBL', FALSE)
    RETURNING id INTO acc_credit;

    INSERT INTO account (id, name, type, user_id, userid, "userId", account_limit, interest_rate, ledger_mode, archived)
    VALUES (nextval('hibernate_sequence'), 'Emergency Savings Pot', 'SHORT_TERM_SAVINGS', u_id, u_id, u_id, 0.0, 4.5, 'TBL', FALSE)
    RETURNING id INTO acc_savings;

    INSERT INTO account (id, name, type, user_id, userid, "userId", account_limit, interest_rate, ledger_mode, archived)
    VALUES (nextval('hibernate_sequence'), 'Vanguard S&S ISA', 'LONG_TERM_SAVINGS', u_id, u_id, u_id, 0.0, 0.0, 'TBL', FALSE)
    RETURNING id INTO acc_invest;

    -- 3. Create Incomings
    INSERT INTO incoming (id, name, netvalue, incomingmonthday, payday, user_id, userid, "userId", account_id, created_at, archived)
    VALUES (nextval('hibernate_sequence'), 'Senior Software Engineer Salary', 3450.00, 25, TRUE, u_id, u_id, u_id, acc_monzo, now_ms, FALSE);

    INSERT INTO incoming (id, name, netvalue, incomingmonthday, payday, user_id, userid, "userId", account_id, created_at, archived)
    VALUES (nextval('hibernate_sequence'), 'Consulting Retainer', 550.00, 1, FALSE, u_id, u_id, u_id, acc_monzo, now_ms, FALSE);

    -- 4. Create Outgoings
    -- 4a. The VPS outgoing with apostrophes & brackets
    INSERT INTO outgoing (
        id, name, cost, outgoingday, user_id, userid, "userId", account_id,
        outgoingmonth, bill, rent, hiddenfromtotal, paused, archived
    )
    VALUES (
        nextval('hibernate_sequence'),
        '[£9 for 24 months exVAT, started 2025-06] boom.dryark.uk :: (Ionos VPS L => 4vCPU, 8GB RAM, 240GB SSD) (what happens if we don''t pay?)',
        10.80, 15, u_id, u_id, u_id, acc_monzo,
        0, TRUE, FALSE, FALSE, FALSE, FALSE
    );

    -- 4b. Rent
    INSERT INTO outgoing (
        id, name, cost, outgoingday, user_id, userid, "userId", account_id,
        outgoingmonth, bill, rent, hiddenfromtotal, paused, archived
    )
    VALUES (
        nextval('hibernate_sequence'),
        'Monthly Apartment Rent',
        1250.00, 1, u_id, u_id, u_id, acc_monzo,
        0, FALSE, TRUE, FALSE, FALSE, FALSE
    );

    -- 4c. Council Tax
    INSERT INTO outgoing (
        id, name, cost, outgoingday, user_id, userid, "userId", account_id,
        outgoingmonth, bill, rent, hiddenfromtotal, paused, archived
    )
    VALUES (
        nextval('hibernate_sequence'),
        'Bristol City Council Tax',
        168.00, 5, u_id, u_id, u_id, acc_monzo,
        0, TRUE, FALSE, FALSE, FALSE, FALSE
    );

    -- 4d. Paused Gym Membership
    INSERT INTO outgoing (
        id, name, cost, outgoingday, user_id, userid, "userId", account_id,
        outgoingmonth, bill, rent, hiddenfromtotal, paused, pause_reason, pausereason, paused_at, pausedat, archived
    )
    VALUES (
        nextval('hibernate_sequence'),
        'PureGym Membership (Winter Pause)',
        29.99, 1, u_id, u_id, u_id, acc_monzo,
        0, FALSE, FALSE, FALSE, TRUE, 'Winter freeze until March', 'Winter freeze until March', now_ms, now_ms, FALSE
    );

    -- 4e. Yearly Car Insurance (Due in November)
    INSERT INTO outgoing (
        id, name, cost, outgoingday, user_id, userid, "userId", account_id,
        outgoingmonth, yearinterval, startingyear, bill, rent, hiddenfromtotal, paused, archived
    )
    VALUES (
        nextval('hibernate_sequence'),
        'Admiral Car Insurance (Yearly)',
        520.00, 12, u_id, u_id, u_id, acc_monzo,
        11, 1, 2025, TRUE, FALSE, FALSE, FALSE, FALSE
    );

    -- 4f. Streaming subscription
    INSERT INTO outgoing (
        id, name, cost, outgoingday, user_id, userid, "userId", account_id,
        outgoingmonth, bill, rent, hiddenfromtotal, paused, archived
    )
    VALUES (
        nextval('hibernate_sequence'),
        'Netflix 4K Ultra HD',
        17.99, 18, u_id, u_id, u_id, acc_credit,
        0, FALSE, FALSE, FALSE, FALSE, FALSE
    );

    -- 5. Create Active Monthly Pot (linked to Monzo Current Account)
    INSERT INTO pot (
        id, userid, user_id, "userId", type, createdat, lastupdated, created_at, updated_at, archived,
        spending_plan_name, spending_plan_food, spending_plan_fuel, spending_plan_transport, spending_plan_fun,
        spending_plan_sections_json
    )
    VALUES (
        nextval('hibernate_sequence'), u_id, u_id, u_id, 'MONTHLY',
        pot_start_sec, pot_start_sec, pot_start_ms, pot_start_ms, FALSE,
        'Demo Monthly Plan', 250.0, 0.0, 100.0, 150.0,
        '[{"heading":"Food","amount":250,"accountId":null},{"heading":"Fun","amount":150,"accountId":null},{"heading":"Transport","amount":100,"accountId":null}]'
    )
    RETURNING id INTO monthly_pot_id;

    INSERT INTO pot_accounts (pot_id, account_id, created_at, updated_at)
    VALUES (monthly_pot_id, acc_monzo, pot_start_ms, pot_start_ms);

    -- 6. Create Recent Time-Series Balances (relative to NOW() so SPOG & /historicSpent always show live cycles)
    INSERT INTO balance (id, value, timestamp, user_id, userid, "userId", account_id)
    VALUES
        -- Previous cycle snapshots
        (nextval('hibernate_sequence'), 2600.00, now_sec - 40 * 86400, u_id, u_id, u_id, acc_monzo),
        (nextval('hibernate_sequence'), 2320.00, now_sec - 32 * 86400, u_id, u_id, u_id, acc_monzo),
        (nextval('hibernate_sequence'), 2140.00, now_sec - 25 * 86400, u_id, u_id, u_id, acc_monzo),
        -- Current cycle snapshots (with realistic outflows)
        (nextval('hibernate_sequence'), 2550.00, now_sec - 14 * 86400, u_id, u_id, u_id, acc_monzo),
        (nextval('hibernate_sequence'), 2410.50, now_sec -  7 * 86400, u_id, u_id, u_id, acc_monzo),
        (nextval('hibernate_sequence'), 2285.00, now_sec -  1 * 86400, u_id, u_id, u_id, acc_monzo),
        -- Credit, savings & investment balances
        (nextval('hibernate_sequence'), -580.00, now_sec - 25 * 86400, u_id, u_id, u_id, acc_credit),
        (nextval('hibernate_sequence'), -420.00, now_sec -  1 * 86400, u_id, u_id, u_id, acc_credit),
        (nextval('hibernate_sequence'), 3200.00, now_sec - 25 * 86400, u_id, u_id, u_id, acc_savings),
        (nextval('hibernate_sequence'), 3500.00, now_sec -  1 * 86400, u_id, u_id, u_id, acc_savings),
        (nextval('hibernate_sequence'), 14500.00, now_sec - 25 * 86400, u_id, u_id, u_id, acc_invest),
        (nextval('hibernate_sequence'), 14800.00, now_sec -  1 * 86400, u_id, u_id, u_id, acc_invest);
END $$;
