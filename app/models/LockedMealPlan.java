package models;

import javax.persistence.*;

/**
 * Non-volatile persistent database cache for a user's locked NomMon meal plan.
 * Persists across application restarts and redeployments.
 */
@Entity
@Table(name = "locked_meal_plan", indexes = {
        @Index(columnList = "userId", unique = true)
})
public class LockedMealPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "userId", nullable = false, unique = true)
    private String userId;

    @Column(name = "planJson", columnDefinition = "TEXT", nullable = false)
    private String planJson;

    @Column(name = "weeklyBudget")
    private Float weeklyBudget;

    @Column(name = "people")
    private Integer people;

    @Column(name = "postcode")
    private String postcode;

    @Column(name = "lockedAt")
    private Long lockedAt;

    public LockedMealPlan() {}

    public LockedMealPlan(String userId, String planJson, Float weeklyBudget, Integer people, String postcode, Long lockedAt) {
        this.userId = userId;
        this.planJson = planJson;
        this.weeklyBudget = weeklyBudget;
        this.people = people;
        this.postcode = postcode;
        this.lockedAt = lockedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public Float getWeeklyBudget() {
        return weeklyBudget;
    }

    public void setWeeklyBudget(Float weeklyBudget) {
        this.weeklyBudget = weeklyBudget;
    }

    public Integer getPeople() {
        return people;
    }

    public void setPeople(Integer people) {
        this.people = people;
    }

    public String getPostcode() {
        return postcode;
    }

    public void setPostcode(String postcode) {
        this.postcode = postcode;
    }

    public Long getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Long lockedAt) {
        this.lockedAt = lockedAt;
    }
}
