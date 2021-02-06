package models;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
    public boolean bill = false;
    public boolean rent = false;
    public boolean archived = false;
    public boolean hiddenFromTotal = false;

    @ManyToOne
    @JoinColumn(name="account_id")
    @JsonIgnore
    public Account account;

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
    // God, this needs tests
    public static Float getTotalOutgoings(List<Outgoing> outgoings) {
        return outgoings
                .stream()
                .reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
    }

    public static Float getTotalOutgoingsWithoutHidden(List<Outgoing> outgoings) {
        return outgoings
                .stream()
                .filter(o -> !o.isHiddenFromTotal())
                .reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }
}
