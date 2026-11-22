package com.verifygn.vcount

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val vfLoader = VFModelLoader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VCountAppScreen(vfLoader)
            }
        }
    }
}

@Composable
fun VCountAppScreen(vfLoader: VFModelLoader) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableIntStateOf(0) } // 0=Image Detect, 1=Live Camera, 2=GitHub Model
    var githubUrl by remember {
        mutableStateOf("https://raw.githubusercontent.com/rajkumarverifygn-lang/shortlink_model/main/best_fast.vf")
    }
    var modelStatus by remember { mutableStateOf("Loading model from GitHub...") }
    var isModelLoaded by remember { mutableStateOf(false) }

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var annotatedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var shortlinkCount by remember { mutableIntStateOf(0) }
    var inferMs by remember { mutableFloatStateOf(0f) }
    var confThreshold by remember { mutableFloatStateOf(0.25f) }
    var iouThreshold by remember { mutableFloatStateOf(0.45f) }
    var isDetecting by remember { mutableStateOf(false) }

    var liveDetections by remember { mutableStateOf<List<ShortlinkDetection>>(emptyList()) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                selectedBitmap = BitmapFactory.decodeStream(stream)
                annotatedBitmap = null
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // Auto-request camera permission and auto-load best_fast.vf from GitHub on startup
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        try {
            withContext(Dispatchers.IO) {
                vfLoader.loadFromGithubUrl(githubUrl)
            }
            isModelLoaded = true
            modelStatus = "Model Ready (GitHub)"
        } catch (e: Exception) {
            modelStatus = "Load Error: ${e.message}"
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color(0xFF070B14),
        bottomBar = {
            NavigationBar(
                containerColor = androidx.compose.ui.graphics.Color(0xFF0B1120)
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    label = { Text("Image Detect") },
                    icon = { Text("🖼️", fontSize = 18.sp) }
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    label = { Text("Live Camera") },
                    icon = { Text("🎥", fontSize = 18.sp) }
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    label = { Text("GitHub Model") },
                    icon = { Text("☁️", fontSize = 18.sp) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            // HEADER BAR WITH VERIFYGN LOGO
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = androidx.compose.ui.graphics.Color.White,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.vfn_logo),
                            contentDescription = "VerifyGN Logo",
                            modifier = Modifier
                                .height(34.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Column {
                        Text(
                            text = "V-Count",
                            color = androidx.compose.ui.graphics.Color(0xFF4ADE80),
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "SHORTLINK COUNTER",
                            color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Surface(
                    color = androidx.compose.ui.graphics.Color(0xFF112233),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isModelLoaded) "READY" else "LOADING",
                        color = if (isModelLoaded) androidx.compose.ui.graphics.Color(0xFF4ADE80)
                        else androidx.compose.ui.graphics.Color(0xFFFBBF24),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // HERO COUNT CARD
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF111928))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SHORTLINKS COUNT",
                            color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$shortlinkCount",
                            color = androidx.compose.ui.graphics.Color(0xFF4ADE80),
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${"%.0f".format(inferMs)} ms",
                            color = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = modelStatus,
                            color = androidx.compose.ui.graphics.Color(0xFFCBD5E1),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (activeTab) {
                0 -> {
                    // TAB 1: UPLOAD IMAGE -> PRESS DETECT & COUNT
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF111928))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "1. Upload Image → 2. Press Detect & Count",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            val displayBmp = annotatedBitmap ?: selectedBitmap
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(330.dp)
                                    .background(androidx.compose.ui.graphics.Color(0xFF030712), RoundedCornerShape(12.dp))
                                    .border(1.dp, androidx.compose.ui.graphics.Color(0xFF1E293B), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (displayBmp != null) {
                                    Image(
                                        bitmap = displayBmp.asImageBitmap(),
                                        contentDescription = "Shortlink Detection Result",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    Text(
                                        text = "Tap 'Upload Image' to select a photo",
                                        color = androidx.compose.ui.graphics.Color(0xFF64748B)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { imagePicker.launch("image/*") },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Upload Image", fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val bmp = selectedBitmap ?: return@Button
                                        scope.launch {
                                            isDetecting = true
                                            try {
                                                val (res, drawn) = withContext(Dispatchers.Default) {
                                                    runBitmapDetection(vfLoader, bmp, confThreshold, iouThreshold)
                                                }
                                                shortlinkCount = res.count
                                                inferMs = res.inferMs
                                                annotatedBitmap = drawn
                                            } catch (e: Exception) {
                                                modelStatus = "Detect Error: ${e.message}"
                                            } finally {
                                                isDetecting = false
                                            }
                                        }
                                    },
                                    enabled = (selectedBitmap != null && isModelLoaded && !isDetecting),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = androidx.compose.ui.graphics.Color(0xFF22C55E)
                                    )
                                ) {
                                    Text(
                                        if (isDetecting) "Detecting..." else "Detect & Count",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // TAB 2: LIVE CAMERA DETECTION & COUNTING
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF111928))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Live Camera Detection & Count",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            if (!isModelLoaded) {
                                Text(
                                    text = "Waiting for model to finish loading from GitHub...",
                                    color = androidx.compose.ui.graphics.Color(0xFFFBBF24),
                                    fontSize = 13.sp
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(380.dp)
                                        .background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(12.dp))
                                ) {
                                    LiveCameraAnalyzerView(
                                        vfLoader = vfLoader,
                                        confThreshold = confThreshold,
                                        iouThreshold = iouThreshold,
                                        onResult = { res ->
                                            shortlinkCount = res.count
                                            inferMs = res.inferMs
                                            liveDetections = res.detections
                                        }
                                    )
                                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                        for (det in liveDetections) {
                                            val x1 = det.boxNorm.left * size.width
                                            val y1 = det.boxNorm.top * size.height
                                            val x2 = det.boxNorm.right * size.width
                                            val y2 = det.boxNorm.bottom * size.height
                                            drawRect(
                                                color = androidx.compose.ui.graphics.Color(0xFF22C55E),
                                                topLeft = Offset(x1, y1),
                                                size = Size(x2 - x1, y2 - y1),
                                                style = Stroke(width = 4f)
                                            )
                                            val cx = (x1 + x2) * 0.5f
                                            val cy = (y1 + y2) * 0.5f
                                            drawCircle(
                                                color = androidx.compose.ui.graphics.Color(0xFF0F172A),
                                                radius = 8f,
                                                center = Offset(cx, cy)
                                            )
                                            drawCircle(
                                                color = androidx.compose.ui.graphics.Color(0xFFFACC15),
                                                radius = 6f,
                                                center = Offset(cx, cy)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // TAB 3: GITHUB MODEL LOADER
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF111928))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "GitHub .vf Model Source",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = githubUrl,
                                onValueChange = { githubUrl = it },
                                label = { Text("GitHub Raw .vf URL") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        modelStatus = "Downloading .vf from GitHub..."
                                        try {
                                            withContext(Dispatchers.IO) {
                                                vfLoader.loadFromGithubUrl(githubUrl)
                                            }
                                            isModelLoaded = true
                                            modelStatus = "Model Ready (GitHub)"
                                        } catch (e: Exception) {
                                            modelStatus = "Error: ${e.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = androidx.compose.ui.graphics.Color(0xFF16A34A)
                                )
                            ) {
                                Text("Reload Model from GitHub", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveCameraAnalyzerView(
    vfLoader: VFModelLoader,
    confThreshold: Float,
    iouThreshold: Float,
    onResult: (VFDetectionResult) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analyzer.setAnalyzer(executor) { imageProxy ->
                    try {
                        val rawBmp = imageProxy.toBitmap()
                        val rot = imageProxy.imageInfo.rotationDegrees
                        val bmp = if (rot != 0) {
                            val mat = Matrix().apply { postRotate(rot.toFloat()) }
                            Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, mat, true)
                        } else {
                            rawBmp
                        }
                        val (res, _) = runBitmapDetection(vfLoader, bmp, confThreshold, iouThreshold, drawOutput = false)
                        ContextCompat.getMainExecutor(ctx).execute {
                            onResult(res)
                        }
                    } catch (_: Exception) {
                    } finally {
                        imageProxy.close()
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

/**
 * Resizes an Android Bitmap to 640x640, runs VFModelLoader ONNX inference, and draws clean bounding boxes + center dot marks.
 */
fun runBitmapDetection(
    loader: VFModelLoader,
    src: Bitmap,
    confThreshold: Float,
    iouThreshold: Float,
    drawOutput: Boolean = true
): Pair<VFDetectionResult, Bitmap?> {
    val target = loader.inputSize
    val origW = src.width
    val origH = src.height
    val resized = Bitmap.createScaledBitmap(src, target, target, true)

    val pixels = IntArray(target * target)
    resized.getPixels(pixels, 0, target, 0, 0, target, target)
    val area = target * target
    val chw = FloatArray(3 * area)
    for (i in 0 until area) {
        val px = pixels[i]
        chw[i] = ((px shr 16) and 0xFF) / 255.0f
        chw[area + i] = ((px shr 8) and 0xFF) / 255.0f
        chw[2 * area + i] = (px and 0xFF) / 255.0f
    }

    val result = loader.detectAndCount(
        rgbFloatCHW = chw,
        origWidth = origW,
        origHeight = origH,
        confThreshold = confThreshold,
        iouThreshold = iouThreshold
    )

    if (!drawOutput) return Pair(result, null)

    val outBmp = src.copy(Bitmap.Config.ARGB_8888, true)
    val outCanvas = Canvas(outBmp)
    val strokeW = (min(origW, origH) / 220f).coerceAtLeast(3f)
    val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 197, 94)
        style = Paint.Style.STROKE
        strokeWidth = strokeW
    }
    val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 23, 42)
        style = Paint.Style.FILL
    }
    val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 204, 21)
        style = Paint.Style.FILL
    }
    val dotRadius = (strokeW + 2f).coerceAtLeast(5f)

    for (det in result.detections) {
        outCanvas.drawRect(det.boxPx, boxPaint)
        val cx = (det.boxPx.left + det.boxPx.right) * 0.5f
        val cy = (det.boxPx.top + det.boxPx.bottom) * 0.5f
        outCanvas.drawCircle(cx, cy, dotRadius + 1.5f, dotRingPaint)
        outCanvas.drawCircle(cx, cy, dotRadius, dotFillPaint)
    }

    return Pair(result, outBmp)
}
