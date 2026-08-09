package com.glyph.glyph_v3.ui.users

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.databinding.ActivityUserListBinding
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.utils.PhoneNumberUtil
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver

class UserListActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SELECTION_MODE = "selection_mode"
        const val EXTRA_SELECTED_USER_ID = "selected_user_id"
        const val EXTRA_SELECTED_USER_NAME = "selected_user_name"
        const val EXTRA_SELECTED_USER_AVATAR = "selected_user_avatar"

        const val SELECTION_MODE_CHAT = "chat"
        const val SELECTION_MODE_CALL = "call"

        private const val BUBBLE_IN_MS = 140
        private const val BUBBLE_OUT_MS = 100
    }

    private lateinit var binding: ActivityUserListBinding
    private val repository = FirebaseRepository()
    private var allContacts: List<ContactListItem> = listOf()
    private lateinit var adapter: UserAdapter
    private var selectionMode: String = SELECTION_MODE_CHAT

    data class DeviceContact(val name: String, val phoneNumbers: List<String>, val contactId: Long)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            loadAndDisplayContacts()
        } else {
            Toast.makeText(this, "Permission is required to find your contacts.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        // Edge-to-edge + explicit insets handling for a consistent header under status bar
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityUserListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectionMode = intent.getStringExtra(EXTRA_SELECTION_MODE) ?: SELECTION_MODE_CHAT

        // Apply pastel gradient for Pastel-Sky
        val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this)
        if (currentTheme == com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY) {
            binding.root.background = ContextCompat.getDrawable(this, com.glyph.glyph_v3.R.drawable.bg_pastel_gradient)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (selectionMode == SELECTION_MODE_CALL) {
            getString(com.glyph.glyph_v3.R.string.calls_select_contact_title)
        } else {
            getString(com.glyph.glyph_v3.R.string.calls_select_chat_contact_title)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Ensure toolbar + search UI respect theme contrast (especially dark mode)
        applyHeaderTheming()

        // Apply status-bar inset to the app bar so it doesn't render under the status bar
        binding.toolbar.updatePadding(top = 0)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }
        binding.appBarLayout.requestApplyInsets()

        // Keep the alphabetical index inside the platform's safe area on every
        // edge so the rounded pill doesn't bleed behind status-bar / gesture
        // regions or display-cutout edges. We treat insets as padding via the
        // view's margins (rather than padding) so the RecyclerView's full scroll
        // length doesn't shrink. Status-bar top is already absorbed by the
        // toolbar's edge-to-edge padding, so we only add a small visual gap
        // here — the *meaningful* safe-area application is `bottom` (gesture
        // pill) and `right` (waterfall / side display cutout).
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val mlp = binding.alphabetIndex.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            mlp.topMargin = dp(6)
            mlp.bottomMargin = safeInsets.bottom + dp(8)
            // Right safe area: waterfall displays (Samsung Edge, etc.) report a
            // non-zero displayCutout.right; some tablets report a systemBars.right
            // (gesture nav on the right edge in landscape). Either way, push the
            // strip inward by at least 12 dp so the rounded corner doesn't kiss
            // the screen chrome.
            mlp.marginEnd = safeInsets.right + dp(12)
            binding.alphabetIndex.layoutParams = mlp
            binding.alphabetBubble.translationY = -safeInsets.bottom / 2f
            insets
        }
        binding.root.requestApplyInsets()

        setupRecyclerView()
        setupAlphabetIndex()
        setupSearchView()
        checkPermissionsAndLoad()

        // Show "New group" entry only in chat selection mode (not for calls or share)
        binding.newGroupRow.visibility = if (selectionMode == SELECTION_MODE_CHAT) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        binding.newGroupRow.setOnClickListener {
            startActivity(android.content.Intent(this, com.glyph.glyph_v3.ui.groups.CreateGroupActivity::class.java))
        }
    }

    private fun applyHeaderTheming() {
        // Title color comes from GlyphToolbarTitle, but we also want nav icon to match.
        val iconColor = resolveColorAttr(com.glyph.glyph_v3.R.attr.glyphToolbarIcon)
        binding.toolbar.navigationIcon?.setTint(iconColor)

        // SearchView text + hint colors
        val searchText = binding.searchView.findViewById<android.widget.TextView>(androidx.appcompat.R.id.search_src_text)
        val textColor = resolveColorAttr(com.glyph.glyph_v3.R.attr.glyphTextPrimary)
        val hintColor = resolveColorAttr(com.glyph.glyph_v3.R.attr.glyphTextHint)
        searchText?.setTextColor(textColor)
        searchText?.setHintTextColor(hintColor)
        // Ensure placeholder text is explicit and localized and shown with the search icon
        binding.searchView.queryHint = getString(com.glyph.glyph_v3.R.string.search_contacts_placeholder)
        binding.searchView.setIconifiedByDefault(false)

        // Remove the default inner plate background so our rounded container looks clean
        val plate = binding.searchView.findViewById<View>(androidx.appcompat.R.id.search_plate)
        plate?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Search + close icons
        val searchIcon = binding.searchView.findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_mag_icon)
        val closeIcon = binding.searchView.findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_close_btn)
        val iconSecondary = resolveColorAttr(com.glyph.glyph_v3.R.attr.glyphIconSecondary)
        searchIcon?.setColorFilter(iconSecondary)
        closeIcon?.setColorFilter(iconSecondary)
        // Prevent clipping: set icon translation to 0 and add 10dp start padding to the text field
        val offsetPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt()
        searchIcon?.translationX = 0f
        searchText?.translationX = 0f
        // Add 10dp start padding to the text field for both LTR and RTL
        searchText?.setPaddingRelative(offsetPx, searchText.paddingTop, searchText.paddingEnd, searchText.paddingBottom)
    }

    private fun resolveColorAttr(attrRes: Int): Int {
        val outValue = TypedValue()
        theme.resolveAttribute(attrRes, outValue, true)
        return if (outValue.resourceId != 0) {
            ContextCompat.getColor(this, outValue.resourceId)
        } else {
            outValue.data
        }
    }

    private fun setupRecyclerView() {
        adapter = UserAdapter(emptyList()) { item ->
            if (item.isRegistered) {
                val user = item.registeredUser!!
                if (selectionMode == SELECTION_MODE_CALL) {
                    returnSelectedUser(user)
                } else {
                    openChat(user)
                }
            } else {
                if (selectionMode == SELECTION_MODE_CALL) {
                    Toast.makeText(this, com.glyph.glyph_v3.R.string.calls_only_registered_contacts, Toast.LENGTH_SHORT).show()
                } else {
                    // Show invite options for non-registered contacts
                    showInviteDialog(item)
                }
            }
        }
        binding.recyclerViewUsers.adapter = adapter
        binding.recyclerViewUsers.layoutManager = LinearLayoutManager(this)

        // Mirror list scroll position to the strip's "current section" green
        // circle. We deliberately sync on SCROLL_STATE_IDLE — NOT on every
        // onScrolled frame — so the indicator doesn't tick through A → B → C
        // as a smoothscroll passes through those sections. There's no tween
        // or animation; the indicator simply appears at the destination
        // section as soon as the scroll commits.
        binding.recyclerViewUsers.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            if (sectionIndicatorSuppressed) {
                                sectionIndicatorSuppressed = false
                                syncSectionIndicatorToList(rv)
                            }
                        }
                        RecyclerView.SCROLL_STATE_DRAGGING,
                        RecyclerView.SCROLL_STATE_SETTLING -> {
                            sectionIndicatorSuppressed = true
                        }
                    }
                }
            },
        )
    }

    /** While the user (or a smoothscroll launched from the strip) is moving
     *  the list, don't tick the strip's section indicator through the
     *  intermediate letters — wait until scroll settles. */
    private var sectionIndicatorSuppressed: Boolean = false

    /** Resolve the first visible row's section and pipe it into the strip. */
    private fun syncSectionIndicatorToList(rv: RecyclerView) {
        val llm = rv.layoutManager as? LinearLayoutManager ?: return
        val idx = llm.findFirstVisibleItemPosition()
        val section = currentSectionIndex?.sectionAtIndex(idx)
        if (binding.alphabetIndex.currentSection != section) {
            binding.alphabetIndex.currentSection = section
        }
    }

    private fun setupAlphabetIndex() {
        // Hide index while the user has typed a search query — the resulting
        // flat filter result isn't alphabet-navigable in a useful way.
        binding.alphabetIndex.visibility =
            if (binding.searchView.query.isNullOrBlank()) View.VISIBLE else View.GONE

        binding.alphabetIndex.onLetterChanged = { letter ->
            currentSectionIndex?.let { index ->
                val target = index.nearestIndexFor(letter)
                if (target >= 0) {
                    // Mirror the strip touch to the green indicator *instantly*,
                    // before the smoothscroll lands. The user's intent on the
                    // strip is the source of truth for "which letter am I
                    // aiming at" — the list's eventual scroll position is just
                    // a follow-up. Without this line the indicator would only
                    // snap to the touched letter after the smoothscroll
                    // commits (~220ms later), which feels laggy and decoupled.
                    binding.alphabetIndex.currentSection = letter
                    jumpToRowSnappy(target)
                    showBubble(letter)
                }
            }
        }
        binding.alphabetIndex.onPressEnded = {
            hideBubble()
        }
    }

    /**
     * Anchored smooth jump for the alphabetical index strip: pins the target
     * row at the TOP of the viewport and animates it there quickly regardless
     * of distance or how many section-header rows sit in between.
     *
     * Replaces the previous delta-based math (`smoothScrollBy(0, delta)` with
     * `delta = (target - firstVisible) * rowHeight - firstVisibleView.top`),
     * which assumed every row had the same height and ignored that section
     * headers (~40dp) take different space from contact rows (~64dp). After
     * a few headers mixed in, the cumulative estimate drifted and the section
     * header landed near the bottom of the viewport instead of the top.
     *
     * Using [LinearSmoothScroller] with [LinearSmoothScroller.SNAP_TO_START]
     * lets RecyclerView measure the target's actual offset — handling
     * variable row heights automatically — and pinning the row at top:0 once
     * the animation settles. The duration override keeps long jumps snappy
     * (default ~25ms/px on xxhdpi makes a 1500 px jump take ~3s) while
     * leaving short hops smooth (no abrupt tick from 0→100 px).
     */
    private fun jumpToRowSnappy(target: Int) {
        val rv = binding.recyclerViewUsers
        val llm = rv.layoutManager as? LinearLayoutManager ?: run {
            rv.scrollToPosition(target)
            return
        }

        // Already anchored? Nothing to do. Anchored = target is the first
        // visible row AND its top edge is at (or within 4px of zero from) the
        // list's top edge. `findViewByPosition(target) != null` would also be
        // true when the row is visible near the bottom of the viewport, which
        // is exactly the position we want to escape.
        val firstVisible = llm.findFirstVisibleItemPosition()
        val firstView = if (firstVisible != RecyclerView.NO_POSITION) {
            llm.findViewByPosition(firstVisible)
        } else null
        if (firstVisible == target && firstView != null && firstView.top >= -4) {
            return
        }

        val scroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START
            override fun calculateTimeForScrolling(dx: Int): Int {
                // Per-step duration during the seek phase. Capped at 45 ms so a
                // typical 5-7 step ramp completes in 150 - 300 ms wall-clock for
                // 1500 px jumps instead of the 1 s the (60 - 180 ms) curve produced.
                // Short hops still feel smooth — the underlying ValueAnimator
                // runs at 60 fps with the platform's interceptor-style easing, so
                // even a single 28 ms tick visibly eases rather than teleports.
                val px = Math.abs(dx)
                return (px * 0.012f + 25f).toInt().coerceIn(22, 45)
            }
        }
        scroller.targetPosition = target
        llm.startSmoothScroll(scroller)
    }

    /**
     * Show the floating letter indicator with a small scale-in pulse. The
     * underlying list jump is already in flight, so this is just to give the
     * user a clear "where am I" affordance during the (very short) gesture.
     */
    private fun showBubble(letter: String) {
        val bubble = binding.alphabetBubble
        bubble.text = letter
        if (bubble.visibility != View.VISIBLE) {
            bubble.alpha = 0f
            bubble.scaleX = 0.7f
            bubble.scaleY = 0.7f
            bubble.visibility = View.VISIBLE
        }
        bubble.animate().cancel()
        bubble.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(BUBBLE_IN_MS.toLong())
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
    }

    /** Quick fade-out when the finger lifts or the gesture is cancelled. */
    private fun hideBubble() {
        val bubble = binding.alphabetBubble
        if (bubble.visibility != View.VISIBLE) return
        bubble.animate().cancel()
        bubble.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(BUBBLE_OUT_MS.toLong())
            .withEndAction { bubble.visibility = View.GONE }
            .start()
    }

    /**
     * Tear-down path used when the bubble should disappear without ceremony
     * (e.g. the index strip itself was just hidden by a search query change).
     */
    private fun hideBubbleImmediate() {
        val bubble = binding.alphabetBubble
        bubble.animate().cancel()
        bubble.alpha = 0f
        bubble.scaleX = 0.85f
        bubble.scaleY = 0.85f
        bubble.visibility = View.GONE
    }

    /** Updated every time the displayed contact list changes; consumed by the index UI. */
    private var currentSectionIndex: AlphabetSectionIndex? = null

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterContacts(newText.orEmpty())
                return true
            }
        })
    }

    private fun filterContacts(query: String) {
        val q = query.trim()
        val filteredList = if (q.isEmpty()) {
            allContacts
        } else {
            val normalizedQueryDigits = PhoneNumberUtil.normalizeToLast10Digits(q)
            allContacts.filter { item ->
                val nameMatches = item.name.contains(q, ignoreCase = true)
                val phoneNormalized = PhoneNumberUtil.normalizeToLast10Digits(item.phoneNumber)
                val phoneMatches = normalizedQueryDigits.isNotEmpty() &&
                    phoneNormalized.contains(normalizedQueryDigits)
                nameMatches || phoneMatches
            }
        }
        publishContacts(filteredList, query = q)
    }

    /**
     * Updates the adapter, the alphabet index, and the floating-bubble visibility
     * from a freshly-filtered contact list. Single source of truth so the three
     * pieces never disagree.
     */
    private fun publishContacts(list: List<ContactListItem>, query: String) {
        val index = AlphabetSectionIndex(list)
        currentSectionIndex = index
        adapter.updateRows(index.rows)
        binding.alphabetIndex.presentLetters = index.presentLetters
        // The alphabet index is meaningful only when we're showing the full
        // alphabetical list — hide it during an active search so the right edge
        // doesn't get crowded by a useless strip.
        binding.alphabetIndex.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
        // Reset bubble state without animating — publishContacts can fire while
        // a fade-in is already on its way and we don't want a stale frame.
        hideBubbleImmediate()
        // Seed the section indicator with whatever section the first visible row
        // sits under — otherwise the green circle would only appear after the
        // user scrolls at least once, which feels like a stale frame on first
        // contact load.
        syncSectionIndicatorToList(binding.recyclerViewUsers)
    }

    // ---------- Invite helpers ----------

    private fun showInviteDialog(item: ContactListItem) {
        val dialogView = layoutInflater.inflate(com.glyph.glyph_v3.R.layout.dialog_invite, null)

        val titleView = dialogView.findViewById<android.widget.TextView>(com.glyph.glyph_v3.R.id.invite_title)
        val subtitleView = dialogView.findViewById<android.widget.TextView>(com.glyph.glyph_v3.R.id.invite_subtitle)
        val previewView = dialogView.findViewById<android.widget.EditText>(com.glyph.glyph_v3.R.id.invite_message_preview)
        val btnSend = dialogView.findViewById<com.google.android.material.button.MaterialButton>(com.glyph.glyph_v3.R.id.btn_send_sms)
        val btnShare = dialogView.findViewById<com.google.android.material.button.MaterialButton>(com.glyph.glyph_v3.R.id.btn_share)

        titleView.text = getString(com.glyph.glyph_v3.R.string.invite_dialog_title, item.name)
        subtitleView.text = getString(com.glyph.glyph_v3.R.string.invite_dialog_subtitle)

        val playStoreUrl = "https://play.google.com/store/apps/details?id=${packageName}"
        val message = getString(com.glyph.glyph_v3.R.string.invite_message, playStoreUrl)
        previewView.setText(message)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setNegativeButton(getString(com.glyph.glyph_v3.R.string.action_not_now), null)
            .create()

        dialog.setCanceledOnTouchOutside(true)

        btnSend.setOnClickListener {
            val finalMessage = previewView.text.toString()
            dialog.dismiss()
            sendSmsInvite(item.phoneNumber, finalMessage)
        }

        btnShare.setOnClickListener {
            val finalMessage = previewView.text.toString()
            dialog.dismiss()
            shareInviteText(item.phoneNumber, finalMessage)
        }

        dialog.show()

        // Ensure negative button color matches theme and is visible in Pastel-Sky
        val neg = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
        // Use primary text color for better visibility
        neg?.setTextColor(resolveColorAttr(com.glyph.glyph_v3.R.attr.glyphTextPrimary))
    }

    private fun sendSmsInvite(phoneNumber: String, message: String) {
        val smsUri = android.net.Uri.parse("smsto:${android.net.Uri.encode(phoneNumber)}")
        val smsIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", message)
        }

        // Prefer Google Messages (RCS capable) if available
        val pm = packageManager
        val resolved = pm.queryIntentActivities(smsIntent, 0)
        if (resolved.isNotEmpty()) {
            val preferred = resolved.find { it.activityInfo.packageName == "com.google.android.apps.messaging" }?.activityInfo?.packageName
            if (preferred != null) smsIntent.setPackage(preferred)
            try {
                startActivity(smsIntent)
                finish()
            } catch (e: android.content.ActivityNotFoundException) {
                // fallback to share
                shareInviteText(phoneNumber, message)
            }
        } else {
            // no SMS app: fall back to share
            shareInviteText(phoneNumber, message)
        }
    }

    private fun shareInviteText(phoneNumber: String, message: String) {
        val sendIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, message)
            type = "text/plain"
        }

        val chooser = android.content.Intent.createChooser(sendIntent, getString(com.glyph.glyph_v3.R.string.invite_share))
        // Verify there's at least one app to handle it
        val pm = packageManager
        if (sendIntent.resolveActivity(pm) != null) {
            startActivity(chooser)
            finish()
        } else {
            // subtle feedback if nothing can handle the share
            com.google.android.material.snackbar.Snackbar.make(binding.root, getString(com.glyph.glyph_v3.R.string.invite_no_apps), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
        }
    }

    private fun checkPermissionsAndLoad() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            loadAndDisplayContacts()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun loadAndDisplayContacts() {
        binding.progressBar.visibility = View.VISIBLE
        
        repository.getAllUsers(
            onSuccess = { firestoreUsers ->

                // Build lookup by last-10-digit normalized phone, skipping entries with no phone
                val registeredUsersMap = firestoreUsers
                    .filter { it.phoneNumber.isNotBlank() }
                    .associateBy { PhoneNumberUtil.normalizeToLast10Digits(it.phoneNumber) }

                val deviceContacts = getDeviceContacts()

                val contacts = deviceContacts.map { contact ->
                    // Check ALL phone numbers for this contact so we never miss a match
                    // when a contact has multiple numbers (home, work, mobile, etc.)
                    var registeredUser: com.glyph.glyph_v3.data.models.User? = null
                    var primaryPhone = contact.phoneNumbers.firstOrNull() ?: ""

                    for (phone in contact.phoneNumbers) {
                        val normalized = PhoneNumberUtil.normalizeToLast10Digits(phone)
                        val user = if (normalized.isNotEmpty()) registeredUsersMap[normalized] else null
                        if (user != null) {
                            registeredUser = user
                            primaryPhone = phone
                            break
                        }
                    }

                    ContactListItem(
                        name = contact.name,
                        phoneNumber = primaryPhone,
                        isRegistered = registeredUser != null,
                        registeredUser = registeredUser
                    )
                }

                val registeredCount = contacts.count { it.isRegistered }

                // Deduplicate: registered contacts by Firebase UID (same person saved under
                // multiple names or synced from several accounts), unregistered by normalized phone.
                val seenUids = mutableSetOf<String>()
                val seenPhones = mutableSetOf<String>()
                val dedupedContacts = contacts.filter { item ->
                    if (item.isRegistered) {
                        seenUids.add(item.registeredUser!!.id)
                    } else {
                        val norm = PhoneNumberUtil.normalizeToLast10Digits(item.phoneNumber)
                        if (norm.isNotEmpty()) seenPhones.add(norm) else true
                    }
                }

                allContacts = if (selectionMode == SELECTION_MODE_CALL) {
                    dedupedContacts.filter { it.isRegistered }.sortedBy { it.name }
                } else {
                    dedupedContacts.sortedWith(compareBy({ !it.isRegistered }, { it.name }))
                }

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    filterContacts("")
                }
            },
            onFailure = { exception ->
                Log.e("UserListActivity", "Failed to load users: ${exception.message}", exception)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this,
                        "Could not load contacts: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    @SuppressLint("Range")
    private fun getDeviceContacts(): List<DeviceContact> {
        // Group by CONTACT_ID so the same person synced from multiple accounts
        // (e.g. Google + phone storage) only produces one DeviceContact entry.
        val idToEntry = linkedMapOf<Long, Pair<String, MutableList<String>>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val contactId = it.getLong(
                    it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                )
                val name = it.getString(
                    it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                ) ?: continue
                val phone = it.getString(
                    it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                ) ?: continue
                val entry = idToEntry.getOrPut(contactId) { Pair(name, mutableListOf()) }
                entry.second.add(phone)
            }
        }
        return idToEntry.map { (id, pair) ->
            DeviceContact(name = pair.first, phoneNumbers = pair.second.distinct(), contactId = id)
        }
    }
    
    private fun openChat(user: User) {
        val currentUserId = repository.currentUserId ?: return
        val otherUserId = user.id
        val chatId = if (currentUserId < otherUserId) "${currentUserId}_${otherUserId}" else "${otherUserId}_${currentUserId}"
        
        // Launch XML ChatActivity
        val intent = ChatActivity.newIntent(
            this,
            chatId,
            otherUserId,
            ContactDisplayNameResolver.getDisplayName(
                otherUserId = user.id,
                remoteProfileName = user.username,
                remotePhoneNumber = user.phoneNumber
            ),
            user.profileImageUrl
        )
        startActivity(intent)
        finish() // Close UserListActivity
    }

    private fun returnSelectedUser(user: User) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_SELECTED_USER_ID, user.id)
                putExtra(EXTRA_SELECTED_USER_NAME, ContactDisplayNameResolver.getDisplayName(
                    otherUserId = user.id,
                    remoteProfileName = user.username,
                    remotePhoneNumber = user.phoneNumber
                ))
                putExtra(EXTRA_SELECTED_USER_AVATAR, user.profileImageUrl)
            }
        )
        finish()
    }
}
