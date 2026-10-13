package com.example.prototipopagosoffline.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationV5ToV7Test {
    @Test
    fun preservesLegacyWalletBalanceAndAddsReservationTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v5-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        try {
            db.execSQL("CREATE TABLE offline_wallet (id TEXT NOT NULL PRIMARY KEY, saldo_disponible INTEGER NOT NULL)")
            db.execSQL("INSERT INTO offline_wallet (id, saldo_disponible) VALUES ('wallet-legacy', 1000000)")
            db.execSQL("CREATE TABLE nfc_exchanges (operation_id TEXT NOT NULL PRIMARY KEY, request_envelope BLOB NOT NULL, receipt_envelope BLOB, ack_envelope BLOB, state TEXT NOT NULL, created_at_epoch_seconds INTEGER NOT NULL)")
            AppDatabase.MIGRATION_5_6.migrate(db)
            AppDatabase.MIGRATION_6_7.migrate(db)

            db.query("SELECT confirmed_balance_cents, reserved_balance_cents, currency FROM offline_wallet WHERE id = 'wallet-legacy'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(1_000_000, cursor.getLong(0))
                assertEquals(0, cursor.getLong(1))
                assertEquals("ARS", cursor.getString(2))
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('local_offline_authorizations', 'wallet_key_metadata') ORDER BY name").use { cursor ->
                var count = 0
                while (cursor.moveToNext()) count++
                assertEquals(2, count)
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
