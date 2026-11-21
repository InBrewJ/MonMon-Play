use chrono::{Datelike, NaiveDate};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::sync::LazyLock;

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct BankHolidayDivision {
    pub division: String,
    pub events: Vec<BankHolidayEvent>,
}

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct BankHolidayEvent {
    pub title: String,
    pub date: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub bunting: bool,
}

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct UkBankHolidaysData {
    #[serde(rename = "england-and-wales")]
    pub england_and_wales: BankHolidayDivision,
    pub scotland: BankHolidayDivision,
    #[serde(rename = "northern-ireland")]
    pub northern_ireland: BankHolidayDivision,
}

pub static UK_BANK_HOLIDAYS_RAW: LazyLock<UkBankHolidaysData> = LazyLock::new(|| {
    let json_str = include_str!("bank-holidays-uk.json");
    serde_json::from_str::<UkBankHolidaysData>(json_str)
        .expect("bank-holidays-uk.json should be valid UkBankHolidaysData")
});

pub static UK_BANK_HOLIDAYS: LazyLock<HashSet<NaiveDate>> = LazyLock::new(|| {
    let mut set = HashSet::new();
    for ev in &UK_BANK_HOLIDAYS_RAW.england_and_wales.events {
        if let Ok(d) = NaiveDate::parse_from_str(&ev.date, "%Y-%m-%d") {
            set.insert(d);
        }
    }
    // Hardcoded safety net matching Java BankHolidayHelper
    if let Some(d) = NaiveDate::from_ymd_opt(2026, 8, 31) { set.insert(d); }
    if let Some(d) = NaiveDate::from_ymd_opt(2026, 12, 25) { set.insert(d); }
    if let Some(d) = NaiveDate::from_ymd_opt(2026, 12, 28) { set.insert(d); }
    set
});

pub static SCOTLAND_BANK_HOLIDAYS: LazyLock<HashSet<NaiveDate>> = LazyLock::new(|| {
    let mut set = HashSet::new();
    for ev in &UK_BANK_HOLIDAYS_RAW.scotland.events {
        if let Ok(d) = NaiveDate::parse_from_str(&ev.date, "%Y-%m-%d") {
            set.insert(d);
        }
    }
    set
});

pub static NI_BANK_HOLIDAYS: LazyLock<HashSet<NaiveDate>> = LazyLock::new(|| {
    let mut set = HashSet::new();
    for ev in &UK_BANK_HOLIDAYS_RAW.northern_ireland.events {
        if let Ok(d) = NaiveDate::parse_from_str(&ev.date, "%Y-%m-%d") {
            set.insert(d);
        }
    }
    set
});

pub fn is_bank_holiday(date: NaiveDate) -> bool {
    is_bank_holiday_in_region(date, "UK")
}

pub fn is_bank_holiday_in_region(date: NaiveDate, region: &str) -> bool {
    match region.to_uppercase().as_str() {
        "SCOTLAND" => SCOTLAND_BANK_HOLIDAYS.contains(&date),
        "NI" | "NORTHERN IRELAND" => NI_BANK_HOLIDAYS.contains(&date),
        _ => UK_BANK_HOLIDAYS.contains(&date),
    }
}

pub fn get_bank_holidays_for_region(region: &str) -> Vec<BankHolidayEvent> {
    match region.to_uppercase().as_str() {
        "SCOTLAND" => UK_BANK_HOLIDAYS_RAW.scotland.events.clone(),
        "NI" | "NORTHERN IRELAND" => UK_BANK_HOLIDAYS_RAW.northern_ireland.events.clone(),
        _ => UK_BANK_HOLIDAYS_RAW.england_and_wales.events.clone(),
    }
}

pub fn get_all_bank_holidays() -> &'static UkBankHolidaysData {
    &UK_BANK_HOLIDAYS_RAW
}

pub fn is_weekend(date: NaiveDate) -> bool {
    let wd = date.weekday();
    wd == chrono::Weekday::Sat || wd == chrono::Weekday::Sun
}

pub fn is_working_day(date: NaiveDate) -> bool {
    !is_weekend(date) && !is_bank_holiday(date)
}

