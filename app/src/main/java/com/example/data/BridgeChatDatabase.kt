package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.ContactDao
import com.example.data.dao.MeshStatsDao
import com.example.data.dao.MessageDao
import com.example.data.dao.QueueDao
import com.example.data.dao.UserDao
import com.example.data.entity.ContactEntity
import com.example.data.entity.MeshStatsEntity
import com.example.data.entity.MessageEntity
import com.example.data.entity.QueuedPacketEntity
import com.example.data.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        ContactEntity::class,
        MessageEntity::class,
        QueuedPacketEntity::class,
        MeshStatsEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BridgeChatDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun queueDao(): QueueDao
    abstract fun meshStatsDao(): MeshStatsDao

    companion object {
        @Volatile
        private var INSTANCE: BridgeChatDatabase? = null

        fun getInstance(context: Context): BridgeChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BridgeChatDatabase::class.java,
                    "bridgechat_mesh.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
