const functions = require("firebase-functions");
const admin = require("firebase-admin");

// Initialize Firebase Admin SDK once at module level
admin.initializeApp();
const PENDING_AUTH_SENDER_ID = "__pending_auth__";

// WhatsApp-style delete-for-all window (48 hours)
const DELETE_FOR_ALL_WINDOW_MS = 48 * 60 * 60 * 1000;

/**
 * Callable: deleteMessageForAll
 * Validates:
 * - Authenticated
 * - Message exists in chats/{chatId}/messages/{messageId}
 * - senderId matches caller
 * - within time window
 * Behavior:
 * - Idempotent: if already deleted, returns already_deleted
 * - Soft-delete only: sets isDeletedForAll=true and deletedAt=serverTimestamp
 * - Deletes associated media from Storage
 */
exports.deleteMessageForAll = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Authentication required");
  }

  const uid = context.auth.uid;
  const chatId = typeof data.chatId === "string" ? data.chatId : "";
  const messageIds = Array.isArray(data.messageIds) ? data.messageIds : [];

  if (!chatId) {
    throw new functions.https.HttpsError("invalid-argument", "chatId is required");
  }
  if (messageIds.length === 0) {
    throw new functions.https.HttpsError("invalid-argument", "messageIds must be a non-empty array");
  }

  const results = {};
  const nowMs = Date.now();
  const db = admin.firestore();
  
  // Helper to extract path from Firebase Storage URL
  // Matches .../o/folder%2Ffile.jpg?alt=...
  const getStoragePathFromUrl = (url) => {
    if (!url || typeof url !== 'string') return null;
    try {
      if (url.includes('/o/')) {
        return decodeURIComponent(url.split('/o/')[1].split('?')[0]);
      }
    } catch (e) {
      console.warn("Could not parse storage path from URL", url);
    }
    return null;
  };

/**
 * RTDB trigger for presence changes.
 * Mirrors authoritative presence/lastSeen updates into Firestore users/{userId}
 * so chat headers do not have to wait for RTDB reconnects after app close/open.
 */
exports.mirrorPresenceToFirestore = functions.database
  .ref("presence/{userId}")
  .onWrite(async (change, context) => {
    const userId = context.params.userId;
    if (!userId) {
      return null;
    }

    const after = change.after.exists() ? change.after.val() : null;
    if (!after) {
      await admin.firestore().collection("users").doc(userId).set({
        isOnline: false,
        viewingChat: null,
        presenceUpdatedAt: Date.now(),
      }, { merge: true });
      return null;
    }

    const isOnline = after.isOnline === true;
    const lastSeen = typeof after.lastSeen === "number" ? after.lastSeen : null;
    const lastHeartbeat = typeof after.lastHeartbeat === "number" ? after.lastHeartbeat : null;
    const viewingChat = typeof after.viewingChat === "string" && after.viewingChat ? after.viewingChat : null;

    const payload = {
      isOnline,
      viewingChat,
      presenceUpdatedAt: Date.now(),
    };

    if (lastSeen !== null) {
      payload.lastSeen = lastSeen;
    } else if (!isOnline && lastHeartbeat !== null) {
      payload.lastSeen = lastHeartbeat;
    }

    await admin.firestore().collection("users").doc(userId).set(payload, { merge: true });
    return null;
  });

  // Process sequentially to keep transactions small/predictable.
  for (const messageId of messageIds) {
    if (typeof messageId !== "string" || !messageId) {
      results[String(messageId)] = { status: "invalid_id" };
      continue;
    }

    const ref = db.collection("chats").doc(chatId).collection("messages").doc(messageId);
    const chatRef = db.collection("chats").doc(chatId);
    const pendingRef = db.collection("pending_messages").doc(messageId);
    let mediaToDelete = [];
    let pendingRecipients = [];

    try {
      await db.runTransaction(async (tx) => {
        const snap = await tx.get(ref);
        const chatSnap = await tx.get(chatRef);
        const chatData = chatSnap.exists ? (chatSnap.data() || {}) : {};
        pendingRecipients = Array.isArray(chatData.participants)
          ? chatData.participants.filter((participantId) =>
              typeof participantId === "string" && participantId && participantId !== uid
            )
          : [];
        let msg = snap.exists ? (snap.data() || {}) : null;

        if (!msg) {
          const pendingSnap = await tx.get(pendingRef);
          if (!pendingSnap.exists) {
            results[messageId] = { status: "missing" };
            return;
          }

          const pending = pendingSnap.data() || {};
          const pendingChatId = typeof pending.chatId === "string" ? pending.chatId : "";
          if (!pendingChatId || pendingChatId !== chatId) {
            results[messageId] = { status: "missing" };
            return;
          }

          msg = pending;
          tx.set(ref, pending, { merge: true });
        }
        if (msg.isDeletedForAll === true) {
          results[messageId] = { status: "already_deleted" };
          return;
        }

        const senderId = typeof msg.senderId === "string"
          ? msg.senderId
          : (typeof msg.sender_id === "string" ? msg.sender_id : "");
        const senderMatches = senderId === uid || senderId === PENDING_AUTH_SENDER_ID;
        console.log("deleteMessageForAll sender check:", {
          messageId,
          callerUid: uid,
          storedSenderId: senderId,
          senderMatches,
          docExists: snap.exists,
          msgKeys: Object.keys(msg),
        });
        if (!senderMatches) {
          results[messageId] = { status: "not_sender" };
          return;
        }

        let sentAtMs = 0;
        const ts = msg.timestamp;
        if (typeof ts === "number") sentAtMs = ts;
        else if (ts && typeof ts.toMillis === "function") sentAtMs = ts.toMillis();

        if (!sentAtMs || nowMs - sentAtMs > DELETE_FOR_ALL_WINDOW_MS) {
          results[messageId] = { status: "expired" };
          return;
        }

        // Collect media URLs to delete
        const urls = [];
        if (msg.imageUrl) urls.push(msg.imageUrl);
        if (msg.videoUrl) urls.push(msg.videoUrl);
        if (msg.audioUrl) urls.push(msg.audioUrl);
        if (msg.localUri && msg.localUri.startsWith("http")) urls.push(msg.localUri); 
        
        if (msg.mediaItems) {
           try {
               const items = JSON.parse(msg.mediaItems);
               if (Array.isArray(items)) {
                   items.forEach(item => {
                       if (item.url) urls.push(item.url);
                   });
               }
           } catch(e) {}
        }
        
        // De-duplicate URLs
        mediaToDelete = [...new Set(urls)].map(getStoragePathFromUrl).filter(p => p);

        // Perform soft delete update
        tx.update(ref, {
          isDeletedForAll: true,
          deletedAt: admin.firestore.FieldValue.serverTimestamp(),
          // Clear media fields to prevent accidental display or leaking info
          text: "", // Clear text content
          imageUrl: admin.firestore.FieldValue.delete(),
          videoUrl: admin.firestore.FieldValue.delete(),
          audioUrl: admin.firestore.FieldValue.delete(),
          mediaItems: admin.firestore.FieldValue.delete(),
          thumbnailUrl: admin.firestore.FieldValue.delete()
        });
        results[messageId] = { status: "deleted" };
      });
      
      // Cleanup storage files if transaction succeeded
      if (results[messageId] && results[messageId].status === "deleted" && mediaToDelete.length > 0) {
        const bucket = admin.storage().bucket();
        await Promise.allSettled(mediaToDelete.map(path => 
           bucket.file(path).delete().catch(err => {
               // Ignore object-not-found errors, log others
               if (err.code !== 404) console.warn("Failed to delete media file", path, err.message);
           })
        ));
      }

      if (results[messageId] && results[messageId].status === "deleted") {
        await pendingRef.delete().catch(() => null);

        if (pendingRecipients.length > 0) {
          const rtdb = admin.database();
          // Write a DELETE_FOR_ALL notification so recipients whose Firestore listener
          // hasn't fired yet (e.g. app backgrounded) will still get instant removal.
          // Also removes the original pending node if it was never delivered.
          const deleteNotif = {
            type: "DELETE_FOR_ALL",
            id: messageId,
            chatId: chatId,
            senderId: uid,
            deletedAt: Date.now()
          };
          await Promise.allSettled(
            pendingRecipients.flatMap((recipientId) => [
              rtdb.ref(`pending_messages/${recipientId}/delete_${messageId}`).set(deleteNotif),
              rtdb.ref(`pending_messages/${recipientId}/${messageId}`).remove()
            ])
          );
        }
      }

    } catch (e) {
      console.error("deleteMessageForAll failed", { chatId, messageId, error: e && e.message });
      results[messageId] = { status: "error" };
    }
  }

  return { results };
});

/**
 * Callable: likeStatus
 * Server-side status like so clients do not need direct Firestore write permission.
 */
exports.likeStatus = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Authentication required");
  }

  const uid = context.auth.uid;
  const statusId = typeof data.statusId === "string" ? data.statusId.trim() : "";

  if (!statusId) {
    throw new functions.https.HttpsError("invalid-argument", "statusId is required");
  }

  const db = admin.firestore();
  const statusRef = db.collection("statuses").doc(statusId);

  try {
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(statusRef);
      if (!snap.exists) {
        throw new functions.https.HttpsError("not-found", "Status not found");
      }

      const status = snap.data() || {};
      const ownerId = typeof status.userId === "string" ? status.userId : "";
      const likedBy = Array.isArray(status.likedByIds) ? status.likedByIds : [];

      if (!ownerId) {
        throw new functions.https.HttpsError("failed-precondition", "Invalid status document");
      }

      if (ownerId === uid) {
        throw new functions.https.HttpsError("failed-precondition", "You cannot like your own status");
      }

      if (likedBy.includes(uid)) {
        return;
      }

      tx.update(statusRef, {
        likedByIds: admin.firestore.FieldValue.arrayUnion(uid),
      });
    });

    return { status: "liked" };
  } catch (e) {
    if (e instanceof functions.https.HttpsError) {
      throw e;
    }
    console.error("likeStatus failed", { statusId, uid, error: e && e.message });
    throw new functions.https.HttpsError("internal", "Failed to like status");
  }
});

