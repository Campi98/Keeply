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
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.ipt.dam.a25269a24639.keeply.R
import pt.ipt.dam.a25269a24639.keeply.api.LogoutRequest
import pt.ipt.dam.a25269a24639.keeply.api.UserApi
import pt.ipt.dam.a25269a24639.keeply.data.NoteAdapter
import pt.ipt.dam.a25269a24639.keeply.data.infrastructure.Note.NoteDatabase
import pt.ipt.dam.a25269a24639.keeply.data.infrastructure.Note.NoteRepository
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


/**
 * Activity principal da aplicação.
 *
 * Esta Activity é responsável por:
 * - Exibir a lista de notas do utilizador
 * - Gerir operações de sincronização com o servidor
 * - Gerir operações de conta (logout, apagar conta)
 * - Verificar conectividade de rede
 */
class MainActivity : AppCompatActivity() {
    private lateinit var noteRepository: NoteRepository

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://keeplybackend-production.up.railway.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val api = retrofit.create(UserApi::class.java)

    /**
     * Verifica se existe conexão à Internet disponível
     * @return true se existir conexão, false caso contrário
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
            val userIdFromAppPrefs =
                getSharedPreferences("AppPrefs", MODE_PRIVATE).getInt("userId", 0).toLong()
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

        // Lógica para mostrar o menu de opções
        findViewById<ImageButton>(R.id.menuButton).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_delete_all -> {
                        showDeleteAllNotesDialog()
                        true
                    }

                    R.id.action_delete_account -> {
                        showDeleteAccountDialog()
                        true
                    }

                    R.id.action_logout -> {
                        handleLogout()
                        true
                    }

                    else -> false
                }
            }
            popup.show()
        }

        // este botão serve para sincronizar as notas com o servidor
        val syncButton = findViewById<ImageButton>(R.id.syncButton)
        syncButton.setOnClickListener {
            lifecycleScope.launch {
                val userIdFromAppPrefs =
                    getSharedPreferences("AppPrefs", MODE_PRIVATE).getInt("userId", 0).toLong()
                if (userIdFromAppPrefs == 0L) {
                    Toast.makeText(
                        this@MainActivity,
                        "Utilizador não encontrado",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                if (isNetworkAvailable()) {
                    var syncToast: Toast? =
                        Toast.makeText(
                            this@MainActivity,
                            "Sincronizando notas...",
                            Toast.LENGTH_SHORT
                        )
                    syncToast?.show()
                    noteRepository.syncNotes(
                        getSharedPreferences(
                            "AppPrefs",
                            MODE_PRIVATE
                        ).getInt("userId", 0).toLong()
                    )
                    syncToast?.cancel()
                    Toast.makeText(
                        this@MainActivity,
                        "Notas sincronizadas com sucesso",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "No internet connection. Working in offline mode.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Função para mostrar um diálogo de confirmação antes de apagar todas as notas
    private fun showDeleteAllNotesDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Apagar TODAS as notas")
            .setMessage("Tens a certeza que queres apagar TODAS as notas? Esta ação não pode ser revertida.")
            .setPositiveButton("Apagar") { _, _ ->
                lifecycleScope.launch {
                    val userId = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                        .getInt("userId", 0).toLong()
                    if (userId != 0L) {
                        try {
                            // Primeiro, obter todas as notas
                            val notes = noteRepository.getAllNotes(userId).first()

                            // Marcar cada nota como apagada em vez de apagar fisicamente
                            notes.forEach { note ->
                                noteRepository.delete(note)
                            }

                            // Tenta sincronizar as notas apagadas com o servidor
                            if (isNetworkAvailable()) {
                                noteRepository.syncNotes(userId)
                            }

                            Toast.makeText(
                                this@MainActivity,
                                "Todas as notas foram apagadas",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@MainActivity,
                                "Error deleting notes: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Função para mostrar um diálogo de confirmação antes de apagar a conta
    private fun showDeleteAccountDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Apagar Conta")
            .setMessage("Tens a certeza que queres apagar a tua conta? Esta ação não pode ser revertida e todas as tuas notas serão apagadas.")
            .setPositiveButton("Apagar") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val userId = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                            .getInt("userId", 0)

                        if (userId != 0) {
                            withContext(Dispatchers.IO) {
                                // Get notes uma vez
                                val notes = noteRepository.getAllNotes(userId.toLong()).first()

                                // Apagar todas as notas
                                notes.forEach { note ->
                                    noteRepository.delete(note)
                                }

                                // Apagar o user
                                api.deleteUser(userId)
                            }

                            // Limpar as SharedPreferences
                            getSharedPreferences("AppPrefs", MODE_PRIVATE)
                                .edit()
                                .clear()
                                .apply()

                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Conta apagada com sucesso",
                                    Toast.LENGTH_SHORT
                                ).show()

                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        LoginActivity::class.java
                                    ).apply {
                                        flags =
                                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    })
                                finish()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Erro ao apagar conta: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Função para lidar com o logout
    private fun handleLogout() {
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
                val emailFromPrefs =
                    getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("email", "")!!
                val response = api.logout(LogoutRequest(emailFromPrefs))

                if (response.isSuccessful) {
                    clearLocalDataAndRedirectToLogin()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Server logout failed, logging out locally",
                        Toast.LENGTH_SHORT
                    ).show()
                    clearLocalDataAndRedirectToLogin()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Offline logout", Toast.LENGTH_SHORT).show()
                clearLocalDataAndRedirectToLogin()
            }
        }
    }
}