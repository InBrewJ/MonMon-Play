package models;

import javax.persistence.*;

@Entity
public class Plan {

    // A lot of this might be a YAGNI thing
    // The idea is that there are many types of plans
    // The 'Plan' thing might act as a base class for
    // other more complex plans like using the savings
    // slider to pay various things off

    // For now, sharing bills and rent would be accomplished
    // by making a BILL_SHARE and a RENT_SHARE plan with a
    // split of '0.5'. On the UI, there will be a box that says:
    // "Bills are shared between N people"
    // "Rent is shared between N people"
    // Plan.split = 1/N

    public enum PlanType {
        BILL_SHARE,
        RENT_SHARE
    }

    public enum PlanScope {
        THIS_MONTH_ONLY,
        TWO_PLUS_MONTH,
        PERMANENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    @Enumerated(EnumType.STRING)
    public PlanType type;

    @Enumerated(EnumType.STRING)
    public PlanScope scope;

    public Float split;

    public String notes;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PlanType getType() {
        return type;
    }

    public void setType(PlanType type) {
        this.type = type;
    }

    public Float getSplit() {
        return split;
    }

    public void setSplit(Float split) {
        this.split = split;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
