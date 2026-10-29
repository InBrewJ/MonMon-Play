package models;

import static helpers.TimeHelpers.unixTimestampToDisplayDate;

/**
 * Represents an erratic balance jump detected across consecutive balance records for an account.
 */
public class ErraticBalance {
    private Long id;
    private Long accountId;
    private String accountName;
    private Long timestamp;
    private Float value;
    private Float prevValue;
    private Long prevTimestamp;
    private Float jumpAmount;

    public ErraticBalance() {}

    public ErraticBalance(Long id, Long accountId, String accountName, Long timestamp, Float value, Float prevValue, Long prevTimestamp, Float jumpAmount) {
        this.id = id;
        this.accountId = accountId;
        this.accountName = accountName;
        this.timestamp = timestamp;
        this.value = value;
        this.prevValue = prevValue;
        this.prevTimestamp = prevTimestamp;
        this.jumpAmount = jumpAmount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getAccountName() {
        return accountName != null ? accountName : "Account #" + accountId;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public String getTimestampHumanReadable() {
        return timestamp != null ? unixTimestampToDisplayDate(timestamp) : "";
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Float getValue() {
        return value;
    }

    public void setValue(Float value) {
        this.value = value;
    }

    public Float getPrevValue() {
        return prevValue;
    }

    public void setPrevValue(Float prevValue) {
        this.prevValue = prevValue;
    }

    public Long getPrevTimestamp() {
        return prevTimestamp;
    }

    public String getPrevTimestampHumanReadable() {
        return prevTimestamp != null ? unixTimestampToDisplayDate(prevTimestamp) : "";
    }

    public void setPrevTimestamp(Long prevTimestamp) {
        this.prevTimestamp = prevTimestamp;
    }

    public Float getJumpAmount() {
        return jumpAmount;
    }

    public void setJumpAmount(Float jumpAmount) {
        this.jumpAmount = jumpAmount;
    }

    public Float getSignedDelta() {
        return (value != null && prevValue != null) ? (value - prevValue) : 0.0f;
    }
}
