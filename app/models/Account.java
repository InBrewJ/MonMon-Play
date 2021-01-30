package models;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    public String name;
    public String nickname;
    public String type;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "account")
    @Column(nullable = true)
    public List<Balance> balances = new ArrayList<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "account")
    @Column(nullable = true)
    public List<Outgoing> outgoings = new ArrayList<>();

    public List<Balance> getBalances() {
        return balances;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }


}