/**
 * Firestore trigger: onStatusCreated
 * Fired when a new status document is written to /statuses/{statusId}.
 * Sends FCM to all users who have opted in for notifications from the status owner.
 *
 * Subscription data is stored by the Android client at:
 *   /statusNotifSubscriptions/{publisherUserId}/subscribers/{subscriberUserId}
 */
exports.onStatusCreated = functions.firestore
  .document("statuses/{statusId}")
  .onCreate(async (snap, context) => {
    const statusData = snap.data() || {};
    const statusId = context.params.statusId;
    const publisherUserId = typeof statusData.userId === "string" ? statusData.userId.trim() : "";

    if (!publisherUserId) {
      console.log("onStatusCreated: no userId on status, skipping");
      return null;
    }

    // Fetch all subscribers for this publisher
    const subscribersSnap = await admin.firestore()
      .collection("statusNotifSubscriptions")
      .doc(publisherUserId)
      .collection("subscribers")
      .get();

    if (subscribersSnap.empty) {
      console.log(`onStatusCreated: no subscribers for publisher ${publisherUserId}`);
      return null;
    }

    // Get publisher's display name
    const publisherDoc = await admin.firestore().collection("users").doc(publisherUserId).get();
    const publisherName = (publisherDoc.exists && publisherDoc.data().username) || "A contact";

    const statusType = typeof statusData.type === "string" ? statusData.type : "TEXT";
    const mediaUrl = typeof statusData.mediaUrl === "string" ? statusData.mediaUrl : "";
    const thumbnailUrl = typeof statusData.thumbnailUrl === "string" ? statusData.thumbnailUrl : "";
    const timestamp = typeof statusData.timestamp === "number" ? String(statusData.timestamp) : String(Date.now());

    const sendPromises = subscribersSnap.docs.map(async (subDoc) => {
      const subscriberId = subDoc.id;
      if (subscriberId === publisherUserId) return; // never notify self

      const subUserDoc = await admin.firestore().collection("users").doc(subscriberId).get();
      if (!subUserDoc.exists) return;

      const fcmToken = subUserDoc.data().fcmToken;
      if (!fcmToken) return;

      const payload = {
        token: fcmToken,
        android: {
          priority: "high",
          ttl: 86400, // 24 hours
        },
        data: {
          type: "STATUS_UPDATE",
          statusId: statusId,
          publisherUserId: publisherUserId,
          publisherName: publisherName,
          statusType: statusType,
          mediaUrl: mediaUrl,
          thumbnailUrl: thumbnailUrl,
          timestamp: timestamp,
        },
      };

      try {
        const response = await admin.messaging().send(payload);
        console.log(`onStatusCreated: FCM sent to ${subscriberId} — ${response}`);
      } catch (err) {
        console.error(`onStatusCreated: FCM error for ${subscriberId}:`, err.code, err.message);
        if (
          err.code === "messaging/invalid-registration-token" ||
          err.code === "messaging/registration-token-not-registered"
        ) {
          await admin.firestore().collection("users").doc(subscriberId).update({
            fcmToken: admin.firestore.FieldValue.delete(),
          }).catch(() => {});
        }
      }
    });

    await Promise.allSettled(sendPromises);
    return null;
  });

/**
 * Helper to acquire a processing lock for a message ID.
 * Returns true if lock acquired (first time processing), false if already processed.
 * Uses RTDB transaction for atomic check-and-set.
 */
async function tryAcquireLock(messageId) {  const ref = admin.database().ref(`processed_messages/${messageId}`);
  const result = await ref.transaction((current) => {
    if (current === null) {
      return { timestamp: admin.database.ServerValue.TIMESTAMP };
    } else {
      return; // Abort transaction
    }
  });
  return result.committed;
}

/**
 * Option B: Server-side archived state so we can suppress phase-1 (system-handled) alerts.
 * Stored at: users/{uid}/archived_chats/{chatId}
 */
async function isChatArchivedForRecipient(recipientId, chatId, senderId) {
  if (!recipientId) {
    console.log("Archive check: no recipientId provided");
    return false;
  }
  try {
    const ids = new Set();
    if (typeof chatId === "string" && chatId.trim()) ids.add(chatId.trim());

    // Some parts of the app/backend may use different ordering for chatId.
    // Check common variants derived from sender/recipient ids.
    if (typeof senderId === "string" && senderId.trim()) {
      const a = senderId.trim();
      const b = recipientId.trim();
      ids.add(`${a}_${b}`);
      ids.add(`${b}_${a}`);
      ids.add([a, b].sort().join("_"));
    }

    console.log("Archive check: recipientId=%s, checking %d variants: %s", 
      recipientId, ids.size, Array.from(ids).join(", "));

    const col = admin
      .firestore()
      .collection("users")
      .doc(recipientId)
      .collection("archived_chats");

    const snapshots = await Promise.all(
      Array.from(ids).map(async (id) => ({ id, snap: await col.doc(id).get() }))
    );

    let latestState = null;
    let foundAny = false;

    snapshots.forEach(({ id, snap }) => {
      if (!snap.exists) return;

      foundAny = true;
      const data = snap.data() || {};
      const hasArchivedField = Object.prototype.hasOwnProperty.call(data, "archived");
      const archivedValue = hasArchivedField ? data.archived : true;
      const isArchived = archivedValue === true || archivedValue === "true";

      const updatedAt = data.updatedAt;
      let updatedAtMs = 0;
      if (updatedAt && typeof updatedAt.toMillis === "function") {
        updatedAtMs = updatedAt.toMillis();
      }

      console.log(
        "Archive check: FOUND chat marker id=%s, archived=%s, updatedAtMs=%s, data=%j",
        id,
        isArchived,
        updatedAtMs,
        data
      );

      if (!latestState || updatedAtMs > latestState.updatedAtMs) {
        latestState = { id, isArchived, updatedAtMs };
      }
    });

    if (!foundAny) {
      console.log("Archive check: chat NOT archived for recipient");
      return false;
    }

    const resolvedArchived = latestState ? latestState.isArchived : false;
    console.log(
      "Archive check: resolved from latest marker id=%s archived=%s updatedAtMs=%s",
      latestState && latestState.id,
      resolvedArchived,
      latestState && latestState.updatedAtMs
    );
    return resolvedArchived;
  } catch (e) {
    console.error("Archive check failed (fail-open):", e && e.message);
    return false;
  }
}

/**
 * Firestore trigger for new chat messages.
 * Mirrors committed chat messages into RTDB pending_messages so recipient fan-out
 * does not depend on the sender's client RTDB socket being connected.
 *
 * Flow:
 * 1. Client writes chats/{chatId}/messages/{messageId} to Firestore.
 * 2. This function mirrors that committed message into RTDB pending_messages/{recipientId}/{messageId}.
 * 3. Existing RTDB trigger handles FCM + recipient-side delivery path.
 */
exports.enqueueChatMessageForRealtimeDelivery = functions.firestore
    .document("chats/{chatId}/messages/{messageId}")
    .onCreate(async (snap, context) => {
      const messageData = snap.data() || {};
      const chatId = context.params.chatId;
      const messageId = context.params.messageId;
      const senderId = typeof messageData.senderId === "string" ? messageData.senderId : "";

      if (!chatId || !messageId || !senderId) {
        console.log("enqueueChatMessageForRealtimeDelivery: missing identifiers", {
          chatId,
          messageId,
          senderId,
        });
        return null;
      }

      const chatSnap = await admin.firestore().collection("chats").doc(chatId).get();
      if (!chatSnap.exists) {
        console.log("enqueueChatMessageForRealtimeDelivery: chat doc missing", { chatId, messageId });
        return null;
      }

      const chatData = chatSnap.data() || {};
      const participants = Array.isArray(chatData.participants)
        ? chatData.participants.filter((participantId) => typeof participantId === "string" && participantId)
        : [];
      const recipientIds = participants.filter((participantId) => participantId !== senderId);

      if (!recipientIds.length) {
        console.log("enqueueChatMessageForRealtimeDelivery: no recipients", { chatId, messageId, senderId });
        return null;
      }

      const pendingPayload = {
        id: typeof messageData.id === "string" ? messageData.id : messageId,
        chatId,
        text: typeof messageData.text === "string" ? messageData.text : "",
        senderId,
        timestamp: typeof messageData.timestamp === "number" ? messageData.timestamp : Date.now(),
        status: typeof messageData.status === "string" ? messageData.status : "SENT",
        type: typeof messageData.type === "string" ? messageData.type : "TEXT",
      };

      const optionalFields = [
        "imageUrl",
        "videoUrl",
        "thumbnailUrl",
        "linkPreviewTitle",
        "linkPreviewDomain",
        "linkPreviewDescription",
        "linkPreviewSiteName",
        "fileSize",
        "videoDuration",
        "mediaWidth",
        "mediaHeight",
        "contactName",
        "contactPhone",
        "replyToMessageId",
        "replyToText",
        "replyToSenderId",
        "replyToType",
        "audioUrl",
        "audioDuration",
        "mediaItems",
        "documentCaption",
        "statusId",
        "statusOwnerId",
        "statusThumbnailUrl",
        "statusType",
        "statusText",
        "statusBgColor",
        "isVideoNote",
        "mediaCount",
      ];
      optionalFields.forEach((field) => {
        const value = messageData[field];
        if (value !== undefined && value !== null) {
          pendingPayload[field] = value;
        }
      });

      await Promise.all(
        recipientIds.map(async (recipientId) => {
          const ref = admin.database().ref(`pending_messages/${recipientId}/${messageId}`);
          const existing = await ref.once("value");
          if (existing.exists()) {
            return null;
          }
          return ref.set(pendingPayload);
        })
      );

      console.log("enqueueChatMessageForRealtimeDelivery: mirrored message", {
        chatId,
        messageId,
        senderId,
        recipientCount: recipientIds.length,
      });
      return null;
    });

