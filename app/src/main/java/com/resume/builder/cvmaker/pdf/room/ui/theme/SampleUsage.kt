/*
package com.resume.builder.cvmaker.pdf.room.ui.theme

import android.content.Context
import android.util.Log
import com.roomx.annotations.ColumnInfo
import com.roomx.annotations.Entity
import com.roomx.annotations.Indexed
import com.roomx.annotations.PrimaryKey
import com.roomx.core.RoomXDao
import com.roomx.core.RoomXDatabase
import com.roomx.exception.RoomXError
import com.roomx.exception.RoomXFailureCallback

// ---------------------------------------------------------------------
// 1. Define an entity. Just a plain class + annotations, no codegen needed.
// ---------------------------------------------------------------------
@Entity(tableName = "users")
class User {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    @ColumnInfo(notNull = true)
    var name: String = ""

    @Indexed(unique = true)
    var email: String = ""

    var age: Int = 0

    // Suppose in v2 of your app you add this new field. RoomX will detect it's
    // a pure addition on next launch and safely ALTER TABLE ADD COLUMN -- no crash,
    // no data loss, no manual Migration object required.
    var phoneNumber: String? = null
}

// ---------------------------------------------------------------------
// 2. Implement a failure callback so you know what happened, but the app
//    itself never crashes -- this is purely for logging/analytics.
// ---------------------------------------------------------------------
class AppFailureCallback : RoomXFailureCallback {
    override fun onFailure(error: RoomXError) {
        Log.e("RoomX", "[${error.type}] ${error.message} table=${error.tableName}", error.cause)
        // e.g. send to Crashlytics/Firebase as a non-fatal, or your own analytics
    }

    override fun onSchemaChange(tableName: String, succeeded: Boolean, details: String) {
        Log.w("RoomX", "Schema change on '$tableName' succeeded=$succeeded: $details")
    }
}

// ---------------------------------------------------------------------
// 3. Define your database by listing entities and bumping dbVersion whenever
//    you change an entity class. RoomX reconciles the real schema automatically
//    on every onCreate/onUpgrade/onOpen -- you never write a Migration class.
// ---------------------------------------------------------------------
class AppDatabase(context: Context) : RoomXDatabase(
    context = context.applicationContext,
    dbName = "app.db",
    dbVersion = 2, // bump this whenever User (or any entity) changes shape
    entities = listOf(User::class.java */
/*, Post::class.java, ... *//*
),
    failureCallback = AppFailureCallback()
)

// ---------------------------------------------------------------------
// 4. Use it. Every call returns a RoomXResult -- handle success/failure,
//    it will never throw an unhandled exception up into your UI code.
// ---------------------------------------------------------------------
class UserRepository(context: Context) {
    private val db = AppDatabase(context)
    private val userDao: RoomXDao<User> = db.daoFor(User::class.java)

    fun addUser(name: String, email: String, age: Int) {
        val user = User().apply {
            this.name = name
            this.email = email
            this.age = age
        }
        userDao.insert(user)
            .onSuccess { newId -> Log.i("RoomX", "Inserted user with id=$newId") }
            .onFailure { error -> Log.e("RoomX", "Insert failed: ${error.message}") }
    }

    fun getAllUsers(): List<User> =
        userDao.getAll().getOrNull() ?: emptyList() // empty list instead of a crash on failure

    fun getUser(id: Long): User? =
        userDao.getByPrimaryKey(id).getOrNull()

    fun renameUser(id: Long, newName: String) {
        val user = getUser(id) ?: return
        user.name = newName
        userDao.updateByPrimaryKey(user)
            .onFailure { error -> Log.e("RoomX", "Update failed: ${error.message}") }
    }

    fun deleteUser(id:Long Long) {
        userDao.delete("id = ?", arrayOf(id.toString()))
    }

    fun searchByName(query: String): List<User> =
        userDao.query("SELECT * FROM users WHERE name LIKE ?", arrayOf("%$query%")).getOrNull() ?: emptyList()
}
*/