pub fn days_in_month(year: i32, month: u32) -> u32 {
    match month {
        4 | 6 | 9 | 11 => 30,
        2 => {
            if (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0) {
                29
            } else {
                28
            }
        }
        _ => 31,
    }
}

pub fn get_last_working_day_on_or_before(mut date: NaiveDate) -> NaiveDate {
    while !is_working_day(date) {
        match date.pred_opt() {
            Some(prev) => date = prev,
            None => break,
        }
    }
    date
}

pub fn get_first_working_day_on_or_after(mut date: NaiveDate) -> NaiveDate {
    while !is_working_day(date) {
        match date.succ_opt() {
            Some(next) => date = next,
            None => break,
        }
    }
    date
}

/// Rule 1: Payday Anchoring
/// Clamps nominal payday to month length and shifts back to the last preceding working day
/// taking UK bank holidays and weekends into account.
pub fn get_actual_payday_date(nominal_payday: i32, year: i32, month: u32) -> NaiveDate {
    let dim = days_in_month(year, month);
    let target_day = (nominal_payday.max(1) as u32).clamp(1, dim);
    let pay_date = NaiveDate::from_ymd_opt(year, month, target_day)
        .unwrap_or_else(|| NaiveDate::from_ymd_opt(year, month, 28).unwrap());
    get_last_working_day_on_or_before(pay_date)
}

fn add_months_ym(year: i32, month: u32, delta: i32) -> (i32, u32) {
    let total = (year * 12 + (month as i32 - 1)) + delta;
    let new_year = total.div_euclid(12);
    let new_month = (total.rem_euclid(12) + 1) as u32;
    (new_year, new_month)
}

pub fn find_last_payday_date(as_of: NaiveDate, nominal_payday: i32) -> NaiveDate {
    // A nominal payday on the 1st/2nd can shift backward across a month boundary into the
    // previous calendar month (e.g. Sun 1 Nov 2026 -> Fri 30 Oct 2026). Check prev, current,
    // and next nominal months and pick the latest actual payday <= as_of.
    let mut best: Option<NaiveDate> = None;
    for delta in -1..=1 {
        let (y, m) = add_months_ym(as_of.year(), as_of.month(), delta);
        let candidate = get_actual_payday_date(nominal_payday, y, m);
        if candidate <= as_of {
            best = Some(match best {
                Some(b) => b.max(candidate),
                None => candidate,
            });
        }
    }
    best.unwrap_or_else(|| {
        let (y, m) = add_months_ym(as_of.year(), as_of.month(), -1);
        get_actual_payday_date(nominal_payday, y, m)
    })
}

pub fn find_next_payday_date(as_of: NaiveDate, nominal_payday: i32) -> NaiveDate {
    // Check current, next, and +2 nominal months and pick the earliest actual payday strictly > as_of,
    // so a shifted payday landing on `as_of` itself (e.g. Fri 30 Oct 2026 for Sun 1 Nov 2026)
    // advances cleanly to Dec 2026 instead of returning `as_of`.
    let mut best: Option<NaiveDate> = None;
    for delta in 0..=2 {
        let (y, m) = add_months_ym(as_of.year(), as_of.month(), delta);
        let candidate = get_actual_payday_date(nominal_payday, y, m);
        if candidate > as_of {
            best = Some(match best {
                Some(b) => b.min(candidate),
                None => candidate,
            });
        }
    }
    best.unwrap_or_else(|| {
        let (y, m) = add_months_ym(as_of.year(), as_of.month(), 1);
        get_actual_payday_date(nominal_payday, y, m)
    })
}

/// Rules 2 & 3: Outgoings Anchoring
/// - Rule 2: If the day falls on a weekend or a bank holiday, the actual outgoing date is the next working day.
/// - Rule 3: If the day is set to 31 but there are only 30, 28 or 29 days in that month,
///   it is anchored back to the last working day of that month.
///   An outgoing should never be rolled forward into the next month.
pub fn get_actual_outgoing_date(outgoing_day: i32, year: i32, month: u32) -> NaiveDate {
    let dim = days_in_month(year, month);
    let day_u32 = outgoing_day.max(1) as u32;
    let is_overflow = day_u32 > dim;
    let clamped_day = day_u32.clamp(1, dim);
    let clamped_date = NaiveDate::from_ymd_opt(year, month, clamped_day)
        .unwrap_or_else(|| NaiveDate::from_ymd_opt(year, month, 28).unwrap());

    if is_overflow {
        // Rule 3: Day is beyond month end (e.g. 31 in 30/28-day month).
        // Anchor back to last working day in this month.
        get_last_working_day_on_or_before(clamped_date)
    } else if is_working_day(clamped_date) {
        clamped_date
    } else {
        // Rule 2: Nominal day is on weekend or bank holiday.
        // Try rolling forward to next working day.
        let next_work = get_first_working_day_on_or_after(clamped_date);
        if next_work.month() == month {
            next_work
        } else {
            // Cannot roll into next month: anchor back to last working day in current month!
            get_last_working_day_on_or_before(clamped_date)
        }
    }
}

