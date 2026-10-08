package com.example.watchearn

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : AppCompatActivity() {

    companion object {
        // TEST rewarded unit. Replace with your real ad unit ID before publishing.
        const val AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
        const val DAILY_LIMIT = 20          // keep in sync with functions/index.js
        const val MIN_PAYOUT_POINTS = 1000  // keep in sync with firestore.rules
    }

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var rewardedAd: RewardedAd? = null
    private var points = 0L
    private var adsToday = 0L

    private lateinit var pointsText: TextView
    private lateinit var statusText: TextView
    private lateinit var watchButton: Button
    private lateinit var withdrawButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pointsText = findViewById(R.id.pointsText)
        statusText = findViewById(R.id.statusText)
        watchButton = findViewById(R.id.watchButton)
        withdrawButton = findViewById(R.id.withdrawButton)

        MobileAds.initialize(this)

        watchButton.setOnClickListener { showAd() }
        withdrawButton.setOnClickListener { requestPayout() }

        signIn()
    }

    private fun signIn() {
        val user = auth.currentUser
        if (user != null) { onSignedIn(user.uid); return }
        auth.signInAnonymously()
            .addOnSuccessListener { onSignedIn(it.user!!.uid) }
            .addOnFailureListener { statusText.text = "Sign-in failed: ${it.message}" }
    }

    private fun onSignedIn(uid: String) {
        // Read-only listener. Points are only ever written by the server.
        db.collection("users").document(uid).addSnapshotListener { snap, _ ->
            points = snap?.getLong("points") ?: 0L
            adsToday = if (snap?.getString("adsDate") == todayUtc()) snap.getLong("adsToday") ?: 0L else 0L
            pointsText.text = "$points points"
            updateStatus()
        }
        loadAd()
    }

    private fun loadAd() {
        RewardedAd.load(this, AD_UNIT_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    // Tell AdMob which user this reward is for, so the server callback can credit them.
                    ad.setServerSideVerificationOptions(
                        ServerSideVerificationOptions.Builder()
                            .setUserId(auth.currentUser!!.uid)
                            .build()
                    )
                    rewardedAd = ad
                    updateStatus()
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    statusText.text = "No ad available, try again soon"
                }
            })
    }

    private fun showAd() {
        val ad = rewardedAd ?: return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { rewardedAd = null; updateStatus(); loadAd() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { rewardedAd = null; loadAd() }
        }
        ad.show(this) {
            // Don't add points here. The server credits them after AdMob verifies the view.
            Toast.makeText(this, "Reward is being verified...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPayout() {
        val uid = auth.currentUser?.uid ?: return
        if (points < MIN_PAYOUT_POINTS) {
            Toast.makeText(this, "Minimum payout is $MIN_PAYOUT_POINTS points", Toast.LENGTH_SHORT).show()
            return
        }
        db.collection("payouts").add(mapOf(
            "uid" to uid,
            "points" to points,
            "status" to "pending",
            "createdAt" to FieldValue.serverTimestamp()
        )).addOnSuccessListener {
            Toast.makeText(this, "Payout requested", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            Toast.makeText(this, "Request denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val left = (DAILY_LIMIT - adsToday).coerceAtLeast(0)
        watchButton.isEnabled = rewardedAd != null && left > 0
        statusText.text = when {
            left == 0L -> "Daily limit reached. Come back tomorrow!"
            rewardedAd == null -> "Loading ad..."
            else -> "$left ads left today"
        }
    }

    private fun todayUtc(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}
