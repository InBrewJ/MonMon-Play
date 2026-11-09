use std::fmt;
use std::iter::Sum;
use std::ops::{Add, AddAssign, Div, DivAssign, Mul, MulAssign, Neg, Sub, SubAssign};
use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// Money represented as an exact integer number of GB Pence (1/100 of a Pound).
/// e.g. £12.50 = Pence(1250)
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Default, Hash)]
pub struct Pence(pub i64);

impl Pence {
    pub const ZERO: Pence = Pence(0);

    #[inline]
    pub fn from_pence(pence: i64) -> Self {
        Pence(pence)
    }

    #[inline]
    pub fn as_pence(&self) -> i64 {
        self.0
    }

    #[inline]
    pub fn from_pounds_f64(pounds: f64) -> Self {
        Pence((pounds * 100.0).round() as i64)
    }

    #[inline]
    pub fn from_pounds_f32(pounds: f32) -> Self {
        Self::from_pounds_f64(pounds as f64)
    }

    #[inline]
    pub fn to_pounds_f64(&self) -> f64 {
        self.0 as f64 / 100.0
    }

    #[inline]
    pub fn to_pounds_f32(&self) -> f32 {
        (self.0 as f32) / 100.0
    }

    #[inline]
    pub fn format_pounds(&self) -> String {
        format!("{:.2}", self.to_pounds_f64())
    }

    #[inline]
    pub fn format_currency(&self) -> String {
        format!("£{:.2}", self.to_pounds_f64())
    }

    #[inline]
    pub fn abs(&self) -> Self {
        Pence(self.0.abs())
    }

    #[inline]
    pub fn is_positive(&self) -> bool {
        self.0 > 0
    }

    #[inline]
    pub fn is_negative(&self) -> bool {
        self.0 < 0
    }

    #[inline]
    pub fn is_zero(&self) -> bool {
        self.0 == 0
    }

    #[inline]
    pub fn max(self, other: Pence) -> Pence {
        Pence(self.0.max(other.0))
    }

    #[inline]
    pub fn min(self, other: Pence) -> Pence {
        Pence(self.0.min(other.0))
    }

    #[inline]
    pub fn checked_div_days(self, days: i64) -> Pence {
        if days <= 0 {
            Pence::ZERO
        } else {
            Pence((self.0 as f64 / days as f64).round() as i64)
        }
    }
}

impl Add for Pence {
    type Output = Pence;
    #[inline]
    fn add(self, rhs: Pence) -> Pence {
        Pence(self.0 + rhs.0)
    }
}

impl AddAssign for Pence {
    #[inline]
    fn add_assign(&mut self, rhs: Pence) {
        self.0 += rhs.0;
    }
}

impl Sub for Pence {
    type Output = Pence;
    #[inline]
    fn sub(self, rhs: Pence) -> Pence {
        Pence(self.0 - rhs.0)
    }
}

impl SubAssign for Pence {
    #[inline]
    fn sub_assign(&mut self, rhs: Pence) {
        self.0 -= rhs.0;
    }
}

impl Neg for Pence {
    type Output = Pence;
    #[inline]
    fn neg(self) -> Pence {
        Pence(-self.0)
    }
}

impl Mul<i64> for Pence {
    type Output = Pence;
    #[inline]
    fn mul(self, rhs: i64) -> Pence {
        Pence(self.0 * rhs)
    }
}

impl Mul<f64> for Pence {
    type Output = Pence;
    #[inline]
    fn mul(self, rhs: f64) -> Pence {
        Pence(((self.0 as f64) * rhs).round() as i64)
    }
}

impl Mul<f32> for Pence {
    type Output = Pence;
    #[inline]
    fn mul(self, rhs: f32) -> Pence {
        self * (rhs as f64)
    }
}

impl Div<i64> for Pence {
    type Output = Pence;
    #[inline]
    fn div(self, rhs: i64) -> Pence {
        if rhs == 0 {
            Pence::ZERO
        } else {
            Pence((self.0 as f64 / rhs as f64).round() as i64)
        }
    }
}

