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

    @Column(name = "userId")
    public String userId;

    @Enumerated(EnumType.STRING)
    public PlanType type;

    @Enumerated(EnumType.STRING)
    public PlanScope scope;

    public Float split;
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
}
