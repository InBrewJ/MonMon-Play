--
-- PostgreSQL database dump
--

-- Dumped from database version 11.16 (Debian 11.16-1.pgdg90+1)
-- Dumped by pg_dump version 11.16 (Debian 11.16-1.pgdg90+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: sync_user_id_columns(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.sync_user_id_columns() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    resolved_uid VARCHAR(255);
BEGIN
    resolved_uid := COALESCE(NEW.userid, NEW.user_id, NEW."userId");
    NEW.userid := resolved_uid;
    NEW.user_id := resolved_uid;
    NEW."userId" := resolved_uid;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_with_oids = false;

--
-- Name: account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account (
    id bigint NOT NULL,
    archived boolean NOT NULL,
    account_limit real,
    interest_rate real,
    ledger_mode character varying(255),
    name character varying(255),
    nickname character varying(255),
    type character varying(255),
    userid character varying(255),
    user_id character varying(255),
    "userId" character varying(255),
    created_at bigint,
    updated_at bigint,
    archived_at bigint
);


--
-- Name: balance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.balance (
    id bigint NOT NULL,
    "timestamp" bigint,
    userid character varying(255),
    value real,
    account_id bigint,
    user_id character varying(255),
    "userId" character varying(255),
    created_at bigint,
    updated_at bigint
);


--
-- Name: bank_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bank_transaction (
    id bigint NOT NULL,
    accountid bigint,
    amount real,
    category character varying(255),
    createdat bigint,
    date character varying(255),
    description character varying(500),
    externalid character varying(255),
    latitude double precision,
    longitude double precision,
    merchantaddress character varying(500),
    merchantemoji character varying(255),
    merchantname character varying(255),
    pending boolean NOT NULL,
    raw_data text,
    source character varying(255),
    "timestamp" bigint,
    userid character varying(255) NOT NULL,
    user_id character varying(255),
    "userId" character varying(255),
    account_id bigint,
    "accountId" bigint,
    merchant_address character varying(500),
    merchant_emoji character varying(50),
    merchant_name character varying(255),
    created_at bigint,
    updated_at bigint
);


--
-- Name: incoming; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.incoming (
    id bigint NOT NULL,
    archived boolean NOT NULL,
    archived_at bigint,
    created_at bigint,
    grossvalue real,
    history_json text,
    incomingmonthday integer NOT NULL,
    name character varying(255),
    netvalue real,
    payday boolean NOT NULL,
    type character varying(255),
    userid character varying(255),
    account_id bigint,
    user_id character varying(255),
    "userId" character varying(255),
    updated_at bigint
);


--
-- Name: locked_meal_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.locked_meal_plan (
    id bigint NOT NULL,
    lockedat bigint,
    people integer,
    planjson text NOT NULL,
    postcode character varying(255),
    userid character varying(255) NOT NULL,
    weeklybudget real,
    user_id character varying(255),
    "userId" character varying(255),
    "weeklyBudget" real,
    "planJson" text,
    "lockedAt" bigint,
    created_at bigint,
    updated_at bigint
);


--
-- Name: net_worth_asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.net_worth_asset (
    id bigint NOT NULL,
    acquired_at bigint,
    archived boolean NOT NULL,
    category character varying(255) NOT NULL,
    created_at bigint,
    deleted_at bigint,
    name character varying(255) NOT NULL,
    notes character varying(255),
    updated_at bigint,
    userid character varying(255) NOT NULL,
    asset_value double precision NOT NULL,
    createdat bigint,
    updatedat bigint,
    acquiredat bigint,
    user_id character varying(255),
    "userId" character varying(255),
    archived_at bigint
);


--
-- Name: outgoing; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outgoing (
    id bigint NOT NULL,
    archived boolean NOT NULL,
    bill boolean NOT NULL,
    cost real,
    hiddenfromtotal boolean NOT NULL,
    name character varying(255),
    outgoingday integer,
    outgoingmonth integer,
    pause_reason character varying(255),
    paused boolean,
    paused_at bigint,
    rent boolean NOT NULL,
    startingyear integer,
    userid character varying(255),
    yearinterval integer,
    account_id bigint,
    pausereason character varying(255),
    pausedat bigint,
    user_id character varying(255),
    "userId" character varying(255),
    "OUTGOINGDAY" integer,
    "OUTGOINGMONTH" integer,
    created_at bigint,
    updated_at bigint,
    archived_at bigint
);


--
-- Name: pending_statement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pending_statement (
    id bigint NOT NULL,
    applied_at bigint,
    bank_name character varying(255),
    closing_balance real,
    created_at bigint,
    end_date character varying(255),
    end_timestamp bigint,
    error_message text,
    job_id character varying(255),
    matched_account_id bigint,
    opening_balance real,
    raw_payload text,
    start_date character varying(255),
    start_timestamp bigint,
    status character varying(255),
    total_paid_in real,
    total_paid_out real,
    transaction_count integer,
    user_id character varying(255),
    userid character varying(255),
    "userId" character varying(255),
    updated_at bigint
);


--
-- Name: plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan (
    id bigint NOT NULL,
    archived boolean NOT NULL,
    cost real,
    notes character varying(255),
    scope character varying(255),
    split real,
    type character varying(255),
    userid character varying(255),
    user_id character varying(255),
    "userId" character varying(255),
    created_at bigint,
    updated_at bigint,
    archived_at bigint
);


--
-- Name: pot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pot (
    id bigint NOT NULL,
    archived boolean NOT NULL,
    createdat bigint NOT NULL,
    lastupdated bigint NOT NULL,
    spending_plan_food real,
    spending_plan_fuel real,
    spending_plan_fun real,
    spending_plan_name character varying(255),
    spending_plan_sections_json text,
    spending_plan_transport real,
    type character varying(255),
    userid character varying(255),
    food_account_id bigint,
    fuel_account_id bigint,
    fun_account_id bigint,
    transport_account_id bigint,
    user_id character varying(255),
    "userId" character varying(255),
    created_at bigint,
    updated_at bigint,
    archived_at bigint
);


--
-- Name: savings_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.savings_plan (
    id bigint NOT NULL,
    archived boolean NOT NULL,
    createdat bigint,
    lastupdated bigint,
    name character varying(255),
    savingspercentage integer NOT NULL,
    userid character varying(255) NOT NULL,
    user_id character varying(255),
    "userId" character varying(255),
    savings_percentage real DEFAULT 0,
    last_updated bigint,
    created_at bigint,
    updated_at bigint,
    archived_at bigint
);


--
-- Name: savings_plan_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.savings_plan_item (
    id bigint NOT NULL,
    archived boolean NOT NULL,
    locked boolean NOT NULL,
    monthlyamount real,
    name character varying(255),
    priority integer,
    targetamount real,
    type character varying(255),
    account_id bigint,
    savings_plan_id bigint NOT NULL,
    target_amount real DEFAULT 0,
    monthly_amount real DEFAULT 0,
    created_at bigint,
    updated_at bigint,
    archived_at bigint
);


--
-- Name: app_search_view; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.app_search_view AS
 SELECT 'account'::character varying(50) AS entity_type,
    (a.id)::character varying AS entity_id,
    (COALESCE(a.user_id, ''::character varying))::character varying(255) AS user_id,
    (COALESCE(a.name, 'Account'::character varying))::character varying(255) AS title,
    (((COALESCE(a.type, 'Account'::character varying))::text ||
        CASE
            WHEN ((a.nickname IS NOT NULL) AND ((a.nickname)::text <> ''::text)) THEN ((' ('::text || (a.nickname)::text) || ')'::text)
            ELSE ''::text
        END))::character varying(255) AS subtitle,
    (((('/myAccounts?highlight=account-'::text || ((a.id)::character varying)::text) || '#account-'::text) || ((a.id)::character varying)::text))::character varying(255) AS target_url,
    (('account-'::text || ((a.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-university'::character varying(50) AS icon,
    'Account'::character varying(50) AS badge,
    lower(((((((COALESCE(a.name, ''::character varying))::text || ' '::text) || (COALESCE(a.nickname, ''::character varying))::text) || ' '::text) || (COALESCE(a.type, ''::character varying))::text) || ' account'::text)) AS search_text
   FROM public.account a
  WHERE ((a.archived = false) OR (a.archived IS NULL))
UNION ALL
 SELECT 'outgoing'::character varying(50) AS entity_type,
    (o.id)::character varying AS entity_id,
    (COALESCE(o.user_id, ''::character varying))::character varying(255) AS user_id,
    (COALESCE(o.name, 'Outgoing'::character varying))::character varying(255) AS title,
    ((((('£'::text || to_char(COALESCE(o.cost, (0)::real), 'FM999999990.00'::text)) || ' • Day '::text) || ((COALESCE(o."OUTGOINGDAY", 1))::character varying)::text) ||
        CASE
            WHEN (o.paused = true) THEN ' (Paused)'::text
            ELSE ''::text
        END))::character varying(255) AS subtitle,
    (((('/myOutgoings?highlight=outgoing-'::text || ((o.id)::character varying)::text) || '#outgoing-'::text) || ((o.id)::character varying)::text))::character varying(255) AS target_url,
    (('outgoing-'::text || ((o.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-credit-card'::character varying(50) AS icon,
    'Outgoing'::character varying(50) AS badge,
    lower(((((((COALESCE(o.name, ''::character varying))::text || ' '::text) || (COALESCE(o.pause_reason, ''::character varying))::text) || ' '::text) || ((COALESCE(o.cost, (0)::real))::character varying)::text) || ' outgoing bill rent'::text)) AS search_text
   FROM public.outgoing o
  WHERE ((o.archived = false) OR (o.archived IS NULL))
UNION ALL
 SELECT 'incoming'::character varying(50) AS entity_type,
    (i.id)::character varying AS entity_id,
    (COALESCE(i.user_id, ''::character varying))::character varying(255) AS user_id,
    (COALESCE(i.name, 'Incoming'::character varying))::character varying(255) AS title,
    ((((('£'::text || to_char(COALESCE(i.netvalue, (0)::real), 'FM999999990.00'::text)) || ' • Day '::text) || ((COALESCE(i.incomingmonthday, 1))::character varying)::text) ||
        CASE
            WHEN (i.payday = true) THEN ' (Payday)'::text
            ELSE ''::text
        END))::character varying(255) AS subtitle,
    (((('/myIncomings?highlight=incoming-'::text || ((i.id)::character varying)::text) || '#incoming-'::text) || ((i.id)::character varying)::text))::character varying(255) AS target_url,
    (('incoming-'::text || ((i.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-money-bill-wave'::character varying(50) AS icon,
    'Incoming'::character varying(50) AS badge,
    lower(((((((COALESCE(i.name, ''::character varying))::text || ' '::text) || (COALESCE(i.type, ''::character varying))::text) || ' '::text) || ((COALESCE(i.netvalue, (0)::real))::character varying)::text) || ' incoming salary payday income'::text)) AS search_text
   FROM public.incoming i
  WHERE ((i.archived = false) OR (i.archived IS NULL))
UNION ALL
 SELECT 'balance'::character varying(50) AS entity_type,
    (b.id)::character varying AS entity_id,
    (COALESCE(b.user_id, ''::character varying))::character varying(255) AS user_id,
    (((COALESCE(acc.name, 'Account'::character varying))::text || ' Balance'::text))::character varying(255) AS title,
    ((('£'::text || to_char(COALESCE(b.value, (0)::real), 'FM999999990.00'::text)) ||
        CASE
            WHEN ((b."timestamp" IS NOT NULL) AND (b."timestamp" > 0)) THEN ((' ('::text || to_char(to_timestamp(((b."timestamp" / 1000))::double precision), 'DD Mon YYYY'::text)) || ')'::text)
            ELSE ''::text
        END))::character varying(255) AS subtitle,
    (((('/myBalances?highlight=balance-'::text || ((b.id)::character varying)::text) || '#balance-'::text) || ((b.id)::character varying)::text))::character varying(255) AS target_url,
    (('balance-'::text || ((b.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-wallet'::character varying(50) AS icon,
    'Balance'::character varying(50) AS badge,
    lower((((COALESCE(acc.name, ''::character varying))::text || ' balance '::text) || ((COALESCE(b.value, (0)::real))::character varying)::text)) AS search_text
   FROM (public.balance b
     LEFT JOIN public.account acc ON ((b.account_id = acc.id)))
UNION ALL
 SELECT 'pot'::character varying(50) AS entity_type,
    (p.id)::character varying AS entity_id,
    (COALESCE(p.user_id, ''::character varying))::character varying(255) AS user_id,
    (COALESCE(p.spending_plan_name, (((COALESCE(p.type, 'Monthly'::character varying))::text || ' Pot'::text))::character varying))::character varying(255) AS title,
    (((((COALESCE(p.type, 'Monthly'::character varying))::text || ' Pot'::text) ||
        CASE
            WHEN (p.spending_plan_food IS NOT NULL) THEN (' • Food £'::text || ((p.spending_plan_food)::character varying)::text)
            ELSE ''::text
        END) ||
        CASE
            WHEN (p.spending_plan_fun IS NOT NULL) THEN (', Fun £'::text || ((p.spending_plan_fun)::character varying)::text)
            ELSE ''::text
        END))::character varying(255) AS subtitle,
    (((('/myMonthlyPot?highlight=pot-'::text || ((p.id)::character varying)::text) || '#pot-'::text) || ((p.id)::character varying)::text))::character varying(255) AS target_url,
    (('pot-'::text || ((p.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-archive'::character varying(50) AS icon,
    'Pot'::character varying(50) AS badge,
    lower(((((COALESCE(p.spending_plan_name, ''::character varying))::text || ' '::text) || (COALESCE(p.type, ''::character varying))::text) || ' pot spending plan food fun fuel'::text)) AS search_text
   FROM public.pot p
UNION ALL
 SELECT 'savings_plan'::character varying(50) AS entity_type,
    (sp.id)::character varying AS entity_id,
    (COALESCE(sp.userid, sp.user_id, ''::character varying))::character varying(255) AS user_id,
    (COALESCE(sp.name, 'Monthly Savings Plan'::character varying))::character varying(255) AS title,
    ((((COALESCE((sp.savingspercentage)::real, sp.savings_percentage, (0)::real))::character varying)::text || '% of net income target pot'::text))::character varying(255) AS subtitle,
    (((('/savingsPlan?highlight=savings-plan-'::text || ((sp.id)::character varying)::text) || '#savings-plan-'::text) || ((sp.id)::character varying)::text))::character varying(255) AS target_url,
    (('savings-plan-'::text || ((sp.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-piggy-bank'::character varying(50) AS icon,
    'Savings Plan'::character varying(50) AS badge,
    lower(((((COALESCE(sp.name, ''::character varying))::text || ' savings plan '::text) || ((COALESCE((sp.savingspercentage)::real, sp.savings_percentage, (0)::real))::character varying)::text) || '%'::text)) AS search_text
   FROM public.savings_plan sp
  WHERE ((sp.archived = false) OR (sp.archived IS NULL))
UNION ALL
 SELECT 'savings_plan_item'::character varying(50) AS entity_type,
    (spi.id)::character varying AS entity_id,
    (COALESCE(sp.userid, sp.user_id, ''::character varying))::character varying(255) AS user_id,
    (COALESCE(spi.name, 'Savings Target'::character varying))::character varying(255) AS title,
    (((((((((COALESCE(spi.type, 'SAVINGS'::character varying))::text || ' • P'::text) || ((COALESCE(spi.priority, 1))::character varying)::text) || ' • £'::text) || to_char(COALESCE(spi.monthlyamount, spi.monthly_amount, (0)::real), 'FM999999990.00'::text)) || '/mo (Target £'::text) || to_char(COALESCE(spi.targetamount, spi.target_amount, (0)::real), 'FM999999990.00'::text)) || ')'::text))::character varying(255) AS subtitle,
    (((('/savingsPlan?highlight=savings-item-'::text || ((spi.id)::character varying)::text) || '#savings-item-'::text) || ((spi.id)::character varying)::text))::character varying(255) AS target_url,
    (('savings-item-'::text || ((spi.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-bullseye'::character varying(50) AS icon,
    'Savings Item'::character varying(50) AS badge,
    lower((((((COALESCE(spi.name, ''::character varying))::text || ' '::text) || (COALESCE(spi.type, ''::character varying))::text) || ' savings debt payoff target priority '::text) || ((COALESCE(spi.monthlyamount, spi.monthly_amount, (0)::real))::character varying)::text)) AS search_text
   FROM (public.savings_plan_item spi
     JOIN public.savings_plan sp ON ((spi.savings_plan_id = sp.id)))
  WHERE (((spi.archived = false) OR (spi.archived IS NULL)) AND ((sp.archived = false) OR (sp.archived IS NULL)))
UNION ALL
 SELECT 'meal_plan'::character varying(50) AS entity_type,
    (mp.id)::character varying AS entity_id,
    (COALESCE(mp.user_id, ''::character varying))::character varying(255) AS user_id,
    'Weekly Meal Plan (NomMon)'::character varying(255) AS title,
    (((((('£'::text || to_char(COALESCE(mp."weeklyBudget", (0)::real), 'FM999999990.00'::text)) || ' budget • '::text) || ((COALESCE(mp.people, 1))::character varying)::text) || ' people'::text) ||
        CASE
            WHEN ((mp.postcode IS NOT NULL) AND ((mp.postcode)::text <> ''::text)) THEN (' • '::text || (mp.postcode)::text)
            ELSE ''::text
        END))::character varying(255) AS subtitle,
    '/nommon?highlight=nommon-content#nommon-content'::character varying(255) AS target_url,
    'nommon-content'::character varying(100) AS dom_id,
    'fa-utensils'::character varying(50) AS icon,
    'Meal Plan'::character varying(50) AS badge,
    lower(((('meal plan grocery weekly budget nommon recipe food shopping ingredients '::text || (COALESCE(mp.postcode, ''::character varying))::text) || ' '::text) || COALESCE(mp."planJson", ''::text))) AS search_text
   FROM public.locked_meal_plan mp
UNION ALL
 SELECT 'net_worth_asset'::character varying(50) AS entity_type,
    (nwa.id)::character varying AS entity_id,
    (COALESCE(nwa.user_id, ''::character varying))::character varying(255) AS user_id,
    (COALESCE(nwa.name, 'Asset'::character varying))::character varying(255) AS title,
    (((((COALESCE(nwa.category, 'OTHER'::character varying))::text || ' • £'::text) || to_char(COALESCE(nwa.asset_value, (0)::double precision), 'FM999999990.00'::text)) ||
        CASE
            WHEN ((nwa.notes IS NOT NULL) AND ((nwa.notes)::text <> ''::text)) THEN (' • '::text || (nwa.notes)::text)
            ELSE ''::text
        END))::character varying(255) AS subtitle,
    (((('/netWorth?highlight=asset-'::text || ((nwa.id)::character varying)::text) || '#asset-'::text) || ((nwa.id)::character varying)::text))::character varying(255) AS target_url,
    (('asset-'::text || ((nwa.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-gem'::character varying(50) AS icon,
    'Asset'::character varying(50) AS badge,
    lower((((((((COALESCE(nwa.name, ''::character varying))::text || ' '::text) || (COALESCE(nwa.category, ''::character varying))::text) || ' '::text) || (COALESCE(nwa.notes, ''::character varying))::text) || ' asset net worth '::text) || ((COALESCE(nwa.asset_value, (0)::double precision))::character varying)::text)) AS search_text
   FROM public.net_worth_asset nwa
  WHERE ((nwa.archived = false) OR (nwa.archived IS NULL))
UNION ALL
 SELECT 'plan'::character varying(50) AS entity_type,
    (pl.id)::character varying AS entity_id,
    (COALESCE(pl.user_id, ''::character varying))::character varying(255) AS user_id,
    (
        CASE
            WHEN ((pl.type)::text = 'BILL_SHARE'::text) THEN 'Bill Sharing Plan'::character varying
            WHEN ((pl.type)::text = 'RENT_SHARE'::text) THEN 'Rent Sharing Plan'::character varying
            ELSE COALESCE(pl.type, 'Sharing Plan'::character varying)
        END)::character varying(255) AS title,
    (((('Split: '::text || to_char((COALESCE(pl.split, (0)::real) * (100)::double precision), 'FM990.0'::text)) || '%'::text) ||
        CASE
            WHEN ((pl.notes IS NOT NULL) AND ((pl.notes)::text <> ''::text)) THEN (' • '::text || (pl.notes)::text)
            ELSE ''::text
        END))::character varying(255) AS subtitle,
    (((('/myPlans?highlight=plan-'::text || ((pl.id)::character varying)::text) || '#plan-'::text) || ((pl.id)::character varying)::text))::character varying(255) AS target_url,
    (('plan-'::text || ((pl.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-sliders-h'::character varying(50) AS icon,
    'Sharing Plan'::character varying(50) AS badge,
    lower(((((COALESCE(pl.type, ''::character varying))::text || ' '::text) || (COALESCE(pl.notes, ''::character varying))::text) || ' sharing plan split bills rent'::text)) AS search_text
   FROM public.plan pl
  WHERE ((pl.archived = false) OR (pl.archived IS NULL))
UNION ALL
 SELECT 'bank_transaction'::character varying(50) AS entity_type,
    (bt.id)::character varying AS entity_id,
    (COALESCE(bt.user_id, ''::character varying))::character varying(255) AS user_id,
    (COALESCE(bt.merchant_name, bt.description, 'Bank Transaction'::character varying))::character varying(255) AS title,
    (((((('£'::text || to_char(COALESCE(bt.amount, (0)::real), 'FM999999990.00'::text)) || ' • '::text) || (COALESCE(bt.date, ''::character varying))::text) || ' • '::text) || (COALESCE(bt.category, 'General'::character varying))::text))::character varying(255) AS subtitle,
    (((('/bank?highlight=tx-'::text || ((bt.id)::character varying)::text) || '#tx-'::text) || ((bt.id)::character varying)::text))::character varying(255) AS target_url,
    (('tx-'::text || ((bt.id)::character varying)::text))::character varying(100) AS dom_id,
    'fa-receipt'::character varying(50) AS icon,
    'Transaction'::character varying(50) AS badge,
    lower(((((((((COALESCE(bt.merchant_name, ''::character varying))::text || ' '::text) || (COALESCE(bt.description, ''::character varying))::text) || ' '::text) || (COALESCE(bt.category, ''::character varying))::text) || ' '::text) || ((COALESCE(bt.amount, (0)::real))::character varying)::text) || ' transaction bank'::text)) AS search_text
   FROM public.bank_transaction bt
UNION ALL
 SELECT 'pending_statement'::character varying(50) AS entity_type,
    (ps.id)::character varying AS entity_id,
    (COALESCE(ps.user_id, ''::character varying))::character varying(255) AS user_id,
    (((COALESCE(ps.bank_name, 'Bank'::character varying))::text || ' Statement'::text))::character varying(255) AS title,
    ((((((COALESCE(ps.status, 'PENDING'::character varying))::text || ' • '::text) || (COALESCE(ps.start_date, ''::character varying))::text) || ' to '::text) || (COALESCE(ps.end_date, ''::character varying))::text))::character varying(255) AS subtitle,
    '/bank?highlight=card-bank-statements#card-bank-statements'::character varying(255) AS target_url,
    'card-bank-statements'::character varying(100) AS dom_id,
    'fa-file-invoice'::character varying(50) AS icon,
    'Statement'::character varying(50) AS badge,
    lower((((COALESCE(ps.bank_name, ''::character varying))::text || ' statement pdf import '::text) || (COALESCE(ps.status, ''::character varying))::text)) AS search_text
   FROM public.pending_statement ps;


--
-- Name: app_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user (
    id character varying(255) NOT NULL,
    username character varying(128) NOT NULL,
    email character varying(255),
    password_hash character varying(255) NOT NULL,
    roles character varying(255) DEFAULT 'ROLE_ADMIN'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now()
);


--
-- Name: custom_spending_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.custom_spending_plan (
    id bigint NOT NULL,
    archived boolean NOT NULL,
    createdat bigint,
    lastupdated bigint,
    name character varying(255),
    sectionsjson text,
    userid character varying(255) NOT NULL,
    sections_json text,
    user_id character varying(255),
    created_at bigint,
    last_updated bigint,
    updated_at bigint,
    archived_at bigint
);


--
-- Name: hibernate_sequence; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.hibernate_sequence
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: monzo_connection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.monzo_connection (
    id bigint NOT NULL,
    accesstoken character varying(1000),
    clientid character varying(500),
    clientsecret character varying(1000),
    createdat bigint,
    expiresat bigint,
    lastreceivedat bigint,
    lastsyncedat bigint,
    monzouserid character varying(255),
    redirecturi character varying(500),
    refreshtoken character varying(1000),
    userid character varying(255) NOT NULL,
    webhookdebitid character varying(255),
    webhookflexid character varying(255),
    webhookurl character varying(500),
    webhook_debit_id character varying(255),
    webhook_flex_id character varying(255),
    webhook_url character varying(500),
    last_received_at bigint,
    created_at bigint,
    updated_at bigint
);


--
-- Name: pending_statement_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.pending_statement ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.pending_statement_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: pot_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pot_accounts (
    pot_id bigint NOT NULL,
    account_id bigint NOT NULL,
    created_at bigint DEFAULT ((date_part('epoch'::text, now()) * (1000)::double precision))::bigint,
    updated_at bigint DEFAULT ((date_part('epoch'::text, now()) * (1000)::double precision))::bigint
);


--
-- Name: user_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_access (
    id bigint NOT NULL,
    approvedat timestamp without time zone,
    approvedby character varying(255),
    createdat timestamp without time zone NOT NULL,
    email character varying(255),
    status character varying(255) NOT NULL,
    userid character varying(255) NOT NULL,
    user_id character varying(255),
    username character varying(255),
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now()
);


--
-- Name: account account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account
    ADD CONSTRAINT account_pkey PRIMARY KEY (id);


--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);


--
-- Name: app_user app_user_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_username_key UNIQUE (username);


--
-- Name: balance balance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.balance
    ADD CONSTRAINT balance_pkey PRIMARY KEY (id);


--
-- Name: bank_transaction bank_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_transaction
    ADD CONSTRAINT bank_transaction_pkey PRIMARY KEY (id);


--
-- Name: custom_spending_plan custom_spending_plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.custom_spending_plan
    ADD CONSTRAINT custom_spending_plan_pkey PRIMARY KEY (id);


--
-- Name: user_access idx_user_access_user_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_access
    ADD CONSTRAINT idx_user_access_user_id UNIQUE (userid);


--
-- Name: incoming incoming_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.incoming
    ADD CONSTRAINT incoming_pkey PRIMARY KEY (id);


--
-- Name: locked_meal_plan locked_meal_plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.locked_meal_plan
    ADD CONSTRAINT locked_meal_plan_pkey PRIMARY KEY (id);


--
-- Name: monzo_connection monzo_connection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monzo_connection
    ADD CONSTRAINT monzo_connection_pkey PRIMARY KEY (id);


--
-- Name: net_worth_asset net_worth_asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.net_worth_asset
    ADD CONSTRAINT net_worth_asset_pkey PRIMARY KEY (id);


--
-- Name: outgoing outgoing_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outgoing
    ADD CONSTRAINT outgoing_pkey PRIMARY KEY (id);


--
-- Name: pending_statement pending_statement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_statement
    ADD CONSTRAINT pending_statement_pkey PRIMARY KEY (id);


--
-- Name: plan plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan
    ADD CONSTRAINT plan_pkey PRIMARY KEY (id);


--
-- Name: pot pot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pot
    ADD CONSTRAINT pot_pkey PRIMARY KEY (id);


--
-- Name: savings_plan_item savings_plan_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.savings_plan_item
    ADD CONSTRAINT savings_plan_item_pkey PRIMARY KEY (id);


--
-- Name: savings_plan savings_plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.savings_plan
    ADD CONSTRAINT savings_plan_pkey PRIMARY KEY (id);


--
-- Name: user_access uk_hbuqnfgct8t1hv8vfy7ukpt4n; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_access
    ADD CONSTRAINT uk_hbuqnfgct8t1hv8vfy7ukpt4n UNIQUE (userid);


--
-- Name: monzo_connection uk_mgfc72q2ivj2mrfhihthr0u3j; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monzo_connection
    ADD CONSTRAINT uk_mgfc72q2ivj2mrfhihthr0u3j UNIQUE (userid);


--
-- Name: locked_meal_plan uk_mvf62gonsefwxpgjunee4ydgm; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.locked_meal_plan
    ADD CONSTRAINT uk_mvf62gonsefwxpgjunee4ydgm UNIQUE (userid);


--
-- Name: bank_transaction uk_r6kn8nws9du0ilu8yuuokaelc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_transaction
    ADD CONSTRAINT uk_r6kn8nws9du0ilu8yuuokaelc UNIQUE (externalid);


--
-- Name: monzo_connection ukmgfc72q2ivj2mrfhihthr0u3j; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.monzo_connection
    ADD CONSTRAINT ukmgfc72q2ivj2mrfhihthr0u3j UNIQUE (userid);


--
-- Name: locked_meal_plan ukmvf62gonsefwxpgjunee4ydgm; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.locked_meal_plan
    ADD CONSTRAINT ukmvf62gonsefwxpgjunee4ydgm UNIQUE (userid);


--
-- Name: bank_transaction ukr6kn8nws9du0ilu8yuuokaelc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_transaction
    ADD CONSTRAINT ukr6kn8nws9du0ilu8yuuokaelc UNIQUE (externalid);


--
-- Name: user_access user_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_access
    ADD CONSTRAINT user_access_pkey PRIMARY KEY (id);


--
-- Name: idx2tqywh5cwpooef7gqv7an42jj; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx2tqywh5cwpooef7gqv7an42jj ON public.account USING btree (userid);


--
-- Name: idx7cgovtibvfia4mntt7sg0vgr2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx7cgovtibvfia4mntt7sg0vgr2 ON public.custom_spending_plan USING btree (userid);


--
-- Name: idx_account_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_account_name ON public.account USING btree (name);


--
-- Name: idx_account_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_account_user_id ON public.account USING btree (user_id);


--
-- Name: idx_app_user_username; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_user_username ON public.app_user USING btree (username);


--
-- Name: idx_balance_acc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_balance_acc ON public.balance USING btree (account_id);


--
-- Name: idx_balance_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_balance_user_id ON public.balance USING btree (user_id);


--
-- Name: idx_bt_acc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bt_acc ON public.bank_transaction USING btree ("accountId");


--
-- Name: idx_bt_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bt_account ON public.bank_transaction USING btree (account_id);


--
-- Name: idx_bt_search_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bt_search_merchant ON public.bank_transaction USING btree (merchant_name);


--
-- Name: idx_bt_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bt_time ON public.bank_transaction USING btree ("timestamp");


--
-- Name: idx_bt_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bt_uid ON public.bank_transaction USING btree ("userId");


--
-- Name: idx_bt_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bt_user ON public.bank_transaction USING btree (user_id);


--
-- Name: idx_bt_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bt_user_id ON public.bank_transaction USING btree (user_id);


--
-- Name: idx_incoming_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_incoming_name ON public.incoming USING btree (name);


--
-- Name: idx_incoming_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_incoming_user_id ON public.incoming USING btree (user_id);


--
-- Name: idx_locked_meal_plan_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_locked_meal_plan_user ON public.locked_meal_plan USING btree (userid);


--
-- Name: idx_mc_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mc_user ON public.monzo_connection USING btree (userid);


--
-- Name: idx_mealplan_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mealplan_user_id ON public.locked_meal_plan USING btree (user_id);


--
-- Name: idx_nw_asset_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_nw_asset_name ON public.net_worth_asset USING btree (name);


--
-- Name: idx_nw_asset_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_nw_asset_user_id ON public.net_worth_asset USING btree (user_id);


--
-- Name: idx_outgoing_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outgoing_name ON public.outgoing USING btree (name);


--
-- Name: idx_outgoing_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outgoing_user_id ON public.outgoing USING btree (user_id);


--
-- Name: idx_plan_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_user_id ON public.plan USING btree (user_id);


--
-- Name: idx_pot_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pot_user_id ON public.pot USING btree (user_id);


--
-- Name: idx_ps_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ps_job ON public.pending_statement USING btree (job_id);


--
-- Name: idx_ps_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ps_status ON public.pending_statement USING btree (status);


--
-- Name: idx_ps_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ps_user ON public.pending_statement USING btree (user_id);


--
-- Name: idx_ps_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ps_user_id ON public.pending_statement USING btree (user_id);


--
-- Name: idx_sp_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sp_user_id ON public.savings_plan USING btree (user_id);


--
-- Name: idx_spi_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_spi_name ON public.savings_plan_item USING btree (name);


--
-- Name: idx_spi_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_spi_plan ON public.savings_plan_item USING btree (savings_plan_id);


--
-- Name: idxa1qxa7y1gff4gbs0guiiuytsf; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxa1qxa7y1gff4gbs0guiiuytsf ON public.balance USING btree (userid);


--
-- Name: idxbdvq0ts98nlksbwj1pyf3io1u; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxbdvq0ts98nlksbwj1pyf3io1u ON public.plan USING btree (userid);


--
-- Name: idxerwhhnxog3aq5v479tc0no1rj; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxerwhhnxog3aq5v479tc0no1rj ON public.pot USING btree (userid);


--
-- Name: idxhadl8i9fsvv1u9l4rnw2sgc70; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhadl8i9fsvv1u9l4rnw2sgc70 ON public.outgoing USING btree (userid);


--
-- Name: idxhcx5jv9cbriwafx40d6an2elx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhcx5jv9cbriwafx40d6an2elx ON public.bank_transaction USING btree (userid);


--
-- Name: idxhnss1x0fct7f00ddmixvyg07e; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhnss1x0fct7f00ddmixvyg07e ON public.net_worth_asset USING btree (userid);


--
-- Name: idxihtc5pkmv7oj506qdyuh6kif6; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxihtc5pkmv7oj506qdyuh6kif6 ON public.savings_plan USING btree (userid);


--
-- Name: idxjk6yqrussyphwo5b06mp3618o; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxjk6yqrussyphwo5b06mp3618o ON public.incoming USING btree (userid);


--
-- Name: idxq0mhe35p8xx9bd8pup6a7wks5; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxq0mhe35p8xx9bd8pup6a7wks5 ON public.bank_transaction USING btree (accountid);


--
-- Name: idxrolgvgehmsujuj0abx1afg124; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxrolgvgehmsujuj0abx1afg124 ON public.bank_transaction USING btree ("timestamp");


--
-- Performance & time-series indexes
--

CREATE INDEX IF NOT EXISTS idx_balance_account_ts_asc ON public.balance USING btree (account_id, "timestamp" ASC, id ASC) INCLUDE (value);
CREATE INDEX IF NOT EXISTS idx_balance_account_ts_sec_desc ON public.balance USING btree (account_id, (CASE WHEN "timestamp" > 100000000000 THEN "timestamp" / 1000 ELSE "timestamp" END) DESC, id DESC) INCLUDE (value, "timestamp");
CREATE INDEX IF NOT EXISTS idx_balance_userid_ts_desc ON public.balance USING btree (userid, "timestamp" DESC, id DESC) INCLUDE (account_id, value);
CREATE INDEX IF NOT EXISTS idx_balance_userid_ts_sec_asc ON public.balance USING btree (userid, (CASE WHEN "timestamp" > 100000000000 THEN "timestamp" / 1000 ELSE "timestamp" END) ASC, id ASC) INCLUDE (account_id, value, "timestamp");
CREATE INDEX IF NOT EXISTS idx_balance_coalesce_user_ts_desc ON public.balance USING btree ((COALESCE(user_id, userid, "userId")), "timestamp" DESC, id DESC) INCLUDE (account_id, value);
CREATE INDEX IF NOT EXISTS idx_balance_coalesce_user_account_ts ON public.balance USING btree ((COALESCE(user_id, userid, "userId")), account_id, "timestamp" ASC, id ASC) INCLUDE (value);
CREATE INDEX IF NOT EXISTS idx_account_userid_archived_id ON public.account USING btree (userid, archived, id);
CREATE INDEX IF NOT EXISTS idx_account_coalesce_user_archived_id ON public.account USING btree ((COALESCE(user_id, userid, "userId")), archived, id);
CREATE INDEX IF NOT EXISTS idx_outgoing_account_id ON public.outgoing USING btree (account_id);
CREATE INDEX IF NOT EXISTS idx_outgoing_userid_active ON public.outgoing USING btree (userid, archived, paused, outgoingmonth, outgoingday);
CREATE INDEX IF NOT EXISTS idx_outgoing_coalesce_user_active ON public.outgoing USING btree ((COALESCE(user_id, userid, "userId")), archived, paused);
CREATE INDEX IF NOT EXISTS idx_incoming_account_id ON public.incoming USING btree (account_id);
CREATE INDEX IF NOT EXISTS idx_incoming_userid_active ON public.incoming USING btree (userid, archived, payday, id);
CREATE INDEX IF NOT EXISTS idx_incoming_coalesce_user_active ON public.incoming USING btree ((COALESCE(user_id, userid, "userId")), archived, incomingmonthday, id);
CREATE INDEX IF NOT EXISTS idx_pot_userid_type_archived ON public.pot USING btree (userid, type, archived, createdat DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_pot_accounts_pot_id ON public.pot_accounts USING btree (pot_id, account_id);
CREATE INDEX IF NOT EXISTS idx_pot_accounts_account_id ON public.pot_accounts USING btree (account_id);
CREATE INDEX IF NOT EXISTS idx_nw_asset_userid_archived ON public.net_worth_asset USING btree (userid, archived, asset_value DESC, id ASC);
CREATE INDEX IF NOT EXISTS idx_savings_plan_userid_archived ON public.savings_plan USING btree (userid, archived, id DESC);
CREATE INDEX IF NOT EXISTS idx_spi_plan_archived_priority ON public.savings_plan_item USING btree (savings_plan_id, archived, priority ASC, id ASC);
CREATE INDEX IF NOT EXISTS idx_spi_account_id ON public.savings_plan_item USING btree (account_id);
CREATE INDEX IF NOT EXISTS idx_user_access_lower_username ON public.user_access USING btree (lower(username));
CREATE INDEX IF NOT EXISTS idx_user_access_lower_email ON public.user_access USING btree (lower(email));
CREATE INDEX IF NOT EXISTS idx_user_access_user_id_col ON public.user_access USING btree (user_id);
CREATE INDEX IF NOT EXISTS idx_bt_userid_ts_desc ON public.bank_transaction USING btree (userid, "timestamp" DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_bt_account_ts_desc ON public.bank_transaction USING btree (account_id, "timestamp" DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_ps_userid_status_created ON public.pending_statement USING btree (userid, status, created_at DESC);


--
-- Name: account trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.account FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: balance trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.balance FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: bank_transaction trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.bank_transaction FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: incoming trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.incoming FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: locked_meal_plan trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.locked_meal_plan FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: net_worth_asset trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.net_worth_asset FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: outgoing trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.outgoing FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: pending_statement trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.pending_statement FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: plan trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.plan FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: pot trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.pot FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: savings_plan trg_sync_user_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON public.savings_plan FOR EACH ROW EXECUTE PROCEDURE public.sync_user_id_columns();


--
-- Name: incoming fk2j2s2rclhyesagicl8732coq6; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.incoming
    ADD CONSTRAINT fk2j2s2rclhyesagicl8732coq6 FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: savings_plan_item fk36ntcyji78cl47m7563pcm9tn; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.savings_plan_item
    ADD CONSTRAINT fk36ntcyji78cl47m7563pcm9tn FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: pot fk3qjhcls4sppp7blvkxkwa9e6b; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pot
    ADD CONSTRAINT fk3qjhcls4sppp7blvkxkwa9e6b FOREIGN KEY (food_account_id) REFERENCES public.account(id);


--
-- Name: pot fk6cuxyw8msw91o08p21aypw7ru; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pot
    ADD CONSTRAINT fk6cuxyw8msw91o08p21aypw7ru FOREIGN KEY (fun_account_id) REFERENCES public.account(id);


--
-- Name: pot fk9upk2ylbs4xdgc1tuf51d6wpc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pot
    ADD CONSTRAINT fk9upk2ylbs4xdgc1tuf51d6wpc FOREIGN KEY (transport_account_id) REFERENCES public.account(id);


--
-- Name: incoming fk_incoming_account; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.incoming
    ADD CONSTRAINT fk_incoming_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE SET NULL;


--
-- Name: savings_plan_item fkd8tkrk1ck2aajgxkrqfrdujri; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.savings_plan_item
    ADD CONSTRAINT fkd8tkrk1ck2aajgxkrqfrdujri FOREIGN KEY (savings_plan_id) REFERENCES public.savings_plan(id);


--
-- Name: pot fkhpfh9icllg2fmkmccstrj9ie4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pot
    ADD CONSTRAINT fkhpfh9icllg2fmkmccstrj9ie4 FOREIGN KEY (fuel_account_id) REFERENCES public.account(id);


--
-- Name: balance fkk589ww9tv55u5jri4g092ew5u; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.balance
    ADD CONSTRAINT fkk589ww9tv55u5jri4g092ew5u FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: outgoing fkmuwlanwwojo5oc0rq332vrijw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outgoing
    ADD CONSTRAINT fkmuwlanwwojo5oc0rq332vrijw FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: pot_accounts fkn4dx0jakqce6k5f78eaamya1h; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pot_accounts
    ADD CONSTRAINT fkn4dx0jakqce6k5f78eaamya1h FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: pot_accounts fksaui0o2s67pr68og3eb8ttsnx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pot_accounts
    ADD CONSTRAINT fksaui0o2s67pr68og3eb8ttsnx FOREIGN KEY (pot_id) REFERENCES public.pot(id);


--
-- PostgreSQL database dump complete
--

