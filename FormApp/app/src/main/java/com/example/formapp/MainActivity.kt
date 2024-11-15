package com.example.formapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private lateinit var appDir: File
    var capturedImageFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize app directory
        appDir = File(getExternalFilesDir(null), "CapturedImages")
        if (!appDir.exists()) appDir.mkdirs()

        // Request permissions
        val requestPermissions = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            permissions.entries.forEach {
                Log.d("Permissions", "${it.key} = ${it.value}")
            }
        }
        requestPermissions.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            )
        )

        // Camera launcher
        val cameraLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && capturedImageFile != null) {
                Log.d("Camera", "Image saved at: ${capturedImageFile!!.absolutePath}")
            } else {
                Log.d("Camera", "Image capture failed or canceled")
            }
        }

        setContent {
            var formData by remember { mutableStateOf(FormData()) }
            var showJsonScreen by remember { mutableStateOf(false) }

            if (showJsonScreen) {
                JsonDataScreen(jsonData = formData)
            } else {
                MainScreen(
                    context = this,
                    formData = formData,
                    onFormSubmit = { updatedData ->
                        formData = updatedData
                        showJsonScreen = true
                    },
                    cameraLauncher = cameraLauncher
                )
            }
        }
    }

    // Start Recording
    fun startRecording(): File? {
        val audioDir = File(getExternalFilesDir(null), "AudioFiles")
        if (!audioDir.exists()) audioDir.mkdirs()

        audioFile = File(audioDir, "audio_${System.currentTimeMillis()}.wav")
        return try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            audioFile
        } catch (e: Exception) {
            Log.e("RecordingError", "Error starting recording: ${e.message}")
            null
        }
    }

    // Stop Recording
    fun stopRecording(): File? {
        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.e("RecordingError", "Error stopping recording: ${e.message}")
            } finally {
                mediaRecorder?.release()
                mediaRecorder = null
                isRecording = false
            }
        }
        return audioFile
    }

    // Get Current Location
    fun getCurrentLocation(context: Context): Pair<Double, Double>? {
        val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val location: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        return location?.latitude?.let { lat ->
            location.longitude.let { lon -> Pair(lat, lon) }
        }
    }

    // Get Timestamp
    fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    // Launch Camera Intent Using FileProvider
    fun captureImage(cameraLauncher: ActivityResultLauncher<Intent>) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            capturedImageFile = File(appDir, "IMG_$timestamp.jpg")

            val imageUri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                capturedImageFile!!
            )

            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
            }

            if (cameraIntent.resolveActivity(packageManager) != null) {
                cameraLauncher.launch(cameraIntent)
            }
        } catch (e: IOException) {
            Log.e("CameraError", "Error creating file: ${e.message}")
        }
    }
}


@Composable
fun MainScreen(
    context: Context,
    formData: FormData,
    onFormSubmit: (FormData) -> Unit,
    cameraLauncher: ActivityResultLauncher<Intent>,
) {
    var currentStep by remember { mutableStateOf(0) }
    val location = (context as MainActivity).getCurrentLocation(context)
    val timestamp = context.getCurrentTimestamp()

    var audioFile by remember { mutableStateOf<File?>(null) }
    var capturedImagePath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentStep) {
        if (currentStep == 0) {
            audioFile = context.startRecording()
        } else {
            context.stopRecording()
        }
    }

    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentStep > 0) {
                    Button(onClick = { currentStep-- }) {
                        Text("Previous")
                    }
                }
                if (currentStep < 2) {
                    Button(onClick = { currentStep++ }) {
                        Text("Next")
                    }
                } else {
                    Button(onClick = {
                        val updatedFormData = formData.copy(
                            gps = "${location?.first},${location?.second}",
                            submitTime = timestamp,
                            recording = audioFile?.absolutePath.orEmpty(),
                            image = context.capturedImageFile.toString()
                        )

                        val jsonFile = File(context.getExternalFilesDir(null), "AppName/form_data.json")
                        if (!jsonFile.parentFile.exists()) jsonFile.parentFile.mkdirs()
                        jsonFile.writeText(Gson().toJson(updatedFormData))

                        context.stopRecording()
                        onFormSubmit(updatedFormData)
                    }) {
                        Text("Submit")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (currentStep) {
                0 -> GenderQuestion { formData.gender = it }
                1 -> AgeQuestion { formData.age = it }
                2 -> {
                    ImageCaptureQuestion(
                        onCaptureImage = {
                            context.captureImage(
                                cameraLauncher
                            )
                        },
                        
                    )
                }
            }
        }
    }
}


@Composable
fun JsonDataScreen(jsonData: FormData) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("JSON Data", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Table(jsonData)
    }
}

@Composable
fun Table(jsonData: FormData) {
    Column {
        jsonData::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            val value = field.get(jsonData)?.toString().orEmpty()
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = "${field.name.capitalize()}: ", modifier = Modifier.weight(1f))
                Text(text = value, modifier = Modifier.weight(2f))
            }
        }
    }
}

@Composable
fun GenderQuestion(onGenderSelected: (String) -> Unit) {
    var gender by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Select your gender:")
        TextField(
            value = gender,
            onValueChange = {
                gender = it
                onGenderSelected(it)
            },
            label = { Text("Gender") }
        )
    }
}

@Composable
fun AgeQuestion(onAgeEntered: (Int) -> Unit) {
    var age by remember { mutableStateOf(TextFieldValue("")) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Enter your age:")
        TextField(
            value = age,
            onValueChange = {
                age = it
                it.text.toIntOrNull()?.let(onAgeEntered)
            },
            label = { Text("Age") }
        )
    }
}

@Composable
fun ImageCaptureQuestion(onCaptureImage: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Capture your image:")
        Button(onClick = onCaptureImage) {
            Text("Capture")
        }
        
    }
}



data class FormData(
    var gender: String = "",
    var age: Int = 0,
    var image: String = "",
    var recording: String = "",
    var gps: String = "",
    var submitTime: String = ""
)