impl Div<f64> for Pence {
    type Output = Pence;
    #[inline]
    fn div(self, rhs: f64) -> Pence {
        if rhs == 0.0 {
            Pence::ZERO
        } else {
            Pence(((self.0 as f64) / rhs).round() as i64)
        }
    }
}

impl Div<Pence> for Pence {
    type Output = f64;
    #[inline]
    fn div(self, rhs: Pence) -> f64 {
        if rhs.0 == 0 {
            0.0
        } else {
            self.0 as f64 / rhs.0 as f64
        }
    }
}

impl DivAssign<i64> for Pence {
    #[inline]
    fn div_assign(&mut self, rhs: i64) {
        *self = *self / rhs;
    }
}

impl MulAssign<i64> for Pence {
    #[inline]
    fn mul_assign(&mut self, rhs: i64) {
        *self = *self * rhs;
    }
}

impl Sum for Pence {
    fn sum<I: Iterator<Item = Pence>>(iter: I) -> Self {
        iter.fold(Pence::ZERO, |a, b| a + b)
    }
}

impl<'a> Sum<&'a Pence> for Pence {
    fn sum<I: Iterator<Item = &'a Pence>>(iter: I) -> Self {
        iter.fold(Pence::ZERO, |a, b| a + *b)
    }
}

impl fmt::Display for Pence {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:.2}", self.to_pounds_f64())
    }
}

impl Serialize for Pence {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        // Serializes as decimal e.g. 12.50 to preserve backwards compatibility with API and templates
        serializer.serialize_f64(self.to_pounds_f64())
    }
}

impl<'de> Deserialize<'de> for Pence {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct PenceVisitor;

        impl<'de> serde::de::Visitor<'de> for PenceVisitor {
            type Value = Pence;

            fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
                formatter.write_str("a float, integer, or string representing pounds")
            }

            fn visit_i64<E>(self, v: i64) -> Result<Pence, E> {
                // When an integer amount of pounds is passed (e.g. 12 -> £12 = 1200 pence)
                Ok(Pence(v * 100))
            }

            fn visit_u64<E>(self, v: u64) -> Result<Pence, E> {
                Ok(Pence(v as i64 * 100))
            }

            fn visit_f64<E>(self, v: f64) -> Result<Pence, E> {
                Ok(Pence::from_pounds_f64(v))
            }

            fn visit_str<E>(self, v: &str) -> Result<Pence, E>
            where
                E: serde::de::Error,
            {
                let trimmed = v.trim().trim_start_matches('£').trim();
                if trimmed.is_empty() {
                    return Ok(Pence::ZERO);
                }
                let val: f64 = trimmed.parse().map_err(serde::de::Error::custom)?;
                Ok(Pence::from_pounds_f64(val))
            }
        }

        deserializer.deserialize_any(PenceVisitor)
    }
}

impl sqlx::Type<sqlx::Postgres> for Pence {
    fn type_info() -> sqlx::postgres::PgTypeInfo {
        <f32 as sqlx::Type<sqlx::Postgres>>::type_info()
    }
    fn compatible(ty: &sqlx::postgres::PgTypeInfo) -> bool {
        <f32 as sqlx::Type<sqlx::Postgres>>::compatible(ty)
            || <f64 as sqlx::Type<sqlx::Postgres>>::compatible(ty)
            || <i64 as sqlx::Type<sqlx::Postgres>>::compatible(ty)
            || <i32 as sqlx::Type<sqlx::Postgres>>::compatible(ty)
    }
}

