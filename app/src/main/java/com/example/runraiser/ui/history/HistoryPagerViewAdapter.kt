package com.example.runraiser.ui.history

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter

class HistoryPagerViewAdapter (fm: FragmentManager) : FragmentStatePagerAdapter(fm){
    override fun getItem(position: Int): Fragment {
        when (position) {
            0 -> return TrainingsFragment()
            else -> return DonationsFragment()
        }
    }

    override fun getCount(): Int {
        return 2
    }
}