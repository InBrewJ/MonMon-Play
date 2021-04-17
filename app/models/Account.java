package models;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(indexes = @Index(columnList = "userId"))
public class Account {

    public enum AccountType {
        DEBIT,
        CREDIT,
        LONG_TERM_SAVINGS,
        SHORT_TERM_SAVINGS,
        DEBIT_SHARED_BILLS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    @Column(name = "userId")
    public String userId;

    public String name;
    public String nickname;

    @Enumerated(EnumType.STRING)
    public AccountType type;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "account")
    @Column(nullable = true)
    public List<Balance> balances = new ArrayList<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "account")
    @Column(nullable = true)
    public List<Outgoing> outgoings = new ArrayList<>();

    @Column(name = "account_limit", nullable = true )
    public Float availableLimit = 0f;

    @Column(name = "interest_rate", nullable = true )
    public Float interestRate = 0f;

    public boolean archived = false;

    public List<Balance> getBalances() {
        return balances;
    }

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccountType getType() {
        return type;
    }

    public void setType(AccountType type) {
        this.type = type;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Float getAvailableLimit() {
        return availableLimit;
    }

    public void setAvailableLimit(Float availableLimit) {
        this.availableLimit = availableLimit;
    }

    public Float getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(Float interestRate) {
        this.interestRate = interestRate;
    }
}
