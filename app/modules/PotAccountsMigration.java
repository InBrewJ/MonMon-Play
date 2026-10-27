package modules;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * One-off, idempotent startup migration.
 *
 * Pots used to own accounts via a single account.pot_id column (OneToMany), which meant
 * an account could only ever belong to one pot - creating a new monthly pot silently
 * stole the accounts from the previous one, destroying history.
 *
 * Pots now reference accounts via the pot_accounts join table (ManyToMany), so archived
 * (soft-deleted) pot configs keep their accounts and can be used for historic 'Spent this month'.
 *
 * This copies any legacy account.pot_id links into pot_accounts. Safe to run repeatedly.
 */
@Singleton
public class PotAccountsMigration {

    @Inject
    public PotAccountsMigration(JPAApi jpaApi) {
        try {
            int copied = jpaApi.withTransaction((java.util.function.Function<javax.persistence.EntityManager, Integer>) em -> em.createNativeQuery(
                    "INSERT INTO pot_accounts (pot_id, account_id) " +
                    "SELECT a.pot_id, a.id FROM account a " +
                    "WHERE a.pot_id IS NOT NULL " +
                    "AND NOT EXISTS (SELECT 1 FROM pot_accounts pa WHERE pa.pot_id = a.pot_id AND pa.account_id = a.id)"
            ).executeUpdate());
            System.out.println("PotAccountsMigration :: copied " + copied + " legacy pot/account links");
        } catch (Exception e) {
            // Fresh databases won't have the legacy account.pot_id column - nothing to migrate
            System.out.println("PotAccountsMigration :: skipped (" + e.getClass().getSimpleName() + ")");
        }
    }
}
