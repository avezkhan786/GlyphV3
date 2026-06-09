/**
 * ADDITIONAL FIREBASE CLOUD FUNCTIONS
 * 
 * Add these functions to your functions/index.js file to enhance
 * the presence and notification system reliability.
 * 
 * IMPORTANT: These functions require the Firebase Blaze (Pay as you go) plan
 * for scheduled functions and RTDB access.
 */

// ==================== STALE PRESENCE CLEANUP ====================

/**
 * Clean up stale presence data
 * 
 * Runs every 5 minutes and marks users as offline if their lastHeartbeat
 * is older than 5 minutes. This is a server-side safeguard against clients
 * that disconnect abnormally without triggering onDisconnect() handlers.
 * 
 * Usage: Add this to functions/index.js
 */
exports.cleanupStalePresence = functions.pubsub
    .schedule('every 5 minutes')
    .timeZone('UTC')
    .onRun(async (context) => {
      const now = Date.now();
      const STALE_THRESHOLD = 5 * 60 * 1000; // 5 minutes

      try {
        const presenceRef = admin.database().ref('presence');
        const snapshot = await presenceRef.once('value');
        const updates = {};
        let staleCount = 0;

        snapshot.forEach((child) => {
          const userId = child.key;
          const presence = child.val();

          // Only process users marked as online
          if (presence && presence.isOnline) {
            const lastHeartbeat = presence.lastHeartbeat || presence.lastSeen || 0;
            const age = now - lastHeartbeat;

            // If heartbeat is too old, mark as offline
            if (age > STALE_THRESHOLD) {
              console.log(`Marking user ${userId} as offline (stale for ${Math.floor(age / 1000)}s)`);
              updates[`presence/${userId}/isOnline`] = false;
              // CRITICAL FIX: Use lastHeartbeat as lastSeen, NOT Date.now().
              // lastHeartbeat represents the last actual user activity.
              // Using Date.now() would create a phantom "last seen" bump at cleanup time,
              // making it look like the user was recently active when they weren't.
              updates[`presence/${userId}/lastSeen`] = lastHeartbeat;
              staleCount++;
            }
          }
        });

        if (Object.keys(updates).length > 0) {
          await admin.database().ref().update(updates);
          console.log(`✅ Cleaned up ${staleCount} stale presence records`);
        } else {
          console.log('✅ No stale presence records found');
        }

        return null;
      } catch (error) {
        console.error('❌ Error cleaning up presence:', error);
        throw error;
      }
    });

// ==================== PRESENCE MONITORING ====================

/**
 * Monitor presence system health
 * 
 * Runs hourly and logs statistics about the presence system.
 * Useful for monitoring and debugging presence issues.
 */
exports.monitorPresenceHealth = functions.pubsub
    .schedule('every 1 hours')
    .timeZone('UTC')
    .onRun(async (context) => {
      try {
        const presenceRef = admin.database().ref('presence');
        const snapshot = await presenceRef.once('value');
        
        let totalUsers = 0;
        let onlineUsers = 0;
        let usersWithRecentHeartbeat = 0;
        let staleOnlineUsers = 0;
        const now = Date.now();
        const HEARTBEAT_THRESHOLD = 60 * 1000; // 1 minute

        snapshot.forEach((child) => {
          totalUsers++;
          const presence = child.val();

          if (presence && presence.isOnline) {
            onlineUsers++;
            
            const lastHeartbeat = presence.lastHeartbeat || presence.lastSeen || 0;
            const age = now - lastHeartbeat;

            if (age < HEARTBEAT_THRESHOLD) {
              usersWithRecentHeartbeat++;
            } else {
              staleOnlineUsers++;
            }
          }
        });

        const stats = {
          timestamp: new Date().toISOString(),
          totalUsers,
          onlineUsers,
          offlineUsers: totalUsers - onlineUsers,
          usersWithRecentHeartbeat,
          staleOnlineUsers,
          onlinePercentage: totalUsers > 0 ? (onlineUsers / totalUsers * 100).toFixed(2) : 0,
          healthScore: onlineUsers > 0 ? (usersWithRecentHeartbeat / onlineUsers * 100).toFixed(2) : 100
        };

        console.log('📊 Presence Health Statistics:', JSON.stringify(stats, null, 2));

        // Store in Firestore for monitoring dashboard
        await admin.firestore()
          .collection('system_monitoring')
          .doc('presence_health')
          .set({
            latestStats: stats,
            lastUpdated: admin.firestore.FieldValue.serverTimestamp()
          }, { merge: true });

        return stats;
      } catch (error) {
        console.error('❌ Error monitoring presence health:', error);
        throw error;
      }
    });

// ==================== NOTIFICATION DELIVERY TRACKING ====================

/**
 * Track notification delivery success/failure
 * 
 * Logs notification delivery metrics for monitoring.
 * Called automatically by FCM after message delivery attempt.
 */
exports.trackNotificationDelivery = functions.database
    .ref('notification_tracking/{notificationId}')
    .onCreate(async (snapshot, context) => {
      const notificationId = context.params.notificationId;
      const data = snapshot.val();

      console.log(`📬 Notification delivery tracked: ${notificationId}`, {
        delivered: data.delivered,
        timestamp: data.timestamp,
        recipientId: data.recipientId
      });

      // Aggregate metrics
      const metricsRef = admin.firestore()
        .collection('system_monitoring')
        .doc('notification_metrics');

      await metricsRef.set({
        totalNotifications: admin.firestore.FieldValue.increment(1),
        successfulDeliveries: data.delivered ? 
          admin.firestore.FieldValue.increment(1) : 
          admin.firestore.FieldValue.increment(0),
        failedDeliveries: !data.delivered ? 
          admin.firestore.FieldValue.increment(1) : 
          admin.firestore.FieldValue.increment(0),
        lastUpdated: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      return null;
    });

// ==================== DEPLOYMENT INSTRUCTIONS ====================

/**
 * TO DEPLOY THESE FUNCTIONS:
 * 
 * 1. Install dependencies:
 *    cd functions
 *    npm install
 * 
 * 2. Deploy all functions:
 *    firebase deploy --only functions
 * 
 * 3. Deploy specific function:
 *    firebase deploy --only functions:cleanupStalePresence
 * 
 * 4. View logs:
 *    firebase functions:log
 * 
 * 5. Test scheduled functions locally:
 *    firebase functions:shell
 *    > cleanupStalePresence()
 * 
 * IMPORTANT NOTES:
 * - Scheduled functions require Firebase Blaze (Pay as you go) plan
 * - Free tier allows up to 2M function invocations per month
 * - cleanupStalePresence runs 288 times per day (every 5 minutes)
 * - monitorPresenceHealth runs 24 times per day (every hour)
 * - Estimated cost: < $0.50 per month for typical usage
 */

// ==================== FIREBASE RTDB SECURITY RULES ====================

/**
 * REQUIRED: Add these rules to Firebase Realtime Database
 * 
 * Firebase Console → Realtime Database → Rules
 * 
 * {
 *   "rules": {
 *     "presence": {
 *       "$uid": {
 *         ".read": true,
 *         ".write": "$uid === auth.uid"
 *       }
 *     },
 *     "pending_messages": {
 *       "$uid": {
 *         ".read": "$uid === auth.uid",
 *         ".write": true
 *       }
 *     },
 *     "notification_tracking": {
 *       ".write": "auth != null",
 *       ".read": "auth != null"
 *     }
 *   }
 * }
 */
