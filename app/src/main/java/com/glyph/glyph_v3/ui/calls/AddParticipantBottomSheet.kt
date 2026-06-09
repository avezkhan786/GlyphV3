package com.glyph.glyph_v3.ui.calls

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.webrtc.GroupCallManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * BottomSheet overlay for selecting participants to add to a call.
 * Displays on top of the call UI without interrupting or destroying
 * the ongoing call activity.
 *
 * Can be used from both [ActiveCallActivity] (during 1:1 → group upgrade)
 * and [GroupCallActivity] (adding participants to existing group call).
 */
class AddParticipantBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AddParticipantSheet"

        /** Set of user IDs already in the call (to exclude from picker). */
        private const val ARG_EXCLUDE_IDS = "exclude_ids"

        fun newInstance(
            excludeUserIds: List<String> = emptyList()
        ): AddParticipantBottomSheet {
            return AddParticipantBottomSheet().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_EXCLUDE_IDS, ArrayList(excludeUserIds))
                }
            }
        }
    }

    /** Callback when a user is selected. */
    var onUserSelected: ((User) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                it.setBackgroundResource(android.R.color.transparent)
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_add_participant, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rvContactsBottomSheet)
        val progress = view.findViewById<ProgressBar>(R.id.progressAddParticipant)
        val tvNoContacts = view.findViewById<TextView>(R.id.tvNoContacts)

        rv.layoutManager = LinearLayoutManager(requireContext())

        val excludeIds = arguments?.getStringArrayList(ARG_EXCLUDE_IDS)?.toSet() ?: emptySet()
        loadContacts(rv, progress, tvNoContacts, excludeIds)
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }

    private fun loadContacts(
        rv: RecyclerView,
        progress: ProgressBar,
        tvNoContacts: TextView,
        excludeIds: Set<String>
    ) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Also exclude participants already in the group call
        val groupParticipantIds = GroupCallManager.participants.value.map { it.userId }.toSet()
        val allExcluded = excludeIds + groupParticipantIds + myUid

        scope.launch {
            try {
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
                        if (uid !in allExcluded) {
                            contactIds.add(uid)
                        }
                    }
                }

                progress.visibility = View.GONE

                if (contactIds.isEmpty()) {
                    tvNoContacts.visibility = View.VISIBLE
                    return@launch
                }

                val users = mutableListOf<User>()
                for (chunk in contactIds.chunked(30)) {
                    val snapshot = FirebaseFirestore.getInstance()
                        .collection("users")
                        .whereIn("id", chunk)
                        .get()
                        .await()
                    users.addAll(snapshot.toObjects(User::class.java))
                }

                if (users.isEmpty()) {
                    tvNoContacts.visibility = View.VISIBLE
                    return@launch
                }

                val adapter = AddParticipantAdapter(users) { user ->
                    onUserSelected?.invoke(user)
                    dismiss()
                }
                rv.adapter = adapter
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load contacts", e)
                progress.visibility = View.GONE
                tvNoContacts.text = "Failed to load contacts"
                tvNoContacts.visibility = View.VISIBLE
            }
        }
    }
}
