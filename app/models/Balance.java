package models;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.*;

import static helpers.TimeHelpers.unixTimestampToDisplayDate;

@Entity
@Table(indexes = @Index(columnList = "userId"))
public class Balance {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;
    public Long timestamp; // this makes this time series data, for graphs and junk
    public Float value;

    @ManyToOne
    @JoinColumn(name="account_id")
    @JsonIgnore
    public Account account;

    @Column(name = "userId")
    public String userId;

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public String getTimestampHumanReadable() {
        return unixTimestampToDisplayDate(timestamp);
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

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }
}
