package com.resume.builder.cvmaker.pdf.room.ui.theme

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roomx.annotations.ColumnInfo
import com.roomx.annotations.Entity
import com.roomx.annotations.Ignore
import com.roomx.annotations.PrimaryKey
import com.roomx.core.RoomXDao
import com.roomx.core.RoomXDatabase
import com.roomx.exception.RoomXError
import com.roomx.exception.RoomXFailureCallback

private const val TAG = "RoomX_Demo"

// ---------------------------------------------------------------------
// 1. Entity
// ---------------------------------------------------------------------
@Entity(tableName = "users")
class User {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    @ColumnInfo(notNull = true)
    var name: String = ""

    @ColumnInfo(notNull = true,defaultValue = "0")
    var number: Int = 0
    @Ignore
    var transientCache: String = ""
    override fun toString(): String {
        return "User(id=$id, name='$name', number='$number')"
    }
}

// ---------------------------------------------------------------------
// 2. Failure callback (schema changes / errors are logged, never crash)
// ---------------------------------------------------------------------
class AppFailureCallback : RoomXFailureCallback {
    override fun onFailure(error: RoomXError) {
        Log.e(TAG, "❌ [${error.type}] ${error.message} table=${error.tableName}", error.cause)
        Log.e(TAG, "   Stack trace:", error.cause)
    }

    override fun onSchemaChange(tableName: String, succeeded: Boolean, details: String) {
        Log.w(TAG, "🔄 Schema change on '$tableName' succeeded=$succeeded: $details")
    }
}

// ---------------------------------------------------------------------
// 3. Database
// ---------------------------------------------------------------------
class AppDatabase(context: Context) : RoomXDatabase(
    context = context.applicationContext,
    dbName = "app.db",
    dbVersion = 2,
    entities = listOf(User::class.java),
    failureCallback = AppFailureCallback()
) {
    init {
        Log.d(TAG, "🏗️ AppDatabase initialized with version 1")
    }
}

// ---------------------------------------------------------------------
// 4. Activity wiring up a full CRUD screen against RoomX
// ---------------------------------------------------------------------
class MainActivity : ComponentActivity() {

    private lateinit var userDao: RoomXDao<User>
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "🚀 MainActivity onCreate started")
        enableEdgeToEdge()

        try {
            Log.d(TAG, "📦 Creating AppDatabase instance...")
            db = AppDatabase(this)
            Log.d(TAG, "✅ AppDatabase created successfully")

            Log.d(TAG, "📋 Getting DAO for User class...")
            userDao = db.daoFor(User::class.java)
            Log.d(TAG, "✅ DAO obtained: ${userDao::class.java.simpleName}")

            // Test database connection
            Log.d(TAG, "🧪 Testing database connection...")
            try {
                val testResult = userDao.getAll()
                Log.d(TAG, "✅ Database connection test passed. Result: $testResult")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Database connection test failed", e)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize database or DAO", e)
            throw e
        }

        setContent {
            Log.d(TAG, "🎨 Setting Compose content")
            RoomTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UserCrudScreen(
                        dao = userDao,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        Log.d(TAG, "✅ MainActivity onCreate completed")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "♻️ MainActivity onDestroy - closing database")
        try {
            db.close()
            Log.d(TAG, "✅ Database closed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error closing database", e)
        }
    }
}