/**
 * Firestore trigger for walkie-talkie session requests.
 * Mirrors Firestore-first requests into RTDB so outbound walkie requests do not
 * wait on the caller's RTDB socket to reconnect after a cold open.
 */
exports.enqueueWalkieTalkieRequestForRealtimeDelivery = functions.firestore
  .document("walkieTalkieRequests/{sessionId}")
  .onCreate(async (snap, context) => {
    const session = snap.data() || {};
    const sessionId = context.params.sessionId;
    const initiatorId = typeof session.initiatorId === "string" ? session.initiatorId : "";
    const responderId = typeof session.responderId === "string" ? session.responderId : "";
    const status = typeof session.status === "string" ? session.status : "requesting";

    if (!sessionId || !initiatorId || !responderId) {
      console.log("enqueueWalkieTalkieRequestForRealtimeDelivery: missing identifiers", {
        sessionId,
        initiatorId,
        responderId,
      });
      return null;
    }

    if (status !== "requesting") {
      return null;
    }

    const ref = admin.database().ref(`walkieTalkieSessions/${sessionId}`);
    const existing = await ref.once("value");
    if (existing.exists()) {
      return null;
    }

    await ref.set(session);
    console.log("enqueueWalkieTalkieRequestForRealtimeDelivery: mirrored walkie request", {
      sessionId,
      initiatorId,
      responderId,
    });
    return null;
  });

/**
 * RTDB trigger for new messages - handles instant delivery acknowledgment
 * 
 * WhatsApp-style delivery flow:
 * 1. Message written to pending_messages/{recipientId}/{messageId}
 * 2. This function triggers IMMEDIATELY
 * 3. FCM push sent with HIGH priority
 * 4. Message marked as DELIVERED in Firestore IMMEDIATELY (server-side acknowledgment)
 * 5. Sender sees double-tick instantly (doesn't wait for receiver to open app)
 * 
 * The receiver's app will process the message when it wakes up (via FCM or app open),
 * but the delivery status is already confirmed server-side.
 */
exports.sendChatNotificationRTDB = functions.database
    .ref("pending_messages/{recipientId}/{messageId}")
    .onCreate(async (snapshot, context) => {
      const messageData = snapshot.val();
      const recipientId = context.params.recipientId;
      const messageId = context.params.messageId;
      
      const chatId = messageData.chatId;
      const senderId = messageData.senderId;
      const messageIdResolved = messageData.id || messageId;

      // 1. DEDUPLICATION: Try to acquire lock immediately
      let lockAcquired = false;
      try {
        lockAcquired = await tryAcquireLock(messageIdResolved);
      } catch (e) {
        console.error("Lock acquisition failed:", e);
        // Fail open: proceed to ensure delivery, but risk duplicates (handled by tag)
        lockAcquired = true; 
      }
      
      if (!lockAcquired) {
        console.log(`Duplicate trigger detected for ${messageIdResolved} (RTDB), skipping.`);
        return null;
      }

      // 2. SERVER-ACKNOWLEDGED DELIVERY
      // As soon as the backend accepts the pending message and starts fan-out,
      // publish DELIVERED so the sender is not blocked on the recipient device's
      // socket or app wake-up path.
      const deliveredAt = Date.now();
      try {
        await Promise.all([
          admin.database()
            .ref(`delivery_receipts/${senderId}/${chatId}/${messageIdResolved}`)
            .set({
              status: "DELIVERED",
              recipientId,
              deliveredAt,
            }),
          admin.firestore()
            .collection("chats").doc(chatId)
            .collection("messages").doc(messageIdResolved)
            .set({
              status: "DELIVERED",
              deliveredTimestamp: deliveredAt,
              deliveredAt,
              senderId,
              chatId,
            }, { merge: true }),
        ]);
        console.log("sendChatNotificationRTDB: delivery acknowledged", {
          chatId,
          messageId: messageIdResolved,
          senderId,
          recipientId,
          deliveredAt,
        });
      } catch (e) {
        console.error("sendChatNotificationRTDB: failed to write delivery acknowledgment", {
          chatId,
          messageId: messageIdResolved,
          senderId,
          recipientId,
          error: e && e.message,
        });
      }

      const text = messageData.text || "";
      const type = messageData.type || "TEXT";
      const imageUrl = messageData.imageUrl || "";
      const videoUrl = messageData.videoUrl || "";
      const thumbnailUrl = messageData.thumbnailUrl || "";
      const mediaItemsRaw = messageData.mediaItems;
      const mediaItems = (() => {
        if (Array.isArray(mediaItemsRaw)) return mediaItemsRaw;
        if (typeof mediaItemsRaw === "string" && mediaItemsRaw.trim()) {
          try {
            const parsed = JSON.parse(mediaItemsRaw);
            if (Array.isArray(parsed)) return parsed;
            if (parsed && typeof parsed === "object") return Object.values(parsed);
          } catch (e) {
            console.warn("MEDIA_GROUP mediaItems JSON parse failed:", e && e.message);
          }
        }
        return mediaItemsRaw && typeof mediaItemsRaw === "object" ? Object.values(mediaItemsRaw) : [];
      })();
      const fileSize = messageData.fileSize ? String(messageData.fileSize) : "";
      const videoDuration = messageData.videoDuration ? String(messageData.videoDuration) : "";
      const mimeType = type === "IMAGE" ? "image/*" : (type === "VIDEO" ? "video/mp4" : "");

      // For MEDIA_GROUP, pick representative fields from the first item
      let repImageUrl = imageUrl;
      let repVideoUrl = videoUrl;
      let repThumbnailUrl = thumbnailUrl;
      if (type === "MEDIA_GROUP" && mediaItems.length > 0) {
        const first = mediaItems[0] || {};
        const firstUrl = typeof first.url === "string" ? first.url : "";
        const firstThumb = typeof first.thumbnailUrl === "string" ? first.thumbnailUrl : "";
        const firstType = typeof first.type === "string" ? first.type : "";
        if (!repImageUrl && firstType === "IMAGE") repImageUrl = firstUrl;
        if (!repVideoUrl && firstType === "VIDEO") repVideoUrl = firstUrl;
        if (!repThumbnailUrl) repThumbnailUrl = firstThumb;
        if (!repImageUrl && !repVideoUrl) repImageUrl = firstUrl;
      }

      console.log("New RTDB message:", { 
        messageId: messageIdResolved, 
        chatId, 
        senderId, 
        recipientId, 
        type,
        hasImageUrl: !!imageUrl,
        hasVideoUrl: !!videoUrl,
        hasThumbnailUrl: !!thumbnailUrl,
        hasMediaItems: !!mediaItemsRaw,
        mediaItemsType: mediaItemsRaw ? typeof mediaItemsRaw : null,
        mediaItemsCount: mediaItems.length,
        imageUrlLength: imageUrl ? imageUrl.length : 0
      });

      if (type === "MEDIA_GROUP") {
        try {
          const miJson = JSON.stringify(mediaItems);
          console.log("MEDIA_GROUP payload diagnostics:", {
            mediaItemsJsonLength: miJson.length,
            repImageUrlLength: repImageUrl ? repImageUrl.length : 0,
            repVideoUrlLength: repVideoUrl ? repVideoUrl.length : 0,
            repThumbnailUrlLength: repThumbnailUrl ? repThumbnailUrl.length : 0,
          });
        } catch (e) {
          console.log("MEDIA_GROUP payload diagnostics failed:", e && e.message);
        }
      }

      if (!recipientId || !senderId || !chatId) {
        console.log("Missing recipient, sender, or chatId");
        return null;
      }

      // ──────────────────────────────────────────────
      // BLOCK CHECK: Do NOT send FCM if recipient has blocked the sender.
      // Check both Firestore blockedBy sub-collection and RTDB /blocks mirror.
      // This is critical: even if the RTDB write somehow succeeded (race condition),
      // we must never deliver a notification from a blocked user.
      // ──────────────────────────────────────────────
      try {
        // Phase 7: detect group chats by chatId prefix. Group fan-out is performed
        // client-side in GroupChatRepository.fanOutRtdb (one pending_messages entry
        // per recipient), so this trigger still runs once per recipient. The only
        // delta is that we (a) look up the group name to use as the FCM title,
        // (b) skip the mutual-block check (no "sender blocked recipient" concept
        // in groups — sender is implicitly opting in by posting), and (c) tag the
        // payload so the client renders MessagingStyle as a group conversation.
        const isGroupChat = typeof chatId === "string" && chatId.startsWith("group_");
        let groupName = "";
        if (isGroupChat) {
          try {
            const chatDoc = await admin.firestore().collection("chats").doc(chatId).get();
            if (chatDoc.exists) {
              const cd = chatDoc.data() || {};
              groupName = (cd.groupName || "").toString().trim();
            }
          } catch (gnErr) {
            console.warn("Failed to load group name for chat", chatId, gnErr && gnErr.message);
          }
          if (!groupName) groupName = "Group";
        }

        const recipientUserRef = admin.firestore().collection("users").doc(recipientId);
        const senderUserRef = admin.firestore().collection("users").doc(senderId);
        const recipientBlockedRef = recipientUserRef.collection("blockedUsers").doc(senderId);
        const senderBlockedRef = senderUserRef.collection("blockedUsers").doc(recipientId);
        const archivePromise = isChatArchivedForRecipient(recipientId, chatId, senderId);

        const [blockDoc, reverseBlockDoc, userDoc, senderDoc] = await Promise.all([
          recipientBlockedRef.get(),
          senderBlockedRef.get(),
          recipientUserRef.get(),
          senderUserRef.get(),
        ]);

        if (blockDoc.exists) {
          console.log(`BLOCKED: Recipient ${recipientId} has blocked sender ${senderId}. Suppressing FCM.`);
          // Clean up the pending message since it shouldn't have been written
          try {
            await snapshot.ref.remove();
          } catch (cleanupErr) {
            console.warn("Failed to clean up blocked pending message:", cleanupErr.message);
          }
          return null;
        }

        // Also check if sender blocked the recipient (mutual block edge case).
        // Skipped for group chats: a member posting to a group is by definition
        // willing to deliver to the rest of the group; per-recipient personal
        // blocks are still enforced above.
        if (!isGroupChat && reverseBlockDoc.exists) {
          console.log(`BLOCKED: Sender ${senderId} has blocked recipient ${recipientId}. Suppressing FCM.`);
          try {
            await snapshot.ref.remove();
          } catch (cleanupErr) {
            console.warn("Failed to clean up blocked pending message:", cleanupErr.message);
          }
          return null;
        }
        if (!userDoc.exists) {
          console.log("Recipient user document not found");
          return null;
        }

        const userData = userDoc.data() || {};
        const fcmToken = userData.fcmToken;

        const senderData = senderDoc.exists ? (senderDoc.data() || {}) : {};
        const senderName = senderData.username || "New Message";
        const senderAvatar = senderData.profileImageUrl || "";

        // Determine notification body based on message type
        let notificationBody = text || "New message";
        if (type === "IMAGE") notificationBody = "📷 Photo";
        else if (type === "VIDEO") notificationBody = "🎥 Video";
        else if (type === "AUDIO") notificationBody = "🎵 Audio";
        else if (type === "CONTACT") notificationBody = "👤 Contact";
        else if (type === "MEDIA_GROUP") notificationBody = "📷 Media";
        else if (type === "STATUS_REPLY") notificationBody = text === "❤️"
          ? "Liked your status"
          : "Replied to your status: " + (text || "");

        if (!fcmToken) {
          console.log("Recipient has no FCM token - message delivered but no push");
          return null;
        }

        const isArchivedForRecipient = await archivePromise;

        // SIMPLIFIED APPROACH: Always send data-only for archived chats
        // For non-archived: skip phase-1 notification - let client handle everything
        // This prevents flash issues and gives client full control
        const chatTag = `chat_${chatId}`;

        console.log("Sending high-priority data-only FCM to:", recipientId, "from:", senderName, "archived:", isArchivedForRecipient);

        const payload = {
          token: fcmToken,
          android: {
            priority: "high",
            ttl: 86400,
            directBootOk: true,
          },
          data: {
            title: isGroupChat ? groupName : senderName,
            body: notificationBody,
            text: text,
            chat_id: chatId,
            other_user_id: senderId,
            sender_avatar: senderAvatar,
            sender_name: senderName,
            is_group: isGroupChat ? "true" : "false",
            group_name: isGroupChat ? groupName : "",
            type: type,
            imageUrl: repImageUrl,
            videoUrl: repVideoUrl,
            thumbnailUrl: repThumbnailUrl,
            mediaItems: type === "MEDIA_GROUP" ? JSON.stringify(mediaItems) : "",
            mediaCount: type === "MEDIA_GROUP" ? String(mediaItems.length) : "",
            fileSize: fileSize,
            videoDuration: videoDuration,
            messageId: messageIdResolved,
            mimeType: mimeType,
            click_action: "OPEN_CHAT",
            timestamp: String(Date.now()),
            show_notification: isArchivedForRecipient ? "false" : "true",
            statusId: messageData.statusId || "",
            statusOwnerId: messageData.statusOwnerId || "",
            statusThumbnailUrl: messageData.statusThumbnailUrl || "",
            statusType: messageData.statusType || "",
            statusText: messageData.statusText || "",
            statusBgColor: messageData.statusBgColor != null ? String(messageData.statusBgColor) : "",
          },
          apns: {
            headers: {
              "apns-priority": "10",
              "apns-push-type": "alert",
              "apns-expiration": String(Math.floor(Date.now() / 1000) + 86400)
            },
            payload: {
              aps: {
                alert: {
                  title: isGroupChat ? groupName : senderName,
                  body: isGroupChat ? `${senderName}: ${notificationBody}` : notificationBody,
                },
                sound: "default",
                badge: 1,
                contentAvailable: true,
                mutableContent: true,
              },
            },
          },
        };

        try {
          const approxSize = Buffer.byteLength(JSON.stringify(payload.data), "utf8");
          console.log("FCM data approx bytes:", approxSize);
        } catch (e) {
          console.log("FCM size calc failed:", e && e.message);
        }

        try {
          const response = await admin.messaging().send(payload);
          console.log("FCM sent successfully:", response);
          return response;
        } catch (error) {
          console.error("FCM send error:", error.code, error.message);
          
          if (error.code === "messaging/invalid-registration-token" ||
              error.code === "messaging/registration-token-not-registered") {
            console.log("Removing invalid FCM token for user:", recipientId);
            await recipientUserRef.update({
              fcmToken: admin.firestore.FieldValue.delete()
            });
          }
          return null;
        }
      } catch (blockCheckErr) {
        console.error("Error checking block status, allowing notification:", blockCheckErr);
        // Fail-open for now; security rules are the hard enforcement
        return null;
      }
    });

