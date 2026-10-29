package modules;

import com.google.inject.AbstractModule;

public class MigrationModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(PotAccountsMigration.class).asEagerSingleton();
        bind(SavingsPlanMigration.class).asEagerSingleton();
        bind(NetWorthAssetMigration.class).asEagerSingleton();
        bind(IncomingMigration.class).asEagerSingleton();
        bind(BankTransactionMigration.class).asEagerSingleton();
        bind(AppSearchMigration.class).asEagerSingleton();
    }
}
