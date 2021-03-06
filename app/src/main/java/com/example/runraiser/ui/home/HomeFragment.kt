package com.example.runraiser.ui.home

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.location.Location
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.replace
import androidx.lifecycle.ViewModelProviders
import com.bumptech.glide.request.target.CustomTarget
import com.example.runraiser.ActiveUsersDataCallback
import com.example.runraiser.Firebase
import com.example.runraiser.GlideApp
import com.example.runraiser.R
import com.example.runraiser.ui.donate.DonateFragment
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.firebase.geofire.GeoQuery
import com.firebase.geofire.GeoQueryEventListener
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.gms.maps.model.LatLngBounds.Builder
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.gson.Gson
import kotlinx.android.synthetic.main.fragment_home.*
import kotlinx.android.synthetic.main.send_message_dialog.view.*
import kotlinx.android.synthetic.main.trainig_setup_dialog.view.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.concurrent.schedule


class HomeFragment : Fragment(), OnMapReadyCallback {

    private lateinit var homeViewModel: HomeViewModel
    private lateinit var mMap: GoogleMap

    private lateinit var previousLatLng: LatLng
    private lateinit var currentLatLng: LatLng
    private var distance: Float = 0F
    private var distanceKm: Float = 0F
    private var speed: Float = 0F
    private var kilometers: String = ""
    private var raisedVal: Int = 0
    private var valueKn: String = ""
    private lateinit var trainingId: String
    private var timesRan: Int = 0
    private var kmNotificationFlag: Boolean = false

    private var latLngArray: ArrayList<LatLng> = ArrayList()
    private var speedArray: ArrayList<Float> = ArrayList()
    lateinit var marker: Marker
    private var circle: Circle? = null
    val userId = Firebase.auth!!.currentUser!!.uid

    private var geoQuery: GeoQuery? = null
    private lateinit var geoFire: GeoFire

    private var activeUsersMarkers: MutableMap<String, Marker> = HashMap()
    private var entered: HashMap<String, Int> = HashMap()

    private var mFirestore: FirebaseFirestore? = null
    var tooFast = false


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        homeViewModel =
            ViewModelProviders.of(this).get(HomeViewModel::class.java)
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        toggleButton.textOff = null
        toggleButton.textOn = null
        toggleButton.text = null
        toggleButton.visibility = View.VISIBLE

        mFirestore = FirebaseFirestore.getInstance()

        //setting up GeoFire
        geoFire = GeoFire(FirebaseDatabase.getInstance().getReference("MyLocation/${userId}"))

        ActiveUsersData.getActiveUsersData(object: ActiveUsersDataCallback {
            @RequiresApi(Build.VERSION_CODES.N)
            override fun onActiveUsersDataCallback(activeUsersData: HashMap<String, ActiveUser>) {
                addActiveUsersPins()
            }
        })
        var stopTime: Long = 0
        var timer: Timer? = null