// Legacy Firestore trigger - kept for backwards compatibility
// The RTDB trigger above is the primary path for new messages
exports.sendChatNotification = functions.firestore
    .document("pending_messages/{messageId}")
    .onCreate(async (snap, context) => {
      const messageData = snap.data();
      const chatId = messageData.chatId;
      const senderId = messageData.senderId;
      const recipientId = messageData.receiverId;
      const messageId = messageData.id || context.params.messageId;

      // 1. DEDUPLICATION: Try to acquire lock immediately
      let lockAcquired = false;
      try {
        lockAcquired = await tryAcquireLock(messageId);
      } catch (e) {
        console.error("Lock acquisition failed:", e);
        lockAcquired = true;
      }

      if (!lockAcquired) {
        console.log(`Duplicate trigger detected for ${messageId} (Firestore), skipping.`);
        return null;
      }

      // 2. INSTANT DELIVERY RECEIPT: Write receipt BEFORE any other work
      try {
        await Promise.all([
          // A. Write to RTDB delivery_receipts
          admin.database()
            .ref("delivery_receipts")
            .child(senderId)
            .child(chatId)
            .child(messageId)
            .set({
              status: "DELIVERED",
              recipientId,
              deliveredAt: admin.database.ServerValue.TIMESTAMP
            }),
          
          // B. Update Firestore status (include senderId so if document doesn't
          //    exist yet — race with client's persistToFirestore — the doc is
          //    still usable by deleteMessageForAll which checks senderId)
          admin.firestore()
            .collection("chats").doc(chatId)
            .collection("messages").doc(messageId)
            .set({
              status: "DELIVERED",
              deliveredAt: admin.firestore.FieldValue.serverTimestamp(),
              senderId: senderId,
              chatId: chatId
            }, { merge: true })
        ]);
        console.log("Instant delivery receipt sent for:", messageId);
      } catch (e) {
        console.error("Failed to send instant delivery receipt:", e);
      }

      const text = messageData.text || "";
      const type = messageData.type || "TEXT";
      const imageUrl = messageData.imageUrl || "";
      const videoUrl = messageData.videoUrl || "";
      const thumbnailUrl = messageData.thumbnailUrl || "";
      const fileSize = messageData.fileSize ? String(messageData.fileSize) : "";
      const videoDuration = messageData.videoDuration ? String(messageData.videoDuration) : "";
      const mimeType = type === "IMAGE" ? "image/*" : (type === "VIDEO" ? "video/mp4" : "");

      // Optional: MEDIA_GROUP support in legacy path by reading the canonical chat message
      let mediaItems = [];
      try {
        if (type === "MEDIA_GROUP") {
          const msgSnap = await admin.firestore()
            .collection("chats").doc(chatId)
            .collection("messages").doc(messageId)
            .get();

          const raw = msgSnap.exists ? msgSnap.data().mediaItems : null;
          // In this project Firestore stores MEDIA_GROUP mediaItems as JSON string.
          // Convert it into RTDB/FCM-friendly items with stable ids.
          if (typeof raw === "string" && raw.trim().length > 0) {
            const parsed = JSON.parse(raw);
            if (Array.isArray(parsed)) {
              mediaItems = parsed
                .filter(Boolean)
                .map((it, index) => ({
                  id: `${messageId}_${index}`,
                  url: typeof it.url === "string" ? it.url : "",
                  type: typeof it.type === "string" ? it.type : (typeof it.mediaType === "string" ? it.mediaType : ""),
                  duration: typeof it.duration === "number" ? it.duration : 0,
                  fileSize: typeof it.fileSize === "number" ? it.fileSize : 0,
                  thumbnailUrl: typeof it.thumbnailUrl === "string" ? it.thumbnailUrl : "",
                }))
                .filter((it) => !!it.id && !!it.url);
            }
          }
        }
      } catch (e) {
        console.log("Legacy MEDIA_GROUP mediaItems fetch/parse failed:", e && e.message);
      }

      // Representative fields for MEDIA_GROUP
      let repImageUrl = imageUrl;
      let repVideoUrl = videoUrl;
      let repThumbnailUrl = thumbnailUrl;
      if (type === "MEDIA_GROUP" && mediaItems.length > 0) {
        const first = mediaItems[0] || {};
        const firstUrl = typeof first.url === "string" ? first.url : "";
        const firstThumb = typeof first.thumbnailUrl === "string" ? first.thumbnailUrl : "";
        const firstType = typeof first.type === "string" ? first.type : "";
        if (!repImageUrl && firstType === "IMAGE") repImageUrl = firstUrl;
        if (!repVideoUrl && firstType === "VIDEO") repVideoUrl = firstUrl;
        if (!repThumbnailUrl) repThumbnailUrl = firstThumb;
        if (!repImageUrl && !repVideoUrl) repImageUrl = firstUrl;
      }

      console.log("Legacy Firestore message:", { chatId, senderId, recipientId });

      if (!recipientId || !chatId) {
        console.log("Missing recipient or chatId");
        return null;
      }

      const archivePromise = isChatArchivedForRecipient(recipientId, chatId, senderId);

      // ──────────────────────────────────────────────
      // BLOCK CHECK: Suppress FCM if either user blocked the other
      // ──────────────────────────────────────────────
      try {
        const [recipientBlockedSender, senderBlockedRecipient] = await Promise.all([
          admin.firestore().collection("users").doc(recipientId)
            .collection("blockedUsers").doc(senderId).get(),
          admin.firestore().collection("users").doc(senderId)
            .collection("blockedUsers").doc(recipientId).get(),
        ]);
        if (recipientBlockedSender.exists || senderBlockedRecipient.exists) {
          console.log(`BLOCKED (legacy): Suppressing FCM between ${senderId} and ${recipientId}`);
          return null;
        }
      } catch (blockErr) {
        console.error("Legacy block check error, allowing:", blockErr);
      }

      // Get recipient FCM token
      const userDoc = await admin.firestore().collection("users").doc(recipientId).get();
      if (!userDoc.exists) return null;

      const fcmToken = userDoc.data().fcmToken;
      if (!fcmToken) return null;

      // Get sender info
      const senderDoc = await admin.firestore().collection("users").doc(senderId).get();
      const senderData = senderDoc.exists ? senderDoc.data() : {};
      const senderName = senderData.username || "New Message";
      const senderAvatar = senderData.profileImageUrl || "";

      let notificationBody = text || "New message";
      if (type === "IMAGE") notificationBody = "📷 Photo";
      else if (type === "VIDEO") notificationBody = "🎥 Video";
      else if (type === "MEDIA_GROUP") notificationBody = "📷 Media";
      else if (type === "STATUS_REPLY") notificationBody = text === "❤️"
        ? "Liked your status"
        : "Replied to your status: " + (text || "");

      // Data-only payload for rich replacement/actions
      const isArchivedForRecipient = await archivePromise;

      // SIMPLIFIED: Always send data-only, let client decide based on local DB
      console.log("Sending legacy FCM (data-only) to:", recipientId, "archived:", isArchivedForRecipient);

      const payload = {
        token: fcmToken,
        android: {
          priority: "high",
          ttl: 86400,
          directBootOk: true,
        },
        data: {
          title: senderName,
          body: notificationBody,
          text: text,
          chat_id: chatId || "",
          other_user_id: senderId || "",
          sender_avatar: senderAvatar,
          sender_name: senderName,
          type: type,
          imageUrl: repImageUrl,
          videoUrl: repVideoUrl,
          thumbnailUrl: repThumbnailUrl,
          mediaItems: type === "MEDIA_GROUP" ? JSON.stringify(mediaItems) : "",
          mediaCount: type === "MEDIA_GROUP" ? String(mediaItems.length) : "",
          messageId: messageId || "",
          mimeType: mimeType,
          fileSize: fileSize,
          videoDuration: videoDuration,
          show_notification: isArchivedForRecipient ? "false" : "true",
          timestamp: String(Date.now()),
          click_action: "OPEN_CHAT",
        },
      };

      try {
        const response = await admin.messaging().send(payload);
        console.log("Legacy FCM sent:", response);
        return response;
      } catch (error) {
        console.error("Legacy FCM error:", error);
        return null;
      }
    });

