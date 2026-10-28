package models;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.*;
import java.util.List;

@Entity
@Table(indexes = @Index(columnList = "userId"))
public class Outgoing {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    @Column(name = "userId")
    public String userId;

    public String name;
    public Float cost;

    @Column(name = "OUTGOINGDAY")
    public int outgoingDay;

    @Column(name = "OUTGOINGMONTH")
    public Integer outgoingMonth; // null or 0 for monthly (every month); 1-12 for Jan-Dec

    @Column(name = "YEARINTERVAL")
    public Integer yearInterval = 1; // 1 = every year, 2 = every 2 years, 3 = every 3 years, etc.

    @Column(name = "STARTINGYEAR")
    public Integer startingYear; // e.g. 2025

    public boolean bill = false;
    public boolean rent = false;
    public boolean archived = false;
    public boolean hiddenFromTotal = false;

    @Column(name = "paused")
    public boolean paused = false;

    @Column(name = "pause_reason")
    public String pauseReason;

    @Column(name = "paused_at")
    public Long pausedAt;

    @ManyToOne
    @JoinColumn(name="account_id")
    @JsonIgnore
    public Account account;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isHiddenFromTotal() {
        return hiddenFromTotal;
    }

    public void setHiddenFromTotal(boolean hiddenFromTotal) {
        this.hiddenFromTotal = hiddenFromTotal;
    }

    public boolean isBill() {
        return bill;
    }

    public void setBill(boolean bill) {
        this.bill = bill;
    }

    public boolean isRent() {
        return rent;
    }

    public void setRent(boolean rent) {
        this.rent = rent;
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

    public Float getCost() {
        return cost;
    }

    public void setCost(Float cost) {
        this.cost = cost;
    }

    public int getOutgoingDay() {
        return outgoingDay;
    }

    public void setOutgoingDay(int outgoingDay) {
        this.outgoingDay = outgoingDay;
    }

    public Integer getOutgoingMonth() {
        return outgoingMonth;
    }

    public void setOutgoingMonth(Integer outgoingMonth) {
        this.outgoingMonth = (outgoingMonth != null && outgoingMonth > 0 && outgoingMonth <= 12) ? outgoingMonth : null;
    }

    public Integer getYearInterval() {
        return (yearInterval != null && yearInterval > 0) ? yearInterval : 1;
    }

    public void setYearInterval(Integer yearInterval) {
        this.yearInterval = (yearInterval != null && yearInterval > 0) ? yearInterval : 1;
    }

    public Integer getStartingYear() {
        return startingYear;
    }

    public void setStartingYear(Integer startingYear) {
        this.startingYear = (startingYear != null && startingYear > 1900) ? startingYear : null;
    }

    public boolean isYearly() {
        return outgoingMonth != null && outgoingMonth > 0;
    }

    public boolean isDueInMonth(int month) {
        return !isYearly() || (this.outgoingMonth != null && this.outgoingMonth == month);
    }

    public boolean isDueInMonth(java.time.LocalDate asOf) {
        if (asOf == null) {
            asOf = helpers.BankHolidayHelper.nowInRegion("UK");
        }
        if (!isYearly()) {
            return true;
        }
        if (this.outgoingMonth == null || this.outgoingMonth != asOf.getMonthValue()) {
            return false;
        }
        int interval = getYearInterval();
        if (interval > 1) {
            int start = (this.startingYear != null && this.startingYear > 0) ? this.startingYear : asOf.getYear();
            int currentYear = asOf.getYear();
            if (currentYear < start) {
                return false;
            }
            return (currentYear - start) % interval == 0;
        }
        return true;
    }

    public int getNextDueYear(java.time.LocalDate asOf) {
        if (asOf == null) {
            asOf = helpers.BankHolidayHelper.nowInRegion("UK");
        }
        int interval = getYearInterval();
        if (interval <= 1) {
            if (this.outgoingMonth != null && asOf.getMonthValue() > this.outgoingMonth) {
                return asOf.getYear() + 1;
            }
            return asOf.getYear();
        }
        int start = (this.startingYear != null && this.startingYear > 0) ? this.startingYear : asOf.getYear();
        int y = start;
        while (y < asOf.getYear() || (y == asOf.getYear() && this.outgoingMonth != null && asOf.getMonthValue() > this.outgoingMonth)) {
            y += interval;
        }
        return y;
    }

    public String getFrequencyLabel() {
        if (!isYearly()) {
            return "Monthly";
        }
        String month = getMonthName();
        int interval = getYearInterval();
        if (interval > 1) {
            String startStr = (this.startingYear != null && this.startingYear > 0) ? (" started " + this.startingYear) : "";
            return "Every " + interval + " yrs (" + month + startStr + ")";
        }
        return "Yearly (" + month + ")";
    }

    public String getMonthName() {
        if (!isYearly()) {
            return "Monthly";
        }
        try {
            return java.time.Month.of(this.outgoingMonth).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK);
        } catch (Exception e) {
            return "Month " + this.outgoingMonth;
        }
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public void setPauseReason(String pauseReason) {
        this.pauseReason = pauseReason;
    }

    public Long getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Long pausedAt) {
        this.pausedAt = pausedAt;
    }

    public Float getEffectiveCost() {
        if (paused) {
            return 0.0f;
        }
        return cost != null ? cost : 0.0f;
    }

    // MWM-29
    // Add 'hidden outgoing' flag to Outgoing
    // and filter before reducing here...
    // we could also have other static methods here
    // that take things like bill share/rent share into account.
    public static Float getTotalOutgoings(List<Outgoing> outgoings) {
        return getTotalOutgoings(outgoings, helpers.BankHolidayHelper.nowInRegion("UK"));
    }

    public static Float getTotalOutgoings(List<Outgoing> outgoings, java.time.LocalDate asOf) {
        return outgoings
                .stream()
                .filter(o -> o != null && !o.isPaused() && o.isDueInMonth(asOf))
                .reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
    }

    public static Float getTotalOutgoingsWithoutHidden(List<Outgoing> outgoings) {
        return getTotalOutgoingsWithoutHidden(outgoings, helpers.BankHolidayHelper.nowInRegion("UK"));
    }

    public static Float getTotalOutgoingsWithoutHidden(List<Outgoing> outgoings, java.time.LocalDate asOf) {
        return outgoings
                .stream()
                .filter(o -> o != null && !o.isPaused() && !o.isHiddenFromTotal() && o.isDueInMonth(asOf))
                .reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
    }

    public int getEffectiveOutgoingDay(java.time.LocalDate asOf) {
        return Math.min(this.outgoingDay, asOf.lengthOfMonth());
    }

    public int getEffectiveOutgoingDay() {
        return getEffectiveOutgoingDay(helpers.BankHolidayHelper.nowInRegion("UK"));
    }

    public boolean isAdjustedForMonth(java.time.LocalDate asOf) {
        return this.outgoingDay > asOf.lengthOfMonth();
    }

    public boolean isAdjustedForMonth() {
        return isAdjustedForMonth(helpers.BankHolidayHelper.nowInRegion("UK"));
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }
}
