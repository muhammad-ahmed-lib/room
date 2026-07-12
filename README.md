# RoomX

A lightweight, reflection-based Room-style persistence library for Android/Kotlin.

**Core promise:** schema changes and database errors never crash your app.
No `Migration` objects to hand-write, no KAPT/KSP codegen — just annotate a
class and bump a version number.

---

## Table of Contents

1. [Installation](#1-installation)
2. [Defining an Entity](#2-defining-an-entity)
3. [Annotation Reference](#3-annotation-reference)
4. [Failure Callback](#4-failure-callback)
5. [Defining the Database](#5-defining-the-database)
6. [CRUD Operations](#6-crud-operations)
7. [Schema Migration Behavior](#7-schema-migration-behavior)
8. [Threading](#8-threading)
9. [Troubleshooting](#9-troubleshooting)
10. [Limitations](#10-limitations)

---

## 1. Installation

**Gradle module setup**

`settings.gradle.kts`:
```kotlin
   maven { url=uri("https://jitpack.io") }
```

`app/build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.github.muhammad-ahmed-lib:room:1.0.6")
}
```
---

## 2. Defining an Entity

```kotlin
@Entity(tableName = "users")   // optional, defaults to class name
class User {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    @ColumnInfo(notNull = true)
    var name: String = ""

    @Indexed(unique = true)
    var email: String = ""

    var age: Int = 0

    @Ignore
    var transientCache: String = "" // never persisted
}
```

> **Keep entity classes in a plain Kotlin file, not one containing
> `@Composable` functions.** The Compose compiler injects a synthetic static
> `$stable` field into every class it compiles in a Compose-enabled module.
> RoomX filters out static/synthetic/`$`-named fields automatically, but
> separating your data layer from UI code is still the safer, standard
> practice regardless of which persistence library you use.

---

## 3. Annotation Reference

| Annotation | Target | Purpose |
|---|---|---|
| `@Entity(tableName = "")` | class | Marks a table. Defaults to the class's simple name. |
| `@PrimaryKey(autoGenerate = false)` | field | Marks the primary key. `autoGenerate = true` → `INTEGER PRIMARY KEY AUTOINCREMENT`. |
| `@ColumnInfo(name, typeAffinity, notNull, defaultValue)` | field | Override column name/type, mark `NOT NULL`, set a `DEFAULT`. |
| `@Ignore` | field | Field is skipped entirely — never read/written. |
| `@Indexed(unique = false)` | field | Creates an index (or unique index) for the column. |
| `@ForeignKey(parentEntity, parentColumn, onDelete)` | field | Informational FK metadata (soft-enforced, not hard-checked by SQLite unless you enable `PRAGMA foreign_keys`). |
| `@Dao` | class | Organizational marker if you group DAO-style classes yourself. |

**Supported field types → SQL affinity**

| Kotlin type | SQL affinity |
|---|---|
| `Int`, `Long`, `Short`, `Boolean` | `INTEGER` |
| `Float`, `Double` | `REAL` |
| `String`, `enum` | `TEXT` |
| `ByteArray` | `BLOB` |
| anything else | `TEXT` (via `.toString()`) — handle custom serialization yourself for complex types |

---

## 4. Failure Callback

```kotlin
class AppFailureCallback : RoomXFailureCallback {
    override fun onFailure(error: RoomXError) {
        // error.type: SCHEMA_MISMATCH, MIGRATION_FAILED, QUERY_FAILED,
        //             INSERT_FAILED, UPDATE_FAILED, DELETE_FAILED,
        //             MAPPING_FAILED, DATABASE_LOCKED, UNKNOWN
        Log.e("RoomX", "[${error.type}] ${error.message}", error.cause)
    }

    override fun onSchemaChange(tableName: String, succeeded: Boolean, details: String) {
        Log.w("RoomX", "Schema change on $tableName succeeded=$succeeded: $details")
    }
}
```
If you don't provide one, RoomX uses a silent no-op callback.

---

## 5. Defining the Database

```kotlin
class AppDatabase(context: Context) : RoomXDatabase(
    context = context.applicationContext,
    dbName = "app.db",
    dbVersion = 1,                       // bump any time an entity's shape changes
    entities = listOf(User::class.java, Post::class.java),
    failureCallback = AppFailureCallback()
)
```
You never write a `Migration(1, 2) { ... }` object — just bump `dbVersion`.
RoomX diffs the live on-disk schema against your entity classes on every
`onCreate` / `onUpgrade` / `onDowngrade` / `onOpen` and reconciles automatically.

---

## 6. CRUD Operations

```kotlin
val userDao: RoomXDao<User> = db.daoFor(User::class.java)
```
Every method returns `RoomXResult<T>` (`Success`/`Failure`) — nothing throws.

**Create**
```kotlin
val newUser = User().apply { name = "Ali"; email = "ali@example.com" }
userDao.insert(newUser)
    .onSuccess { id -> println("inserted id=$id") }
    .onFailure { err -> println("insert failed: ${err.message}") }

userDao.insertAll(listOf(user1, user2, user3)) // single transaction
```

**Read**
```kotlin
val all = userDao.getAll().getOrNull() ?: emptyList()
val one = userDao.getByPrimaryKey(5L).getOrNull()
val count = userDao.count().getOrNull() ?: 0L
val results = userDao.query(
    "SELECT * FROM users WHERE name LIKE ?", arrayOf("%ali%")
).getOrNull() ?: emptyList()
```

**Update**
```kotlin
val user = userDao.getByPrimaryKey(5L).getOrNull() ?: return
user.name = "Ali Khan"
userDao.updateByPrimaryKey(user)
    .onSuccess { rows -> println("$rows updated") }
    .onFailure { err -> println("update failed: ${err.message}") }
```

**Delete**
```kotlin
userDao.delete("id = ?", arrayOf("5"))
userDao.deleteAll()
```

---

## 7. Schema Migration Behavior

You change your entity class, bump `dbVersion`, and RoomX handles the rest
the next time the app opens the database:

1. **Table doesn't exist yet** → created fresh from the current entity spec.
2. **Only new fields were added** → safe `ALTER TABLE ADD COLUMN` for each.
   Zero data loss. Reported via `onSchemaChange(table, true, "Added N new column(s)...")`.
3. **Fields removed, renamed, or retyped** → RoomX rebuilds the table:
   creates a temp table with the new shape, copies over every column that
   still matches by name, drops the old table, renames temp → original.
   Columns that no longer match lose their data; everything else survives.
   Reported via `onSchemaChange(table, true, "Rebuilt table; preserved columns: [...]")`.
4. **Anything unexpected during that rebuild** (corruption, lock, disk
   error) → caught, reported via `onFailure(RoomXError(SCHEMA_MISMATCH, ...))`,
   and RoomX falls back to dropping + recreating an empty table with the
   correct schema so the app keeps running instead of crash-looping.

This runs automatically on `onCreate`, `onUpgrade`, `onDowngrade`, and
defensively again on every `onOpen` (catches schema drift from manual edits,
restored backups, etc.) — no manual DB clearing or migration code needed.

---

## 8. Threading

DAO calls are synchronous, same as Room's underlying SQLite calls. Call them
from a background thread/coroutine in production:
```kotlin
viewModelScope.launch(Dispatchers.IO) {
    val result = userDao.insert(user)
    withContext(Dispatchers.Main) { /* update UI with result */ }
}
```

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unresolved reference 'targetSdk'` in library module | `targetSdk` doesn't exist in a library's `defaultConfig` | Remove it — only the `application` module has `targetSdk`. |
| `Cannot add extension with name 'kotlin'` | AGP 9.0 alpha builds have *built-in* Kotlin support and register the `kotlin` extension automatically; applying `org.jetbrains.kotlin.android` on top collides | Either drop to a stable AGP (recommended, see §1) and keep the Kotlin plugin, or on AGP 9 alpha, don't apply the Kotlin plugin explicitly. |
| `Cannot inline bytecode built with JVM target 17 into ... target 11` | Modules disagree on JVM target | Make every module's `compileOptions` match (see §1). |
| `near "$stable": syntax error` on insert | A `@Composable`-containing file injects a synthetic `$stable` field into classes compiled there; if your `@Entity` lives in that file, reflection could pick it up | RoomX filters static/synthetic/`$`-named fields automatically (fixed in current version). Still best practice: keep entities in a plain Kotlin file separate from Compose UI code. |
| App keeps losing data after a schema change | You removed/renamed a field | Expected — RoomX preserves only columns that still match by name during a rebuild; renamed fields are treated as removed old + added new. |

---

## 10. Limitations

- Reflection-based, not compile-time codegen — no compile-time SQL
  validation like Room's KSP processor.
- No built-in `Flow`/`LiveData` observers — wrap DAO calls in your own
  `Flow { emit(dao.getAll()) }` if you need reactive streams.
- Complex/nested object fields fall back to `TEXT` via `.toString()` — handle
  custom serialization in your repository layer if needed.
- Foreign keys are informational only unless you enable
  `PRAGMA foreign_keys` yourself.