/**
 * Callable: sendBuzz
 * Sends a "Buzz" notification to the recipient.
 * Ephemeral, high priority, TTL=0.
 */
exports.sendBuzz = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Authentication required");
  }

  const senderId = context.auth.uid;
  const chatId = data.chatId;
  const recipientId = data.recipientId;

  if (!chatId || !recipientId) {
    throw new functions.https.HttpsError("invalid-argument", "Missing parameters");
  }

  const userDoc = await admin.firestore().collection("users").doc(recipientId).get();
  if (!userDoc.exists) {
     return { status: "user_not_found" };
  }
  const userData = userDoc.data();
  const fcmToken = userData.fcmToken;

  if (!fcmToken) {
    return { status: "no_token" };
  }

  const senderDoc = await admin.firestore().collection("users").doc(senderId).get();
  const senderName = senderDoc.exists ? (senderDoc.data().username || "Someone") : "Someone";

  // Check if chat is archived for recipient - if so, suppress notification
  const isArchivedForRecipient = await isChatArchivedForRecipient(recipientId, chatId, senderId);
  const shouldShowNotification = !isArchivedForRecipient;

  if (isArchivedForRecipient) {
    console.log("Recipient has chat archived - buzz will be silent:", { recipientId, chatId });
  }

  const payload = {
    token: fcmToken,
    data: {
      type: "BUZZ",
      chat_id: chatId,
      senderId: senderId,
      senderName: senderName,
      timestamp: String(Date.now()),
      show_notification: shouldShowNotification ? "true" : "false"
    },
    android: {
      priority: "high"
    }
  };

  try {
    await admin.messaging().send(payload);
    return { status: "sent" };
  } catch (error) {
    console.error("Error sending buzz:", error);
    // Log more details to help debug
    console.error("Error details:", {
      code: error.code,
      message: error.message,
      recipientId: recipientId,
      hasFcmToken: !!fcmToken
    });
    throw new functions.https.HttpsError("internal", "Failed to send buzz: " + error.message);
  }
});

/**
 * Callable: repairArchivedChatMarkers (temporary maintenance utility)
 *
 * Purpose:
 * - Fix inconsistent archived marker variants inside users/{uid}/archived_chats
 * - Resolves state per conversation using the latest updatedAt marker
 * - Normalizes all docs in that conversation to the resolved archived value
 *
 * Auth:
 * - Non-admin users can only repair their own uid
 * - Admin users (custom claim: admin=true) can repair all users or a target uid
 */
exports.repairArchivedChatMarkers = functions
    .runWith({ timeoutSeconds: 540, memory: "1GB" })
    .https.onCall(async (data, context) => {
      if (!context.auth || !context.auth.uid) {
        throw new functions.https.HttpsError("unauthenticated", "Authentication required");
      }

      const requesterUid = context.auth.uid;
      const isAdmin = context.auth.token && context.auth.token.admin === true;
      const dryRun = !!(data && data.dryRun === true);
      const targetUidRaw = (typeof data?.targetUid === "string" ? data.targetUid.trim() : "");
      const limitUsersRaw = Number(data?.limitUsers || 200);
      const limitUsers = Number.isFinite(limitUsersRaw)
        ? Math.min(Math.max(Math.floor(limitUsersRaw), 1), 5000)
        : 200;

      if (!isAdmin && targetUidRaw && targetUidRaw !== requesterUid) {
        throw new functions.https.HttpsError("permission-denied", "Not allowed to repair another user");
      }

      const targetUid = targetUidRaw || (!isAdmin ? requesterUid : "");
      const db = admin.firestore();

      const usersToProcess = [];
      if (targetUid) {
        usersToProcess.push(targetUid);
      } else {
        const usersSnap = await db.collection("users").limit(limitUsers).get();
        usersSnap.forEach((doc) => usersToProcess.push(doc.id));
      }

      let usersScanned = 0;
      let usersTouched = 0;
      let groupsProcessed = 0;
      let docsScanned = 0;
      let docsUpdated = 0;
      const sampleChanges = [];

      const getUpdatedAtMs = (value) => {
        if (value && typeof value.toMillis === "function") return value.toMillis();
        return 0;
      };

      const toArchivedBool = (value, hasField) => {
        if (!hasField) return true; // Legacy marker without field = archived
        return value === true || value === "true";
      };

      const conversationKeyForDoc = (docId, docData) => {
        const chatIdFromData = typeof docData.chatId === "string" ? docData.chatId.trim() : "";
        if (chatIdFromData) return `chat:${chatIdFromData}`;

        const parts = docId.split("_");
        if (parts.length === 2) {
          return `pair:${[parts[0], parts[1]].sort().join("_")}`;
        }
        return `doc:${docId}`;
      };

      for (const uid of usersToProcess) {
        usersScanned += 1;

        const col = db.collection("users").doc(uid).collection("archived_chats");
        const snap = await col.get();
        if (snap.empty) continue;

        const groups = new Map();
        snap.forEach((doc) => {
          const raw = doc.data() || {};
          const hasArchivedField = Object.prototype.hasOwnProperty.call(raw, "archived");
          const archived = toArchivedBool(raw.archived, hasArchivedField);
          const updatedAtMs = getUpdatedAtMs(raw.updatedAt);
          const chatId = typeof raw.chatId === "string" ? raw.chatId.trim() : "";
          const otherUserId = typeof raw.otherUserId === "string" ? raw.otherUserId.trim() : "";
          const key = conversationKeyForDoc(doc.id, raw);

          const item = {
            docId: doc.id,
            ref: doc.ref,
            archived,
            hasArchivedField,
            updatedAtMs,
            chatId,
            otherUserId,
          };

          if (!groups.has(key)) groups.set(key, []);
          groups.get(key).push(item);
          docsScanned += 1;
        });

        let userHadChanges = false;
        let batch = db.batch();
        let batchOps = 0;
        const commitBatch = async () => {
          if (batchOps === 0 || dryRun) return;
          await batch.commit();
          batch = db.batch();
          batchOps = 0;
        };

        for (const [groupKey, items] of groups.entries()) {
          groupsProcessed += 1;

          const latest = items.reduce((best, candidate) => {
            if (!best) return candidate;
            if (candidate.updatedAtMs > best.updatedAtMs) return candidate;
            return best;
          }, null);

          const resolvedArchived = latest ? latest.archived : false;
          const resolvedChatId = (latest && latest.chatId) || (latest && latest.docId) || "";
          const resolvedOtherUserId = latest && latest.otherUserId ? latest.otherUserId : "";

          for (const item of items) {
            const needsUpdate =
              item.archived !== resolvedArchived ||
              !item.hasArchivedField ||
              (resolvedChatId && item.chatId !== resolvedChatId) ||
              (resolvedOtherUserId && item.otherUserId !== resolvedOtherUserId);

            if (!needsUpdate) continue;

            userHadChanges = true;
            docsUpdated += 1;

            if (sampleChanges.length < 25) {
              sampleChanges.push({ uid, groupKey, docId: item.docId, from: item.archived, to: resolvedArchived });
            }

            if (!dryRun) {
              batch.set(
                  item.ref,
                  {
                    archived: resolvedArchived,
                    chatId: resolvedChatId || item.docId,
                    otherUserId: resolvedOtherUserId,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                  },
                  { merge: true }
              );
              batchOps += 1;
              if (batchOps >= 450) {
                await commitBatch();
              }
            }
          }
        }

        await commitBatch();

        if (userHadChanges) usersTouched += 1;
      }

      const response = {
        ok: true,
        dryRun,
        isAdmin,
        scope: targetUid ? "single-user" : "multi-user",
        targetUid: targetUid || null,
        requestedLimitUsers: limitUsers,
        usersScanned,
        usersTouched,
        groupsProcessed,
        docsScanned,
        docsUpdated,
        sampleChanges,
      };

      console.log("repairArchivedChatMarkers result:", response);
      return response;
    });

