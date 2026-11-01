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

    @Column(name = "history_json", columnDefinition = "TEXT")
    public String historyJson;

    public static class IncomingHistoryEntry {
        private Long timestamp;
        private Float netValue;
        private Float grossValue;
        private String name;
        private Integer incomingMonthDay;
        private Boolean payDay;
        private String type;
        private Long accountId;

        public IncomingHistoryEntry() {}

        public IncomingHistoryEntry(Long timestamp, Float netValue, Float grossValue, String name, Integer incomingMonthDay, Boolean payDay, String type, Long accountId) {
            this.timestamp = timestamp;
            this.netValue = netValue;
            this.grossValue = grossValue;
            this.name = name;
            this.incomingMonthDay = incomingMonthDay;
            this.payDay = payDay;
            this.type = type;
            this.accountId = accountId;
        }

        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
        public Float getNetValue() { return netValue; }
        public void setNetValue(Float netValue) { this.netValue = netValue; }
        public Float getGrossValue() { return grossValue; }
        public void setGrossValue(Float grossValue) { this.grossValue = grossValue; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getIncomingMonthDay() { return incomingMonthDay; }
        public void setIncomingMonthDay(Integer incomingMonthDay) { this.incomingMonthDay = incomingMonthDay; }
        public Boolean getPayDay() { return payDay; }
        public void setPayDay(Boolean payDay) { this.payDay = payDay; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
    }

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

    public String getHistoryJson() {
        return historyJson;
    }

    public void setHistoryJson(String historyJson) {
        this.historyJson = historyJson;
    }

    public List<IncomingHistoryEntry> getHistory() {
        if (historyJson != null && !historyJson.trim().isEmpty()) {
            try {
                List<IncomingHistoryEntry> parsed = play.libs.Json.mapper().readValue(
                        historyJson,
                        new com.fasterxml.jackson.core.type.TypeReference<List<IncomingHistoryEntry>>() {}
                );
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception ignored) {}
        }
        return new java.util.ArrayList<>();
    }

    public void setHistory(List<IncomingHistoryEntry> history) {
        try {
            this.historyJson = play.libs.Json.mapper().writeValueAsString(history != null ? history : new java.util.ArrayList<>());
        } catch (Exception e) {
            this.historyJson = "[]";
        }
    }

    public void recordEditHistorySnapshot(Long replacedAtSec) {
        if (this.netValue == null && this.grossValue == null && this.name == null) {
            return;
        }
        List<IncomingHistoryEntry> history = getHistory();
        Long accId = this.account != null ? this.account.getId() : null;
        history.add(new IncomingHistoryEntry(
                replacedAtSec,
                this.netValue,
                this.grossValue,
                this.name,
                this.incomingMonthDay,
                this.payDay,
                this.type,
                accId
        ));
        setHistory(history);
    }

    public Float getEffectiveNetValueForCycle(long cycleStartSec, long cycleEndSec) {
        List<IncomingHistoryEntry> history = getHistory();
        if (history == null || history.isEmpty()) {
            return this.netValue != null ? this.netValue : 0f;
        }
        for (IncomingHistoryEntry entry : history) {
            Long entryTimestamp = entry.getTimestamp();
            if (entryTimestamp != null) {
                long tsSec = entryTimestamp > 100_000_000_000L ? entryTimestamp / 1000L : entryTimestamp;
                if (tsSec >= cycleStartSec) {
                    return entry.getNetValue() != null ? entry.getNetValue() : 0f;
                }
            }
        }
        return this.netValue != null ? this.netValue : 0f;
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
