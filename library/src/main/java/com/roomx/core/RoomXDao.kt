package com.roomx.core

import android.database.Cursor
import android.database.sqlite.SQLiteException
import com.roomx.exception.RoomXError
import com.roomx.exception.RoomXErrorType
import com.roomx.exception.RoomXFailureCallback
import com.roomx.exception.RoomXResult

/**
 * Generic, reflection-driven DAO. Every method returns a [RoomXResult] instead
 * of throwing -- so a bad query, a locked database, or a schema mismatch that
 * slipped through never brings down the calling code. All failures are also
 * reported to the [RoomXFailureCallback] registered on the database.
 */
class RoomXDao<T : Any>(
    private val klass: Class<T>,
    private val database: RoomXDatabase,
    private val callback: RoomXFailureCallback
) {
    private val spec: TableSpec = SchemaManager.tableSpecOf(klass)

    /** Insert one row. Returns the new rowid on success. */
    fun insert(entity: T): RoomXResult<Long> = database.safeWrite(
        RoomXResult.Failure(RoomXError(RoomXErrorType.INSERT_FAILED, "insert failed before execution", null, spec.tableName))
    ) { db ->
        try {
            val values = EntityMapper.toContentValues(entity, spec, callback)
            val id = db.insertOrThrow(spec.tableName, null, values)
            RoomXResult.Success(id)
        } catch (e: SQLiteException) {
            val err = RoomXError(RoomXErrorType.INSERT_FAILED, e.message ?: "insert failed", e, spec.tableName)
            callback.onFailure(err)
            RoomXResult.Failure(err)
        }
    }

    /** Insert many rows in a single transaction. Returns list of new rowids for the ones that succeeded. */
    fun insertAll(entities: List<T>): RoomXResult<List<Long>> = database.safeWrite(
        RoomXResult.Failure(RoomXError(RoomXErrorType.INSERT_FAILED, "batch insert failed before execution", null, spec.tableName))
    ) { db ->
        val ids = ArrayList<Long>()
        db.beginTransaction()
        try {
            for (entity in entities) {
                val values = EntityMapper.toContentValues(entity, spec, callback)
                val id = db.insertWithOnConflict(spec.tableName, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT)
                if (id != -1L) ids.add(id)
            }
            db.setTransactionSuccessful()
            RoomXResult.Success(ids)
        } catch (e: SQLiteException) {
            val err = RoomXError(RoomXErrorType.INSERT_FAILED, e.message ?: "batch insert failed", e, spec.tableName)
            callback.onFailure(err)
            RoomXResult.Failure(err)
        } finally {
            if (db.inTransaction()) db.endTransaction()
        }
    }

    /** Update rows matching [whereClause] (e.g. "id = ?") with [whereArgs]. Returns number of rows updated. */
    fun update(entity: T, whereClause: String, whereArgs: Array<String>): RoomXResult<Int> = database.safeWrite(
        RoomXResult.Failure(RoomXError(RoomXErrorType.UPDATE_FAILED, "update failed before execution", null, spec.tableName))
    ) { db ->
        try {
            val values = EntityMapper.toContentValues(entity, spec, callback)
            val rows = db.update(spec.tableName, values, whereClause, whereArgs)
            RoomXResult.Success(rows)
        } catch (e: SQLiteException) {
            val err = RoomXError(RoomXErrorType.UPDATE_FAILED, e.message ?: "update failed", e, spec.tableName)
            callback.onFailure(err)
            RoomXResult.Failure(err)
        }
    }

    /** Convenience update-by-primary-key, using the entity's own @PrimaryKey field value. */
    fun updateByPrimaryKey(entity: T): RoomXResult<Int> {
        val pk = spec.primaryKey
            ?: return RoomXResult.Failure(RoomXError(RoomXErrorType.UPDATE_FAILED, "No @PrimaryKey defined on ${spec.tableName}", null, spec.tableName))
        val pkValue = try {
            pk.field.get(entity)
        } catch (e: Exception) {
            callback.onFailure(RoomXError(RoomXErrorType.UPDATE_FAILED, "Cannot read primary key value", e, spec.tableName))
            return RoomXResult.Failure(RoomXError(RoomXErrorType.UPDATE_FAILED, "Cannot read primary key value", e, spec.tableName))
        }
        return update(entity, "`${pk.name}` = ?", arrayOf(pkValue.toString()))
    }

    /** Delete rows matching [whereClause]. Returns number of rows deleted. */
    fun delete(whereClause: String, whereArgs: Array<String>): RoomXResult<Int> = database.safeWrite(
        RoomXResult.Failure(RoomXError(RoomXErrorType.DELETE_FAILED, "delete failed before execution", null, spec.tableName))
    ) { db ->
        try {
            val rows = db.delete(spec.tableName, whereClause, whereArgs)
            RoomXResult.Success(rows)
        } catch (e: SQLiteException) {
            val err = RoomXError(RoomXErrorType.DELETE_FAILED, e.message ?: "delete failed", e, spec.tableName)
            callback.onFailure(err)
            RoomXResult.Failure(err)
        }
    }

    fun deleteAll(): RoomXResult<Int> = delete("1 = 1", arrayOf())

    /** Fetch all rows. */
    fun getAll(): RoomXResult<List<T>> = query("SELECT * FROM `${spec.tableName}`", arrayOf())

    /** Fetch a single row by primary key. */
    fun getByPrimaryKey(id: Any): RoomXResult<T?> {
        val pk = spec.primaryKey
            ?: return RoomXResult.Failure(RoomXError(RoomXErrorType.QUERY_FAILED, "No @PrimaryKey defined on ${spec.tableName}", null, spec.tableName))
        return when (val res = query("SELECT * FROM `${spec.tableName}` WHERE `${pk.name}` = ? LIMIT 1", arrayOf(id.toString()))) {
            is RoomXResult.Success -> RoomXResult.Success(res.data.firstOrNull())
            is RoomXResult.Failure -> res
        }
    }

    /** Raw SQL query mapped back to entities. Never throws. */
    fun query(sql: String, args: Array<String>): RoomXResult<List<T>> = database.safeRead(
        RoomXResult.Failure(RoomXError(RoomXErrorType.QUERY_FAILED, "query failed before execution", null, spec.tableName))
    ) { db ->
        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery(sql, args)
            val results = ArrayList<T>()
            while (cursor.moveToNext()) {
                EntityMapper.fromCursor(cursor, klass, spec, callback)?.let { results.add(it) }
            }
            RoomXResult.Success(results)
        } catch (e: SQLiteException) {
            val err = RoomXError(RoomXErrorType.QUERY_FAILED, e.message ?: "query failed", e, spec.tableName)
            callback.onFailure(err)
            RoomXResult.Failure(err)
        } finally {
            cursor?.close()
        }
    }

    /** Count rows matching an optional where clause. */
    fun count(whereClause: String? = null, whereArgs: Array<String> = arrayOf()): RoomXResult<Long> = database.safeRead(
        RoomXResult.Failure(RoomXError(RoomXErrorType.QUERY_FAILED, "count failed before execution", null, spec.tableName))
    ) { db ->
        var cursor: Cursor? = null
        try {
            val sql = "SELECT COUNT(*) FROM `${spec.tableName}`" + (whereClause?.let { " WHERE $it" } ?: "")
            cursor = db.rawQuery(sql, whereArgs)
            val count = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            RoomXResult.Success(count)
        } catch (e: SQLiteException) {
            val err = RoomXError(RoomXErrorType.QUERY_FAILED, e.message ?: "count failed", e, spec.tableName)
            callback.onFailure(err)
            RoomXResult.Failure(err)
        } finally {
            cursor?.close()
        }
    }
}