// ─── Translation + TTS ────────────────────────────────────
const translateModule = require("./translate");
exports.translateMessage = translateModule.translateMessage;

// ─── Speech-to-Text ───────────────────────────────────────
const sttModule = require("./speechToText");
exports.speechToText = sttModule.speechToText;

// ─── AI Message Enhancement ───────────────────────────────
const enhanceModule = require("./enhanceMessage");
exports.enhanceMessage = enhanceModule.enhanceMessage;

// ─── Global AI Agent ──────────────────────────────────────
const aiAgentModule = require("./glyphAiAgent");
exports.glyphAiAgent = aiAgentModule.glyphAiAgent;

// ═══════════════════════════════════════════════════════════
// ─── Block User: Firestore → RTDB Mirror ──────────────────
// ═══════════════════════════════════════════════════════════
// Mirror Firestore blockedUsers sub-collection to RTDB /blocks/{userId}/{blockedId}
// so RTDB security rules can enforce block checks on pending_messages and presence.

/**
 * When a user blocks someone (Firestore write to /users/{userId}/blockedUsers/{blockedId}),
 * mirror the block to RTDB at /blocks/{userId}/{blockedId}.
 */
exports.onBlockCreated = functions.firestore
  .document("users/{userId}/blockedUsers/{blockedId}")
  .onCreate(async (snap, context) => {
    const { userId, blockedId } = context.params;
    console.log(`Block created: ${userId} blocked ${blockedId}`);

    try {
      await admin.database().ref(`blocks/${userId}/${blockedId}`)
        .set({ blockedAt: admin.database.ServerValue.TIMESTAMP });

      // Also clean up any pending messages from the blocked user
      // that might be sitting in the recipient's queue
      const pendingRef = admin.database().ref(`pending_messages/${userId}`);
      const pendingSnap = await pendingRef.orderByChild("senderId").equalTo(blockedId).once("value");
      if (pendingSnap.exists()) {
        const updates = {};
        pendingSnap.forEach((child) => {
          updates[child.key] = null; // Delete
        });
        await pendingRef.update(updates);
        console.log(`Cleaned ${Object.keys(updates).length} pending messages from blocked user ${blockedId}`);
      }

      console.log(`RTDB block mirror created: blocks/${userId}/${blockedId}`);
    } catch (err) {
      console.error("Failed to mirror block to RTDB:", err);
    }
  });

/**
 * When a user unblocks someone, remove the RTDB mirror.
 */
exports.onBlockDeleted = functions.firestore
  .document("users/{userId}/blockedUsers/{blockedId}")
  .onDelete(async (snap, context) => {
    const { userId, blockedId } = context.params;
    console.log(`Block removed: ${userId} unblocked ${blockedId}`);

    try {
      await admin.database().ref(`blocks/${userId}/${blockedId}`).remove();
      console.log(`RTDB block mirror removed: blocks/${userId}/${blockedId}`);
    } catch (err) {
      console.error("Failed to remove RTDB block mirror:", err);
    }
  });

/**
 * Daily cleanup of the `processed_messages` dedup-lock store.
 * Nodes older than 7 days are deleted in a single multi-path update.
 * This prevents the RTDB `processed_messages/` tree from growing without bound.
 *
 * Deploy: firebase deploy --only functions:cleanupProcessedMessages
 */
exports.cleanupProcessedMessages = functions.pubsub
  .schedule("every 24 hours")
  .onRun(async () => {
    const TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 days
    const cutoff = Date.now() - TTL_MS;

    try {
      const ref = admin.database().ref("processed_messages");
      const snap = await ref
        .orderByChild("timestamp")
        .endAt(cutoff)
        .once("value");

      if (!snap.exists()) {
        console.log("cleanupProcessedMessages: nothing to delete");
        return null;
      }

      const updates = {};
      snap.forEach((child) => {
        updates[child.key] = null;
      });

      const count = Object.keys(updates).length;
      await ref.update(updates);
      console.log(`cleanupProcessedMessages: deleted ${count} stale lock(s) older than 7 days`);
    } catch (err) {
      console.error("cleanupProcessedMessages failed:", err);
    }
    return null;
  });

/**
 * Firestore trigger: when a new call document is created, send an INCOMING_CALL
 * FCM data message to the receiver so their device wakes up and shows the call UI.
 *
 * Deploy: firebase deploy --only functions:sendIncomingCallNotification
 */
exports.sendIncomingCallNotification = functions.firestore
  .document("calls/{callId}")
  .onCreate(async (snap, context) => {
    const call = snap.data();
    if (!call) return null;

    const { callerId, receiverId, type: callType } = call;
    if (!callerId || !receiverId) {
      console.log("sendIncomingCallNotification: missing callerId or receiverId");
      return null;
    }

    try {
      const [callerDoc, receiverDoc] = await Promise.all([
        admin.firestore().collection("users").doc(callerId).get(),
        admin.firestore().collection("users").doc(receiverId).get(),
      ]);

      const callerData = callerDoc.exists ? callerDoc.data() : {};
      const callerName = callerData.username || "Unknown";
      const callerAvatar = callerData.profileImageUrl || "";
      const callerPhone =
        call.callerPhone ||
        callerData.phoneNumber ||
        callerData.phone ||
        callerData.mobile ||
        "";

      if (!receiverDoc.exists) {
        console.log("sendIncomingCallNotification: receiver doc not found:", receiverId);
        return null;
      }
      const receiverData = receiverDoc.data();
      const fcmToken = receiverData.fcmToken;
      if (!fcmToken) {
        console.log("sendIncomingCallNotification: receiver has no FCM token:", receiverId);
        return null;
      }

      const callId = context.params.callId;

      const payload = {
        token: fcmToken,
        android: {
          priority: "high",
          ttl: 30000, // Only relevant for 30 seconds
          directBootOk: true,
        },
        data: {
          type: "INCOMING_CALL",
          call_id: callId,
          caller_id: callerId,
          caller_name: callerName,
          caller_phone: String(callerPhone),
          caller_avatar: callerAvatar,
          call_type: callType === "VIDEO" ? "VIDEO" : "VOICE",
          created_at: String(call.createdAt || Date.now()),
        },
      };

      const response = await admin.messaging().send(payload);

      snap.ref.update({
        callerName: callerName,
        callerAvatar: callerAvatar,
      }).catch((err) => {
        console.warn("sendIncomingCallNotification: failed to backfill caller metadata", err);
      });

      console.log("Incoming call FCM sent:", response, "callId:", callId, "to:", receiverId);
      return response;
    } catch (err) {
      console.error("sendIncomingCallNotification error:", err);
      return null;
    }
  });

/**
 * RTDB trigger: when a new liveAudioSession is created (listener requests to join),
 * send a LIVE_AUDIO_REQUEST FCM data message to the broadcaster so their device
 * wakes up and handles the request even when the app is closed.
 *
 * Deploy: firebase deploy --only functions:notifyLiveAudioRequest
 */
