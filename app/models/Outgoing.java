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

    public boolean bill = false;
    public boolean rent = false;
    public boolean archived = false;
    public boolean hiddenFromTotal = false;

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

    public boolean isYearly() {
        return outgoingMonth != null && outgoingMonth > 0;
    }

    public boolean isDueInMonth(int month) {
        return !isYearly() || (this.outgoingMonth != null && this.outgoingMonth == month);
    }

    public boolean isDueInMonth(java.time.LocalDate asOf) {
        if (asOf == null) {
            return isDueInMonth(helpers.BankHolidayHelper.nowInRegion("UK").getMonthValue());
        }
        return isDueInMonth(asOf.getMonthValue());
    }

    public String getFrequencyLabel() {
        if (!isYearly()) {
            return "Monthly";
        }
        try {
            return java.time.Month.of(this.outgoingMonth).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK);
        } catch (Exception e) {
            return "Yearly (" + this.outgoingMonth + ")";
        }
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
                .filter(o -> o != null && o.isDueInMonth(asOf))
                .reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
    }

    public static Float getTotalOutgoingsWithoutHidden(List<Outgoing> outgoings) {
        return getTotalOutgoingsWithoutHidden(outgoings, helpers.BankHolidayHelper.nowInRegion("UK"));
    }

    public static Float getTotalOutgoingsWithoutHidden(List<Outgoing> outgoings, java.time.LocalDate asOf) {
        return outgoings
                .stream()
                .filter(o -> o != null && !o.isHiddenFromTotal() && o.isDueInMonth(asOf))
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
