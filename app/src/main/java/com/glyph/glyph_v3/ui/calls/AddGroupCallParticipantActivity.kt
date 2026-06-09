package com.glyph.glyph_v3.ui.calls

import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.webrtc.GroupCallManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Simple user picker for adding participants to an active group call.
 * Shows existing contacts (users the current user has chatted with).
 * Excludes users already in the call.
 */
class AddGroupCallParticipantActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AddGroupParticipant"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_group_participant)

        val rvContacts = findViewById<RecyclerView>(R.id.rvContactsForGroupCall)
        val btnBack = findViewById<ImageView>(R.id.btnBackFromAddParticipant)

        btnBack.setOnClickListener { finish() }

        rvContacts.layoutManager = LinearLayoutManager(this)

        loadContacts(rvContacts)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun loadContacts(rv: RecyclerView) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val existingParticipantIds = GroupCallManager.participants.value.map { it.userId }.toSet()

        scope.launch {
            try {
                // Get users from chats the current user is in
                val chatSnapshots = FirebaseFirestore.getInstance()
                    .collection("chats")
                    .whereArrayContains("participants", myUid)
                    .get()
                    .await()

                val contactIds = mutableSetOf<String>()
                for (doc in chatSnapshots.documents) {
                    @Suppress("UNCHECKED_CAST")
                    val participants = doc.get("participants") as? List<String> ?: continue
                    for (uid in participants) {
                        if (uid != myUid && uid !in existingParticipantIds) {
                            contactIds.add(uid)
                        }
                    }
                }

                if (contactIds.isEmpty()) {
                    Toast.makeText(this@AddGroupCallParticipantActivity, "No contacts available to add", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Fetch user profiles in chunks
                val users = mutableListOf<User>()
                for (chunk in contactIds.chunked(30)) {
                    val snapshot = FirebaseFirestore.getInstance()
                        .collection("users")
                        .whereIn("id", chunk)
                        .get()
                        .await()
                    users.addAll(snapshot.toObjects(User::class.java))
                }

                val adapter = AddParticipantAdapter(users) { user ->
                    GroupCallManager.addParticipant(
                        this@AddGroupCallParticipantActivity,
                        user.id,
                        user.username,
                        user.profileImageUrl
                    )
                    Toast.makeText(this@AddGroupCallParticipantActivity, "Added ${user.username}", Toast.LENGTH_SHORT).show()
                    finish()
                }
                rv.adapter = adapter
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load contacts", e)
                Toast.makeText(this@AddGroupCallParticipantActivity, "Failed to load contacts", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
