package models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SpendingPlanSection {
    private String heading;
    private Float amount = 0.0f;
    private Long accountId;

    public SpendingPlanSection() {
    }

    public SpendingPlanSection(String heading, Float amount, Long accountId) {
        this.heading = heading != null ? heading.trim() : "";
        this.amount = amount != null ? amount : 0.0f;
        this.accountId = (accountId != null && accountId > 0) ? accountId : null;
    }

    public String getHeading() {
        return heading != null ? heading : "";
    }

    public void setHeading(String heading) {
        this.heading = heading != null ? heading.trim() : "";
    }

    public Float getAmount() {
        return amount != null ? amount : 0.0f;
    }

    public void setAmount(Float amount) {
        this.amount = amount != null ? amount : 0.0f;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = (accountId != null && accountId > 0) ? accountId : null;
    }
}
