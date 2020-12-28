package models;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
public class Incoming {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;
    public String name;
    public String type; // should be an enum of, say, PAYCHECK | ONEOFF | ..., but not yet
    public Float grossValue;
    public Float netValue;
    public int incomingMonthDay;
    public boolean payDay = false;

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

}
