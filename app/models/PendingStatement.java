package models;

import javax.persistence.*;

@Entity
@Table(name = "pending_statement", indexes = {
        @Index(name = "idx_ps_user", columnList = "user_id"),
        @Index(name = "idx_ps_status", columnList = "status")
})
public class PendingStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "job_id")
    private String jobId;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "start_date")
    private String startDate;

    @Column(name = "end_date")
    private String endDate;

    @Column(name = "start_timestamp")
    private Long startTimestamp;

    @Column(name = "end_timestamp")
    private Long endTimestamp;

    @Column(name = "opening_balance")
    private Float openingBalance;

    @Column(name = "closing_balance")
    private Float closingBalance;

    @Column(name = "transaction_count")
    private Integer transactionCount;

    @Column(name = "matched_account_id")
    private Long matchedAccountId;

    @Column(name = "status")
    private String status; // PENDING, APPLIED, DISCARDED, FAILED

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "applied_at")
    private Long appliedAt;

    public PendingStatement() {
    }

    public PendingStatement(String userId, String jobId, String bankName, String startDate, String endDate,
                            Long startTimestamp, Long endTimestamp, Float openingBalance, Float closingBalance,
                            Integer transactionCount, String rawPayload) {
        this.userId = userId;
        this.jobId = jobId;
        this.bankName = bankName;
        this.startDate = startDate;
        this.endDate = endDate;
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.openingBalance = openingBalance;
        this.closingBalance = closingBalance;
        this.transactionCount = transactionCount;
        this.rawPayload = rawPayload;
        this.status = "PENDING";
        this.createdAt = System.currentTimeMillis() / 1000L;
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

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public Long getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(Long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public Long getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(Long endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public Float getOpeningBalance() {
        return openingBalance;
    }

    public void setOpeningBalance(Float openingBalance) {
        this.openingBalance = openingBalance;
    }

    public Float getClosingBalance() {
        return closingBalance;
    }

    public void setClosingBalance(Float closingBalance) {
        this.closingBalance = closingBalance;
    }

    public Integer getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(Integer transactionCount) {
        this.transactionCount = transactionCount;
    }

    public Long getMatchedAccountId() {
        return matchedAccountId;
    }

    public void setMatchedAccountId(Long matchedAccountId) {
        this.matchedAccountId = matchedAccountId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Long appliedAt) {
        this.appliedAt = appliedAt;
    }
}
