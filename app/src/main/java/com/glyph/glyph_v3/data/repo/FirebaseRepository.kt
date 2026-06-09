package com.glyph.glyph_v3.data.repo

import android.net.Uri
import android.util.Log
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.data.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirebaseRepository {

    companion object {
        private const val USERS_CACHE_TTL_MS = 2 * 60 * 1000L

        @Volatile
        private var cachedUsers: List<User>? = null

        @Volatile
        private var cachedUsersAt: Long = 0L
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    val currentUserId: String?
        get() = auth.currentUser?.uid

    fun saveUserProfile(username: String, phone: String, bio: String, imageUri: Uri?, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val uid = currentUserId ?: run {
            onFailure(Exception("User not logged in."))
            return
        }

        if (imageUri != null) {
            val ref = storage.reference.child("profile_images/$uid.jpg")
            ref.putFile(imageUri)
                .addOnSuccessListener {
                    ref.downloadUrl.addOnSuccessListener { downloadUrl ->
                        saveUserDocument(uid, phone, username, bio, downloadUrl.toString(), onSuccess, onFailure)
                    }.addOnFailureListener { exception ->
                        Log.e("ProfileSave", "FAILURE at Step 3: Could not get download URL.", exception)
                        onFailure(exception)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e("ProfileSave", "FAILURE at Step 2: Could not upload image.", exception)
                    onFailure(exception)
                }
        } else {
            saveUserDocument(uid, phone, username, bio, "", onSuccess, onFailure)
        }
    }

    private fun saveUserDocument(uid: String, phone: String, username: String, bio: String, imageUrl: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val userData = hashMapOf(
            "id" to uid,
            "phoneNumber" to phone,
            "username" to username,
            "profileImageUrl" to imageUrl,
            "profileImageFullUrl" to imageUrl,
            "bio" to bio
        )

        firestore.collection("users").document(uid).set(userData)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e("ProfileSave", "FAILURE at Step 4: Could not write user document to Firestore.", e)
                onFailure(e)
            }
    }
    
    suspend fun saveUser(username: String, phone: String, imageUri: Uri?) {
       // This function is now deprecated
    }

    fun getUser(onSuccess: (User?) -> Unit) {
        val uid = currentUserId ?: run { onSuccess(null); return }
        getUser(uid, onSuccess)
    }

    fun getUser(userId: String, onSuccess: (User?) -> Unit) {
        firestore.collection("users").document(userId).get()
            .addOnSuccessListener { document -> onSuccess(document.toObject(User::class.java)) }
            .addOnFailureListener { exception ->
                Log.e("FirebaseRepo", "Error getting user $userId", exception)
                onSuccess(null)
            }
    }

    fun getAllUsers(
        forceRefresh: Boolean = false,
        onSuccess: (List<User>) -> Unit,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()
        val cached = cachedUsers
        if (!forceRefresh && cached != null && now - cachedUsersAt < USERS_CACHE_TTL_MS) {
            onSuccess(cached.filter { it.id != currentUserId })
            return
        }

        firestore.collection("users").get()
            .addOnSuccessListener { snapshot ->
                val users = snapshot.documents.mapNotNull { it.toObject(User::class.java) }
                cachedUsers = users
                cachedUsersAt = System.currentTimeMillis()
                onSuccess(users.filter { it.id != currentUserId })
            }
            .addOnFailureListener { exception ->
                Log.e("FirebaseRepo", "getAllUsers FAILED: ${exception.message}", exception)
                onFailure?.invoke(exception) ?: onSuccess(emptyList())
            }
    }
    
    // ... messaging functions ...
    fun sendMessage(chatId: String, text: String, otherUserId: String) {
        val userId = currentUserId ?: return
        val timestamp = System.currentTimeMillis()
        val messageData = hashMapOf("text" to text, "senderId" to userId, "timestamp" to timestamp, "status" to "SENT", "type" to "TEXT")
        
        // Ensure the chat document exists with participants array for querying
        val chatData = hashMapOf(
            "participants" to listOf(userId, otherUserId),
            "lastMessage" to text,
            "lastMessageTimestamp" to timestamp,
            "lastMessageSenderId" to userId
        )
        firestore.collection("chats").document(chatId).set(chatData, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                // Now add the message to the subcollection
                firestore.collection("chats").document(chatId).collection("messages").add(messageData)
                    .addOnFailureListener { e -> Log.e("FirebaseRepo", "Error sending message", e) }
            }
            .addOnFailureListener { e -> Log.e("FirebaseRepo", "Error creating/updating chat document", e) }
    }
    fun sendImageMessage(chatId: String, imageUri: Uri, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val userId = currentUserId ?: return
        val filename = "${UUID.randomUUID()}.jpg"
        val ref = storage.reference.child("chat_images/$chatId/$filename")
        ref.putFile(imageUri).continueWithTask { task ->
            if (!task.isSuccessful) { task.exception?.let { throw it } }
            ref.downloadUrl
        }.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val downloadUrl = task.result.toString()
                val messageData = hashMapOf("text" to "Photo", "senderId" to userId, "timestamp" to System.currentTimeMillis(), "status" to "SENT", "type" to "IMAGE", "imageUrl" to downloadUrl)
                firestore.collection("chats").document(chatId).collection("messages").add(messageData).addOnSuccessListener { onSuccess() }.addOnFailureListener(onFailure)
            } else {
                onFailure(task.exception!!)
            }
        }
    }
    fun sendAudioMessage(chatId: String, audioUri: Uri, duration: Long, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val userId = currentUserId ?: return
        val filename = "${UUID.randomUUID()}.3gp"
        val ref = storage.reference.child("chat_audio/$chatId/$filename")
        ref.putFile(audioUri).continueWithTask { task ->
            if (!task.isSuccessful) { task.exception?.let { throw it } }
            ref.downloadUrl
        }.addOnCompleteListener { task ->
             if (task.isSuccessful) {
                val downloadUrl = task.result.toString()
                val messageData = hashMapOf("text" to "Voice Note", "senderId" to userId, "timestamp" to System.currentTimeMillis(), "status" to "SENT", "type" to "AUDIO", "audioUrl" to downloadUrl, "audioDuration" to duration)
                firestore.collection("chats").document(chatId).collection("messages").add(messageData).addOnSuccessListener { onSuccess() }.addOnFailureListener(onFailure)
            } else {
                onFailure(task.exception!!)
            }
        }
    }

    // CORRECTED getMessages function
    fun getMessages(chatId: String): Flow<List<Message>> = callbackFlow {
        val userId = currentUserId
        val listenerRegistration = firestore.collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot == null) {
                    return@addSnapshotListener
                }

                val messages = snapshot.documents.mapNotNull { doc ->
                    try {
                        Message(
                            id = doc.id,
                            chatId = chatId,
                            text = doc.getString("text") ?: "",
                            senderId = doc.getString("senderId") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0,
                            status = MessageStatus.valueOf(doc.getString("status") ?: "SENT"),
                            isIncoming = doc.getString("senderId") != userId,
                            type = MessageType.valueOf(doc.getString("type") ?: "TEXT"),
                            imageUrl = doc.getString("imageUrl"),
                            audioUrl = doc.getString("audioUrl"),
                            audioDuration = doc.getLong("audioDuration") ?: 0
                        )
                    } catch (e: Exception) {
                        Log.e("FirebaseRepo", "Error parsing message: ${doc.id}", e)
                        null
                    }
                }
                trySend(messages)
            }
        awaitClose { listenerRegistration.remove() }
    }

    fun getRecentChats(): Flow<List<Chat>> = callbackFlow {
        val userId = currentUserId
        if (userId == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val registration = firestore.collection("chats")
            .whereArrayContains("participants", userId)
            .orderBy("lastMessageTimestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Do not crash the app on Firestore query errors (e.g., missing index)
                    Log.e("FirebaseRepo", "Error listening to recent chats", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val chats = snapshot.documents.mapNotNull { doc ->
                    try {
                        Chat(
                            id = doc.id,
                            participants = (doc.get("participants") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                            lastMessage = doc.getString("lastMessage") ?: "",
                            lastMessageTimestamp = doc.getDate("lastMessageTimestamp"),
                            lastMessageSenderId = doc.getString("lastMessageSenderId") ?: "",
                            unreadCount = (doc.getLong("unreadCount") ?: 0L).toInt()
                        )
                    } catch (e: Exception) {
                        Log.e("FirebaseRepo", "Error parsing chat ${doc.id}", e)
                        null
                    }
                }

                trySend(chats)
            }

        awaitClose { registration.remove() }
    }

    fun updateFcmToken(token: String) {
        val userId = currentUserId ?: return
        firestore.collection("users").document(userId)
            .update("fcmToken", token)
            .addOnSuccessListener { }
            .addOnFailureListener { e -> Log.e("FirebaseRepo", "Error updating FCM token", e) }
    }

    fun markMessageAsRead(chatId: String, messageId: String) {
        firestore.collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update("status", "READ")
            .addOnFailureListener { e -> Log.e("FirebaseRepo", "Error marking message as read", e) }
    }

    // Profile management functions
    suspend fun uploadProfileImage(uri: Uri, onProgress: (Float) -> Unit): String {
        val uid = currentUserId ?: throw Exception("User not logged in")
        val ref = storage.reference.child("profile_images/$uid.jpg")
        
        return try {
            val uploadTask = ref.putFile(uri)
            
            uploadTask.addOnProgressListener { taskSnapshot ->
                val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toFloat()
                onProgress(progress)
            }
            
            uploadTask.await()
            val downloadUrl = ref.downloadUrl.await().toString()
            
            // Update user profile with new image URL
            // This is kept for backward compatibility if the caller doesn't specify full version
            firestore.collection("users").document(uid)
                .update("profileImageUrl", downloadUrl)
                .await()
            
            downloadUrl
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "Error uploading profile image", e)
            throw e
        }
    }

    suspend fun uploadProfileImages(
        fullImageUri: Uri,
        croppedImageUri: Uri,
        onProgress: (Float) -> Unit
    ): Pair<String, String> {
        val uid = currentUserId ?: throw Exception("User not logged in")
        val fullRef = storage.reference.child("profile_images/${uid}_full.jpg")
        val thumbRef = storage.reference.child("profile_images/${uid}.jpg")

        return try {
            // Upload full image
            val fullUpload = fullRef.putFile(fullImageUri).await()
            val fullUrl = fullRef.downloadUrl.await().toString()
            onProgress(50f)

            // Upload cropped image
            val thumbUpload = thumbRef.putFile(croppedImageUri).await()
            val thumbUrl = thumbRef.downloadUrl.await().toString()
            onProgress(100f)

            // Update user profile
            val updates = mapOf(
                "profileImageUrl" to thumbUrl,
                "profileImageFullUrl" to fullUrl
            )
            firestore.collection("users").document(uid)
                .update(updates)
                .await()

            Pair(thumbUrl, fullUrl)
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "Error uploading profile images", e)
            throw e
        }
    }

    suspend fun removeProfileImage() {
        val uid = currentUserId ?: throw Exception("User not logged in")
        val thumbRef = storage.reference.child("profile_images/${uid}.jpg")
        val fullRef = storage.reference.child("profile_images/${uid}_full.jpg")
        
        // Delete thumbnail from storage
        try {
            thumbRef.delete().await()
        } catch (e: Exception) {
            // Image might not exist, that's okay
        }

        // Delete full image from storage
        try {
            fullRef.delete().await()
        } catch (e: Exception) {
        }
        
        // Update Firestore to remove URLs
        val updates = mutableMapOf<String, Any?>(
            "profileImageUrl" to "",
            "profileImageFullUrl" to null
        )
        
        firestore.collection("users").document(uid)
            .update(updates)
            .await()
    }

    suspend fun updateUserProfile(username: String? = null, bio: String? = null) {
        val uid = currentUserId ?: throw Exception("User not logged in")
        
        val updates = mutableMapOf<String, Any>()
        username?.let { updates["username"] = it }
        bio?.let { updates["bio"] = it }
        
        if (updates.isNotEmpty()) {
            firestore.collection("users").document(uid)
                .update(updates)
                .await()
        }
    }
}
