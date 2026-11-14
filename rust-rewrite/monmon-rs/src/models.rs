use chrono::{Datelike, Duration, NaiveDate};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    pub id: i64,
    pub name: String,
    pub nickname: Option<String>,
    #[serde(rename = "type")]
    pub account_type: String,
    pub user_id: String,
    pub account_limit: Option<f32>,
    pub interest_rate: Option<f32>,
    pub ledger_mode: Option<String>,
    pub archived: Option<bool>,
}

impl Account {
    pub fn is_selected(&self, sel: &Option<i64>) -> bool {
        *sel == Some(self.id)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewAccount {
    pub name: String,
    pub nickname: Option<String>,
    #[serde(rename = "type")]
    pub account_type: String,
    pub user_id: String,
    pub account_limit: Option<f32>,
    pub interest_rate: Option<f32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateAccount {
    pub name: String,
    pub nickname: Option<String>,
    #[serde(rename = "type")]
    pub account_type: String,
    pub account_limit: Option<f32>,
    pub interest_rate: Option<f32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub struct Incoming {
    pub id: i64,
    pub name: String,
    pub net_value: f32,
    pub gross_value: Option<f32>,
    pub incoming_month_day: i32,
    pub payday: Option<bool>,
    pub incoming_type: Option<String>,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
    pub archived: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewIncoming {
    pub name: String,
    pub net_value: f32,
    pub gross_value: Option<f32>,
    pub incoming_month_day: i32,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub payday: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateIncoming {
    pub name: String,
    pub net_value: f32,
    pub gross_value: Option<f32>,
    pub incoming_month_day: i32,
    pub user_id: Option<String>,
    pub account_id: Option<i64>,
    pub payday: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub struct Outgoing {
    pub id: i64,
    pub name: String,
    pub cost: f32,
    pub outgoing_day: i32,
    pub outgoing_month: Option<i32>,
    pub year_interval: Option<i32>,
    pub starting_year: Option<i32>,
    pub bill: Option<bool>,
    pub rent: Option<bool>,
    pub hidden_from_total: Option<bool>,
    pub paused: Option<bool>,
    pub pause_reason: Option<String>,
    pub paused_at: Option<i64>,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
    pub account_type: Option<String>,
    pub archived: Option<bool>,
}

impl Outgoing {
    pub fn is_yearly(&self) -> bool {
        self.outgoing_month.map_or(false, |m| m > 0)
    }

    pub fn get_year_interval(&self) -> i32 {
        self.year_interval.unwrap_or(1).max(1)
    }

    pub fn is_due_in_month(&self, as_of: chrono::NaiveDate) -> bool {
        if !self.is_yearly() {
            return true;
        }
        let target_month = match self.outgoing_month {
            Some(m) if m > 0 => m,
            _ => return true,
        };
        if target_month != as_of.month() as i32 {
            return false;
        }
        let interval = self.get_year_interval();
        if interval > 1 {
            let start = self.starting_year.unwrap_or(as_of.year());
            let current_year = as_of.year();
            if current_year < start {
                return false;
            }
            return (current_year - start) % interval == 0;
        }
        true
    }

    pub fn get_next_due_year(&self, as_of: chrono::NaiveDate) -> i32 {
        let interval = self.get_year_interval();
        let target_month = self.outgoing_month.unwrap_or(1);
        if interval <= 1 {
            if as_of.month() as i32 > target_month {
                return as_of.year() + 1;
            }
            return as_of.year();
        }
        let start = self.starting_year.unwrap_or(as_of.year());
        let mut y = start;
        while y < as_of.year() || (y == as_of.year() && as_of.month() as i32 > target_month) {
            y += interval;
        }
        y
    }

    pub fn get_month_name(&self) -> String {
        if !self.is_yearly() {
            return "Monthly".to_string();
        }
        match self.outgoing_month.unwrap_or(0) {
            1 => "January".to_string(),
            2 => "February".to_string(),
            3 => "March".to_string(),
            4 => "April".to_string(),
            5 => "May".to_string(),
            6 => "June".to_string(),
            7 => "July".to_string(),
            8 => "August".to_string(),
            9 => "September".to_string(),
            10 => "October".to_string(),
            11 => "November".to_string(),
            12 => "December".to_string(),
            m => format!("Month {}", m),
        }
    }

    pub fn get_frequency_label(&self) -> String {
        if !self.is_yearly() {
            return "Monthly".to_string();
        }
        let month = self.get_month_name();
        let interval = self.get_year_interval();
        if interval > 1 {
            let start_str = match self.starting_year {
                Some(y) if y > 0 => format!(" started {}", y),
                _ => String::new(),
            };
            format!("Every {} yrs ({}{})", interval, month, start_str)
        } else {
            format!("Yearly ({})", month)
        }
    }
}

pub fn total_effective_outgoings(outgoings: &[Outgoing], as_of: chrono::NaiveDate) -> f32 {
    outgoings
        .iter()
        .filter(|o| !o.paused.unwrap_or(false) && !o.archived.unwrap_or(false) && !o.hidden_from_total.unwrap_or(false) && o.is_due_in_month(as_of))
        .map(|o| o.cost)
        .sum()
}

/// Counts how many paydays occur strictly between `today` and `due_date`.
/// If today is on or after `due_date`, returns 0.
/// If today itself is an actual payday, that payday is counted since it is an immediate opportunity
/// to top up the reserve before the bill is due.
pub fn count_paydays_between(today: NaiveDate, due_date: NaiveDate, nominal_payday: i32) -> u32 {
    if today >= due_date {
        return 0;
    }
    let mut count = 0;
    let nominal = nominal_payday.max(1);

    // If today is an actual payday, count it
    let today_payday = crate::anchoring::get_actual_payday_date(nominal, today.year(), today.month());
    if today == today_payday && today < due_date {
        count += 1;
    }

    let mut probe_date = if today == today_payday {
        today + Duration::days(1)
    } else {
        today
    };

    loop {
        let next_pay = crate::anchoring::find_next_payday_date(probe_date, nominal);
        if next_pay < due_date {
            count += 1;
            probe_date = next_pay + Duration::days(1);
        } else {
            break;
        }
        if count > 120 {
            break;
        }
    }
    count
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct YearlyBillItemBreakdown {
    pub name: String,
    pub total_cost: f32,
    pub covered_amount: f32,
    pub remaining_deficit: f32,
    pub paydays_remaining: u32,
    pub monthly_saving: f32,
    pub due_date: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct YearlyBillsReserveCalculation {
    pub monthly_saving: f32,
    pub upcoming_count: usize,
    pub total_upcoming_cost: f32,
    pub remaining_deficit: f32,
    pub target_account_balance: f32,
    pub breakdown: Vec<YearlyBillItemBreakdown>,
}

impl YearlyBillsReserveCalculation {
    pub fn format_tooltip(&self) -> String {
        if self.breakdown.is_empty() {
            return "No upcoming yearly bills requiring savings.".to_string();
        }
        let mut lines = Vec::new();
        for item in &self.breakdown {
            let note = if item.covered_amount > 0.0 && item.remaining_deficit == 0.0 {
                format!("£{:.2} (Fully funded: £0.00/mo)", item.total_cost)
            } else if item.covered_amount > 0.0 {
                format!("£{:.2}/mo (Due {}, £{:.2} covered, £{:.2} left over {} paydays)",
                    item.monthly_saving, item.due_date, item.covered_amount, item.remaining_deficit, item.paydays_remaining)
            } else {
                format!("£{:.2}/mo (Due {}, £{:.2} over {} paydays)",
                    item.monthly_saving, item.due_date, item.total_cost, item.paydays_remaining)
            };
            lines.push(format!("• {}: {}", item.name, note));
        }
        lines.join("\n")
    }
}

/// Calculates the monthly sinking fund amount needed across all active upcoming yearly outgoings,
/// adjusting for any balance already saved in the target account via a chronological waterfall.
/// - Sorts upcoming bills by due date so existing cash covers the earliest due bills first.
/// - Any remaining deficit per bill is divided across its remaining paydays.
/// - If a bill is due in the current month, it is an active outgoing this month and excluded from sinking fund top-up.
pub fn calculate_yearly_bills_monthly_saving(
    as_of: NaiveDate,
    nominal_payday: i32,
    outgoings: &[Outgoing],
    target_account_balance: f32,
) -> YearlyBillsReserveCalculation {
    let mut upcoming_items: Vec<(NaiveDate, u32, f32, String)> = Vec::new();

    for o in outgoings {
        if !o.is_yearly() {
            continue;
        }
        if o.paused.unwrap_or(false) || o.hidden_from_total.unwrap_or(false) || o.archived.unwrap_or(false) {
            continue;
        }
        let target_month = match o.outgoing_month {
            Some(m) if m > 0 => m,
            _ => continue,
        };

        // If the outgoing is due in the current month, it is an active outgoing this month.
        // It is paid directly this month, so do not double-bill with a sinking fund top-up.
        if o.is_due_in_month(as_of) {
            continue;
        }

        let next_due_year = o.get_next_due_year(as_of);
        let mut due_date = crate::anchoring::get_actual_outgoing_date(
            o.outgoing_day,
            next_due_year,
            target_month as u32,
        );
        if due_date <= as_of {
            let interval = o.get_year_interval();
            due_date = crate::anchoring::get_actual_outgoing_date(
                o.outgoing_day,
                next_due_year + interval,
                target_month as u32,
            );
        }

        let paydays_remaining = count_paydays_between(as_of, due_date, nominal_payday);
        if paydays_remaining > 0 {
            upcoming_items.push((due_date, paydays_remaining, o.cost, o.name.clone()));
        }
    }

    // Sort chronologically by due date so existing target account cash offsets the earliest due bills first
    upcoming_items.sort_by_key(|item| item.0);

    let mut available_cash = target_account_balance.max(0.0);
    let mut total_upcoming_cost = 0.0f32;
    let mut total_remaining_deficit = 0.0f32;
    let mut total_monthly_saving = 0.0f32;
    let upcoming_count = upcoming_items.len();
    let mut breakdown = Vec::with_capacity(upcoming_count);

    for (due_date, paydays_remaining, cost, name) in upcoming_items {
        total_upcoming_cost += cost;
        let covered = available_cash.min(cost);
        available_cash -= covered;
        let deficit = cost - covered;
        total_remaining_deficit += deficit;

        let per_payday = if deficit > 0.0 && paydays_remaining > 0 {
            ((deficit / (paydays_remaining as f32) * 100.0).round()) / 100.0
        } else {
            0.0
        };
        total_monthly_saving += per_payday;

        let due_date_str = due_date.format("%b %Y").to_string();
        breakdown.push(YearlyBillItemBreakdown {
            name,
            total_cost: ((cost * 100.0).round()) / 100.0,
            covered_amount: ((covered * 100.0).round()) / 100.0,
            remaining_deficit: ((deficit * 100.0).round()) / 100.0,
            paydays_remaining,
            monthly_saving: per_payday,
            due_date: due_date_str,
        });
    }

    let monthly_saving = ((total_monthly_saving * 100.0).round()) / 100.0;
    let total_upcoming_cost = ((total_upcoming_cost * 100.0).round()) / 100.0;
    let remaining_deficit = ((total_remaining_deficit * 100.0).round()) / 100.0;
    let target_account_balance = ((target_account_balance * 100.0).round()) / 100.0;

    YearlyBillsReserveCalculation {
        monthly_saving,
        upcoming_count,
        total_upcoming_cost,
        remaining_deficit,
        target_account_balance,
        breakdown,
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewOutgoing {
    pub name: String,
    pub cost: f32,
    pub outgoing_day: i32,
    pub user_id: String,
    pub account_id: Option<i64>,
    pub outgoing_month: Option<i32>,
    pub year_interval: Option<i32>,
    pub starting_year: Option<i32>,
    pub bill: Option<bool>,
    pub rent: Option<bool>,
    pub hidden_from_total: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateOutgoing {
    pub name: String,
    pub cost: f32,
    pub outgoing_day: i32,
    pub user_id: Option<String>,
    pub account_id: Option<i64>,
    pub outgoing_month: Option<i32>,
    pub year_interval: Option<i32>,
    pub starting_year: Option<i32>,
    pub bill: Option<bool>,
    pub rent: Option<bool>,
    pub hidden_from_total: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub struct Balance {
    pub id: i64,
    pub value: f32,
    pub timestamp: i64,
    pub account_id: Option<i64>,
    pub user_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewBalance {
    pub value: f32,
    pub timestamp: Option<i64>,
    pub account_id: i64,
    pub user_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErraticBalance {
    pub account_id: i64,
    pub account_name: String,
    pub prev_balance_id: i64,
    pub curr_balance_id: i64,
    pub prev_value: f32,
    pub curr_value: f32,
    pub jump_amount: f32,
    pub timestamp: i64,
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::NaiveDate;

    fn make_test_outgoing(outgoing_month: Option<i32>, year_interval: Option<i32>, starting_year: Option<i32>) -> Outgoing {
        Outgoing {
            id: 1,
            name: "Test Outgoing".to_string(),
            cost: 50.0,
            outgoing_day: 15,
            outgoing_month,
            year_interval,
            starting_year,
            bill: Some(true),
            rent: Some(false),
            hidden_from_total: Some(false),
            paused: Some(false),
            pause_reason: None,
            paused_at: None,
            user_id: "demo".to_string(),
            account_id: None,
            account_name: None,
            account_type: None,
            archived: Some(false),
        }
    }

    #[test]
    fn test_monthly_outgoing_is_due_every_month() {
        let o = make_test_outgoing(None, None, None);
        assert!(!o.is_yearly());
        assert_eq!(o.get_frequency_label(), "Monthly");

        // Due every month regardless of year or month
        assert!(o.is_due_in_month(NaiveDate::from_ymd_opt(2026, 1, 1).unwrap()));
        assert!(o.is_due_in_month(NaiveDate::from_ymd_opt(2026, 6, 15).unwrap()));
        assert!(o.is_due_in_month(NaiveDate::from_ymd_opt(2027, 12, 31).unwrap()));
    }

    #[test]
    fn test_yearly_outgoing_due_only_in_configured_month() {
        // Due yearly in June (month 6)
        let o = make_test_outgoing(Some(6), Some(1), None);
        assert!(o.is_yearly());
        assert_eq!(o.get_frequency_label(), "Yearly (June)");

        assert!(o.is_due_in_month(NaiveDate::from_ymd_opt(2026, 6, 10).unwrap()));
        assert!(!o.is_due_in_month(NaiveDate::from_ymd_opt(2026, 5, 10).unwrap()));
        assert!(!o.is_due_in_month(NaiveDate::from_ymd_opt(2026, 7, 10).unwrap()));
    }

    #[test]
    fn test_yearly_outgoing_next_due_year() {
        let o = make_test_outgoing(Some(6), Some(1), None);
        // In May 2026, next due year is 2026
        assert_eq!(o.get_next_due_year(NaiveDate::from_ymd_opt(2026, 5, 1).unwrap()), 2026);
        // In June 2026, next due year is 2026
        assert_eq!(o.get_next_due_year(NaiveDate::from_ymd_opt(2026, 6, 1).unwrap()), 2026);
        // In July 2026, next due year is 2027
        assert_eq!(o.get_next_due_year(NaiveDate::from_ymd_opt(2026, 7, 1).unwrap()), 2027);
    }

    #[test]
    fn test_n_yearly_outgoing_due_every_n_years() {
        // Every 3 years in March (month 3), started 2023
        let o = make_test_outgoing(Some(3), Some(3), Some(2023));
        assert!(o.is_yearly());
        assert_eq!(o.get_frequency_label(), "Every 3 yrs (March started 2023)");

        // 2023 is start year -> due in March 2023
        assert!(o.is_due_in_month(NaiveDate::from_ymd_opt(2023, 3, 15).unwrap()));
        // 2024 (1 yr after) -> NOT due
        assert!(!o.is_due_in_month(NaiveDate::from_ymd_opt(2024, 3, 15).unwrap()));
        // 2025 (2 yrs after) -> NOT due
        assert!(!o.is_due_in_month(NaiveDate::from_ymd_opt(2025, 3, 15).unwrap()));
        // 2026 (3 yrs after) -> due in March 2026!
        assert!(o.is_due_in_month(NaiveDate::from_ymd_opt(2026, 3, 15).unwrap()));
        // April 2026 -> wrong month, NOT due
        assert!(!o.is_due_in_month(NaiveDate::from_ymd_opt(2026, 4, 15).unwrap()));
        // 2022 (before start year 2023) -> NOT due
        assert!(!o.is_due_in_month(NaiveDate::from_ymd_opt(2022, 3, 15).unwrap()));
    }

    #[test]
    fn test_n_yearly_outgoing_next_due_year() {
        // Every 2 years in October, starting 2024
        let o = make_test_outgoing(Some(10), Some(2), Some(2024));
        // In Sept 2024 -> 2024
        assert_eq!(o.get_next_due_year(NaiveDate::from_ymd_opt(2024, 9, 1).unwrap()), 2024);
        // In Nov 2024 -> 2026
        assert_eq!(o.get_next_due_year(NaiveDate::from_ymd_opt(2024, 11, 1).unwrap()), 2026);
        // In June 2025 -> 2026
        assert_eq!(o.get_next_due_year(NaiveDate::from_ymd_opt(2025, 6, 1).unwrap()), 2026);
        // In Dec 2026 -> 2028
        assert_eq!(o.get_next_due_year(NaiveDate::from_ymd_opt(2026, 12, 1).unwrap()), 2028);
    }

    #[test]
    fn test_calculate_monthly_outgoings_total_filters_yearly() {
        let monthly = make_test_outgoing(None, None, None); // cost: 50.0
        let yearly_due_june = make_test_outgoing(Some(6), Some(1), None); // cost: 50.0
        let yearly_due_dec = make_test_outgoing(Some(12), Some(1), None); // cost: 50.0
        let paused_monthly = {
            let mut p = make_test_outgoing(None, None, None);
            p.paused = Some(true);
            p
        };

        let list = vec![monthly, yearly_due_june, yearly_due_dec, paused_monthly];

        // In June 2026: monthly (50) + yearly_due_june (50) = 100
        let june_total = total_effective_outgoings(&list, NaiveDate::from_ymd_opt(2026, 6, 15).unwrap());
        assert_eq!(june_total, 100.0);

        // In July 2026: only monthly (50) = 50
        let july_total = total_effective_outgoings(&list, NaiveDate::from_ymd_opt(2026, 7, 15).unwrap());
        assert_eq!(july_total, 50.0);
    }

    #[test]
    fn test_count_paydays_between_two_months_ahead() {
        // Today is June 10, 2026. Payday nominal 25. Due date August 10, 2026.
        // Paydays: 2026-06-25, 2026-07-24 (both before 2026-08-10) -> 2 paydays
        let today = NaiveDate::from_ymd_opt(2026, 6, 10).unwrap();
        let due = NaiveDate::from_ymd_opt(2026, 8, 10).unwrap();
        let count = count_paydays_between(today, due, 25);
        assert_eq!(count, 2);
    }

    #[test]
    fn test_count_paydays_between_after_payday_leaves_one_month() {
        // Today is June 28, 2026 (after June 25 payday). Due date August 10, 2026.
        // Only remaining payday is July 24 -> 1 payday
        let today = NaiveDate::from_ymd_opt(2026, 6, 28).unwrap();
        let due = NaiveDate::from_ymd_opt(2026, 8, 10).unwrap();
        let count = count_paydays_between(today, due, 25);
        assert_eq!(count, 1);
    }

    #[test]
    fn test_count_paydays_between_on_due_date_or_after_is_zero() {
        let today = NaiveDate::from_ymd_opt(2026, 8, 10).unwrap();
        let due = NaiveDate::from_ymd_opt(2026, 8, 10).unwrap();
        assert_eq!(count_paydays_between(today, due, 25), 0);
    }

    #[test]
    fn test_calculate_yearly_bills_monthly_saving_user_car_insurance_example() {
        let mut car_insurance = make_test_outgoing(Some(8), Some(1), None);
        car_insurance.cost = 1000.0;
        car_insurance.outgoing_day = 10;
        let outgoings = vec![car_insurance];

        // 1. In June before payday (June 10) with 0 balance -> 2 paydays remaining -> £500.00/mo
        let res_june = calculate_yearly_bills_monthly_saving(
            NaiveDate::from_ymd_opt(2026, 6, 10).unwrap(),
            25,
            &outgoings,
            0.0,
        );
        assert_eq!(res_june.upcoming_count, 1);
        assert_eq!(res_june.monthly_saving, 500.00);
        assert_eq!(res_june.total_upcoming_cost, 1000.00);
        assert_eq!(res_june.remaining_deficit, 1000.00);

        // 2. In June with £400 already saved in target account -> deficit £600 / 2 = £300.00/mo
        let res_with_balance = calculate_yearly_bills_monthly_saving(
            NaiveDate::from_ymd_opt(2026, 6, 10).unwrap(),
            25,
            &outgoings,
            400.0,
        );
        assert_eq!(res_with_balance.monthly_saving, 300.00);
        assert_eq!(res_with_balance.remaining_deficit, 600.00);

        // 3. In June with £1000 already saved (fully funded) -> £0.00/mo
        let res_fully_funded = calculate_yearly_bills_monthly_saving(
            NaiveDate::from_ymd_opt(2026, 6, 10).unwrap(),
            25,
            &outgoings,
            1000.0,
        );
        assert_eq!(res_fully_funded.monthly_saving, 0.00);
        assert_eq!(res_fully_funded.remaining_deficit, 0.00);

        // 4. In June after payday (June 28) with 0 balance -> 1 payday remaining -> £1000.00/mo
        let res_late_june = calculate_yearly_bills_monthly_saving(
            NaiveDate::from_ymd_opt(2026, 6, 28).unwrap(),
            25,
            &outgoings,
            0.0,
        );
        assert_eq!(res_late_june.upcoming_count, 1);
        assert_eq!(res_late_june.monthly_saving, 1000.00);

        // 5. In August (due month) -> 0 saving because bill itself is due as an active outgoing this month
        let res_aug = calculate_yearly_bills_monthly_saving(
            NaiveDate::from_ymd_opt(2026, 8, 5).unwrap(),
            25,
            &outgoings,
            0.0,
        );
        assert_eq!(res_aug.upcoming_count, 0);
        assert_eq!(res_aug.monthly_saving, 0.0);
    }

    #[test]
    fn test_calculate_yearly_bills_monthly_saving_multiple_bills_and_paused() {
        let mut bill_aug = make_test_outgoing(Some(8), Some(1), None);
        bill_aug.cost = 1000.0;
        bill_aug.outgoing_day = 10;

        let mut bill_nov = make_test_outgoing(Some(11), Some(1), None);
        bill_nov.cost = 120.0;
        bill_nov.outgoing_day = 15;

        let mut paused_bill = make_test_outgoing(Some(9), Some(1), None);
        paused_bill.cost = 300.0;
        paused_bill.paused = Some(true);

        let outgoings = vec![bill_aug, bill_nov, paused_bill];

        // On June 10 with 0 balance:
        // bill_aug: 2 paydays -> 1000 / 2 = 500
        // bill_nov: June, July, Aug, Sept, Oct paydays = 5 paydays -> 120 / 5 = 24
        // paused_bill is ignored
        // Total = 524.00
        let res = calculate_yearly_bills_monthly_saving(
            NaiveDate::from_ymd_opt(2026, 6, 10).unwrap(),
            25,
            &outgoings,
            0.0,
        );
        assert_eq!(res.upcoming_count, 2);
        assert_eq!(res.monthly_saving, 524.00);
        assert_eq!(res.total_upcoming_cost, 1120.00);
        assert_eq!(res.remaining_deficit, 1120.00);

        // On June 10 with £400 balance in target account:
        // £400 offsets bill_aug (£1000 - £400 = £600 remaining / 2 paydays = £300)
        // bill_nov still needs £120 / 5 = £24
        // Total saving = £324.00, remaining deficit = £720.00
        let res_waterfall = calculate_yearly_bills_monthly_saving(
            NaiveDate::from_ymd_opt(2026, 6, 10).unwrap(),
            25,
            &outgoings,
            400.0,
        );
        assert_eq!(res_waterfall.monthly_saving, 324.00);
        assert_eq!(res_waterfall.remaining_deficit, 720.00);
        assert_eq!(res_waterfall.breakdown.len(), 2);
        assert_eq!(res_waterfall.breakdown[0].covered_amount, 400.00);
        assert_eq!(res_waterfall.breakdown[0].remaining_deficit, 600.00);
        assert_eq!(res_waterfall.breakdown[0].monthly_saving, 300.00);
        assert_eq!(res_waterfall.breakdown[1].covered_amount, 0.00);
        assert_eq!(res_waterfall.breakdown[1].remaining_deficit, 120.00);
        assert_eq!(res_waterfall.breakdown[1].monthly_saving, 24.00);
        let tooltip = res_waterfall.format_tooltip();
        assert!(tooltip.contains("Test Outgoing"));
        assert!(tooltip.contains("£300.00/mo"));
        assert!(tooltip.contains("£400.00 covered"));
    }
}
