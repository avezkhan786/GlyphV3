package com.glyph.glyph_v3

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.glyph.glyph_v3.databinding.ActivityMainBinding
import com.glyph.glyph_v3.data.preferences.StatusNotificationPrefs
import com.glyph.glyph_v3.ui.calls.CallsFragment
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.chatlist.ChatListComposeFragment
import com.glyph.glyph_v3.ui.main.MainPagerAdapter
import com.glyph.glyph_v3.ui.settings.SettingsFragment
import com.glyph.glyph_v3.ui.status.StatusFragment
import com.glyph.glyph_v3.data.repo.StatusRepository
import com.glyph.glyph_v3.utils.ThemeManager
import com.google.firebase.messaging.FirebaseMessaging
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.PresenceManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : AppCompatActivity() {

    private var connectionRetryJob: Job? = null
    private var hasPreloadedSecondaryTabs = false

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
        } else {
            Log.w("MainActivity", "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before creating the activity
        ThemeManager.applyTheme(this)
        
        super.onCreate(savedInstanceState)
        
        // Ensure user is authenticated (anonymous auth if not signed in)
        ensureAuthenticated()
        
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Setup ViewPager2 (WhatsApp-style swipe)
        val pagerAdapter = com.glyph.glyph_v3.ui.main.MainPagerAdapter(this)
        binding.mainViewPager.adapter = pagerAdapter
        // Keep cold start focused on the visible Chats tab; adjacent tabs are created lazily.
        binding.mainViewPager.offscreenPageLimit = 1
        
        // Pad ViewPager2 above the bottom nav by 8dp.
        // Uses a layout listener so the padding stays correct when the bottom nav
        // changes size (e.g. after system insets are applied).
        val gapPx = (8 * resources.displayMetrics.density).toInt()
        binding.bottomNavigation.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom != oldBottom) {
                val navHeight = bottom - binding.bottomNavigation.top
                binding.mainViewPager.setPadding(0, 0, 0, navHeight + gapPx)
            }
        }

        // Sync ViewPager2 with Bottom Navigation
        binding.mainViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val menuId = when (position) {
                    0 -> R.id.navigation_chats
                    1 -> R.id.navigation_status
                    2 -> R.id.navigation_calls
                    3 -> R.id.navigation_settings
                    else -> R.id.navigation_chats
                }
                if (binding.bottomNavigation.selectedItemId != menuId) {
                    binding.bottomNavigation.selectedItemId = menuId
                }
            }
        })

        // Modern back handling (replaces deprecated onBackPressed override).
        // Ensures bottom nav visibility is restored when popping any back stack.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    if (supportFragmentManager.backStackEntryCount == 0) {
                        binding.bottomNavigation.visibility = View.VISIBLE
                    }
                } else if (binding.mainViewPager.currentItem != 0) {
                    // Similar to WhatsApp: if not on first tab (chats), back goes back to chats
                    binding.mainViewPager.setCurrentItem(0, false)
                } else {
                    finish()
                }
            }
        })
        
        // Bottom-navigation insets (safe-area margin) are handled inside
        // GlyphBottomNavigationView.onAttachedToWindow – no extra listener needed here.

        
        askNotificationPermission()
        
        // Check battery optimization (for reliable FCM delivery)
        checkBatteryOptimization()
        
        // Update FCM Token
        updateFcmToken()
        
        // Resume any pending media downloads
        com.glyph.glyph_v3.data.media.MediaDownloadWorker.schedulePendingDownloads(applicationContext)

        StatusRepository.startListeningContactStatuses()

        // Apply Pastel-Sky bottom navigation colors if needed
        applyBottomNavigationTheme()
        observeUnreadStatuses()

        // Setup Bottom Navigation interaction
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val position = when (item.itemId) {
                R.id.navigation_chats -> 0
                R.id.navigation_status -> 1
                R.id.navigation_calls -> 2
                R.id.navigation_settings -> 3
                else -> 0
            }
            if (binding.mainViewPager.currentItem != position) {
                binding.mainViewPager.setCurrentItem(position, false)
            }
            true
        }

        if (savedInstanceState == null) {
            // Default fragment is handled by ViewPager2 (index 0)
            
            // Check for deep link
            handleIntent(intent)
        }

        // Non-blocking profile validation (does not delay first render)
        checkUserProfileAsync()
    }

    fun preloadSecondaryTabsAfterChatReady() {
        if (hasPreloadedSecondaryTabs) return
        hasPreloadedSecondaryTabs = true

        binding.mainViewPager.post {
            if (isFinishing || isDestroyed) return@post
            // Once Chats has rendered, keep every root tab resident so horizontal swipes do
            // not trigger fragment/view creation on the gesture path.
            binding.mainViewPager.offscreenPageLimit = 3
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            StatusRepository.cleanup()
        }
    }

    private fun observeUnreadStatuses() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                StatusRepository.contactStatuses
                    .map { groups -> groups.any { !it.allViewed } }
                    .collect { hasUnseenStatuses ->
                        binding.bottomNavigation.setStatusIndicatorVisible(hasUnseenStatuses)
                    }
            }
        }
    }
    
    private fun askNotificationPermission() {
        // This is only necessary for API level >= 33 (TIRAMISU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                // FCM SDK (and your app) can post notifications.
            } else if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                // TODO: Display an educational UI explaining to the user the features that will be enabled
                //       by them granting the POST_NOTIFICATION permission. This UI should provide the user
                //       "OK" and "No thanks" buttons. If the user selects "OK," directly request the permission.
                //       If the user selects "No thanks," allow the user to continue without notifications.
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Directly ask for the permission
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun updateFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("MainActivity", "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }

            // Get new FCM registration token
            val token = task.result
            
            // Update token in repository
            val repo = FirebaseRepository()
            repo.updateFcmToken(token)
        }
    }

    private fun checkUserProfileAsync() {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                val hasProfile = document.exists() && !document.getString("username").isNullOrEmpty()
                if (!hasProfile) {
                    val intent = Intent(this, com.glyph.glyph_v3.ui.login.SetupProfileActivity::class.java)
                    intent.putExtra("phone_number", user.phoneNumber)
                    startActivity(intent)
                    finish()
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "Failed to check profile", e)
            }
    }
    
    /**
     * Check if app is excluded from battery optimization.
     * This is critical for reliable FCM delivery when app is in background/killed.
     * WhatsApp, Telegram, and other chat apps request this exemption.
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // Only show dialog once per app install
                val prefs = getSharedPreferences("glyph_prefs", Context.MODE_PRIVATE)
                val hasAskedBattery = prefs.getBoolean("asked_battery_optimization", false)
                
                if (!hasAskedBattery) {
                    prefs.edit().putBoolean("asked_battery_optimization", true).apply()
                    
                    AlertDialog.Builder(this)
                        .setTitle("Battery Optimization")
                        .setMessage("For reliable message notifications, please disable battery optimization for Glyph. This ensures you receive messages instantly even when the app is closed.")
                        .setPositiveButton("Settings") { _, _ ->
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to open battery settings", e)
                                // Fallback to general battery settings
                                try {
                                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                } catch (e2: Exception) {
                                    Log.e("MainActivity", "Failed to open fallback battery settings", e2)
                                }
                            }
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }
    }
    
    private fun loadFragment(fragment: Fragment): Boolean {
        // Obsolete with ViewPager2, keeping signature only for legacy
        return true
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val shareIntent = Intent(this, com.glyph.glyph_v3.ui.share.ShareTargetActivity::class.java).apply {
                action = intent.action
                type = intent.type
                clipData = intent.clipData
                putExtras(intent)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(shareIntent)
            return
        }

        intent.getStringExtra("open_status_user_id")?.takeIf { it.isNotBlank() }?.let { statusOwnerId ->
            val statusId = intent.getStringExtra("open_status_id").orEmpty()
            val ownerName = intent.getStringExtra("open_status_owner_name").orEmpty()
            lifecycleScope.launch {
                runCatching {
                    StatusRepository.primeIncomingContactStatus(statusId, statusOwnerId, ownerName)
                }.onFailure { error ->
                    Log.w("MainActivity", "Failed to prime status from notification intent", error)
                }
            }
            binding.mainViewPager.setCurrentItem(1, false)
            return
        }

        intent?.getStringExtra("chat_id")?.let { chatId ->
            val otherUserId = intent.getStringExtra("other_user_id")
            if (otherUserId.isNullOrEmpty()) {
                Log.w("MainActivity", "Missing other_user_id for chat $chatId; ignoring deep link")
                return
            }

            val otherUsername = intent.getStringExtra("other_username") ?: ""
            val otherUserAvatar = intent.getStringExtra("other_user_avatar") ?: ""

            // Launch XML ChatActivity instead of fragment
            val chatIntent = ChatActivity.newIntent(
                this,
                chatId,
                otherUserId,
                otherUsername,
                otherUserAvatar
            )
            startActivity(chatIntent)
        }
    }
    
    private fun applyBottomNavigationTheme() {
        val currentTheme = ThemeManager.getCurrentTheme(this)
        
        when (currentTheme) {
            ThemeManager.THEME_PASTEL_SKY -> applyPastelSkyNavigation()
            ThemeManager.THEME_DARK -> applyDarkNavigation()
            else -> applyLightNavigation()  // Default to light theme
        }
        
        // Set system navigation bar color to match bottom navigation
        val navBarColor = getNavBackgroundColor(currentTheme)
        window.navigationBarColor = navBarColor
    }
    
    private fun applyPastelSkyNavigation() {
        // Pastel-Sky: ensure icon/text use the common selector (no color change on selection)
        binding.bottomNavigation.itemIconTintList = ContextCompat.getColorStateList(this, R.color.selector_bottom_nav_icon)
        binding.bottomNavigation.itemTextColor = ContextCompat.getColorStateList(this, R.color.selector_bottom_nav)
        binding.bottomNavigation.setLabelVisibilityMode(com.google.android.material.bottomnavigation.BottomNavigationView.LABEL_VISIBILITY_LABELED)
        
        // Use pastel-specific gradient background
        binding.bottomNavigation.background = ContextCompat.getDrawable(this, R.drawable.bg_bottom_nav_pastel)

        // Pastel-specific: compute a pastel color with translucent alpha and set it explicitly
        val pastelColor = ContextCompat.getColor(this, R.color.pastel_nav_indicator)
        val alpha = (0.45f * 255).toInt().coerceIn(0, 255)
        val pastelWithAlpha = androidx.core.graphics.ColorUtils.setAlphaComponent(pastelColor, alpha)
        binding.bottomNavigation.itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(pastelWithAlpha)

    }
    
    private fun applyDarkNavigation() {
        // Dark theme: icons should remain same for unselected, but selected icon should match nav background.
        // Resolve theme attributes safely so this behavior stays theme-driven.
        val typedActive = TypedValue()
        val typedInactive = TypedValue()
        theme.resolveAttribute(com.glyph.glyph_v3.R.attr.glyphNavItemActive, typedActive, true)
        theme.resolveAttribute(com.glyph.glyph_v3.R.attr.glyphNavItemInactive, typedInactive, true)

        val activeColor = if (typedActive.resourceId != 0) ContextCompat.getColor(this, typedActive.resourceId) else typedActive.data
        val inactiveColor = if (typedInactive.resourceId != 0) ContextCompat.getColor(this, typedInactive.resourceId) else typedInactive.data

        val iconColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(activeColor, inactiveColor)
        )

        binding.bottomNavigation.itemIconTintList = iconColorStateList
        binding.bottomNavigation.itemTextColor = ContextCompat.getColorStateList(this, R.color.selector_bottom_nav)
        binding.bottomNavigation.setLabelVisibilityMode(com.google.android.material.bottomnavigation.BottomNavigationView.LABEL_VISIBILITY_LABELED)
        
        // Use theme-driven background from XML
        binding.bottomNavigation.background = ContextCompat.getDrawable(this, R.drawable.bg_bottom_nav_surface_with_divider)

        // Use dark-specific indicator color for the pill (keeps subtle highlight)
        binding.bottomNavigation.itemActiveIndicatorColor = ContextCompat.getColorStateList(this, R.color.bottom_nav_active_indicator)

    }
    
    private fun applyLightNavigation() {
        // Light theme: Use the theme-aware selectors so attributes (glyphNavItemActive/Inactive)
        // resolve correctly instead of applying hardcoded values which override selectors.
        binding.bottomNavigation.itemIconTintList = ContextCompat.getColorStateList(this, R.color.selector_bottom_nav_icon)
        binding.bottomNavigation.itemTextColor = ContextCompat.getColorStateList(this, R.color.selector_bottom_nav)
        binding.bottomNavigation.setLabelVisibilityMode(com.google.android.material.bottomnavigation.BottomNavigationView.LABEL_VISIBILITY_LABELED)
        
        // Use theme-driven background from XML
        binding.bottomNavigation.background = ContextCompat.getDrawable(this, R.drawable.bg_bottom_nav_surface_with_divider)

        // Ensure any view-level overrides are re-bound
        binding.bottomNavigation.rebindColors()

        // Apply active indicator color (pill) so the selected item has the correct highlight
        binding.bottomNavigation.itemActiveIndicatorColor = ContextCompat.getColorStateList(this, R.color.bottom_nav_active_indicator)

    }

    private fun getNavBackgroundColor(themeId: String): Int {
        val colorRes = when (themeId) {
            ThemeManager.THEME_PASTEL_SKY -> R.color.pastel_background
            ThemeManager.THEME_DARK -> R.color.dark_background
            else -> R.color.light_bubble_other_mid
        }
        return ContextCompat.getColor(this, colorRes)
    }

    /**
     * Ensures user is authenticated with Firebase Auth.
     * Signs in anonymously if no user is currently signed in.
     * Required for Cloud Functions that need authentication context.
     */
    private fun ensureAuthenticated() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                }
                .addOnFailureListener { e ->
                    Log.e("MainActivity", "Anonymous auth failed", e)
                }
        } else {
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            StatusNotificationPrefs.syncEnabledSubscriptions(applicationContext)
        }

        // CRITICAL: Go online when MainActivity becomes visible
        // This ensures presence is set when user is on chat list, status, etc.
        PresenceManager.primeTransport("main_activity_resume", forceTokenRefresh = true)
        PresenceManager.goOnline()
        com.glyph.glyph_v3.data.service.WalkieTalkieManager
            .getInstance(applicationContext)
            .primeTransport("main_activity_resume", forceTokenRefresh = true)

        // COLD-START FIX: Retry goOnline() once RTDB connection is established.
        // On cold start the initial goOnline() may queue the write before the
        // WebSocket is open. This ensures the write fires as soon as connection is live.
        connectionRetryJob?.cancel()
        if (!PresenceManager.isConnected.value) {
            connectionRetryJob = lifecycleScope.launch {
                withTimeoutOrNull(15_000L) {
                    PresenceManager.isConnected.collect { connected ->
                        if (connected) {
                            PresenceManager.goOnline()
                            return@collect
                        }
                    }
                }
            }
        }
    }
    
}