exports.notifyLiveAudioRequest = functions.database
  .ref("liveAudioSessions/{sessionId}")
  .onCreate(async (snapshot, context) => {
    const session = snapshot.val();
    if (!session) return null;

    const { broadcasterId, listenerId, sessionId: sessionIdField, status } = session;
    const sessionId = context.params.sessionId;

    // Only act on fresh REQUESTING sessions
    if (status !== "requesting") {
      console.log("notifyLiveAudioRequest: ignoring non-requesting session:", sessionId, status);
      return null;
    }

    if (!broadcasterId || !listenerId) {
      console.log("notifyLiveAudioRequest: missing broadcasterId or listenerId");
      return null;
    }

    // Don't notify yourself
    if (broadcasterId === listenerId) return null;

    try {
      // Fetch broadcaster FCM token from Firestore
      const broadcasterDoc = await admin.firestore().collection("users").doc(broadcasterId).get();
      if (!broadcasterDoc.exists) {
        console.log("notifyLiveAudioRequest: broadcaster doc not found:", broadcasterId);
        return null;
      }
      const broadcasterData = broadcasterDoc.data();
      const fcmToken = broadcasterData.fcmToken;
      if (!fcmToken) {
        console.log("notifyLiveAudioRequest: broadcaster has no FCM token:", broadcasterId);
        return null;
      }

      // Fetch listener name for notification display
      const listenerDoc = await admin.firestore().collection("users").doc(listenerId).get();
      const listenerName = listenerDoc.exists ? (listenerDoc.data().username || "Someone") : "Someone";

      const payload = {
        token: fcmToken,
        android: {
          priority: "high",
          ttl: 60000, // Only relevant for 60 seconds
          directBootOk: true,
        },
        data: {
          type: "LIVE_AUDIO_REQUEST",
          session_id: sessionId,
          broadcaster_id: broadcasterId,
          listener_id: listenerId,
          listener_name: listenerName,
          created_at: String(session.createdAt || Date.now()),
        },
      };

      const response = await admin.messaging().send(payload);
      console.log("Live audio request FCM sent:", response, "sessionId:", sessionId, "to:", broadcasterId);
      return response;
    } catch (err) {
      console.error("notifyLiveAudioRequest error:", err);
      return null;
    }
  });

/**
 * RTDB trigger: when a new walkieTalkieSession is created (initiator requests),
 * send a WALKIE_TALKIE_REQUEST FCM data message to the responderId so their device
 * wakes up and handles the request even when the app is closed.
 *
 * Deploy: firebase deploy --only functions:notifyWalkieTalkieRequest
 */
exports.notifyWalkieTalkieRequest = functions.database
  .ref("walkieTalkieSessions/{sessionId}")
  .onCreate(async (snapshot, context) => {
    const session = snapshot.val();
    if (!session) return null;

    const { initiatorId, responderId, status } = session;
    const sessionId = context.params.sessionId;

    // Only act on fresh REQUESTING sessions
    if (status !== "requesting") {
      console.log("notifyWalkieTalkieRequest: ignoring non-requesting session:", sessionId, status);
      return null;
    }

    if (!initiatorId || !responderId) {
      console.log("notifyWalkieTalkieRequest: missing initiatorId or responderId");
      return null;
    }

    // Don't notify yourself
    if (initiatorId === responderId) return null;

    try {
      // Fetch responder FCM token from Firestore
      const responderDoc = await admin.firestore().collection("users").doc(responderId).get();
      if (!responderDoc.exists) {
        console.log("notifyWalkieTalkieRequest: responder doc not found:", responderId);
        return null;
      }
      const responderData = responderDoc.data();
      const fcmToken = responderData.fcmToken;
      if (!fcmToken) {
        console.log("notifyWalkieTalkieRequest: responder has no FCM token:", responderId);
        return null;
      }

      // Prefer the name already written into the RTDB session so the wake-up FCM
      // does not pay for an extra Firestore round-trip on the hot path.
      let initiatorName = typeof session.initiatorName === "string" ? session.initiatorName.trim() : "";
      if (!initiatorName) {
        const initiatorDoc = await admin.firestore().collection("users").doc(initiatorId).get();
        initiatorName = initiatorDoc.exists ? (initiatorDoc.data().username || "Someone") : "Someone";
      }

      const initialOffer = typeof session.offer === "string" ? session.offer : "";
      const initialOfferB64 = initialOffer
        ? Buffer.from(initialOffer, "utf8").toString("base64")
        : "";
      const includeInitialOffer = initialOfferB64.length > 0 && initialOfferB64.length <= 3000;

      const payload = {
        token: fcmToken,
        android: {
          priority: "high",
          ttl: 60000,
          directBootOk: true,
        },
        data: {
          type: "WALKIE_TALKIE_REQUEST",
          session_id: sessionId,
          initiator_id: initiatorId,
          responder_id: responderId,
          initiator_name: initiatorName,
          created_at: String(session.createdAt || Date.now()),
          offer_b64: includeInitialOffer ? initialOfferB64 : "",
          offer_revision: String(session.offerRevision || (initialOffer ? 1 : 0)),
        },
      };

      const response = await admin.messaging().send(payload);
      console.log("Walkie-talkie request FCM sent:", response, "sessionId:", sessionId, "to:", responderId);
      return response;
    } catch (err) {
      console.error("notifyWalkieTalkieRequest error:", err);
      return null;
    }
  });

/**
 * Firestore trigger: when a new groupCallInvitations document is created,
 * send a GROUP_CALL_INVITATION FCM data message to the target user so their
 * device wakes up and shows the incoming group call UI.
 *
 * Deploy: firebase deploy --only functions:sendGroupCallInvitation
 */
exports.sendGroupCallInvitation = functions.firestore
  .document("groupCallInvitations/{invitationId}")
  .onCreate(async (snap, context) => {
    const invitation = snap.data();
    if (!invitation) return null;

    const {
      groupCallId,
      callType,
      targetUserId,
      targetFcmToken,
      callerUserId,
      callerName,
      callerAvatar,
      participantCount,
    } = invitation;

    if (!targetFcmToken || !groupCallId) {
      console.log("sendGroupCallInvitation: missing token or groupCallId");
      // Mark as processed so it doesn't get retried
      await snap.ref.update({ processed: true });
      return null;
    }

    try {
      const payload = {
        token: targetFcmToken,
        android: {
          priority: "high",
          ttl: 30000,
          directBootOk: true,
        },
        data: {
          type: "GROUP_CALL_INVITATION",
          group_call_id: groupCallId,
          call_type: callType === "VIDEO" ? "VIDEO" : "VOICE",
          caller_name: callerName || "Unknown",
          caller_avatar: callerAvatar || "",
          caller_user_id: callerUserId || "",
          participant_count: String(participantCount || 2),
        },
      };

      const response = await admin.messaging().send(payload);
      console.log(
        "Group call invitation FCM sent:",
        response,
        "groupCallId:",
        groupCallId,
        "to:",
        targetUserId
      );

      // Mark as processed
      await snap.ref.update({ processed: true });
      return response;
    } catch (err) {
      console.error("sendGroupCallInvitation error:", err);
      await snap.ref.update({ processed: true, error: err.message });
      return null;
    }
  });

/**
 * RTDB trigger: when a camera invite is written to a user's command slot,
 * send a CAMERA_INVITE FCM data message so the recipient's device wakes up
 * and shows an invite prompt even when the app is in the background.
 *
 * Path: mapVideoSessions/{chatId}/commands/{targetUserId}/cameraInvite
 * Deploy: firebase deploy --only functions:notifyCameraInvite
 */
exports.notifyCameraInvite = functions.database
  .ref("mapVideoSessions/{chatId}/commands/{targetUserId}/cameraInvite")
  .onWrite(async (change, context) => {
    const after = change.after.exists() ? change.after.val() : null;
    if (!after) return null;

    const { chatId, targetUserId } = context.params;
    const requestId = typeof after.requestId === "string" ? after.requestId : "";
    const senderUserId = typeof after.senderUserId === "string" ? after.senderUserId : "";
    const senderName = typeof after.senderName === "string" ? after.senderName : "Someone";
    const createdAt = typeof after.createdAt === "number" ? after.createdAt : 0;

    // Drop stale invites (older than the invite timeout on the client, 12 s)
    if (createdAt > 0 && Date.now() - createdAt > 15000) {
      console.log("notifyCameraInvite: stale invite, skipping", { chatId, targetUserId, requestId });
      return null;
    }

    const recipientDoc = await admin.firestore().collection("users").doc(targetUserId).get();
    if (!recipientDoc.exists) return null;
    const fcmToken = (recipientDoc.data() || {}).fcmToken;
    if (!fcmToken) return null;

    const payload = {
      token: fcmToken,
      android: {
        priority: "high",
        ttl: 15000,
        directBootOk: true,
      },
      data: {
        type: "CAMERA_INVITE",
        chat_id: chatId,
        sender_user_id: senderUserId,
        sender_name: senderName,
        request_id: requestId,
        created_at: String(createdAt),
      },
    };

    try {
      const result = await admin.messaging().send(payload);
      console.log("notifyCameraInvite FCM sent to", targetUserId, ":", result);
    } catch (err) {
      console.error("notifyCameraInvite FCM error:", err.code, err.message);
      if (
        err.code === "messaging/invalid-registration-token" ||
        err.code === "messaging/registration-token-not-registered"
      ) {
        await admin.firestore().collection("users").doc(targetUserId)
          .update({ fcmToken: admin.firestore.FieldValue.delete() }).catch(() => {});
      }
    }
    return null;
  });

/**
 * RTDB trigger: when the recipient responds to a camera invite (accepted or declined),
 * send a CAMERA_INVITE_RESPONSE FCM data message back to the original inviter.
 *
 * Path: mapVideoSessions/{chatId}/commands/{targetUserId}/cameraInviteResponse
 * targetUserId here is the original inviter who should receive the response notification.
 * Deploy: firebase deploy --only functions:notifyCameraInviteResponse
 */
