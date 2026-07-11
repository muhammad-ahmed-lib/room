package com.roomx.core

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.roomx.annotations.ColumnInfo
import com.roomx.annotations.Entity
import com.roomx.annotations.Ignore
import com.roomx.annotations.Indexed
import com.roomx.annotations.PrimaryKey
import com.roomx.exception.RoomXError
import com.roomx.exception.RoomXErrorType
import com.roomx.exception.RoomXFailureCallback
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger

/** Describes one column as derived from a Kotlin field via reflection. */
data class ColumnSpec(
    val field: Field,
    val name: String,
    val sqlType: String,
    val isPrimaryKey: Boolean,
    val autoGenerate: Boolean,
    val notNull: Boolean,
    val defaultValue: String?,
    val indexed: Boolean,
    val unique: Boolean
)

/** Full spec for one Entity class, resolved once and cached. */
data class TableSpec(
    val klass: Class<*>,
    val tableName: String,
    val columns: List<ColumnSpec>
) {
    val primaryKey: ColumnSpec? = columns.firstOrNull { it.isPrimaryKey }
}

/**
 * Builds TableSpecs from annotated classes, generates SQL, inspects the
 * live database schema via PRAGMA, and reconciles differences WITHOUT
 * ever throwing — any mismatch is either auto-healed (add column / recreate
 * with data preserved) or safely reported through the failure callback.
 */
object SchemaManager {

    private val specCache = HashMap<Class<*>, TableSpec>()

    fun tableSpecOf(klass: Class<*>): TableSpec {
        specCache[klass]?.let { return it }

        val entityAnn = klass.getAnnotation(Entity::class.java)
            ?: throw IllegalArgumentException("${klass.simpleName} is not annotated with @Entity")

        val tableName = entityAnn.tableName.ifBlank { klass.simpleName }

        val columns = klass.declaredFields
            .filter { it.getAnnotation(Ignore::class.java) == null }
            .filter { !it.isSynthetic }
            .filter { !Modifier.isStatic(it.modifiers) }      // excludes Compose's injected `$stable`, companion statics, etc.
            .filter { !Modifier.isTransient(it.modifiers) }
            .filter { !it.name.contains('$') }                 // excludes any compiler-generated field like $stable, $delegate, etc.
            .map { field ->
                field.isAccessible = true
                val colInfo = field.getAnnotation(ColumnInfo::class.java)
                val pk = field.getAnnotation(PrimaryKey::class.java)
                val idx = field.getAnnotation(Indexed::class.java)

                val colName = colInfo?.name?.takeIf { it.isNotBlank() } ?: field.name
                val sqlType = colInfo?.typeAffinity?.takeIf { it.isNotBlank() }
                    ?: sqlTypeFor(field.type)

                ColumnSpec(
                    field = field,
                    name = colName,
                    sqlType = sqlType,
                    isPrimaryKey = pk != null,
                    autoGenerate = pk?.autoGenerate ?: false,
                    notNull = colInfo?.notNull ?: (pk != null),
                    defaultValue = colInfo?.defaultValue?.takeIf { it != "__none__" },
                    indexed = idx != null,
                    unique = idx?.unique ?: false
                )
            }

        val spec = TableSpec(klass, tableName, columns)
        specCache[klass] = spec
        return spec
    }

    private fun sqlTypeFor(type: Class<*>): String = when {
        type == Int::class.java || type == Integer::class.java -> "INTEGER"
        type == Long::class.java || type == java.lang.Long::class.java -> "INTEGER"
        type == Short::class.java -> "INTEGER"
        type == Boolean::class.java || type == java.lang.Boolean::class.java -> "INTEGER"
        type == Float::class.java || type == java.lang.Float::class.java -> "REAL"
        type == Double::class.java || type == java.lang.Double::class.java -> "REAL"
        type == BigDecimal::class.java || type == BigInteger::class.java -> "TEXT"
        type == ByteArray::class.java -> "BLOB"
        type == String::class.java -> "TEXT"
        type.isEnum -> "TEXT"
        else -> "TEXT" // fallback: unsupported complex types stored as TEXT (caller should provide TypeConverter)
    }

    fun createTableSql(spec: TableSpec): String {
        val cols = spec.columns.joinToString(", ") { c ->
            val sb = StringBuilder("`${c.name}` ${c.sqlType}")
            if (c.isPrimaryKey) {
                sb.append(" PRIMARY KEY")
                if (c.autoGenerate) sb.append(" AUTOINCREMENT")
            }
            if (c.notNull && !c.isPrimaryKey) sb.append(" NOT NULL")
            c.defaultValue?.let { sb.append(" DEFAULT $it") }
            sb.toString()
        }
        return "CREATE TABLE IF NOT EXISTS `${spec.tableName}` ($cols);"
    }

    fun indexSql(spec: TableSpec): List<String> =
        spec.columns.filter { it.indexed }.map { c ->
            val unique = if (c.unique) "UNIQUE " else ""
            "CREATE ${unique}INDEX IF NOT EXISTS `idx_${spec.tableName}_${c.name}` ON `${spec.tableName}` (`${c.name}`);"
        }

