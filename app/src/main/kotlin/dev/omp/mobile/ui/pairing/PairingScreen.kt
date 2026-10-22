package dev.omp.mobile.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.omp.mobile.collab.PairingCredentials
import dev.omp.mobile.collab.PairingParseResult
import dev.omp.mobile.collab.parseCollabLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * QR/paste pairing. Both paths use the same parser. The screen returns only
 * parsed credentials; the raw link is never retained after a valid parse.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingScreen(
    onClose: () -> Unit,
    onParsed: (PairingCredentials) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }
    var scanningImage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentOnParsed by rememberUpdatedState(onParsed)
    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    fun acceptRaw(raw: String) {
        if (handled) return
        when (val parsed = parseCollabLink(raw)) {
            is PairingParseResult.Valid -> {
                handled = true
                error = null
                currentOnParsed(parsed.credentials)
            }

            is PairingParseResult.Invalid -> error = parsed.error.message
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scanningImage = true
        error = null
        scope.launch {
            val input = withContext(Dispatchers.IO) {
                runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
            }
            if (input == null) {
                error = "Could not read the selected image."
                scanningImage = false
                return@launch
            }
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val raw = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                    if (raw == null) {
                        error = "No QR code found in the selected image."
                    } else {
                        acceptRaw(raw)
                    }
                    scanningImage = false
                }
                .addOnFailureListener {
                    error = "Could not scan the selected image."
                    scanningImage = false
                }
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanner.close() }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Pair") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close pairing")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Scan a Control or View QR code with the camera, pick a QR image, or paste a pairing link.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                hasPermission -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLargeIncreased,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        CameraScanner(
                            scanner = scanner,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            onQr = { raw -> acceptRaw(raw) },
                        )
                    }
                }

                else -> {
                    Text("Camera permission was denied or no camera is available. Pasting a link still works.")
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        shape = CircleShape,
                    ) {
                        Text("Request camera permission")
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    imageLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !scanningImage,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
            ) {
                if (scanningImage) {
                    LoadingIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.padding(4.dp))
                }
                Text(if (scanningImage) "Scanning image…" else "Scan QR from image")
            }

            PasteLink(
                onParsed = { credentials ->
                    handled = true
                    error = null
                    currentOnParsed(credentials)
                },
                onError = { error = it },
            )

            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PasteLink(
    onParsed: (PairingCredentials) -> Unit,
    onError: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            label = { Text("Pairing link") },
            singleLine = false,
            maxLines = 3,
            shape = MaterialTheme.shapes.extraLarge,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = {
                    when (val parsed = parseCollabLink(text)) {
                        is PairingParseResult.Valid -> {
                            text = ""
                            onParsed(parsed.credentials)
                        }

                        is PairingParseResult.Invalid -> onError(parsed.error.message)
                    }
                },
                enabled = text.isNotBlank(),
                shape = CircleShape,
            ) { Text("Paste") }
            TextButton(onClick = { text = "" }) { Text("Clear") }
        }
    }
}

@Composable
private fun CameraScanner(
    scanner: BarcodeScanner,
    modifier: Modifier,
    onQr: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQr by rememberUpdatedState(onQr)
    val executor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, QrAnalyzer(scanner, ContextCompat.getMainExecutor(ctx)) { raw -> currentOnQr(raw) })
                runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private class QrAnalyzer(
    private val scanner: BarcodeScanner,
    private val mainExecutor: java.util.concurrent.Executor,
    private val onRaw: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.rawValue?.let { raw -> mainExecutor.execute { onRaw(raw) } }
            }
            .addOnFailureListener {
                // Invalid frame: keep scanning.
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
