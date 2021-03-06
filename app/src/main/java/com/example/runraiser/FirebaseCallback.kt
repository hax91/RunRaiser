package com.example.runraiser

import com.example.runraiser.ui.donate.OrganizationCard
import com.example.runraiser.ui.history.DonationCard
import com.example.runraiser.ui.history.HistoryCard
import com.example.runraiser.ui.home.ActiveUser

interface TrainingsDataCallback {
    fun onTrainingsDataCallback(myTrainingsData: ArrayList<HistoryCard>)
}

interface DonationsDataCallback {
    fun onDonationsDataCallback(myDonationsData: ArrayList<DonationCard>)
}

interface OrganizationsDataCallback {
    fun onOrganizationsDataCallback(myOrganizationsData: ArrayList<OrganizationCard>)
}

interface ActiveUsersDataCallback {
    fun onActiveUsersDataCallback(activeUsersData: HashMap<String, ActiveUser>)
}

interface UsersMarkersDataCallback {
    fun onUsersMarkersDataCallback()
}