    /** Returns the set of column names currently present in the on-disk table, or null if table doesn't exist. */
    private fun liveColumns(db: SQLiteDatabase, tableName: String): List<String>? {
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery("PRAGMA table_info(`$tableName`)", null)
            if (!cursor.moveToFirst()) return null
            val nameIdx = cursor.getColumnIndex("name")
            val result = ArrayList<String>()
            do {
                result.add(cursor.getString(nameIdx))
            } while (cursor.moveToNext())
            result
        } catch (e: SQLiteException) {
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * The core "never crash on schema change" routine.
     *
     * Strategy:
     * 1. If table doesn't exist -> create it fresh. Done.
     * 2. If table exists and columns match exactly -> nothing to do.
     * 3. If only NEW columns were added to the entity -> ALTER TABLE ADD COLUMN for each (safe, keeps data).
     * 4. If columns were removed/renamed/retyped (i.e. an ADD COLUMN-only migration isn't enough) ->
     *    rebuild the table: create a temp table with the new schema, copy over any columns that still
     *    match by name, drop the old table, rename temp -> original. Data in dropped/renamed columns is lost,
     *    but the app never crashes and all *matching* data survives.
     * 5. Any unexpected SQLiteException at any step is swallowed and reported via callback; RoomX will
     *    fall back to an empty table of the correct new schema rather than propagate the exception.
     */
    fun reconcile(
        db: SQLiteDatabase,
        spec: TableSpec,
        callback: RoomXFailureCallback
    ) {
        try {
            val existingCols = liveColumns(db, spec.tableName)

            if (existingCols == null) {
                db.execSQL(createTableSql(spec))
                indexSql(spec).forEach { db.execSQL(it) }
                return
            }

            val desiredCols = spec.columns.map { it.name }
            val existingSet = existingCols.toSet()
            val desiredSet = desiredCols.toSet()

            if (existingSet == desiredSet) {
                // Columns match by name; assume compatible. Ensure indexes exist too.
                indexSql(spec).forEach { runCatching { db.execSQL(it) } }
                return
            }

            val onlyAdditions = existingSet.all { it in desiredSet }

            if (onlyAdditions) {
                // Safe path: just ADD COLUMN for every new field, no data loss at all.
                val newCols = spec.columns.filter { it.name !in existingSet }
                newCols.forEach { c ->
                    val defClause = c.defaultValue?.let { " DEFAULT $it" }
                        ?: if (c.notNull) " DEFAULT ${defaultLiteralFor(c.sqlType)}" else ""
                    try {
                        db.execSQL("ALTER TABLE `${spec.tableName}` ADD COLUMN `${c.name}` ${c.sqlType}$defClause;")
                    } catch (e: SQLiteException) {
                        callback.onFailure(
                            RoomXError(RoomXErrorType.MIGRATION_FAILED, "Failed to add column ${c.name}", e, spec.tableName)
                        )
                    }
                }
                indexSql(spec).forEach { runCatching { db.execSQL(it) } }
                callback.onSchemaChange(spec.tableName, true, "Added ${newCols.size} new column(s) without data loss")
                return
            }

            // Columns were removed or renamed/retyped: do a safe rebuild-and-copy.
            val tempTable = "${spec.tableName}_roomx_migrate_${System.currentTimeMillis()}"
            db.beginTransaction()
            try {
                db.execSQL(createTableSql(spec).replace(
                    "`${spec.tableName}`", "`$tempTable`"
                ))

                val commonCols = existingSet.intersect(desiredSet)
                if (commonCols.isNotEmpty()) {
                    val colList = commonCols.joinToString(", ") { "`$it`" }
                    db.execSQL(
                        "INSERT INTO `$tempTable` ($colList) SELECT $colList FROM `${spec.tableName}`;"
                    )
                }

                db.execSQL("DROP TABLE `${spec.tableName}`;")
                db.execSQL("ALTER TABLE `$tempTable` RENAME TO `${spec.tableName}`;")

                indexSql(spec).forEach { runCatching { db.execSQL(it) } }

                db.setTransactionSuccessful()
                callback.onSchemaChange(
                    spec.tableName, true,
                    "Rebuilt table; preserved columns: $commonCols. Removed/renamed columns lost their data."
                )
            } catch (e: SQLiteException) {
                callback.onFailure(
                    RoomXError(RoomXErrorType.SCHEMA_MISMATCH, "Rebuild migration failed for ${spec.tableName}", e, spec.tableName)
                )
                callback.onSchemaChange(spec.tableName, false, "Rebuild failed: ${e.message}")
                // Last-resort fallback so the app can still run: force-create an empty table with correct schema.
                runCatching {
                    db.execSQL("DROP TABLE IF EXISTS `${spec.tableName}`;")
                    db.execSQL(createTableSql(spec))
                    indexSql(spec).forEach { runCatching { db.execSQL(it) } }
                }
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        } catch (e: Exception) {
            // Absolute safety net: whatever happens, RoomX must not crash the host app.
            callback.onFailure(
                RoomXError(RoomXErrorType.UNKNOWN, "Unexpected error reconciling schema for ${spec.tableName}", e, spec.tableName)
            )
            runCatching {
                db.execSQL("CREATE TABLE IF NOT EXISTS `${spec.tableName}` (${spec.columns.joinToString(", ") { "`${it.name}` ${it.sqlType}" }});")
            }
        }
    }

    private fun defaultLiteralFor(sqlType: String): String = when (sqlType) {
        "INTEGER" -> "0"
        "REAL" -> "0.0"
        "TEXT" -> "''"
        "BLOB" -> "x''"
        else -> "''"
    }
}
