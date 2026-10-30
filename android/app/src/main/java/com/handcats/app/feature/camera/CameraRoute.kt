package com.handcats.app.feature.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import java.io.File

@Composable fun CameraRoute(vm: CatsViewModel, onBack: () -> Unit, onResult: () -> Unit) {
    val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    var permission by remember {mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    var cameraError by remember {mutableStateOf<String?>(null)}
    var taking by remember {mutableStateOf(false)}
    var imageCapture by remember {mutableStateOf<ImageCapture?>(null)}
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
    DisposableEffect(lifecycleOwner,preview) {
        val future=ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider?=null
        var disposed=false
        val executor=ContextCompat.getMainExecutor(context)
        future.addListener({
            if(!disposed)try {
                provider=future.get()
                val p=Preview.Builder().build().also{it.surfaceProvider=preview.surfaceProvider}
                val capture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(ImageCapture.FLASH_MODE_OFF).build()
                provider?.unbindAll();provider?.bindToLifecycle(lifecycleOwner,CameraSelector.DEFAULT_BACK_CAMERA,p,capture)
                imageCapture=capture;cameraError=null
            } catch(_:Exception){cameraError="相机暂时无法打开，请退出重试，或从相册选图。"}
        },executor)
        onDispose{disposed=true;imageCapture=null;provider?.unbindAll()}
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory={preview},modifier=Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().padding(horizontal=40.dp,vertical=160.dp)) {
            val c=Color.White.copy(alpha=.8f);val l=28.dp.toPx();val sw=2.dp.toPx()
            for((x,y)in listOf(0f to 0f,size.width to 0f,0f to size.height,size.width to size.height)){
                drawLine(c,Offset(x,y),Offset(x+if(x==0f)l else -l,y),sw)
                drawLine(c,Offset(x,y),Offset(x,y+if(y==0f)l else -l),sw)
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha=.35f)).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack,enabled=!taking&&!vm.busy,modifier=Modifier.semantics{contentDescription="返回"}){Glyph(Symbol.Back,color=Color.White)}
            Text("去拾猫",color=Color.White,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            Tag("不闪光 · 不打扰",color=Color.White,background=Color.White.copy(alpha=.15f))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha=.5f)).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text(cameraError?:"让猫咪完整入镜，保持一点温柔的距离",color=Color.White,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceAround) {
                Column(horizontalAlignment=Alignment.CenterHorizontally){IconButton(onClick=gallery,enabled=!taking&&!vm.busy,modifier=Modifier.semantics{contentDescription="从相册选择"}){Glyph(Symbol.Image,color=Color.White)};Text("相册",color=Color.White,style=MaterialTheme.typography.bodySmall)}
                Box(Modifier.size(82.dp).border(3.dp,Color.White,CircleShape).padding(7.dp).clip(CircleShape).background(if(taking)Muted else Color.White).clickable(enabled=imageCapture!=null&&!taking&&!vm.busy){
                    val capture=imageCapture?:return@clickable
                    taking=true;cameraError=null
                    capture.targetRotation=preview.display?.rotation?:Surface.ROTATION_0
                    val output=File.createTempFile("capture_",".jpg",context.cacheDir)
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(output).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback{
                        override fun onImageSaved(results:ImageCapture.OutputFileResults){taking=false;vm.choosePhoto(output)}
                        override fun onError(exception:ImageCaptureException){taking=false;output.delete();cameraError="拍摄失败，请再试一次"}
                    })
                }.semantics{contentDescription="拍摄猫咪照片"},contentAlignment=Alignment.Center){if(taking)CircularProgressIndicator(Modifier.size(24.dp))else Glyph(Symbol.Camera,Modifier.size(30.dp))}
                Column(horizontalAlignment=Alignment.CenterHorizontally){Glyph(Symbol.Leaf,Modifier.padding(12.dp),Color.White);Text("轻声靠近",color=Color.White,style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
}
