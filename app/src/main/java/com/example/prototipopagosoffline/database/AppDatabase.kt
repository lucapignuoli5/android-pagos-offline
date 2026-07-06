package com.example.prototipopagosoffline.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        OfflineWallet::class,
        TransactionHistory::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    abstract fun transactionDao(): TransactionDao

    companion object {
        private const val DATABASE_NAME = "offline_payments.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                val tokenManager = com.example.prototipopagosoffline.utils.TokenManager(context)
                val factory = net.sqlcipher.database.SupportFactory(tokenManager.getDatabaseKey())

                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
        }
    }
}
