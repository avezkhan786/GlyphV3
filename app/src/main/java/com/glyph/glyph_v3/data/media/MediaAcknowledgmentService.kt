package com.glyph.glyph_v3.data.media

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Manages media acknowledgment (ACK) system for reliable media delivery.
 * 
 * Flow:
 * 1. Sender uploads media to Firebase Storage
 * 2. Sender stores media reference in RTDB with pending ACK status
 * 3. Receiver downloads media and saves to local storage
 * 4. Receiver sends ACK to RTDB
 * 5. Sender (or Cloud Function) deletes media from Firebase Storage after ACK
 * 
 * RTDB Structure:
 * media_acks/
 *   {messageId}/
 *     senderId: "uid"
 *     storageRef: "path/to/file"
 *     recipients/
 *       {recipientId}/
 *         status: "pending" | "downloaded" | "acknowledged"
 *         downloadedAt: timestamp
 *         acknowledgedAt: timestamp
 */
object MediaAcknowledgmentService {
    
    private const val TAG = "MediaAckService"
    private const val NODE_MEDIA_ACKS = "media_acks"
    private const val STATUS_PENDING = "pending"
    private const val STATUS_DOWNLOADED = "downloaded"
    private const val STATUS_ACKNOWLEDGED = "acknowledged"
    
    // TTL for media files (7 days) - files older than this can be cleaned up
    private const val MEDIA_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    
    private val rtdb = FirebaseDatabase.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class MediaAckInfo(
        val messageId: String,
        val senderId: String,
        val storageRef: String,
        val createdAt: Long,
        val recipients: Map<String, RecipientStatus>
    )
    
    data class RecipientStatus(
        val status: String,
        val downloadedAt: Long? = null,
        val acknowledgedAt: Long? = null
    )
    
    /**
     * Register a media upload for ACK tracking.
     * Called by sender after successful upload.
     */
    suspend fun registerMediaUpload(
        messageId: String,
        storageRef: String,
        recipientIds: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val currentUserId = auth.currentUser?.uid ?: return@withContext false
        
        try {
            val ackData = mapOf(
                "senderId" to currentUserId,
                "storageRef" to storageRef,
                "createdAt" to ServerValue.TIMESTAMP,
                "recipients" to recipientIds.associateWith { 
                    mapOf("status" to STATUS_PENDING)
                }
            )
            
            rtdb.reference
                .child(NODE_MEDIA_ACKS)
                .child(messageId)
                .setValue(ackData)
                .await()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register media upload: ${e.message}", e)
            false
        }
    }
    
    /**
     * Mark media as downloaded by receiver (intermediate state).
     * Called when download completes but before final verification.
     */
    suspend fun markAsDownloaded(messageId: String): Boolean = withContext(Dispatchers.IO) {
        val currentUserId = auth.currentUser?.uid ?: return@withContext false
        
        try {
            val updates = mapOf(
                "recipients/$currentUserId/status" to STATUS_DOWNLOADED,
                "recipients/$currentUserId/downloadedAt" to ServerValue.TIMESTAMP
            )
            
            rtdb.reference
                .child(NODE_MEDIA_ACKS)
                .child(messageId)
                .updateChildren(updates)
                .await()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark as downloaded: ${e.message}", e)
            false
        }
    }
    
    /**
     * Send acknowledgment that media has been successfully persisted locally.
     * This is the final confirmation that allows Firebase Storage cleanup.
     */
    suspend fun sendAcknowledgment(messageId: String): Boolean = withContext(Dispatchers.IO) {
        val currentUserId = auth.currentUser?.uid ?: return@withContext false
        
        try {
            val updates = mapOf(
                "recipients/$currentUserId/status" to STATUS_ACKNOWLEDGED,
                "recipients/$currentUserId/acknowledgedAt" to ServerValue.TIMESTAMP
            )
            
            rtdb.reference
                .child(NODE_MEDIA_ACKS)
                .child(messageId)
                .updateChildren(updates)
                .await()
            
            
            // Check if all recipients have acknowledged
            checkAndCleanupMedia(messageId)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ACK: ${e.message}", e)
            false
        }
    }
    
