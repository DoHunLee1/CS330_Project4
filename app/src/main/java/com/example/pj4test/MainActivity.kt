package com.example.pj4test

import com.example.pj4test.OnSendCallListener
import android.Manifest.permission.*
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.lang.UCharacter.GraphemeClusterBreak.L
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import java.util.*


class MainActivity : AppCompatActivity(), OnSendCallListener {
    private val TAG = "MainActivity"

    // permissions
    private val permissions = arrayOf(RECORD_AUDIO, CAMERA, CALL_PHONE)
    private val PERMISSIONS_REQUEST = 0x0000001;


    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions() // check permissions
    }

    private fun checkPermissions() {
        if (permissions.all{ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED}){
            Log.d(TAG, "All Permission Granted")
        }
        else{
            requestPermissions(permissions, PERMISSIONS_REQUEST)
        }
    }

    override fun onSendCall(data: String){
        Log.d("Call", data)
        makeEmergencyCall()
    }

    private fun makeEmergencyCall() {
        val phoneNumber = "01047513726"
//        val callIntent = Intent(Intent.ACTION_CALL) // When we use call immediately
        val callIntent = Intent(Intent.ACTION_DIAL)
        callIntent.data = Uri.parse("tel:$phoneNumber")

        Log.d("Call", "EmergencyCall")

        startActivity(callIntent)
    }

}