impl<'r> sqlx::Decode<'r, sqlx::Postgres> for Pence {
    fn decode(value: sqlx::postgres::PgValueRef<'r>) -> Result<Self, sqlx::error::BoxDynError> {
        use sqlx::TypeInfo;
        use sqlx::ValueRef;
        let info = value.type_info();
        match info.name() {
            "FLOAT4" => {
                let f = <f32 as sqlx::Decode<sqlx::Postgres>>::decode(value)?;
                Ok(Pence::from_pounds_f32(f))
            }
            "FLOAT8" => {
                let f = <f64 as sqlx::Decode<sqlx::Postgres>>::decode(value)?;
                Ok(Pence::from_pounds_f64(f))
            }
            "INT8" => {
                let i = <i64 as sqlx::Decode<sqlx::Postgres>>::decode(value)?;
                Ok(Pence(i))
            }
            "INT4" => {
                let i = <i32 as sqlx::Decode<sqlx::Postgres>>::decode(value)?;
                Ok(Pence(i as i64))
            }
            _ => {
                let f = <f64 as sqlx::Decode<sqlx::Postgres>>::decode(value)?;
                Ok(Pence::from_pounds_f64(f))
            }
        }
    }
}

impl<'q> sqlx::Encode<'q, sqlx::Postgres> for Pence {
    fn encode_by_ref(
        &self,
        buf: &mut sqlx::postgres::PgArgumentBuffer,
    ) -> Result<sqlx::encode::IsNull, sqlx::error::BoxDynError> {
        <f32 as sqlx::Encode<sqlx::Postgres>>::encode_by_ref(&self.to_pounds_f32(), buf)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pence_from_pounds_conversions() {
        assert_eq!(Pence::from_pounds_f64(12.99).as_pence(), 1299);
        assert_eq!(Pence::from_pounds_f64(0.01).as_pence(), 1);
        assert_eq!(Pence::from_pounds_f64(0.00).as_pence(), 0);
        assert_eq!(Pence::from_pounds_f64(-5.50).as_pence(), -550);
        assert_eq!(Pence::from_pounds_f32(28.45).as_pence(), 2845);
    }

    #[test]
    fn test_pence_rounding_precision() {
        // 12.995 rounds up to 1300 pence, 12.994 rounds down to 1299 pence
        assert_eq!(Pence::from_pounds_f64(12.995).as_pence(), 1300);
        assert_eq!(Pence::from_pounds_f64(12.994).as_pence(), 1299);
    }

    #[test]
    fn test_pence_arithmetic() {
        let a = Pence::from_pence(1550); // £15.50
        let b = Pence::from_pence(450);  // £4.50
        assert_eq!(a + b, Pence::from_pence(2000));
        assert_eq!(a - b, Pence::from_pence(1100));
        assert_eq!(b - a, Pence::from_pence(-1100));
        assert_eq!((b - a).abs(), Pence::from_pence(1100));
        assert_eq!(-a, Pence::from_pence(-1550));
    }

    #[test]
    fn test_pence_multiplication_and_division() {
        let p = Pence::from_pence(1000); // £10.00
        assert_eq!(p * 3, Pence::from_pence(3000));
        assert_eq!(p * 0.5, Pence::from_pence(500));
        assert_eq!(p / 4, Pence::from_pence(250));
        assert_eq!(p / 3, Pence::from_pence(333)); // 1000 / 3 = 333.33 -> 333 pence

        let total = Pence::from_pence(2000);
        let part = Pence::from_pence(500);
        assert_eq!(part / total, 0.25);
    }

    #[test]
    fn test_checked_div_days() {
        let pot = Pence::from_pounds_f64(300.0); // 30,000 pence
        assert_eq!(pot.checked_div_days(30), Pence::from_pounds_f64(10.0));
        assert_eq!(pot.checked_div_days(10), Pence::from_pounds_f64(30.0));
        assert_eq!(pot.checked_div_days(0), Pence::ZERO);
        assert_eq!(pot.checked_div_days(-5), Pence::ZERO);
    }

    #[test]
    fn test_pence_formatting_and_display() {
        let p = Pence::from_pence(2845);
        assert_eq!(p.format_pounds(), "28.45");
        assert_eq!(p.format_currency(), "£28.45");
        assert_eq!(format!("{}", p), "28.45");

        let zero = Pence::ZERO;
        assert_eq!(zero.format_pounds(), "0.00");
        assert_eq!(format!("{}", zero), "0.00");

        let neg = Pence::from_pence(-150);
        assert_eq!(neg.format_pounds(), "-1.50");
    }

    #[test]
    fn test_pence_serde_json() {
        let p = Pence::from_pence(4999); // £49.99
        let json = serde_json::to_string(&p).unwrap();
        assert_eq!(json, "49.99");

        let deserialized: Pence = serde_json::from_str("49.99").unwrap();
        assert_eq!(deserialized, p);

        let from_int: Pence = serde_json::from_str("50").unwrap();
        assert_eq!(from_int, Pence::from_pence(5000)); // £50.00

        let from_str: Pence = serde_json::from_str("\"£25.50\"").unwrap();
        assert_eq!(from_str, Pence::from_pence(2550));
    }

    #[test]
    fn test_pence_summation() {
        let items = vec![
            Pence::from_pounds_f64(10.50),
            Pence::from_pounds_f64(20.25),
            Pence::from_pounds_f64(5.25),
        ];
        let total: Pence = items.into_iter().sum();
        assert_eq!(total, Pence::from_pounds_f64(36.00));
        assert_eq!(total.as_pence(), 3600);
    }

    #[test]
    fn test_large_amounts_million_pounds() {
        let million = Pence::from_pounds_f64(1_000_000.00);
        assert_eq!(million.as_pence(), 100_000_000);
        assert_eq!(million.format_pounds(), "1000000.00");
        assert_eq!(million.format_currency(), "£1000000.00");

        let half = million / 2;
        assert_eq!(half.as_pence(), 50_000_000);
        assert_eq!(half.format_currency(), "£500000.00");
    }

    #[test]
    fn test_negative_pence_and_overdrafts() {
        let balance = Pence::from_pounds_f64(-150.75); // £150.75 overdraft
        assert!(balance.is_negative());
        assert!(!balance.is_positive());
        assert_eq!(balance.as_pence(), -15075);
        assert_eq!(balance.abs().as_pence(), 15075);
        assert_eq!(balance.format_pounds(), "-150.75");
        assert_eq!(balance.format_currency(), "£-150.75");

        let incoming = Pence::from_pounds_f64(200.00);
        let after_deposit = balance + incoming;
        assert_eq!(after_deposit, Pence::from_pounds_f64(49.25));
        assert!(after_deposit.is_positive());
    }

    #[test]
    fn test_savings_percentage_calculations() {
        let monthly_income = Pence::from_pounds_f64(2500.50); // 250,050 pence
        let savings_rate = 20; // 20%
        let savings_allocation = monthly_income * (savings_rate as f64 / 100.0);
        assert_eq!(savings_allocation.as_pence(), 50010); // £500.10
        assert_eq!(savings_allocation.format_pounds(), "500.10");

        let outgoings = Pence::from_pounds_f64(1200.00);
        let surplus = monthly_income - outgoings - savings_allocation;
        assert_eq!(surplus.as_pence(), 80040); // £800.40
        assert_eq!(surplus.format_pounds(), "800.40");
    }

    #[test]
    fn test_proportional_scaling_in_pence() {
        let initial_items = vec![
            Pence::from_pounds_f64(100.00),
            Pence::from_pounds_f64(200.00),
            Pence::from_pounds_f64(300.00),
        ];
        let total_initial: Pence = initial_items.iter().copied().sum();
        assert_eq!(total_initial, Pence::from_pounds_f64(600.00));

        let new_target = Pence::from_pounds_f64(900.00);
        let scaled: Vec<Pence> = initial_items
            .iter()
            .map(|&it| it * (new_target / total_initial))
            .collect();

        assert_eq!(scaled[0], Pence::from_pounds_f64(150.00));
        assert_eq!(scaled[1], Pence::from_pounds_f64(300.00));
        assert_eq!(scaled[2], Pence::from_pounds_f64(450.00));
        let total_scaled: Pence = scaled.iter().copied().sum();
        assert_eq!(total_scaled, new_target);
    }
}