    /**
     * Check if all recipients have acknowledged and clean up Firebase Storage if so.
     */
    private suspend fun checkAndCleanupMedia(messageId: String) {
        try {
            val snapshot = rtdb.reference
                .child(NODE_MEDIA_ACKS)
                .child(messageId)
                .get()
                .await()
            
            if (!snapshot.exists()) return
            
            val senderId = snapshot.child("senderId").getValue(String::class.java)
            val storageRef = snapshot.child("storageRef").getValue(String::class.java)
            val recipientsSnapshot = snapshot.child("recipients")
            
            // Check if all recipients have acknowledged
            var allAcknowledged = true
            recipientsSnapshot.children.forEach { recipient ->
                val status = recipient.child("status").getValue(String::class.java)
                if (status != STATUS_ACKNOWLEDGED) {
                    allAcknowledged = false
                }
            }
            
            if (allAcknowledged && !storageRef.isNullOrEmpty()) {
                deleteFromStorage(storageRef)
                
                // Remove ACK record
                rtdb.reference
                    .child(NODE_MEDIA_ACKS)
                    .child(messageId)
                    .removeValue()
                    .await()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check/cleanup media: ${e.message}", e)
        }
    }
    
    /**
     * Delete a file from Firebase Storage.
     */
    private suspend fun deleteFromStorage(storagePath: String) {
        try {
            storage.reference.child(storagePath).delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete from storage: ${e.message}", e)
        }
    }
    
    /**
     * Get ACK info for a message.
     */
    suspend fun getAckInfo(messageId: String): MediaAckInfo? = withContext(Dispatchers.IO) {
        try {
            val snapshot = rtdb.reference
                .child(NODE_MEDIA_ACKS)
                .child(messageId)
                .get()
                .await()
            
            if (!snapshot.exists()) return@withContext null
            
            val senderId = snapshot.child("senderId").getValue(String::class.java) ?: return@withContext null
            val storageRef = snapshot.child("storageRef").getValue(String::class.java) ?: return@withContext null
            val createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
            
            val recipients = mutableMapOf<String, RecipientStatus>()
            snapshot.child("recipients").children.forEach { recipientSnapshot ->
                val recipientId = recipientSnapshot.key ?: return@forEach
                val status = recipientSnapshot.child("status").getValue(String::class.java) ?: STATUS_PENDING
                val downloadedAt = recipientSnapshot.child("downloadedAt").getValue(Long::class.java)
                val acknowledgedAt = recipientSnapshot.child("acknowledgedAt").getValue(Long::class.java)
                
                recipients[recipientId] = RecipientStatus(status, downloadedAt, acknowledgedAt)
            }
            
            MediaAckInfo(messageId, senderId, storageRef, createdAt, recipients)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ACK info: ${e.message}", e)
            null
        }
    }
    
    /**
     * Check if media is pending download for current user.
     */
    suspend fun isPendingDownload(messageId: String): Boolean {
        val currentUserId = auth.currentUser?.uid ?: return false
        val ackInfo = getAckInfo(messageId) ?: return false
        val recipientStatus = ackInfo.recipients[currentUserId] ?: return false
        return recipientStatus.status == STATUS_PENDING
    }
    
    /**
     * Clean up stale media that exceeded TTL.
     * Should be called periodically (e.g., on app start or via WorkManager).
     */
    suspend fun cleanupStaleMedia() = withContext(Dispatchers.IO) {
        val currentUserId = auth.currentUser?.uid ?: return@withContext
        val cutoffTime = System.currentTimeMillis() - MEDIA_TTL_MS
        
        try {
            val snapshot = rtdb.reference
                .child(NODE_MEDIA_ACKS)
                .orderByChild("createdAt")
                .endAt(cutoffTime.toDouble())
                .get()
                .await()
            
            snapshot.children.forEach { ackSnapshot ->
                val messageId = ackSnapshot.key ?: return@forEach
                val senderId = ackSnapshot.child("senderId").getValue(String::class.java)
                
                // Only the sender can clean up their own media
                if (senderId == currentUserId) {
                    val storageRef = ackSnapshot.child("storageRef").getValue(String::class.java)
                    if (!storageRef.isNullOrEmpty()) {
                        deleteFromStorage(storageRef)
                    }
                    
                    // Remove ACK record
                    rtdb.reference
                        .child(NODE_MEDIA_ACKS)
                        .child(messageId)
                        .removeValue()
                        .await()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup stale media: ${e.message}", e)
        }
    }
    
    /**
     * Listen for pending media downloads for current user.
     * Useful for showing "tap to download" UI.
     */
    fun observePendingDownloads(onUpdate: (List<String>) -> Unit): ValueEventListener {
        val currentUserId = auth.currentUser?.uid ?: return object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val pendingMessages = mutableListOf<String>()
                
                snapshot.children.forEach { ackSnapshot ->
                    val messageId = ackSnapshot.key ?: return@forEach
                    val status = ackSnapshot
                        .child("recipients")
                        .child(currentUserId)
                        .child("status")
                        .getValue(String::class.java)
                    
                    if (status == STATUS_PENDING) {
                        pendingMessages.add(messageId)
                    }
                }
                
                onUpdate(pendingMessages)
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to observe pending downloads: ${error.message}")
            }
        }
        
        rtdb.reference
            .child(NODE_MEDIA_ACKS)
            .addValueEventListener(listener)
        
        return listener
    }
    
    /**
     * Stop observing pending downloads.
     */
    fun stopObserving(listener: ValueEventListener) {
        rtdb.reference
            .child(NODE_MEDIA_ACKS)
            .removeEventListener(listener)
    }
}
