use chrono::Datelike;
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
        .filter(|o| !o.paused.unwrap_or(false) && !o.archived.unwrap_or(false) && o.is_due_in_month(as_of))
        .map(|o| o.cost)
        .sum()
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
}
