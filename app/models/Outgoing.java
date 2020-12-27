package models;

import javax.persistence.*;

@Entity
public class Outgoing {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    public String name;
    public Float cost;
    public int outgoingDay;
    public int fromAccount;

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

    public int getFromAccount() {
        return fromAccount;
    }

    public void setFromAccount(int fromAccount) {
        this.fromAccount = fromAccount;
    }
}
