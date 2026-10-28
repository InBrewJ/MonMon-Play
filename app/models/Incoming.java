package models;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.*;
import java.util.List;

@Entity
@Table(indexes = @Index(columnList = "userId"))
public class Incoming {

    public static final double DEFAULT_WORKING_DAYS_PER_YEAR = 253.0;
    public static final double DEFAULT_WORKING_HOURS_PER_DAY = 7.5;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    @Column(name = "userId")
    public String userId;

    public String name;
    public String type; // should be an enum of, say, PAYCHECK | ONEOFF | ..., but not yet
    public Float grossValue;
    public Float netValue;
    public int incomingMonthDay;
    public boolean archived = false;
    public boolean payDay = false;

    @ManyToOne
    @JoinColumn(name = "account_id")
    @JsonIgnore
    public Account account;

    @Column(name = "created_at")
    public Long createdAt;

    @Column(name = "archived_at")
    public Long archivedAt;

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Long archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Double getDailyRate() {
        Float val = (this.grossValue != null && this.grossValue > 0) ? this.grossValue : this.netValue;
        if (val == null || val <= 0) {
            return null;
        }
        double annual = (double) val * 12.0;
        return annual / DEFAULT_WORKING_DAYS_PER_YEAR;
    }

    public Double getHourlyRate() {
        Double daily = getDailyRate();
        if (daily == null) {
            return null;
        }
        return daily / DEFAULT_WORKING_HOURS_PER_DAY;
    }

    public String getWorkRateChip() {
        Double daily = getDailyRate();
        Double hourly = getHourlyRate();
        if (daily == null || hourly == null) {
            return null;
        }
        return String.format("[£%.2f/day for 7.5 hours £%.2f/hr]", daily, hourly);
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isPayDay() {
        return payDay;
    }

    public void setPayDay(boolean payDay) {
        this.payDay = payDay;
    }

    public Float getGrossValue() {
        return grossValue;
    }

    public void setGrossValue(Float grossValue) {
        this.grossValue = grossValue;
    }

    public Float getNetValue() {
        return netValue;
    }

    public void setNetValue(Float netValue) {
        this.netValue = netValue;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getIncomingMonthDay() {
        return incomingMonthDay;
    }

    public void setIncomingMonthDay(int incomingMonthDay) {
        this.incomingMonthDay = incomingMonthDay;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public int getEffectiveIncomingMonthDay(java.time.LocalDate asOf) {
        if (this.payDay) {
            return helpers.ModelHelpers.getActualPaydayDate(this.incomingMonthDay, asOf).getDayOfMonth();
        } else {
            return Math.min(this.incomingMonthDay, asOf.lengthOfMonth());
        }
    }

    public int getEffectiveIncomingMonthDay() {
        return getEffectiveIncomingMonthDay(helpers.BankHolidayHelper.nowInRegion("UK"));
    }

    public boolean isAdjustedForMonth(java.time.LocalDate asOf) {
        return getEffectiveIncomingMonthDay(asOf) != this.incomingMonthDay;
    }

    public boolean isAdjustedForMonth() {
        return isAdjustedForMonth(helpers.BankHolidayHelper.nowInRegion("UK"));
    }

    public static Float getTotalIncomings(List<Incoming> incomings) {
        if (incomings.isEmpty()) return 0f;
        return incomings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.netValue, Float::sum);
    }

}