@Composable
fun UserCrudScreen(dao: RoomXDao<User>, modifier: Modifier = Modifier) {
    Log.d(TAG, "🖥️ UserCrudScreen composable rendered")

    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf(0) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var users by remember { mutableStateOf(emptyList<User>()) }

    fun refresh() {
        Log.d(TAG, "🔄 Refreshing user list...")
        try {
            val result = dao.getAll()
            Log.d(TAG, "📊 getAll() result: $result")
            result.onSuccess {
                users = result.getOrNull() ?: emptyList()
                Log.d(TAG, "✅ Loaded ${users.size} users")
                users.forEachIndexed { index, user ->
                    Log.d(TAG, "   User $index: $user")
                }
            }
            result.onFailure {

            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during refresh", e)
            statusMessage = "Error loading users: ${e.message}"
        }
    }

    // Initial load
    Log.d(TAG, "📥 Performing initial user list load")
    refresh()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = if (editingId == null) "Add User" else "Edit User #$editingId",
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                Log.d(TAG, "✏️ Name changed: '$it'")
            },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        OutlinedTextField(
            value = number.toString(),
            onValueChange = {
                number = it.toInt()
                Log.d(TAG, "✏️ Email changed: '$it'")
            },
            label = { Text("Number") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---------------- CREATE / UPDATE ----------------
            Button(onClick = {
                Log.d(TAG, "🔄 Button clicked: ${if (editingId == null) "ADD" else "UPDATE"}")

                if (name.isBlank()) {
                    Log.w(TAG, "⚠️ Validation failed: name or email is blank")
                    statusMessage = "Name and email are required"
                    return@Button
                }

                Log.d(TAG, "📝 Creating/updating user with name='$name'")
                val currentId = editingId
                if (currentId == null) {
                    // CREATE
                    Log.d(TAG, "🆕 Creating new user...")
                    val user = User().apply {
                        this.name = name
                        this.number = number
                    }
                    Log.d(TAG, "   User object created: $user")

                    val result = dao.insert(user)
                    Log.d(TAG, "📤 Insert result: $result")

                    result
                        .onSuccess { newId ->
                            Log.d(TAG, "✅ User inserted successfully with ID: $newId")
                            statusMessage = "Inserted user #$newId"
                        }
                        .onFailure { err ->
                            Log.e(TAG, "❌ Insert failed: ${err.message}")
                            statusMessage = "Insert failed: ${err.message}"
                        }
                } else {
                    // UPDATE
                    Log.d(TAG, "✏️ Updating user with ID: $currentId")
                    val user = User().apply {
                        this.id = currentId
                        this.name = name
                        this.number = number
                    }
                    Log.d(TAG, "   User object for update: $user")

                    val result = dao.updateByPrimaryKey(user)
                    Log.d(TAG, "📤 Update result: $result")

                    result
                        .onSuccess { rows ->
                            Log.d(TAG, "✅ Updated $rows row(s)")
                            statusMessage = "Updated $rows row(s)"
                        }
                        .onFailure { err ->
                            Log.e(TAG, "❌ Update failed: ${err.message}")
                            statusMessage = "Update failed: ${err.message}"
                        }
                    editingId = null
                    Log.d(TAG, "🔓 Editing mode cleared")
                }
                name = ""
                number = 0
                Log.d(TAG, "🧹 Form fields cleared")
                refresh()
            }) {
                Text(if (editingId == null) "Add" else "Save")
            }

            if (editingId != null) {
                Button(onClick = {
                    Log.d(TAG, "🔓 Cancelled editing for ID: $editingId")
                    editingId = null
                    name = ""
                    number = 0
                    Log.d(TAG, "🧹 Form fields cleared")
                }) {
                    Text("Cancel")
                }
            }

            // ---------------- DELETE ALL ----------------
            Button(onClick = {
                Log.d(TAG, "🗑️ DELETE ALL users triggered")
                val result = dao.deleteAll()
                Log.d(TAG, "📤 DeleteAll result: $result")

                result
                    .onSuccess { rows ->
                        Log.d(TAG, "✅ Deleted all users ($rows rows)")
                        statusMessage = "Deleted all ($rows rows)"
                    }
                    .onFailure { err ->
                        Log.e(TAG, "❌ Delete all failed: ${err.message}")
                        statusMessage = "Delete all failed: ${err.message}"
                    }
                refresh()
            }) {
                Text("Delete All")
            }
        }

        if (statusMessage.isNotBlank()) {
            Log.d(TAG, "📢 Status message: $statusMessage")
            Text(
                text = statusMessage,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text(
            text = "Users (${users.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        Log.d(TAG, "👥 Displaying ${users.size} users in list")

        // ---------------- READ (list) ----------------
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(users) { user ->
                Log.d(TAG, "📋 Rendering user card: $user")
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("#${user.id} — ${user.name}", style = MaterialTheme.typography.bodyLarge)
                        Text(user.number.toString(), style = MaterialTheme.typography.bodySmall)

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // ---------------- Load into form for edit ----------------
                            Button(onClick = {
                                Log.d(TAG, "✏️ Edit button clicked for user: $user")
                                editingId = user.id
                                name = user.name
                                number = user.number
                                Log.d(TAG, "📝 Form populated with name='$name', editingId=$editingId")
                            }) {
                                Text("Edit")
                            }

                            // ---------------- DELETE (single row) ----------------
                            Button(onClick = {
                                Log.d(TAG, "🗑️ Delete button clicked for user: $user")
                                val deleteResult = dao.delete("id = ?", arrayOf(user.id.toString()))
                                Log.d(TAG, "📤 Delete result: $deleteResult")

                                deleteResult
                                    .onSuccess { rows ->
                                        Log.d(TAG, "✅ Deleted $rows row(s)")
                                        statusMessage = "Deleted $rows row(s)"
                                    }
                                    .onFailure { err ->
                                        Log.e(TAG, "❌ Delete failed: ${err.message}")
                                        statusMessage = "Delete failed: ${err.message}"
                                    }
                                refresh()
                            }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}