exports.notifyCameraInviteResponse = functions.database
  .ref("mapVideoSessions/{chatId}/commands/{targetUserId}/cameraInviteResponse")
  .onWrite(async (change, context) => {
    const after = change.after.exists() ? change.after.val() : null;
    if (!after) return null;

    const { chatId, targetUserId } = context.params;
    const requestId = typeof after.requestId === "string" ? after.requestId : "";
    const response = typeof after.response === "string" ? after.response : "";
    const responderUserId = typeof after.responderUserId === "string" ? after.responderUserId : "";
    const responderName = typeof after.responderName === "string" ? after.responderName : "Someone";

    if (response !== "accepted" && response !== "declined") {
      console.log("notifyCameraInviteResponse: unknown response, skipping", { response, chatId, targetUserId });
      return null;
    }

    const recipientDoc = await admin.firestore().collection("users").doc(targetUserId).get();
    if (!recipientDoc.exists) return null;
    const fcmToken = (recipientDoc.data() || {}).fcmToken;
    if (!fcmToken) return null;

    const payload = {
      token: fcmToken,
      android: {
        priority: "high",
        ttl: 30000,
        directBootOk: true,
      },
      data: {
        type: "CAMERA_INVITE_RESPONSE",
        chat_id: chatId,
        responder_user_id: responderUserId,
        responder_name: responderName,
        response: response,
        request_id: requestId,
      },
    };

    try {
      const result = await admin.messaging().send(payload);
      console.log("notifyCameraInviteResponse FCM sent to", targetUserId, ":", result);
    } catch (err) {
      console.error("notifyCameraInviteResponse FCM error:", err.code, err.message);
      if (
        err.code === "messaging/invalid-registration-token" ||
        err.code === "messaging/registration-token-not-registered"
      ) {
        await admin.firestore().collection("users").doc(targetUserId)
          .update({ fcmToken: admin.firestore.FieldValue.delete() }).catch(() => {});
      }
    }
    return null;
  });

/**
 * RTDB trigger: when the inviter writes a missedCameraInvite node (after their invite
 * times out with no response from the receiver), send a "missed camera request" FCM
 * notification to the receiver. Uses the default FCM TTL (~4 weeks) so the message
 * is delivered whenever the receiver comes back online.
 *
 * Path: mapVideoSessions/{chatId}/commands/{targetUserId}/missedCameraInvite
 * Deploy: firebase deploy --only functions:notifyMissedCameraInvite
 */
exports.notifyMissedCameraInvite = functions.database
  .ref("mapVideoSessions/{chatId}/commands/{targetUserId}/missedCameraInvite")
  .onWrite(async (change, context) => {
    // Only act on creates/updates — skip deletes (including the self-delete below).
    const after = change.after.exists() ? change.after.val() : null;
    if (!after) return null;

    const { chatId, targetUserId } = context.params;
    const requestId = typeof after.requestId === "string" ? after.requestId : "";
    const senderUserId = typeof after.senderUserId === "string" ? after.senderUserId : "";
    const senderName = typeof after.senderName === "string" ? after.senderName : "Someone";

    // Remove the node immediately to prevent duplicate notifications if this function retries.
    await change.after.ref.remove().catch(() => {});

    const recipientDoc = await admin.firestore().collection("users").doc(targetUserId).get();
    if (!recipientDoc.exists) return null;
    const fcmToken = (recipientDoc.data() || {}).fcmToken;
    if (!fcmToken) return null;

    // No android.ttl set — uses FCM default (~4 weeks) so offline devices receive it
    // whenever they reconnect, no matter how long they were offline.
    const payload = {
      token: fcmToken,
      android: {
        priority: "high",
        directBootOk: true,
      },
      data: {
        type: "MISSED_CAMERA_INVITE",
        chat_id: chatId,
        sender_user_id: senderUserId,
        sender_name: senderName,
        request_id: requestId,
      },
    };

    try {
      const result = await admin.messaging().send(payload);
      console.log("notifyMissedCameraInvite FCM sent to", targetUserId, ":", result);
    } catch (err) {
      console.error("notifyMissedCameraInvite FCM error:", err.code, err.message);
      if (
        err.code === "messaging/invalid-registration-token" ||
        err.code === "messaging/registration-token-not-registered"
      ) {
        await admin.firestore().collection("users").doc(targetUserId)
          .update({ fcmToken: admin.firestore.FieldValue.delete() }).catch(() => {});
      }
    }
    return null;
  });
/**
 * Daily Storage inventory.
 *
 * Sums the size of every object in the default Cloud Storage bucket and writes
 * the running total to `config/storage`, so the admin dashboard can show real
 * storage usage. The portal reads `config/storage.usedBytes` and falls back to
 * 0 when this doc is absent (e.g. before the first run).
 *
 * Deploy: firebase deploy --only functions:computeStorageInventory
 */
exports.computeStorageInventory = functions.pubsub
  .schedule("every 24 hours")
  .onRun(async () => {
    try {
      const bucket = admin.storage().bucket();
      const [files] = await bucket.getFiles();
      let usedBytes = 0;
      for (const file of files) {
        const size = Number(file.metadata && file.metadata.size);
        if (!Number.isNaN(size) && size > 0) usedBytes += size;
      }
      await admin
        .firestore()
        .collection("config")
        .doc("storage")
        .set(
          { usedBytes, fileCount: files.length, bucket: bucket.name, computedAt: Date.now() },
          { merge: true },
        );
      console.log(`computeStorageInventory: ${files.length} file(s), ${usedBytes} byte(s)`);
    } catch (err) {
      console.error("computeStorageInventory failed:", err);
    }
    return null;
  });

/**
 * Per-minute dispatch of due scheduled notifications.
 *
 * Reads `scheduled_notifications` whose `scheduledAt` has passed and whose
 * status is still "scheduled", sends each via FCM (topic broadcast or
 * multicast to resolved tokens — mirroring the portal's send path), then marks
 * the doc sent/failed and updates the linked `notifications_log` entry. The
 * portal's "Send now" affordance does this manually; this function automates
 * it so scheduled sends go out without an operator.
 *
 * A doc is claimed by flipping its status to "dispatching" before send, so
 * overlapping runs never double-send the same notification.
 *
 * Deploy: firebase deploy --only functions:dispatchScheduledNotifications
 */
exports.dispatchScheduledNotifications = functions.pubsub
  .schedule("every 1 minutes")
  .onRun(async () => {
    const now = Date.now();
    let due;
    try {
      const snap = await admin
        .firestore()
        .collection("scheduled_notifications")
        .where("status", "==", "scheduled")
        .where("scheduledAt", "<=", now)
        .get();
      due = snap.docs;
    } catch (err) {
      console.error("dispatchScheduledNotifications: query failed:", err);
      return null;
    }
    if (!due.length) return null;

    const BROADCAST_TOPIC = "glyph_broadcast";
    let dispatched = 0;
    let failed = 0;

    for (const doc of due) {
      const data = doc.data();
      // Claim the doc so an overlapping run won't pick it up again.
      await doc.ref.update({ status: "dispatching", dispatchedAt: now }).catch(() => {});

      const draft = {
        title: data.title,
        body: data.body,
        imageUrl: data.imageUrl,
        deepLink: data.deepLink,
        target: data.target,
        topic: data.topic,
        userIds: data.userIds,
        data: data.data || {},
      };

      let successCount = 0;
      let failureCount = 0;
      const failures = [];

      try {
        const messaging = admin.messaging();
        const fcmData = Object.assign({ type: "glyph_admin" }, draft.data || {});
        if (draft.deepLink) fcmData.deepLink = draft.deepLink;
        const android = { priority: "high", notification: {} };
        if (draft.imageUrl) android.notification.imageUrl = draft.imageUrl;
        if (draft.deepLink) android.notification.clickAction = "OPEN_DEEP_LINK";

        if (draft.target === "userIds" && draft.userIds && draft.userIds.length) {
          const ids = Array.from(new Set(draft.userIds.filter(Boolean)));
          const refs = ids.map((id) => admin.firestore().collection("users").doc(id));
          const snaps = await admin.firestore().getAll(...refs);
          // Keep the id and token together so a send failure maps back to the
          // correct user — filtering out users without a token shifts the index
          // relative to the original `ids` array.
          const entries = snaps
            .map((s, i) => ({ id: ids[i], token: s.get("fcmToken") }))
            .filter((e) => e.token);
          const tokens = entries.map((e) => e.token);
          if (!tokens.length) {
            failureCount = ids.length;
            failures.push({ error: "No registered FCM tokens for the selected users" });
          } else {
            const res = await messaging.sendEachForMulticast({
              tokens,
              notification: { title: draft.title, body: draft.body },
              data: fcmData,
              android,
            });
            successCount = res.successCount;
            failureCount = res.failureCount;
            res.responses.forEach((r, i) => {
              if (!r.success) {
                failures.push({ id: entries[i].id, error: (r.error && r.error.message) || "send failed" });
              }
            });
          }
        } else {
          const topic = draft.target === "topic" && draft.topic ? draft.topic : BROADCAST_TOPIC;
          await messaging.send({
            topic,
            notification: { title: draft.title, body: draft.body },
            data: fcmData,
            android,
          });
          successCount = 1;
        }
      } catch (err) {
        failureCount = draft.target === "userIds" && draft.userIds ? draft.userIds.length : 1;
        successCount = 0;
        failures.push({ error: err.message });
      }

      const status = failureCount === 0 ? "sent" : successCount === 0 ? "failed" : "partial";
      await doc.ref
        .update({ status, sentAt: now, successCount, failureCount })
        .catch(() => {});

      if (data.logId) {
        await admin
          .firestore()
          .collection("notifications_log")
          .doc(data.logId)
          .update({
            status,
            sentAt: now,
            successCount,
            failureCount,
            failures: failures.length ? failures : admin.firestore.FieldValue.delete(),
          })
          .catch(() => {});
      }

      if (status === "sent") dispatched++;
      else failed++;
    }

    console.log(`dispatchScheduledNotifications: ${dispatched} sent, ${failed} failed`);
    return null;
  });
