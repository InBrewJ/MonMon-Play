package models;

import javax.persistence.*;
import java.util.List;

@Entity
@Table(indexes = @Index(columnList = "userId"))
public class Incoming {
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

    public static Float getTotalIncomings(List<Incoming> incomings) {
        if (incomings.isEmpty()) return 0f;
        return incomings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.netValue, Float::sum);
    }

}