        start_btn.setOnClickListener {
            stopTime = 0
            timer = null

            val mDialogView = LayoutInflater.from(context).inflate(R.layout.trainig_setup_dialog, null)
            val mBuilder = AlertDialog.Builder(context)
                .setView(mDialogView)
                .setTitle("Training Setup")

            val mAlertDialog = mBuilder.show()

            val userRef = Firebase.databaseUsers!!.child("/$userId")
            userRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onCancelled(error: DatabaseError) {
                }

                override fun onDataChange(p0: DataSnapshot) {
                    mDialogView.et_kilometers.setText(p0.child("/defaultKm").value.toString())
                    mDialogView.et_value.setText(p0.child("/defaultValue").value.toString())
                }})

            mDialogView.ok_btn.setOnClickListener {
                //get text from EditTexts of custom layout
                trainingId = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT)
                kilometers = mDialogView.et_kilometers.text.toString()
                valueKn = mDialogView.et_value.text.toString()

                if(mDialogView.et_kilometers.text.toString().isEmpty() || mDialogView.et_value.text.toString().isEmpty()) {
                    Toast.makeText(activity, "Please fill out all fields.", Toast.LENGTH_SHORT).show()
                }
                else if (kilometers.toInt() > 80) {
                    mDialogView.et_kilometers.error = "Maximum is 80km"
                    mDialogView.et_kilometers.requestFocus()
                }
                else if (valueKn.toInt() > 100) {
                    mDialogView.et_value.error = "Maximum is 100kn"
                    mDialogView.et_value.requestFocus()
                }
                else if(kilometers.toInt() == 0 || valueKn.toInt() == 0) {
                    Toast.makeText(activity, "Enter something greater than zero.", Toast.LENGTH_SHORT).show()
                }
                else {
                    mAlertDialog.dismiss()
                    var startDate: String?
                    startDate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val current = LocalDateTime.now()
                        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                        current.format(formatter)
                    } else {
                        val date = Date()
                        val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm")
                        formatter.format(date)
                    }
                    val training =
                        Firebase.auth?.uid?.let { it1 -> Training(trainingId, it1, kilometers.toInt(), valueKn.toInt(), startDate) }

                    Firebase.databaseTrainings
                        ?.child(trainingId)
                        ?.setValue(training)

                    Firebase.databaseUsers!!.child(userId).child("inTraining").setValue(true)

                    start_btn.visibility = View.GONE
                    training_content.visibility = View.VISIBLE
                    stop_btn.visibility = View.VISIBLE
                    reset_layout.visibility = View.GONE

                    tv_distance.text = distance.toString()
                    tv_speed.text = speed.toString()
                    tv_goal.text = kilometers + " km"
                    if(distanceKm.toDouble() >= kilometers.toDouble()) {
                        tv_goal.setTextColor(Color.parseColor("#81C784"))
                    }

                    timer = Timer()
                    val raisedAlert = AlertDialog.Builder(context)
                    var testDialog: AlertDialog? = null
                    val task = object: TimerTask() {
                        override fun run() {
//                            println("timer passed ${++timesRan} time(s)")
                            calculateLatLng()
                            if(speed.toInt() > 40) {
                                if(!tooFast) {
                                    tooFast = true
                                    stopTime = chronometer.base - SystemClock.elapsedRealtime()
                                    chronometer.stop()
                                    activity?.runOnUiThread {
//                                       testDialog = raisedAlert.setTitle("Training is paused, speed too high")
//                                           ?.setPositiveButton("Resume") { dialog, _ ->
//                                               chronometer.base = SystemClock.elapsedRealtime()+stopTime
//                                               chronometer.start()
//                                               calculateLatLng()
//                                               tooFast = false
//                                               dialog.cancel()
//                                           }
//                                           ?.setCancelable(false)?.show()
                                        testDialog = raisedAlert.setTitle("Training is paused, speed too high").setMessage("")
                                            ?.setCancelable(false)?.show()
                                    }
                                }
                            }
                            else {
                                if(tooFast) {
                                    activity?.runOnUiThread {
                                        chronometer.base = SystemClock.elapsedRealtime() + stopTime
                                        chronometer.start()
                                    }
                                    testDialog?.cancel()
                                }
                                tooFast = false
                            }
                        }
                    }
                    if(testDialog == null) {
                        chronometer.base = SystemClock.elapsedRealtime()+stopTime
                        chronometer.start()
                    }
                    timer!!.schedule(task, 0, 1000)
                }

                stop_btn.setOnClickListener {
                    val raisedVal = (kotlin.math.floor(distanceKm) * valueKn.toDouble()).toInt()
                    val raisedAlert = AlertDialog.Builder(context)
                    Firebase.databaseUsers!!.child(userId).child("inTraining").setValue(false)

                    activeUsersMarkers.forEach { (s, marker) ->
                        marker.isVisible = false
                    }

                    if(raisedVal > 0) {
                        raisedAlert.setTitle("Congratulations!")
                            ?.setMessage("You raised $raisedVal kn!")
                            ?.setPositiveButton("OK :)") { dialog, _ ->
                                stop_btn.visibility = View.GONE
                                reset_layout.visibility = View.VISIBLE
                                dialog.cancel()
                            }?.setCancelable(false)
                            ?.show()
                    }
                    else {
                        raisedAlert.setTitle("Oh no!")
                            ?.setMessage("Unfortunately you didn’t run enough mileage to raise money.")
                            ?.setPositiveButton("OK :(") { dialog, _ ->
                                stop_btn.visibility = View.GONE
                                reset_layout.visibility = View.VISIBLE
                                dialog.cancel()
                            }?.setCancelable(false)
                            ?.show()
                    }
                    latLngArray.add(currentLatLng)
                    stopTime = chronometer.base-SystemClock.elapsedRealtime()
                    zoomRoute(mMap, latLngArray)
                    stopTime = 0
                    chronometer.stop()
                    timer?.cancel()

                    val ref = Firebase.database?.getReference("/Trainings/${trainingId}")
                    ref?.child("time")?.setValue(chronometer.text.toString())
                    ref?.child("moneyRaised")?.setValue(raisedVal)
                }
                reset_btn.setOnClickListener {
                    training_content.visibility = View.GONE
                    start_btn.visibility = View.VISIBLE
                    reset_layout.visibility = View.GONE
                    circle = null
                    tv_goal.setTextColor(Color.parseColor("#E57373"))
                    kmNotificationFlag = false
                    mMap.clear()

                    var sumMoneyRaised = ""
                    Firebase.databaseUsers?.child("$userId/fund")?.addListenerForSingleValueEvent(object: ValueEventListener {
                        override fun onCancelled(error: DatabaseError) {
                        }
                        override fun onDataChange(snapshot: DataSnapshot) {
                            sumMoneyRaised = snapshot.value.toString()
                            sumMoneyRaised =
                                (sumMoneyRaised.toInt() + raisedVal).toString()
                            Firebase.databaseUsers?.child("$userId/fund")?.setValue(sumMoneyRaised)
                            raisedVal = 0
                        }
                    })

                    if(distanceKm < 1) {
                        distanceKm = BigDecimal(distanceKm.toDouble()).setScale(3, RoundingMode.HALF_EVEN).toFloat()
                    }

                    val locationRequest = LocationRequest()
                    locationRequest.interval = 10000
                    locationRequest.fastestInterval = 3000
                    locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

                    LocationServices.getFusedLocationProviderClient(requireContext()).requestLocationUpdates(locationRequest, object:
                        LocationCallback() {
                        override fun onLocationResult(locationResult: LocationResult) {
                            super.onLocationResult(locationResult)
                            LocationServices.getFusedLocationProviderClient(requireContext()).removeLocationUpdates(this)
                            if(locationResult.locations.size > 0) {
                                val latestLocationIndex = locationResult.locations.size-1
                                val latitude = locationResult.locations[latestLocationIndex].latitude
                                val longitude = locationResult.locations[latestLocationIndex].longitude
                                // Add a marker in Sydney and move the camera
                                val sydney = LatLng(latitude, longitude)
                                previousLatLng = sydney
                                latLngArray.add(previousLatLng)
                                marker = mMap.addMarker(MarkerOptions().position(sydney).title("Me")
                                    .icon(BitmapDescriptorFactory.fromBitmap(ActiveUsersData.usersBitmapMarker[userId])).anchor(0.5f, 0.5f))
                                marker.tag = userId
                                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(sydney, 25.0f))
                            }
                        }
                    }, Looper.getMainLooper())
                }
                donate_btn.setOnClickListener {
                    var sumMoneyRaised = ""
                    Firebase.databaseUsers?.child("$userId/fund")?.addListenerForSingleValueEvent(object: ValueEventListener {
                        override fun onCancelled(error: DatabaseError) {
                        }
                        override fun onDataChange(snapshot: DataSnapshot) {
                            sumMoneyRaised = snapshot.value.toString()
                            sumMoneyRaised =
                                (sumMoneyRaised.toInt() + raisedVal).toString()
                            Firebase.databaseUsers?.child("$userId/fund")?.setValue(sumMoneyRaised)
                            raisedVal = 0
//                            startActivity(Intent(requireContext(), DonateFragment::class.java))
                            toggleButton.visibility = View.GONE
                            activity?.supportFragmentManager
                                ?.beginTransaction()?.replace(R.id.fragment_home, DonateFragment())
                                ?.commit()
                        }
                    })
                }
            }
            //cancel button click of custom layout
            mDialogView.cancel_btn.setOnClickListener {
                //dismiss dialog
                mAlertDialog.dismiss()
            }

        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        val locationRequest = LocationRequest()
        locationRequest.interval = 10000
        locationRequest.fastestInterval = 3000
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        openMessageWindow()

