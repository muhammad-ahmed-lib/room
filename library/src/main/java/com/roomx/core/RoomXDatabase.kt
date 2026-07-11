package com.roomx.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.roomx.exception.NoOpFailureCallback
import com.roomx.exception.RoomXError
import com.roomx.exception.RoomXErrorType
import com.roomx.exception.RoomXFailureCallback

/**
 * Base class for a RoomX database. Subclass this, list your @Entity classes
 * in [entities], and RoomX will create/reconcile every table's schema safely
 * on every open -- including hot schema changes across app updates -- without
 * ever crashing. Register a [RoomXFailureCallback] to be told what happened.
 *
 * Usage:
 * ```
 * class AppDatabase(context: Context) : RoomXDatabase(
 *     context = context,
 *     dbName = "app.db",
 *     dbVersion = 1,
 *     entities = listOf(User::class.java, Post::class.java),
 *     failureCallback = MyFailureCallback()
 * )
 * ```
 */
abstract class RoomXDatabase(
    context: Context,
    dbName: String,
    dbVersion: Int,
    private val entities: List<Class<*>>,
    private val failureCallback: RoomXFailureCallback = NoOpFailureCallback
) : SQLiteOpenHelper(context, dbName, null, dbVersion) {

    private val specs: List<TableSpec> by lazy {
        entities.map { SchemaManager.tableSpecOf(it) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        reconcileAll(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Instead of the classic "DROP everything and recreate" that Room's
        // fallbackToDestructiveMigration does, RoomX always reconciles
        // per-table, preserving as much data as structurally possible.
        reconcileAll(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never let a downgrade crash the app either.
        reconcileAll(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Defensive re-check every open: catches cases where the on-disk schema
        // drifted outside of RoomX's control (manual edits, restored backups, etc.)
        try {
            if (!db.isReadOnly) {
                reconcileAll(db)
            }
        } catch (e: SQLiteException) {
            failureCallback.onFailure(
                RoomXError(RoomXErrorType.UNKNOWN, "onOpen reconcile failed", e)
            )
        }
    }

    private fun reconcileAll(db: SQLiteDatabase) {
        for (spec in specs) {
            SchemaManager.reconcile(db, spec, failureCallback)
        }
    }

    /** Returns a generic DAO for the given entity type, bound to this database. */
    fun <T : Any> daoFor(klass: Class<T>): RoomXDao<T> =
        RoomXDao(klass, this, failureCallback)

    /** Safe wrapper: never throws. Runs [block] with a writable database, catching everything. */
    internal fun <R> safeWrite(default: R, block: (SQLiteDatabase) -> R): R {
        return try {
            block(writableDatabase)
        } catch (e: Exception) {
            failureCallback.onFailure(
                RoomXError(RoomXErrorType.UNKNOWN, "Unhandled error during write operation", e)
            )
            default
        }
    }

    internal fun <R> safeRead(default: R, block: (SQLiteDatabase) -> R): R {
        return try {
            block(readableDatabase)
        } catch (e: Exception) {
            failureCallback.onFailure(
                RoomXError(RoomXErrorType.UNKNOWN, "Unhandled error during read operation", e)
            )
            default
        }
    }
}
