package pt.ipt.dam.a25269a24639.keeply.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import pt.ipt.dam.a25269a24639.keeply.api.NoteApi
import pt.ipt.dam.a25269a24639.keeply.data.dto.NoteDTO
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NoteRepository(private val noteDao: NoteDao) {
    private val retrofit = Retrofit.Builder()
        .baseUrl("http://10.10.210.4:8080/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(NoteApi::class.java)

    suspend fun update(note: Note) {
        noteDao.updateNote(note.copy(synced = false))
        syncNotes()
    }

    suspend fun delete(note: Note) {
        noteDao.deleteNote(note)
    }

    suspend fun getNoteById(id: Long): Note? {
        return noteDao.getNoteById(id)
    }


    // dar fix ao get (sync notes)
    // user api em vez do dao


    // TODO - EDITAR NOTA COMO O QUE FIZ AGORA
    // GARANTIR QUE O NOTEDTO PARA O UPDATE JÁ TEM O ID

    //TODO:

    // isto vai buscar todas as notas
    val allNotes: Flow<List<Note>> = noteDao.getAllNotes()

    suspend fun insert(note: Note) {
        val noteDTO = NoteDTO(note.title, note.content, note.photoUri)
        try {
            // Primeiro, criar nota no servidor
            val syncedNote = api.createNote(noteDTO)
            // Depois, guardar localmente como sincronizada
            noteDao.insertNote(syncedNote.copy(synced = true))
        } catch (e: Exception) {
            // Se falhar, guardar localmente como não sincronizada
            noteDao.insertNote(note.copy(synced = false))
            Log.e("NoteRepository", "Error creating note on server", e)
        }
    }
    
    suspend fun syncNotes() {
        try {
            // Vai buscar todas as notas do servidor
            val remoteNotes = api.getAllNotes()

            // Vai buscar todas as notas da base de dados local
            // Obter TODAS as notas, não apenas as não sincronizadas
            // Isto é porque precisamos de comparar timestamps para determinar qual versão é mais recente
            val localNotes = noteDao.getAllNotesList()
            
            // criar uma lista de notas que são a junção das notas locais e remotas
            val mergedNotes = (remoteNotes + localNotes)
                .groupBy { it.id } // agrupar por ID para remover duplicados
                .map { (_, notes) ->
                    // para cada ID, escolher a nota mais recente
                    notes.maxBy { it.timestamp }
                }
    
            // atualizar a base de dados local com os resultados da junção anterior
            mergedNotes.forEach { note ->
                try {
                    // update nota remota se a local for mais recente
                    val localNote = noteDao.getNoteById(note.id)
                    if (localNote != null && localNote.timestamp > note.timestamp) {
                        val noteDTO = NoteDTO(localNote.title, localNote.content, localNote.photoUri)
                        api.updateNote(note.id, noteDTO)
                        noteDao.updateNote(localNote.copy(synced = true))
                    } else {
                        // update nota local se a remota for mais recente
                        noteDao.updateNote(note.copy(synced = true))
                    }
                } catch (e: Exception) {
                    Log.e("NoteRepository", "Error syncing note", e)
                }
            }
            Log.d("NoteRepository", "Synced notes")
        } catch (e: Exception) {
            Log.e("NoteRepository", "Error during sync", e)
        }
    }
}
