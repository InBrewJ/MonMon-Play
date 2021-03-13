package models;

import javax.persistence.*;

@Entity
@Table(indexes = @Index(columnList = "userId"))
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

    // For 'MONTHLY_SAVINGS_GOAL' it'd be ideal
    // if we had some sort of list of 'elements' in a 'plan'
    // 'elements' could be some sort of configurable thing that
    // could be used to plan what to do with the 'cost' of a plan
    //
    // For a simple way to decide how to allocate the MONTHLY_SAVINGS_GOAL,
    // the element would have type 'outgoing' which would add extra outgoings
    // as 'isHiddenFromTotal' or something. The ids of the 'outgoing' could
    // be saved in the 'elements' of the plan
    //
    // There might also be a way to automatically allocate 'outgoing' 'elements'
    // from a MONTHLY_SAVINGS_GOAL by the 'priority' of the 'element', e.g. to
    // say things like 'I know I want to spend all of this money on savings, and I
    // want the most money to go into this account, so give this element a high priority
    // and adjust the cost of the other elements automatically
    //
    // 'elements' could also be 'things' you want to save for. This could pave the way
    // for something like the 'Stuff for house' tab on the MonMon sheet. But this
    // is just speculation. And over-speculation at that...

    public enum PlanType {
        BILL_SHARE,
        RENT_SHARE,
        MONTHLY_SAVINGS_GOAL
    }

    public enum PlanScope {
        THIS_MONTH_ONLY,
        TWO_PLUS_MONTH,
        PERMANENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    @Column(name = "userId")
    public String userId;

    @Enumerated(EnumType.STRING)
    public PlanType type;

    @Enumerated(EnumType.STRING)
    public PlanScope scope;

    public Float split;
    public Float cost;
    public String notes;
    public boolean archived = false;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

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

    public PlanScope getScope() {
        return scope;
    }

    public void setScope(PlanScope scope) {
        this.scope = scope;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Float getCost() {
        return cost;
    }

    public void setCost(Float cost) {
        this.cost = cost;
    }
}