//        val URL = getDirectionURL(location1, location2)
//        GetDirection(URL).execute()


        LocationServices.getFusedLocationProviderClient(requireContext()).requestLocationUpdates(locationRequest, object:
            LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                LocationServices.getFusedLocationProviderClient(requireContext()).removeLocationUpdates(this)
                if(locationResult.locations.size > 0) {
                    val latestLocationIndex = locationResult.locations.size-1
                    val latitude = locationResult.locations[latestLocationIndex].latitude
                    val longitude = locationResult.locations[latestLocationIndex].longitude
                    // Add a marker in Sydney and move the camera
                    val sydney = LatLng(latitude, longitude)
                    previousLatLng = sydney
                    latLngArray.add(previousLatLng)
                    marker = mMap.addMarker(MarkerOptions().position(sydney).title("Me")
                        .icon(BitmapDescriptorFactory.fromBitmap(ActiveUsersData.usersBitmapMarker[userId])).anchor(0.5f, 0.5f))
                    marker.tag = userId
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(sydney, 25.0f))
                }
            }
        }, Looper.getMainLooper())
    }

    private fun calculateLatLng() {
        val locationRequest = LocationRequest()
        locationRequest.interval = 10000
        locationRequest.fastestInterval = 1000
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        LocationServices.getFusedLocationProviderClient(requireContext()).requestLocationUpdates(locationRequest, object:
            LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                LocationServices.getFusedLocationProviderClient(requireContext()).removeLocationUpdates(this)
                if(locationResult.locations.size > 0) {
                    val latestLocationIndex = locationResult.locations.size-1
                    val latitude = locationResult.locations[latestLocationIndex].latitude
                    val longitude = locationResult.locations[latestLocationIndex].longitude
                    currentLatLng = LatLng(latitude, longitude)
                    Firebase.databaseUsers!!.child(userId).child("lastLat").setValue(currentLatLng.latitude)
                    Firebase.databaseUsers!!.child(userId).child("lastLng").setValue(currentLatLng.longitude)
//                    geoFire.setLocation(userId, GeoLocation(currentLatLng.latitude, currentLatLng.longitude))
                    marker.position = currentLatLng

                    if (toggleButton.isChecked) {
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, mMap.cameraPosition.zoom))
                    }

                    addCircleArea()
                    distance(previousLatLng, currentLatLng)
                }
            }
        }, Looper.getMainLooper())
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun addActiveUsersPins() {
        ActiveUsersData.activeUsersData.forEach { (s, activeUser) ->
            activeUsersMarkers[s] = mMap.addMarker(MarkerOptions().position(LatLng(activeUser.lastLat, activeUser.lastLng)).title(activeUser.username)
                .icon(BitmapDescriptorFactory.fromBitmap(ActiveUsersData.usersBitmapMarker[s])))
            activeUsersMarkers[s]?.tag = activeUser.id
            activeUsersMarkers[s]?.isVisible = false
            entered[s] = 0
        }
    }

    private fun addCircleArea() {
//        if(geoQuery != null) {
//            geoQuery!!.removeAllListeners()
//        }

        ActiveUsersData.getActiveUsersData(object: ActiveUsersDataCallback {
            @RequiresApi(Build.VERSION_CODES.N)
            override fun onActiveUsersDataCallback(activeUsersData: HashMap<String, ActiveUser>) {
                activeUsersData.forEach { (s, activeUser) ->
                    if(!activeUsersMarkers.containsKey(s)) {
                        activeUsersMarkers[s] = mMap.addMarker(MarkerOptions().position(LatLng(activeUser.lastLat, activeUser.lastLng)).title(activeUser.username)
                            .icon(BitmapDescriptorFactory.fromBitmap(ActiveUsersData.usersBitmapMarker[s])))
                        activeUsersMarkers[s]?.tag = activeUser.id
                        activeUsersMarkers[s]?.isVisible = false
                        entered[s] = 0
                    }
                    activeUsersMarkers[s]?.position = LatLng(activeUser.lastLat, activeUser.lastLng)
                    geoFire.setLocation(s, GeoLocation(activeUser.lastLat, activeUser.lastLng))
                }
                activeUsersMarkers.forEach { (s, _) ->
                    Firebase.databaseUsers?.child(s)?.child("inTraining")
                        ?.addListenerForSingleValueEvent(object: ValueEventListener {
                            override fun onCancelled(error: DatabaseError) {}
                            override fun onDataChange(snapshot: DataSnapshot) {
                                if(snapshot.value == false) {
                                    activeUsersMarkers[s]?.remove()
                                    activeUsersMarkers.remove(s)
                                    geoFire.removeLocation(s)
                                    entered.remove(s)
                                }
                            }
                        })
                }
            }
        })


        if(circle == null) {
            circle = mMap.addCircle(CircleOptions().center(currentLatLng).radius(500.0).strokeColor(Color.BLUE).fillColor(0x200000FF).strokeWidth(0.0f))
        }

        circle?.center = currentLatLng
        if(geoQuery == null) geoQuery = geoFire.queryAtLocation(GeoLocation(currentLatLng.latitude, currentLatLng.longitude), 0.5)
        else geoQuery!!.center = GeoLocation(currentLatLng.latitude, currentLatLng.longitude)
        geoQuery!!.addGeoQueryEventListener(object : GeoQueryEventListener {
            override fun onGeoQueryReady() {}
            override fun onKeyEntered(key: String?, location: GeoLocation?) {
                if(entered[key] == 0 && key != userId && ActiveUsersData.activeUsersData[key]?.username != null) sendNotification("ENTERED", String.format("%s entered the dangerous area",
                    ActiveUsersData.activeUsersData[key]?.username))

                entered[key]?.plus(1)?.let { entered.put(key!!, it) }
                activeUsersMarkers[key]?.isVisible = true
            }

            override fun onKeyMoved(key: String?, location: GeoLocation?) {
            }

            override fun onKeyExited(key: String?) {
                if (key != null) {
                    entered.put(key, 0)
                }
                activeUsersMarkers[key]?.isVisible = false
            }

            override fun onGeoQueryError(error: DatabaseError?) {
                TODO("Not yet implemented")
            }
        })
    }

    private fun sendNotification(title: String, content: String) {
        val NOTIFICATION_CHANNEL_ID = "run_raiser"
        val notificationManager = activity?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "myNotification", NotificationManager.IMPORTANCE_DEFAULT)
            notificationChannel.description = "Channel desc"

            notificationManager.createNotificationChannel(notificationChannel)
            val builder = context?.let { NotificationCompat.Builder(it, NOTIFICATION_CHANNEL_ID) }
            builder?.setContentTitle(title)
                ?.setContentText(content)
                ?.setAutoCancel(false)
                ?.setSmallIcon(R.mipmap.ic_launcher)
                ?.setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))

            val notification = builder?.build()
            notificationManager.notify(Random().nextInt(), notification)
        }
    }

    private fun distance(start: LatLng, end: LatLng) {
        latLngArray.add(start)
        val location1 = Location("locationA")
        location1.latitude = start.latitude
        location1.longitude = start.longitude
        val location2 = Location("locationB")
        location2.latitude = end.latitude
        location2.longitude = end.longitude
        val distance_tmp = location1.distanceTo(location2)

        speed = (distance_tmp * 3.6).toFloat()
        speed = BigDecimal(speed.toDouble()).setScale(2, RoundingMode.HALF_EVEN).toFloat()

        if(speed.toInt() < 40) {
            val lineoption = PolylineOptions()
            lineoption.add(start, end)
            lineoption.width(10f)
            lineoption.color(Color.BLUE)
            lineoption.geodesic(true)
            mMap.addPolyline(lineoption)
//        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(end, 25.0f))
            distance += distance_tmp
            distanceKm = distance / 1000
            if (distance > 999) {
                distanceKm = distance / 1000
                distanceKm =
                    BigDecimal(distanceKm.toDouble()).setScale(2, RoundingMode.HALF_EVEN).toFloat()
                tv_distance.text = distanceKm.toString() + " km"
            } else {
                distance =
                    BigDecimal(distance.toDouble()).setScale(2, RoundingMode.HALF_EVEN).toFloat()
                tv_distance.text = distance.toString() + " m"
            }

            if (distanceKm.toDouble() >= kilometers.toDouble()) {
                if (!kmNotificationFlag) {
                    sendNotification("Congrats!", "You reached your desired mileage.")
                    kmNotificationFlag = true
                }
                tv_goal.setTextColor(Color.parseColor("#81C784"))
            }

            speedArray.add(speed)
            tv_speed.text = speed.toString() + " km/h"

            raisedVal = (kotlin.math.floor(distanceKm) * valueKn.toDouble()).toInt()
            tv_money_raised.text = raisedVal.toString() + " kn"
        }
        previousLatLng = end
    }

    private fun getDirectionURL(origin: LatLng, dest: LatLng) : String {
        return "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${dest.latitude},${dest.longitude}&sensor=false&mode=driving&key="+getString(R.string.google_maps_key)
    }

    inner class GetDirection(val url: String) : AsyncTask<Void, Void, List<List<LatLng>>>() {
        override fun doInBackground(vararg p0: Void?): List<List<LatLng>> {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val data = response.body()?.string()
            val result = ArrayList<List<LatLng>>()
            try {
                val respObj = Gson().fromJson(data,GoogleMapDTO::class.java)

                val path =  ArrayList<LatLng>()
                for(i in 0 until respObj.routes[0].legs[0].steps.size) {
                    val startLatLng = LatLng(respObj.routes[0].legs[0].steps[i].start_location.lat.toDouble()
                        ,respObj.routes[0].legs[0].steps[i].start_location.lng.toDouble())
                    path.add(startLatLng)
                    val endLatLng = LatLng(respObj.routes[0].legs[0].steps[i].end_location.lat.toDouble()
                        ,respObj.routes[0].legs[0].steps[i].end_location.lng.toDouble())
                    path.add(endLatLng)
//                    path.addAll(decodePolyline(respObj.routes[0].legs[0].steps[i].polyline.points))
                }
                result.add(path)
            }catch (e: Exception) {
                e.printStackTrace()
            }
            println(result)
            return result
        }
        override fun onPostExecute(result: List<List<LatLng>>?) {
            val lineoption = PolylineOptions()

            if (result != null) {
                for (i in result.indices){
                    lineoption.addAll(result[i])
                    lineoption.width(10f)
                    lineoption.color(Color.BLUE)
                    lineoption.geodesic(true)
                }
            }
            mMap.addPolyline(lineoption)
        }
    }

    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].toInt() - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].toInt() - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val latLng = LatLng((lat.toDouble() / 1E5),(lng.toDouble() / 1E5))
            poly.add(latLng)
        }

        return poly
    }

    private fun snapShot() {
        val callback: GoogleMap.SnapshotReadyCallback = object : GoogleMap.SnapshotReadyCallback {
            var bitmap: Bitmap? = null
            @RequiresApi(Build.VERSION_CODES.N)
            override fun onSnapshotReady(snapshot: Bitmap) {
                bitmap = snapshot
                saveImage(bitmap!!)
            }
        }
        mMap.snapshot(callback)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @Throws(IOException::class)
    private fun saveImage(bitmap: Bitmap) {
        val contentValues = ContentValues()
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "myImage${trainingId}.png")
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        val resolver: ContentResolver? = context?.contentResolver
        val uri: Uri? =
            resolver?.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        var imageOutStream: OutputStream? = null
        println(uri)

        try {
            if (uri == null) {
                throw IOException("Failed to insert MediaStore row")
            }
            imageOutStream = resolver.openOutputStream(uri)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, imageOutStream)) {
                throw IOException("Failed to compress bitmap")
            }
        } finally {
            imageOutStream?.close()
            if (uri != null) {
                uploadProfilePhotoToFirebaseStorage(uri)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun uploadProfilePhotoToFirebaseStorage(uri: Uri) {
        val refStorage =
            FirebaseStorage.getInstance().getReference("/images/maps_screenshots/${trainingId}")

        refStorage.putFile(uri)
            .addOnSuccessListener { task ->
                Log.d(tag, "Successfully uploaded image: ${task.metadata?.path}")

                refStorage.downloadUrl.addOnSuccessListener {
                    Log.d(tag, "File location: $it")

                    saveProfilePhotoToFirebaseDatabase(it.toString())
                }
            }
            .addOnFailureListener {
                Log.d(tag, it.message.toString())
            }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun saveProfilePhotoToFirebaseDatabase(trainingImageUrl: String) {
        val endDate: String?
        endDate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val current = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            current.format(formatter)
        } else {
            val date = Date()
            val formatter = SimpleDateFormat("dd.MM.yyyy")
            formatter.format(date)
        }

        val ref = Firebase.database?.getReference("/Trainings/${trainingId}")
        ref?.child("trainingMapScreenshot")?.setValue(trainingImageUrl)?.addOnSuccessListener {
            Log.d(tag, "Saved profile photo to firebase database")
        }?.addOnFailureListener {
            Log.d(tag, "Failed to save profile photo firebase database")
        }
        if(distanceKm < 1) {
            distanceKm = BigDecimal(distanceKm.toDouble()).setScale(3, RoundingMode.HALF_EVEN).toFloat()
        }
        ref?.child("distanceKm")?.setValue(distanceKm.toString())
        ref?.child("avgSpeed")?.setValue(BigDecimal(speedArray.average()).setScale(2, RoundingMode.HALF_EVEN).toFloat().toString())
        ref?.child("endDate")?.setValue(endDate)

        distance = 0F
        distanceKm = 0F
        speed = 0F
        timesRan = 0
        latLngArray = ArrayList()
        speedArray = ArrayList()
    }

    private fun zoomRoute(
        googleMap: GoogleMap?,
        lstLatLngRoute: ArrayList<LatLng>
    ) {
        if (googleMap == null || lstLatLngRoute.isEmpty()) return
        val boundsBuilder: LatLngBounds.Builder = Builder()
        for (latLngPoint in lstLatLngRoute) boundsBuilder.include(
            latLngPoint
        )
        val routePadding = 200
        val latLngBounds: LatLngBounds = boundsBuilder.build()
        googleMap.setPadding(10,10,10, 10)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, routePadding))
        Timer("Loading Map", false).schedule(2000) {
            snapShot()
        }
    }

    private fun openMessageWindow() {
        mMap.setOnInfoWindowClickListener(object: GoogleMap.OnInfoWindowClickListener {
            override fun onInfoWindowClick(p0: Marker?) {
                if(p0?.tag.toString() != userId) {
                    val mDialogView =
                        LayoutInflater.from(context).inflate(R.layout.send_message_dialog, null)
                    val mBuilder = AlertDialog.Builder(context)
                        .setView(mDialogView)
                        .setTitle("Type in a message")

                    val mAlertDialog = mBuilder.show()
                    mDialogView.rb1.setOnClickListener{
                        mDialogView.rb1.isChecked = !mDialogView.rb1.isChecked
                        if(!mDialogView.rb1.isChecked) {
                            mDialogView.et_message.inputType = InputType.TYPE_CLASS_TEXT
                            mDialogView.et_message.setText("")
                        }
                        else {
                            mDialogView.et_message.setText(mDialogView.rb1.text.toString())
                            mDialogView.et_message.inputType = InputType.TYPE_NULL
                        }
                        mDialogView.rb2.isChecked = false
                        mDialogView.rb3.isChecked = false
                        mDialogView.rb4.isChecked = false
                    }

                    mDialogView.rb2.setOnClickListener{
                        mDialogView.rb2.isChecked = !mDialogView.rb2.isChecked
                        if(!mDialogView.rb2.isChecked) {
                            mDialogView.et_message.inputType = InputType.TYPE_CLASS_TEXT
                            mDialogView.et_message.setText("")
                        }
                        else {
                            mDialogView.et_message.setText(mDialogView.rb2.text.toString())
                            mDialogView.et_message.inputType = InputType.TYPE_NULL
                        }
                        mDialogView.rb1.isChecked = false
                        mDialogView.rb3.isChecked = false
                        mDialogView.rb4.isChecked = false
                    }

                    mDialogView.rb3.setOnClickListener{
                        mDialogView.rb3.isChecked = !mDialogView.rb3.isChecked
                        if(!mDialogView.rb3.isChecked) {
                            mDialogView.et_message.inputType = InputType.TYPE_CLASS_TEXT
                        }
                        else {
                            mDialogView.et_message.setText(mDialogView.rb3.text.toString())
                            mDialogView.et_message.inputType = InputType.TYPE_NULL
                        }
                        mDialogView.rb2.isChecked = false
                        mDialogView.rb4.isChecked = false
                        mDialogView.rb1.isChecked = false
                    }


                    mDialogView.rb4.setOnClickListener{
                        mDialogView.rb4.isChecked = !mDialogView.rb4.isChecked
                        if(!mDialogView.rb4.isChecked) {
                            mDialogView.et_message.inputType = InputType.TYPE_CLASS_TEXT
                        }
                        else {
                            mDialogView.et_message.setText(mDialogView.rb4.text.toString())
                            mDialogView.et_message.inputType = InputType.TYPE_NULL
                        }
                        mDialogView.rb2.isChecked = false
                        mDialogView.rb3.isChecked = false
                        mDialogView.rb1.isChecked = false
                    }

                    mDialogView.send_btn.setOnClickListener {
                        val message = mDialogView.et_message.text.toString()
                        if (message.isEmpty()) {
                            mDialogView.et_message.error = "Message cannot be empty!"
                            mDialogView.et_message.requestFocus()
                        } else {
                            mAlertDialog.dismiss()
                            val notificationMessage: MutableMap<String, Any> = HashMap()
                            notificationMessage["fromId"] = Firebase.userId
                            notificationMessage["message"] = message

                            mFirestore!!
                                .collection("Users/${p0?.tag.toString()}/Notifications")
                                .add(notificationMessage)
                                .addOnSuccessListener {
                                    Log.d(tag, "Notification saved to Firestore")
                                }
                        }
                    }
                    //cancel button click of custom layout
                    mDialogView.cancel_msg_btn.setOnClickListener {
                        //dismiss dialog
                        mAlertDialog.dismiss()
                    }
                }
            }
        })
    }
}

class Training(
    val id: String,
    val userId: String,
    val kilometers: Int,
    val value: Int,
    val startDate: String
)

