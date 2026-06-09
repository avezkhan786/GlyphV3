package com.glyph.glyph_v3.ui.main

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.glyph.glyph_v3.ui.calls.CallsFragment
import com.glyph.glyph_v3.ui.chatlist.ChatListComposeFragment
import com.glyph.glyph_v3.ui.settings.SettingsFragment
import com.glyph.glyph_v3.ui.status.StatusFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ChatListComposeFragment()
            1 -> StatusFragment()
            2 -> CallsFragment()
            3 -> SettingsFragment()
            else -> throw IllegalArgumentException("Invalid position $position")
        }
    }
}
