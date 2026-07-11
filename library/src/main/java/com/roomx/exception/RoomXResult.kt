package com.roomx.exception

/**
 * RoomX never lets a schema or CRUD failure crash the app.
 * Every operation returns a RoomXResult instead of throwing, and every
 * failure is also routed to the registered RoomXFailureCallback.
 */
sealed class RoomXResult<out T> {
    data class Success<out T>(val data: T) : RoomXResult<T>()
    data class Failure(val error: RoomXError) : RoomXResult<Nothing>()

    inline fun onSuccess(block: (T) -> Unit): RoomXResult<T> {
        if (this is Success) block(data)
        return this
    }

    inline fun onFailure(block: (RoomXError) -> Unit): RoomXResult<T> {
        if (this is Failure) block(error)
        return this
    }

    fun getOrNull(): T? = (this as? Success)?.data
}

enum class RoomXErrorType {
    SCHEMA_MISMATCH,
    MIGRATION_FAILED,
    QUERY_FAILED,
    INSERT_FAILED,
    UPDATE_FAILED,
    DELETE_FAILED,
    MAPPING_FAILED,
    DATABASE_LOCKED,
    UNKNOWN
}

data class RoomXError(
    val type: RoomXErrorType,
    val message: String,
    val cause: Throwable? = null,
    val tableName: String? = null
)

/**
 * Callback interface the host app implements to be notified of any
 * failure RoomX absorbed instead of crashing on.
 */
interface RoomXFailureCallback {
    /** Called whenever RoomX catches an exception it would otherwise have thrown. */
    fun onFailure(error: RoomXError) {}

    /**
     * Called specifically when a schema mismatch is detected between the
     * entity class and the on-disk table (e.g. a field was added/removed/renamed
     * without a proper migration). RoomX will attempt a safe auto-migration;
     * this is called whether that auto-migration succeeded or not.
     */
    fun onSchemaChange(tableName: String, succeeded: Boolean, details: String) {}
}

/** Default no-op callback used if the app doesn't provide one. */
object NoOpFailureCallback : RoomXFailureCallback
