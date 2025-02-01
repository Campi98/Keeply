package pt.ipt.dam.a25269a24639.keeply.activity

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import pt.ipt.dam.a25269a24639.keeply.R
import pt.ipt.dam.a25269a24639.keeply.api.LogoutRequest
import pt.ipt.dam.a25269a24639.keeply.api.UserApi
import pt.ipt.dam.a25269a24639.keeply.data.NoteAdapter
import pt.ipt.dam.a25269a24639.keeply.data.infrastructure.Note.NoteDatabase
import pt.ipt.dam.a25269a24639.keeply.data.infrastructure.Note.NoteRepository
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : AppCompatActivity() {
    private lateinit var noteRepository: NoteRepository
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://keeplybackend-production.up.railway.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(UserApi::class.java)

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            return when {
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val database = NoteDatabase.getDatabase(this)
        noteRepository = NoteRepository(database.noteDao())

        val recyclerView = findViewById<RecyclerView>(R.id.notesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        val noteAdapter = NoteAdapter(emptyList())
        recyclerView.adapter = noteAdapter

        lifecycleScope.launch {
            val userIdFromAppPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE).getInt("userId", 0).toLong()
            if (userIdFromAppPrefs == 0L) {
                Toast.makeText(this@MainActivity, "User not found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            noteRepository.syncNotes(userIdFromAppPrefs)
            noteRepository.getAllNotes(userIdFromAppPrefs).collect { notes ->
                noteAdapter.updateNotes(notes)
            }
        }

        findViewById<FloatingActionButton>(R.id.addNoteFab).setOnClickListener {
            startActivity(Intent(this, NoteDetailActivity::class.java))
        }

        val logoutBtn = findViewById<ImageButton>(R.id.logoutButton)
        logoutBtn.setOnClickListener {
            lifecycleScope.launch {
                // limpar dados locais independentemente da conexão
                fun clearLocalDataAndRedirectToLogin() {
                    getSharedPreferences("AppPrefs", MODE_PRIVATE)
                        .edit()
                        .putBoolean("isLoggedIn", false)
                        .remove("email")
                        .remove("userId")
                        .apply()

                    val intent = Intent(this@MainActivity, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }

                try {
                    val emailFromPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("email", "")!!
                    val response = api.logout(LogoutRequest(emailFromPrefs))

                    if (response.isSuccessful) {
                        clearLocalDataAndRedirectToLogin()
                    } else {
                        Toast.makeText(this@MainActivity, "Server logout failed, logging out locally", Toast.LENGTH_SHORT).show()
                        clearLocalDataAndRedirectToLogin()
                    }
                } catch (e: Exception) {
                    // Handle network errors or other exceptions
                    Toast.makeText(this@MainActivity, "Offline logout", Toast.LENGTH_SHORT).show()
                    clearLocalDataAndRedirectToLogin()
                }
            }
        }



        // este botão serve para sincronizar as notas com o servidor
        val syncButton = findViewById<ImageButton>(R.id.syncButton)
        syncButton.setOnClickListener {
            lifecycleScope.launch {
                val userIdFromAppPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE).getInt("userId", 0).toLong()
                if (userIdFromAppPrefs == 0L) {
                    Toast.makeText(this@MainActivity, "User not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (isNetworkAvailable()) {
                    Toast.makeText(this@MainActivity, "Syncing notes...", Toast.LENGTH_SHORT).show()
                    noteRepository.syncNotes(getSharedPreferences("AppPrefs", MODE_PRIVATE).getInt("userId", 0).toLong())
                } else {
                    Toast.makeText(this@MainActivity, "No internet connection. Working in offline mode.", Toast.LENGTH_SHORT).show()
                }

                noteRepository.getAllNotes(userIdFromAppPrefs).collect { notes ->
                    noteAdapter.updateNotes(notes)
                }
            }
        }
    }
}