pub fn get_effective_outgoing_day(outgoing_day: i32, as_of: NaiveDate) -> u32 {
    get_actual_outgoing_date(outgoing_day, as_of.year(), as_of.month()).day()
}

pub fn is_outgoing_adjusted(outgoing_day: i32, as_of: NaiveDate) -> bool {
    get_effective_outgoing_day(outgoing_day, as_of) as i32 != outgoing_day
}

pub fn get_effective_incoming_day(incoming_day: i32, is_payday: bool, as_of: NaiveDate) -> u32 {
    if is_payday {
        get_actual_payday_date(incoming_day, as_of.year(), as_of.month()).day()
    } else {
        let dim = days_in_month(as_of.year(), as_of.month());
        (incoming_day.max(1) as u32).clamp(1, dim)
    }
}

pub fn is_incoming_adjusted(incoming_day: i32, is_payday: bool, as_of: NaiveDate) -> bool {
    get_effective_incoming_day(incoming_day, is_payday, as_of) as i32 != incoming_day
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_bank_holidays_loaded() {
        // Verify known 2026 UK bank holidays
        assert!(is_bank_holiday(NaiveDate::from_ymd_opt(2026, 1, 1).unwrap())); // New Year's Day
        assert!(is_bank_holiday(NaiveDate::from_ymd_opt(2026, 4, 3).unwrap())); // Good Friday
        assert!(is_bank_holiday(NaiveDate::from_ymd_opt(2026, 4, 6).unwrap())); // Easter Monday
        assert!(is_bank_holiday(NaiveDate::from_ymd_opt(2026, 5, 4).unwrap())); // Early May
        assert!(is_bank_holiday(NaiveDate::from_ymd_opt(2026, 5, 25).unwrap())); // Spring
        assert!(is_bank_holiday(NaiveDate::from_ymd_opt(2026, 8, 31).unwrap())); // Summer Bank Holiday
        assert!(is_bank_holiday(NaiveDate::from_ymd_opt(2026, 12, 25).unwrap())); // Christmas
        assert!(is_bank_holiday(NaiveDate::from_ymd_opt(2026, 12, 28).unwrap())); // Boxing Day sub

        // Test Scotland distinct holidays
        let jan2 = NaiveDate::from_ymd_opt(2026, 1, 2).unwrap();
        assert!(is_bank_holiday_in_region(jan2, "SCOTLAND"));
        assert!(!is_bank_holiday_in_region(jan2, "UK"));

        // Test Northern Ireland distinct holidays
        let st_patricks = NaiveDate::from_ymd_opt(2026, 3, 17).unwrap();
        assert!(is_bank_holiday_in_region(st_patricks, "NI"));
        assert!(!is_bank_holiday_in_region(st_patricks, "UK"));
    }

    #[test]
    fn test_payday_anchoring_rule_1() {
        // August 2026: 31st is Monday, but 31st August is Summer Bank Holiday!
        // 30th is Sunday, 29th is Saturday.
        // Therefore, actual payday on 31st August 2026 is Friday 28th August!
        let payday_aug_2026 = get_actual_payday_date(31, 2026, 8);
        assert_eq!(payday_aug_2026, NaiveDate::from_ymd_opt(2026, 8, 28).unwrap());

        // February 2026 (non-leap year, 28 days): 28th Feb 2026 is Saturday.
        // Last working day on or before 28th is Friday 27th Feb!
        let payday_feb_2026 = get_actual_payday_date(31, 2026, 2);
        assert_eq!(payday_feb_2026, NaiveDate::from_ymd_opt(2026, 2, 27).unwrap());

        // April 2026 (30 days): 30th April 2026 is Thursday (working day).
        let payday_apr_2026 = get_actual_payday_date(31, 2026, 4);
        assert_eq!(payday_apr_2026, NaiveDate::from_ymd_opt(2026, 4, 30).unwrap());

        // May 2026: 31st is Sunday, 30th is Saturday, 29th is Friday (working day).
        let payday_may_2026 = get_actual_payday_date(31, 2026, 5);
        assert_eq!(payday_may_2026, NaiveDate::from_ymd_opt(2026, 5, 29).unwrap());
    }

    #[test]
    fn test_outgoing_anchoring_rule_2() {
        // Outgoing on 4th April 2026 (Saturday).
        // 5th is Sunday, 6th is Easter Monday (Bank Holiday).
        // Next working day is Tuesday 7th April 2026!
        let out_apr_4 = get_actual_outgoing_date(4, 2026, 4);
        assert_eq!(out_apr_4, NaiveDate::from_ymd_opt(2026, 4, 7).unwrap());

        // Outgoing on Friday 3rd April 2026 (Good Friday - Bank Holiday).
        // Saturday 4, Sunday 5, Monday 6 (Easter Monday - Bank Holiday).
        // Next working day is Tuesday 7th April 2026!
        let out_apr_3 = get_actual_outgoing_date(3, 2026, 4);
        assert_eq!(out_apr_3, NaiveDate::from_ymd_opt(2026, 4, 7).unwrap());
    }

    #[test]
    fn test_outgoing_anchoring_rule_3() {
        // Rule 3: Day is set to 31 in a 30-day month (April 2026).
        // April 30 is Thursday (working day) -> 30th April!
        let out_apr_31 = get_actual_outgoing_date(31, 2026, 4);
        assert_eq!(out_apr_31, NaiveDate::from_ymd_opt(2026, 4, 30).unwrap());

        // Rule 3: Day is set to 31 in February 2026 (28 days).
        // Feb 28 is Saturday, Feb 27 is Friday (working day).
        // It must NOT roll into March 2nd -> anchors back to Friday 27th Feb!
        let out_feb_31 = get_actual_outgoing_date(31, 2026, 2);
        assert_eq!(out_feb_31, NaiveDate::from_ymd_opt(2026, 2, 27).unwrap());

        // Rule 3: Day is set to 30 in May 2026 (31 days).
        // May 30 is Saturday, May 31 is Sunday.
        // Rolling forward would go to June 1st (next month).
        // Rule 3 forbids rolling forward into next month -> anchors back to Friday May 29th!
        let out_may_30 = get_actual_outgoing_date(30, 2026, 5);
        assert_eq!(out_may_30, NaiveDate::from_ymd_opt(2026, 5, 29).unwrap());
    }

    #[test]
    fn test_payday_1st_shifted_across_month_boundary_never_produces_zero_day_cycle() {
        // Sunday 1 Nov 2026 shifts back to Friday 30 Oct 2026
        let oct_30 = NaiveDate::from_ymd_opt(2026, 10, 30).unwrap();
        let oct_29 = NaiveDate::from_ymd_opt(2026, 10, 29).unwrap();
        let nov_21 = NaiveDate::from_ymd_opt(2026, 11, 21).unwrap();
        let oct_01 = NaiveDate::from_ymd_opt(2026, 10, 1).unwrap();
        let dec_01 = NaiveDate::from_ymd_opt(2026, 12, 1).unwrap();

        assert_eq!(find_last_payday_date(nov_21, 1), oct_30);
        assert_eq!(find_next_payday_date(nov_21, 1), dec_01);

        // Starting from the shifted payday itself (30 Oct) must advance to 1 Dec (not return 30 Oct!)
        assert_eq!(find_last_payday_date(oct_30, 1), oct_30);
        assert_eq!(find_next_payday_date(oct_30, 1), dec_01);

        // One day before shifted payday (29 Oct) belongs to 1 Oct -> 30 Oct cycle
        assert_eq!(find_last_payday_date(oct_29, 1), oct_01);
        assert_eq!(find_next_payday_date(oct_29, 1), oct_30);
    }
}
