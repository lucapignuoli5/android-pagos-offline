package com.example.prototipopagosoffline.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        OfflineWallet::class,
        TransactionHistory::class,
        NfcExchange::class,
        TrustedPublicKey::class,
        LocalOfflineAuthorization::class,
        WalletKeyMetadata::class,
        MobileTerminal::class,
        MobilePosOperation::class
    ],
    version = 11,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    abstract fun transactionDao(): TransactionDao

    abstract fun nfcExchangeDao(): NfcExchangeDao

    abstract fun authorizationDao(): AuthorizationDao

    abstract fun mobileTerminalDao(): MobileTerminalDao

    abstract fun mobilePosOperationDao(): MobilePosOperationDao

    companion object {
        private const val DATABASE_NAME = "offline_payments.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .build().also { instance = it }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS nfc_exchanges (operation_id TEXT NOT NULL PRIMARY KEY, request_envelope BLOB NOT NULL, receipt_envelope BLOB, ack_envelope BLOB, state TEXT NOT NULL, created_at_epoch_seconds INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nfc_exchanges_state_created_at_epoch_seconds ON nfc_exchanges(state, created_at_epoch_seconds)")
                db.execSQL("CREATE TABLE IF NOT EXISTS trusted_public_keys (key_id TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, public_key_b64 TEXT NOT NULL, owner_type TEXT NOT NULL, revoked INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_trusted_public_keys_fingerprint ON trusted_public_keys(fingerprint)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE offline_wallet_v7 (id TEXT NOT NULL PRIMARY KEY, confirmed_balance_cents INTEGER NOT NULL, reserved_balance_cents INTEGER NOT NULL, currency TEXT NOT NULL)")
                db.execSQL("INSERT INTO offline_wallet_v7 (id, confirmed_balance_cents, reserved_balance_cents, currency) SELECT id, saldo_disponible, 0, 'ARS' FROM offline_wallet")
                db.execSQL("DROP TABLE offline_wallet")
                db.execSQL("ALTER TABLE offline_wallet_v7 RENAME TO offline_wallet")
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN wallet_id TEXT")
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN authorization_id TEXT")
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN amount_cents INTEGER")
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN merchant_id TEXT")
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN terminal_id TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS local_offline_authorizations (id TEXT NOT NULL PRIMARY KEY, wallet_id TEXT NOT NULL, wallet_key_id TEXT NOT NULL, max_payment_cents INTEGER NOT NULL, remaining_amount_cents INTEGER NOT NULL, remaining_payments INTEGER NOT NULL, issued_at_epoch_seconds INTEGER NOT NULL, expires_at_epoch_seconds INTEGER NOT NULL, active INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_offline_authorizations_wallet_id ON local_offline_authorizations(wallet_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_offline_authorizations_wallet_key_id ON local_offline_authorizations(wallet_key_id)")
                db.execSQL("CREATE TABLE IF NOT EXISTS wallet_key_metadata (key_id TEXT NOT NULL PRIMARY KEY, wallet_id TEXT NOT NULL, keystore_alias TEXT NOT NULL, public_key_b64 TEXT NOT NULL, fingerprint TEXT NOT NULL, created_at_epoch_seconds INTEGER NOT NULL, retired INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wallet_key_metadata_wallet_id ON wallet_key_metadata(wallet_id)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN sync_attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN next_sync_at_epoch_seconds INTEGER")
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN last_sync_code TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nfc_exchanges_next_sync_at_epoch_seconds ON nfc_exchanges(next_sync_at_epoch_seconds)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS mobile_terminals (terminal_id TEXT NOT NULL PRIMARY KEY, merchant_id TEXT NOT NULL, user_id INTEGER NOT NULL, pos_key_id TEXT NOT NULL, pos_key_fingerprint TEXT NOT NULL, keystore_alias TEXT NOT NULL, public_key_b64 TEXT NOT NULL, created_at_epoch_seconds INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_mobile_terminals_user_id ON mobile_terminals(user_id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_mobile_terminals_pos_key_id ON mobile_terminals(pos_key_id)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS mobile_pos_operations (operation_id TEXT NOT NULL PRIMARY KEY, request_envelope BLOB NOT NULL, receipt_envelope BLOB, ack_envelope BLOB, merchant_id TEXT NOT NULL, terminal_id TEXT NOT NULL, wallet_id TEXT, authorization_id TEXT, amount_cents INTEGER NOT NULL, state TEXT NOT NULL, created_at_epoch_seconds INTEGER NOT NULL, sync_attempts INTEGER NOT NULL DEFAULT 0, next_sync_at_epoch_seconds INTEGER, last_sync_code TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mobile_pos_operations_state_created_at_epoch_seconds ON mobile_pos_operations(state, created_at_epoch_seconds)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mobile_pos_operations_next_sync_at_epoch_seconds ON mobile_pos_operations(next_sync_at_epoch_seconds)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN request_expires_at_epoch_seconds INTEGER")
                db.execSQL("ALTER TABLE nfc_exchanges ADD COLUMN cancelled_at_epoch_seconds INTEGER")
                db.execSQL("ALTER TABLE transaction_history ADD COLUMN wallet_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_history_wallet_id ON transaction_history(wallet_id)")
            }
        }
    }
}
