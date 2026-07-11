package com.roomx.core

import android.content.ContentValues
import android.database.Cursor
import com.roomx.exception.RoomXError
import com.roomx.exception.RoomXErrorType
import com.roomx.exception.RoomXFailureCallback

/**
 * Converts between entity instances and ContentValues/Cursor rows using
 * the reflection-derived TableSpec. Every reflective failure is caught and
 * reported instead of crashing; the offending field is simply skipped.
 */
object EntityMapper {

    fun <T : Any> toContentValues(
        entity: T,
        spec: TableSpec,
        callback: RoomXFailureCallback
    ): ContentValues {
        val values = ContentValues()
        for (col in spec.columns) {
            try {
                val value = col.field.get(entity)
                if (col.isPrimaryKey && col.autoGenerate && (value == null || value == 0 || value == 0L)) {
                    continue // let SQLite assign the rowid
                }
                putValue(values, col.name, value)
            } catch (e: Exception) {
                callback.onFailure(
                    RoomXError(RoomXErrorType.MAPPING_FAILED, "Failed to read field ${col.field.name}", e, spec.tableName)
                )
            }
        }
        return values
    }

    private fun putValue(values: ContentValues, key: String, value: Any?) {
        when (value) {
            null -> values.putNull(key)
            is String -> values.put(key, value)
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            is Short -> values.put(key, value)
            is Boolean -> values.put(key, if (value) 1 else 0)
            is Float -> values.put(key, value)
            is Double -> values.put(key, value)
            is ByteArray -> values.put(key, value)
            is Enum<*> -> values.put(key, value.name)
            else -> values.put(key, value.toString())
        }
    }

    /** Maps the current row of [cursor] onto a new instance of [klass]. Returns null if construction fails. */
    fun <T : Any> fromCursor(
        cursor: Cursor,
        klass: Class<T>,
        spec: TableSpec,
        callback: RoomXFailureCallback
    ): T? {
        return try {
            val instance = instantiate(klass)
            for (col in spec.columns) {
                val idx = cursor.getColumnIndex(col.name)
                if (idx < 0) continue // column missing on this row's cursor (shouldn't happen post-reconcile)
                try {
                    if (cursor.isNull(idx)) {
                        continue
                    }
                    val fieldType = col.field.type
                    val value: Any? = when {
                        fieldType == Int::class.java || fieldType == Integer::class.java -> cursor.getInt(idx)
                        fieldType == Long::class.java || fieldType == java.lang.Long::class.java -> cursor.getLong(idx)
                        fieldType == Short::class.java -> cursor.getShort(idx)
                        fieldType == Boolean::class.java || fieldType == java.lang.Boolean::class.java -> cursor.getInt(idx) != 0
                        fieldType == Float::class.java || fieldType == java.lang.Float::class.java -> cursor.getFloat(idx)
                        fieldType == Double::class.java || fieldType == java.lang.Double::class.java -> cursor.getDouble(idx)
                        fieldType == ByteArray::class.java -> cursor.getBlob(idx)
                        fieldType == String::class.java -> cursor.getString(idx)
                        fieldType.isEnum -> {
                            @Suppress("UNCHECKED_CAST")
                            val enumType = fieldType as Class<out Enum<*>>
                            val raw = cursor.getString(idx)
                            enumType.enumConstants?.firstOrNull { it.name == raw }
                        }
                        else -> cursor.getString(idx)
                    }
                    col.field.set(instance, value)
                } catch (e: Exception) {
                    callback.onFailure(
                        RoomXError(RoomXErrorType.MAPPING_FAILED, "Failed to set field ${col.field.name}", e, spec.tableName)
                    )
                }
            }
            instance
        } catch (e: Exception) {
            callback.onFailure(
                RoomXError(RoomXErrorType.MAPPING_FAILED, "Failed to instantiate ${klass.simpleName}", e, spec.tableName)
            )
            null
        }
    }

    /** Instantiates via no-arg constructor if available, else via the first constructor filled with defaults/nulls. */
    private fun <T : Any> instantiate(klass: Class<T>): T {
        return try {
            val ctor = klass.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        } catch (noArg: NoSuchMethodException) {
            val ctor = klass.declaredConstructors.first()
            ctor.isAccessible = true
            val args = ctor.parameterTypes.map { defaultFor(it) }
            @Suppress("UNCHECKED_CAST")
            ctor.newInstance(*args.toTypedArray()) as T
        }
    }

    private fun defaultFor(type: Class<*>): Any? = when {
        type == Int::class.java -> 0
        type == Long::class.java -> 0L
        type == Short::class.java -> 0.toShort()
        type == Boolean::class.java -> false
        type == Float::class.java -> 0f
        type == Double::class.java -> 0.0
        else -> null
    }
}
