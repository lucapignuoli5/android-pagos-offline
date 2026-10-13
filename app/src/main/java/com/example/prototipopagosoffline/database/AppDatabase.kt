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
        TrustedPublicKey::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    abstract fun transactionDao(): TransactionDao

    abstract fun nfcExchangeDao(): NfcExchangeDao

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
                .addMigrations(MIGRATION_5_6)
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
    }
}
