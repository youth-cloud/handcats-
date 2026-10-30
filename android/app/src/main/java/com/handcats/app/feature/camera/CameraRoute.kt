package com.handcats.app.feature.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.MotionEvent
import android.os.Build
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.handcats.app.BuildConfig
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.handcats.app.data.CatsViewModel
import com.handcats.app.ui.*
import com.handcats.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.Locale
import androidx.lifecycle.Observer

private data class LensOption(
    val key: String, val facing: Int, val ratio: Float, val selector: CameraSelector,
    val physicalId: String? = null, val minZoom: Float = 1f, val maxZoom: Float = 1f
)

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun opticalScale(info: CameraInfo): Float? = runCatching {
    val camera = Camera2CameraInfo.from(info)
    val focal = camera.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
    val sensor = camera.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    if (focal != null && sensor != null && sensor.width > 0) focal / sensor.width else null
}.getOrNull()

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable fun CameraRoute(vm: CatsViewModel, onBack: () -> Unit, onResult: () -> Unit) {
    val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    var permission by remember {mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    var cameraError by remember {mutableStateOf<String?>(null)}
    var taking by remember {mutableStateOf(false)}
    var imageCapture by remember {mutableStateOf<ImageCapture?>(null)}
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var lenses by remember { mutableStateOf(emptyList<LensOption>()) }
    var selectedLens by remember { mutableStateOf<String?>(null) }
    var unavailableLenses by remember { mutableStateOf(emptySet<String>()) }
    var facing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var requestedZoom by remember { mutableFloatStateOf(1f) }
    var activeRatio by remember { mutableFloatStateOf(1f) }
    var showZoom by remember { mutableStateOf(false) }
    var zoomInteraction by remember { mutableIntStateOf(0) }
    var zoomTouching by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->uri?.let(vm::importPhoto)}
    val gallery={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
    DisposableEffect(lifecycleOwner) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME)permission=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED}
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    val file=vm.pendingPhoto
    if(file!=null) {
        val bitmap by produceState<Bitmap?>(null,file.absolutePath,vm.photoRevision) {
            value=withContext(Dispatchers.IO){
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.absolutePath,bounds)
                val opts=BitmapFactory.Options().apply{var sample=1;while(bounds.outWidth/sample>1600||bounds.outHeight/sample>1600)sample*=2;inSampleSize=sample}
                val decoded=BitmapFactory.decodeFile(file.absolutePath,opts)
                if(decoded!=null) {
                    val orientation=runCatching { androidx.exifinterface.media.ExifInterface(file.absolutePath).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                    val matrix=android.graphics.Matrix()
                    when(orientation){
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90->matrix.postRotate(90f)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180->matrix.postRotate(180f)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270->matrix.postRotate(270f)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL->matrix.preScale(-1f,1f)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL->matrix.preScale(1f,-1f)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE->{matrix.postRotate(90f);matrix.postScale(-1f,1f)}
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE->{matrix.postRotate(270f);matrix.postScale(-1f,1f)}
                    }
                    Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true)
                } else null
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            PageHeader("留下这次相遇","确认照片清晰，就可以开始识别了",onBack={if(!vm.busy)vm.discardPhoto()})
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Mint),contentAlignment=Alignment.Center){
                bitmap?.let{Image(it.asImageBitmap(),"待识别照片",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}?:Text("正在准备照片…")
            }
            Text(if(vm.busy)"正在寻找照片里的猫咪，请稍等片刻…" else "照片将上传到你的拾猫服务器，用于识别并保存相遇记录。",color=Muted,style=MaterialTheme.typography.bodySmall)
            PrimaryButton(if(vm.busy)"正在识别" else "识别这只猫",{vm.upload(onResult)},loading=vm.busy,symbol=Symbol.Camera)
            TextButton(onClick=vm::discardPhoto,enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("换一张照片")}
            Spacer(Modifier.height(4.dp))
        }
        return
    }
    if(!permission) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            PageHeader("去拾猫",onBack=onBack)
            Spacer(Modifier.height(8.dp))
            EmptyState("借用镜头，遇见猫咪","开启相机权限，就能拍下眼前的小可爱。\n也可以直接从相册选择照片。")
            PrimaryButton("开启相机权限",{permissionLauncher.launch(Manifest.permission.CAMERA)},symbol=Symbol.Camera)
            OutlinedButton(onClick=gallery,enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("从相册选一张")}
            TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))},modifier=Modifier.fillMaxWidth()){Text("在系统设置中管理权限")}
            Spacer(Modifier.height(8.dp))
        }
        return
    }
    val preview=remember{PreviewView(context).apply{scaleType=PreviewView.ScaleType.FILL_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE}}
    val focusScope=rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<Job?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusStatus by remember { mutableStateOf("") }
    val focusScale=remember { Animatable(1f) }
    DisposableEffect(boundCamera) {
        focusJob?.cancel();focusPoint=null;focusStatus=""
        onDispose { focusJob?.cancel() }
    }
    DisposableEffect(lifecycleOwner,preview,selectedLens,facing) {
        val future=ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider?=null
        var disposed=false
        val executor=ContextCompat.getMainExecutor(context)
        future.addListener({
            if(!disposed)try {
                provider=future.get()
                val options = mutableListOf<LensOption>()
                val available = provider!!.availableCameraInfos
                if(BuildConfig.DEBUG) {
                    val cameraManager=context.getSystemService(CameraManager::class.java)
                    Log.d("HandCatsCamera", "System camera ids=${cameraManager.cameraIdList.joinToString()}; CameraX count=${available.size}")
                }
                available.forEach { info ->
                    val id = Camera2CameraInfo.from(info).cameraId
                    val defaultSelector = if(info.lensFacing==CameraSelector.LENS_FACING_FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val defaultInfo = runCatching { defaultSelector.filter(available).first() }.getOrDefault(info)
                    val referenceScale = opticalScale(defaultInfo)
                    fun ratioFor(camera: CameraInfo): Float {
                        val scale = opticalScale(camera)
                        return if(scale!=null && referenceScale!=null && referenceScale>0) scale/referenceScale
                        else camera.intrinsicZoomRatio.takeIf { it>0 } ?: 1f
                    }
                    val state = info.zoomState.value
                    options.add(LensOption(id,info.lensFacing,ratioFor(info),info.cameraSelector,
                        minZoom=state?.minZoomRatio ?: 1f,maxZoom=state?.maxZoomRatio ?: 1f))
                    if(Build.VERSION.SDK_INT >= 28) info.physicalCameraInfos.forEach { physical ->
                        val physicalId = Camera2CameraInfo.from(physical).cameraId
                        options.add(LensOption("$id/$physicalId",info.lensFacing,ratioFor(physical),info.cameraSelector,physicalId,
                            minZoom=1f,maxZoom=state?.maxZoomRatio ?: 1f))
                    }
                }
                lenses = options.distinctBy { it.key }
                val side = lenses.filter { it.facing==facing }
                val defaultSelector = if(facing==CameraSelector.LENS_FACING_FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val defaultId = runCatching { Camera2CameraInfo.from(defaultSelector.filter(available).first()).cameraId }.getOrNull()
                val lens = side.firstOrNull { it.key == selectedLens } ?: side.firstOrNull { it.key==defaultId } ?: side.firstOrNull()
                val previewBuilder=Preview.Builder()
                val captureBuilder=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(ImageCapture.FLASH_MODE_OFF)
                if(Build.VERSION.SDK_INT >= 28) lens?.physicalId?.let { id ->
                    Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(id)
                    Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(id)
                }
                val p=previewBuilder.build().also{it.surfaceProvider=preview.surfaceProvider}
                val capture=captureBuilder.build()
                provider?.unbindAll()
                boundCamera=provider?.bindToLifecycle(lifecycleOwner,lens?.selector ?: CameraSelector.DEFAULT_BACK_CAMERA,p,capture)
                activeRatio=lens?.ratio ?: 1f
                boundCamera?.let { camera ->
                    camera.cameraInfo.zoomState.value?.let { state ->
                        camera.cameraControl.setZoomRatio((requestedZoom/activeRatio).coerceIn(state.minZoomRatio,state.maxZoomRatio))
                    }
                }
                imageCapture=capture;cameraError=null
            } catch(_:Exception){
                boundCamera=null;imageCapture=null
                if(selectedLens!=null) {
                    unavailableLenses=unavailableLenses+selectedLens!!
                    selectedLens=null;requestedZoom=1f
                    cameraError="此镜头暂不可用，已返回主镜头。"
                } else cameraError="相机暂不可用，请切换前后摄或从相册选图。"
            }
        },executor)
        onDispose{disposed=true;imageCapture=null;boundCamera=null;provider?.unbindAll()}
    }
    DisposableEffect(boundCamera,lifecycleOwner) {
        val source = boundCamera?.cameraInfo?.zoomState
        val observer = Observer<ZoomState> { state ->
            zoomRatio=state.zoomRatio*activeRatio;minZoom=state.minZoomRatio*activeRatio;maxZoom=state.maxZoomRatio*activeRatio
        }
        source?.observe(lifecycleOwner,observer)
        onDispose { source?.removeObserver(observer) }
    }
    val sideLenses=lenses.filter { it.facing==facing && it.key !in unavailableLenses }
    val overallMin=sideLenses.minOfOrNull { it.ratio*it.minZoom } ?: minZoom
    val overallMax=sideLenses.maxOfOrNull { it.ratio*it.maxZoom } ?: maxZoom
    val stops=cameraZoomStops(sideLenses.map { it.ratio },overallMin,overallMax)
    fun setZoom(value: Float, preferOptical: Boolean = false) {
        if(taking || vm.busy) return
        val desired=value.coerceIn(overallMin,overallMax)
        // Keep the current stream while pinching; rebind only across its limits or at a lens shortcut.
        val candidates=sideLenses.filter { desired+.02f>=it.ratio*it.minZoom && desired<=it.ratio*it.maxZoom+.02f }
        val targetLens=candidates.filter { it.ratio<=desired+.06f }.maxWithOrNull(
            compareBy<LensOption> { it.ratio }.thenBy { it.physicalId!=null }
        ) ?: candidates.firstOrNull()
        if(targetLens!=null && (preferOptical || desired<minZoom || desired>maxZoom) && targetLens.key!=selectedLens) {
            requestedZoom=desired;selectedLens=targetLens.key;return
        }
        boundCamera?.let { camera ->
            val state = camera.cameraInfo.zoomState.value ?: return@let
            val target = (desired/activeRatio).coerceIn(state.minZoomRatio,state.maxZoomRatio)
            camera.cameraControl.setZoomRatio(target)
            requestedZoom=target*activeRatio;zoomRatio=requestedZoom
        }
    }
    LaunchedEffect(zoomInteraction,zoomTouching,showZoom) {
        if(showZoom&&!zoomTouching) { delay(1800);showZoom=false }
    }
    val zoomHandler by rememberUpdatedState<(Float)->Unit>({ factor -> showZoom=true;zoomInteraction++;setZoom(zoomRatio*factor) })
    val focusHandler by rememberUpdatedState<(Float,Float)->Unit>({ x,y ->
        val camera=boundCamera
        if(!taking&&!vm.busy&&camera!=null&&preview.width>0&&preview.height>0) {
            focusJob?.cancel()
            focusPoint=Offset(x,y);focusStatus="正在对焦"
            focusJob=focusScope.launch {
                try {
                    val point=preview.meteringPointFactory.createPoint(x,y)
                    val action=FocusMeteringAction.Builder(point,FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(5,TimeUnit.SECONDS).build()
                    val supported=camera.cameraInfo.isFocusMeteringSupported(action)
                    val future=if(supported)camera.cameraControl.startFocusAndMetering(action) else null
                    focusScale.snapTo(1.3f)
                    focusScale.animateTo(1f,tween(180))
                    val focused=if(future!=null)suspendCancellableCoroutine { continuation ->
                        future.addListener({
                            if(continuation.isActive) try { continuation.resume(future.get().isFocusSuccessful) }
                            catch(e:Exception) { continuation.resumeWithException(e) }
                        },ContextCompat.getMainExecutor(context))
                        continuation.invokeOnCancellation { future.cancel(true) }
                    } else false
                    focusStatus=if(focused)"对焦完成" else if(supported)"未能合焦，请重新点击" else "此镜头使用固定焦点"
                    delay(1500)
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { focusStatus="对焦未完成，请重试";delay(1500) }
                focusPoint=null
            }
        }
    })
    DisposableEffect(preview) {
        val detector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScaleBegin(detector:ScaleGestureDetector):Boolean {zoomTouching=true;showZoom=true;return true}
            override fun onScale(detector:ScaleGestureDetector):Boolean { zoomHandler(detector.scaleFactor); return true }
            override fun onScaleEnd(detector:ScaleGestureDetector) {zoomTouching=false;zoomInteraction++}
        })
        val taps=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(event:MotionEvent)=true
            override fun onSingleTapUp(event:MotionEvent):Boolean {
                focusHandler(event.x,event.y);preview.performClick();return true
            }
        })
        preview.setOnTouchListener { _,event -> detector.onTouchEvent(event);taps.onTouchEvent(event);true }
        onDispose { preview.setOnTouchListener(null) }
    }
    val accent=Color(0xFFFFD36A)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val compact=maxHeight<580.dp
        val shutterSize=if(compact)72.dp else 84.dp
        AndroidView(factory={preview},modifier=Modifier.fillMaxSize())
        if(grid) Canvas(Modifier.fillMaxSize()) {
            for(i in 1..2) {
                drawLine(Color.White.copy(alpha=.2f),Offset(size.width*i/3,0f),Offset(size.width*i/3,size.height),1.dp.toPx())
                drawLine(Color.White.copy(alpha=.2f),Offset(0f,size.height*i/3),Offset(size.width,size.height*i/3),1.dp.toPx())
            }
        }
        focusPoint?.let { point ->
            Canvas(Modifier.fillMaxSize().semantics{contentDescription=focusStatus}) {
                val half=30.dp.toPx()*focusScale.value
                val corner=10.dp.toPx()
                val c=if(focusStatus=="对焦完成")Color(0xFF9BE7AD) else accent
                val x=point.x.coerceIn(half,size.width-half)
                val y=point.y.coerceIn(half,size.height-half)
                for((dx,dy) in listOf(-1f to -1f,1f to -1f,-1f to 1f,1f to 1f)) {
                    val p=Offset(x+dx*half,y+dy*half)
                    drawLine(c,p,Offset(p.x-dx*corner,p.y),1.5.dp.toPx(),StrokeCap.Round)
                    drawLine(c,p,Offset(p.x,p.y-dy*corner),1.5.dp.toPx(),StrokeCap.Round)
                }
                drawCircle(c,2.dp.toPx(),Offset(x,y))
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha=.55f),Color.Transparent))
        ).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack,enabled=!taking&&!vm.busy,modifier=Modifier.semantics{contentDescription="返回"}){Glyph(Symbol.Back,color=Color.White)}
            Spacer(Modifier.weight(1f))
            Text("拾猫",color=Color.White,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick={grid=!grid},modifier=Modifier.semantics{contentDescription=if(grid)"关闭构图网格" else "开启构图网格"}) {
                Canvas(Modifier.size(22.dp)) {
                    val c=if(grid)accent else Color.White
                    for(i in 1..2) {
                        drawLine(c,Offset(size.width*i/3,0f),Offset(size.width*i/3,size.height),1.5.dp.toPx())
                        drawLine(c,Offset(0f,size.height*i/3),Offset(size.width,size.height*i/3),1.5.dp.toPx())
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.65f)))
        ).padding(top=24.dp,bottom=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            cameraError?.let { Text(it,color=Color.White,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp)) }
            AnimatedVisibility(visible=showZoom && overallMax>overallMin,enter=fadeIn(),exit=fadeOut()) {
                Row(Modifier.padding(horizontal=28.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha=.45f)).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("${zoomLabel(zoomRatio)}×",color=accent,modifier=Modifier.width(52.dp),style=MaterialTheme.typography.labelLarge)
                    if(overallMax>overallMin) Slider(
                        value=zoomRatio.coerceIn(overallMin,overallMax),
                        onValueChange={showZoom=true;zoomTouching=true;setZoom(it)},
                        onValueChangeFinished={zoomTouching=false;zoomInteraction++},
                        valueRange=overallMin..overallMax,
                        enabled=boundCamera!=null&&!taking&&!vm.busy,
                        colors=SliderDefaults.colors(thumbColor=accent,activeTrackColor=accent,inactiveTrackColor=Color.White.copy(alpha=.25f)),
                        modifier=Modifier.weight(1f).semantics{contentDescription="相机缩放倍率"}
                    )
                }
            }
            Row(Modifier.padding(horizontal=20.dp,vertical=8.dp).clip(RoundedCornerShape(40.dp)).background(Color.Black.copy(alpha=.38f))
                .horizontalScroll(rememberScrollState()).padding(5.dp),horizontalArrangement=Arrangement.spacedBy(2.dp),verticalAlignment=Alignment.CenterVertically) {
                val closest=stops.minByOrNull { kotlin.math.abs(it-zoomRatio) }
                stops.forEach { stop ->
                    val selected=closest==stop
                    Box(Modifier.size(if(compact)40.dp else 44.dp).clip(CircleShape)
                        .background(if(selected)Color.White.copy(alpha=.14f) else Color.Transparent)
                        .clickable(enabled=boundCamera!=null&&!taking&&!vm.busy){showZoom=false;setZoom(stop,preferOptical=true)}
                        .semantics{contentDescription="切换到 ${zoomLabel(stop)} 倍"},contentAlignment=Alignment.Center) {
                        Text(if(selected)"${zoomLabel(zoomRatio)}×" else zoomLabel(stop),color=if(selected)accent else Color.White,
                            style=MaterialTheme.typography.labelLarge,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            if(!compact) {
                Text("拍照",color=accent,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=10.dp,bottom=20.dp))
            } else Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal=32.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                IconButton(onClick=gallery,enabled=!taking&&!vm.busy,modifier=Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha=.12f)).semantics{contentDescription="从相册选择"}){
                    Glyph(Symbol.Image,Modifier.size(26.dp),Color.White)
                }
                Box(Modifier.size(shutterSize).border(3.dp,Color.White,CircleShape).padding(6.dp).clip(CircleShape)
                    .background(if(taking)Color.White.copy(alpha=.5f) else Color.White)
                    .clickable(enabled=imageCapture!=null&&!taking&&!vm.busy){
                        val capture=imageCapture?:return@clickable
                        taking=true;cameraError=null
                        capture.targetRotation=preview.display?.rotation?:Surface.ROTATION_0
                        val output=File.createTempFile("capture_",".jpg",context.cacheDir)
                        capture.takePicture(ImageCapture.OutputFileOptions.Builder(output).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback{
                            override fun onImageSaved(results:ImageCapture.OutputFileResults){taking=false;vm.choosePhoto(output)}
                            override fun onError(exception:ImageCaptureException){taking=false;output.delete();cameraError="拍摄失败，请再试一次"}
                        })
                    }.semantics{contentDescription="拍摄猫咪照片"},contentAlignment=Alignment.Center){
                    if(taking)CircularProgressIndicator(Modifier.size(24.dp),color=Color.DarkGray)
                }
                IconButton(onClick={
                    facing=if(facing==CameraSelector.LENS_FACING_BACK)CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    requestedZoom=1f;selectedLens=null;showZoom=false
                },enabled=!taking&&!vm.busy&&lenses.any{it.facing!=facing},
                    modifier=Modifier.size(54.dp).clip(CircleShape).background(Color.Black.copy(alpha=.35f)).semantics{contentDescription="切换前后摄像头"}) {
                    FlipCameraIcon(Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable private fun FlipCameraIcon(modifier: Modifier=Modifier) {
    Canvas(modifier) {
        val stroke=2.dp.toPx()
        val inset=size.width*.18f
        val arcSize=androidx.compose.ui.geometry.Size(size.width-2*inset,size.height-2*inset)
        drawArc(Color.White,35f,135f,false,Offset(inset,inset),arcSize,style=Stroke(stroke,cap=StrokeCap.Round))
        drawArc(Color.White,215f,135f,false,Offset(inset,inset),arcSize,style=Stroke(stroke,cap=StrokeCap.Round))
        drawLine(Color.White,Offset(size.width*.17f,size.height*.48f),Offset(size.width*.12f,size.height*.29f),stroke,StrokeCap.Round)
        drawLine(Color.White,Offset(size.width*.17f,size.height*.48f),Offset(size.width*.36f,size.height*.42f),stroke,StrokeCap.Round)
        drawLine(Color.White,Offset(size.width*.83f,size.height*.52f),Offset(size.width*.88f,size.height*.71f),stroke,StrokeCap.Round)
        drawLine(Color.White,Offset(size.width*.83f,size.height*.52f),Offset(size.width*.64f,size.height*.58f),stroke,StrokeCap.Round)
    }
}
