-- Seed Data for Demo User (id: 9aee56db-733a-457c-aa74-74fe74e2ac91)
DO $$
DECLARE
    u_id VARCHAR(255) := '9aee56db-733a-457c-aa74-74fe74e2ac91';
    acc_monzo BIGINT;
    acc_credit BIGINT;
    acc_savings BIGINT;
    acc_invest BIGINT;
BEGIN
    -- 1. Clean existing records for demo user
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
    VALUES (nextval('hibernate_sequence'), 'Senior Software Engineer Salary', 3450.00, 25, TRUE, u_id, u_id, u_id, acc_monzo, 1794000000000, FALSE);

    INSERT INTO incoming (id, name, netvalue, incomingmonthday, payday, user_id, userid, "userId", account_id, created_at, archived)
    VALUES (nextval('hibernate_sequence'), 'Consulting Retainer', 550.00, 1, FALSE, u_id, u_id, u_id, acc_monzo, 1794000000000, FALSE);

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
        0, FALSE, FALSE, FALSE, TRUE, 'Winter freeze until March', 'Winter freeze until March', 1794000000000, 1794000000000, FALSE
    );

    -- 4e. Yearly Car Insurance (Due in November!)
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

    -- 4f. Streaming subscriptions
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

    -- 5. Create Balances (Time Series for SPOG Charts)
    INSERT INTO balance (id, value, timestamp, user_id, userid, "userId", account_id)
    VALUES 
        (nextval('hibernate_sequence'), 1850.00, 1791500000, u_id, u_id, u_id, acc_monzo),
        (nextval('hibernate_sequence'), 2100.00, 1792500000, u_id, u_id, u_id, acc_monzo),
        (nextval('hibernate_sequence'), 2450.00, 1793500000, u_id, u_id, u_id, acc_monzo),
        (nextval('hibernate_sequence'), -580.00, 1792500000, u_id, u_id, u_id, acc_credit),
        (nextval('hibernate_sequence'), -420.00, 1793500000, u_id, u_id, u_id, acc_credit),
        (nextval('hibernate_sequence'), 3500.00, 1793500000, u_id, u_id, u_id, acc_savings),
        (nextval('hibernate_sequence'), 14800.00, 1793500000, u_id, u_id, u_id, acc_invest);

END $$;
