package com.example.runraiser.ui.history

import android.util.Log
import com.example.runraiser.DonationsDataCallback
import com.example.runraiser.Firebase
import com.example.runraiser.TrainingsDataCallback
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

class HistoryData {
    companion object {
        var myTrainingsData = ArrayList<HistoryCard>()
        var myDonationsData = ArrayList<DonationCard>()

        fun fetchTrainingsData(trainingsDataCallback: TrainingsDataCallback) {
            myTrainingsData = ArrayList()
            Firebase.databaseTrainings?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onCancelled(error: DatabaseError) {
                    Log.i("fetchTrainingsData", "Failed to read value. " + error.message)
                }

                override fun onDataChange(p0: DataSnapshot) {
                    val children = p0.children
                    children.forEach{
                        val userId = it.child("userId").value.toString()
                        val trainingId = it.child("id").value.toString()
                        if(Firebase.auth?.uid == userId) {
                            myTrainingsData.add (
                                HistoryCard(
                                    trainingId,
                                    it.child("startDate").value.toString(),
                                    userId,
                                    it.child("distanceKm").value.toString(),
                                    it.child("kilometers").value.toString(),
                                    it.child("avgSpeed").value.toString(),
                                    it.child("moneyRaised").value.toString(),
                                    it.child("trainingMapScreenshot").value.toString(),
                                    it.child("time").value.toString()
                                )
                            )
                        }
                    }
                    myTrainingsData.sortByDescending { it.startDate }
                    trainingsDataCallback.onTrainingsDataCallback(myTrainingsData)
                }
            })
        }

        fun fetchDonationsData(donationsDataCallback: DonationsDataCallback) {
            myDonationsData = ArrayList()
            Firebase.databaseDonations?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onCancelled(error: DatabaseError) {
                    Log.i("fetchDonationsData", "Failed to read value. " + error.message)
                }

                override fun onDataChange(p0: DataSnapshot) {
                    val children = p0.children
                    children.forEach{
                        val userId = it.child("userId").value.toString()
                        if(Firebase.auth?.uid == userId) {
                            myDonationsData.add (
                                DonationCard(
                                    it.child("organizationName").value.toString(),
                                    it.child("moneyDonated").value.toString(),
                                    it.child("organizationImage").value.toString(),
                                    it.child("date").value.toString(),
                                    userId
                                )
                            )
                        }
                    }
                    myDonationsData.sortByDescending { it.date }
                    donationsDataCallback.onDonationsDataCallback(myDonationsData)
                }
            })
        }
    }
}
