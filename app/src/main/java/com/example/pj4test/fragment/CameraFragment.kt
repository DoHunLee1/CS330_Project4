/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.pj4test.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.opengl.ETC1.getHeight
import android.opengl.ETC1.getWidth
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.example.pj4test.OnSendCallListener
import com.example.pj4test.ProjectConfiguration
import com.example.pj4test.cameraInference.PersonClassifier
import com.example.pj4test.databinding.FragmentCameraBinding
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.Handler
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt


class CameraFragment : Fragment(), PersonClassifier.DetectorListener {
    private val TAG = "CameraFragment"

    private var state = true
    private var accident_time = 0.0
    private var is_audio_accident = false
    private var is_phone_call = false
    private var camera_on_interval = 0
    private var onSendCallListener: OnSendCallListener? = null

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!
    
    private lateinit var personView: TextView
    
    private lateinit var personClassifier: PersonClassifier
    private lateinit var bitmapBuffer: Bitmap
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    override fun onAttach(context : Context){
        super.onAttach(context)
        onSendCallListener = context as? OnSendCallListener
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        personClassifier = PersonClassifier()
        personClassifier.initialize(requireContext())
        personClassifier.setDetectorListener(this)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        personView = fragmentCameraBinding.PersonView
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                val cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases(cameraProvider)
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {

        // CameraSelector - makes assumption that we're only using the back camera
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()
        // Attach the viewfinder's surface provider to preview use case
        preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)


        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
        // The analyzer can then be assigned to the instance
        imageAnalyzer!!.setAnalyzer(cameraExecutor) { image -> detectObjects(image) }


        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }

        fun shutdownCamera() {
            camera?.let { camera ->
                cameraProvider.unbindAll()
                this.camera = null
            }
            imageAnalyzer!!.clearAnalyzer()
            cameraExecutor.shutdown()
        }

        // Method to turn on the camera
        fun startCamera() {
            val delayInMillis: Long = 500 // Delay of 0.5 seconds
            Handler().postDelayed({
                cameraExecutor = Executors.newSingleThreadExecutor()
                imageAnalyzer!!.setAnalyzer(cameraExecutor) { image -> detectObjects(image) }
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            }, delayInMillis)
        }


        setFragmentResultListener("Accident") {key, bundle ->  //state started
            val result = bundle.getString("bundleKey")

            if (result == "Accident") {
                camera_on_interval = 30 * 20 // 20 seconds
//                camera_on_interval = 30 * 600 // 10 minutes in real situation
                accident_time = 0.0
                if(!state) {
                    startCamera()
                    if(!is_audio_accident){
                        is_phone_call = false
                        is_audio_accident = true
                        accident_time = 0.0
                    }
                    state = true
                    Log.d("Camera", "Camera On")
                }
            }else {
                if(state && camera_on_interval <= 0) {
                    shutdownCamera()
                    val delayInMillis: Long = 500 // Delay of 0.5 seconds
                    Handler().postDelayed({
                        personView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                        personView.setTextColor(ProjectConfiguration.idleTextColor)
                        personView.text = "Accident Not Happened"
                        is_phone_call = false
                    }, delayInMillis)
                    state = false

                    Log.d("Camera", "Camera Off")
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    private fun detectObjects(image: ImageProxy) {
        if (!::bitmapBuffer.isInitialized) {
            // The image rotation and RGB image buffer are initialized only once
            // the analyzer has started running
            bitmapBuffer = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
        }
        // Copy out RGB bits to the shared bitmap buffer
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }
        val imageRotation = image.imageInfo.rotationDegrees

        // Pass Bitmap and rotation to the object detector helper for processing and detection
        personClassifier.detect(bitmapBuffer, imageRotation)
    }


    // Update UI after objects have been detected. Extracts original image height/width
    // to scale and place bounding boxes properly through OverlayView
    override fun onObjectDetectionResults(
        results: MutableList<Detection>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    ) {
        activity?.runOnUiThread {
            // Pass necessary information to OverlayView for drawing on the canvas
            fragmentCameraBinding.overlay.setResults(
                results ?: LinkedList<Detection>(),
                imageHeight,
                imageWidth
            )
            
            // find at least one bounding box of the person
            val isPersonDetected: Boolean = results!!.find { it.categories[0].label == "person" } != null

            val newPerson = results!!.find {it.categories[0].label == "person"}
            camera_on_interval -= 1
            // change UI according to the result
            if (isPersonDetected) {
                personView.setBackgroundColor(ProjectConfiguration.activeBackgroundColor)
                personView.setTextColor(ProjectConfiguration.activeTextColor)

                val boundingBox = newPerson!!.boundingBox

                val top = boundingBox.top
                val bottom = boundingBox.bottom
                val left = boundingBox.left
                val right = boundingBox.right

                // Draw bounding box around detected objects
                val drawableRect = RectF(left, top, right, bottom)

                if(drawableRect.width() > drawableRect.height() && is_audio_accident){
                    accident_time += 1.0/30.0
                    val roundoff = (accident_time * 100.0).roundToInt() / 100.0
                    Log.d("Accident Time", "Accident time : ${roundoff}초")
                }

                if(accident_time > 10.0){ //Use accident_time > 480.0 if real situation
                    if(!is_phone_call){
                        is_phone_call = true
                        val data = "Emergency"
                        onSendCallListener?.onSendCall(data)
                    }
                }

                val roundoff = (accident_time * 100.0).roundToInt() / 100.0
                personView.text = "Accident Time : ${roundoff}초"

            } else {
                personView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                personView.setTextColor(ProjectConfiguration.idleTextColor)

                Log.d("Call", "CameraCall")

                val roundoff = (accident_time * 100.0).roundToInt() / 100.0
                personView.text = "Accident Time : ${roundoff}초"

            }

            // Force a redraw
            fragmentCameraBinding.overlay.invalidate()
        }
    }

    override fun onObjectDetectionError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
}
