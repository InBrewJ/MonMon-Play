package models;

import javax.persistence.*;
import java.util.List;

@Entity
public class Outgoing {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    public String name;
    public Float cost;
    public int outgoingDay;
    public int fromAccount;
    public boolean bill = false;
    public boolean rent = false;

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

    public int getFromAccount() {
        return fromAccount;
    }

    public void setFromAccount(int fromAccount) {
        this.fromAccount = fromAccount;
    }

    public static Float getTotalOutgoings(List<Outgoing> outgoings) {
        return outgoings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
    }
}
