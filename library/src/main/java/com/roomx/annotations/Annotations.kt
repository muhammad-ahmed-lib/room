package com.roomx.annotations

/**
 * Marks a class as a database table.
 * @param tableName optional override, defaults to class simple name
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Entity(val tableName: String = "")

/**
 * Marks the primary key field of an Entity.
 * @param autoGenerate if true, column is INTEGER PRIMARY KEY AUTOINCREMENT
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class PrimaryKey(val autoGenerate: Boolean = false)

/**
 * Customize column name / type / not-null / default value for a field.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ColumnInfo(
    val name: String = "",
    val typeAffinity: String = "", // "TEXT", "INTEGER", "REAL", "BLOB" - auto-detected if blank
    val notNull: Boolean = false,
    val defaultValue: String = "__none__"
)

/** Field will not be persisted to the database. */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ignore

/** Marks a class as a Data Access Object grouping. Purely organizational in RoomX. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Dao

/** Marks a field as a foreign key reference to another Entity's primary key (informational, enforced softly). */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ForeignKey(
    val parentEntity: kotlin.reflect.KClass<*>,
    val parentColumn: String,
    val onDelete: String = "NO ACTION" // NO ACTION, CASCADE, SET NULL, RESTRICT
)

/** Marks a field to be indexed for faster lookups. */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Indexed(val unique: Boolean = false)
