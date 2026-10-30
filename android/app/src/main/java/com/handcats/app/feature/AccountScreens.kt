package com.handcats.app.feature

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.handcats.app.data.CatsViewModel
import com.handcats.app.data.remote.ApiClient
import androidx.core.content.ContextCompat
import com.handcats.app.ui.*
import com.handcats.app.ui.theme.*

@Composable fun PasswordField(value: String, label: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, label={ Text(label) }, singleLine=true, enabled=enabled,
        modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(16.dp),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
        visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon={ TextButton(onClick={ visible=!visible }) { Text(if(visible) "隐藏" else "显示", style=MaterialTheme.typography.bodySmall) } })
}

@Composable fun AuthScreen(vm: CatsViewModel, settings: () -> Unit, privacy: () -> Unit) {
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=28.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top=12.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
            Text("拾猫", color=Forest, style=MaterialTheme.typography.labelLarge)
            TextButton(onClick=settings, enabled=!vm.busy) { Text("连接设置") }
        }
        CatArt(modifier=Modifier.size(120.dp).align(Alignment.CenterHorizontally))
        Text(if(register) "开始你的拾猫故事" else "记住每一次相遇", style=MaterialTheme.typography.headlineSmall)
        Text(if(register) "创建账号，收集属于你的猫咪图鉴。" else "登录拾猫，继续你的城市寻猫之旅。", color=Muted)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!register, { register=false; localError=null }, label={Text("登录")}, enabled=!vm.busy)
            FilterChip(register, { register=true; localError=null }, label={Text("创建账号")}, enabled=!vm.busy)
        }
        if(register) OutlinedTextField(nickname,{ nickname=it.take(30) },label={Text("怎么称呼你")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),enabled=!vm.busy)
        OutlinedTextField(username,{ username=it.take(32) },label={Text("账号")},supportingText={Text("3–32 位字母、数字或下划线")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),enabled=!vm.busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
        PasswordField(password,"密码 · 至少 8 位",{password=it.take(128)},!vm.busy)
        if(register) PasswordField(confirm,"再次输入密码",{confirm=it.take(128)},!vm.busy)
        if(register) Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(accepted,{accepted=it},enabled=!vm.busy)
            Column { Text("我已阅读并同意",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=privacy,contentPadding=PaddingValues(0.dp)) {Text("使用与隐私说明",style=MaterialTheme.typography.bodySmall)} }
        }
        localError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        PrimaryButton(if(register) "创建账号，开始拾猫" else "登录，去遇见猫咪",onClick={
            localError=when {
                !Regex("^[a-zA-Z0-9_]{3,32}$").matches(username.trim()) -> "账号需为 3–32 位字母、数字或下划线"
                password.length < 8 -> "密码至少需要 8 位"
                register && nickname.isBlank() -> "先给自己起个昵称吧"
                register && password!=confirm -> "两次输入的密码不一致"
                register && !accepted -> "请先阅读并同意使用与隐私说明"
                else -> null
            }
            if(localError==null) vm.authenticate(register,username,password,nickname)
        },loading=vm.busy,symbol=Symbol.Arrow)
        if(!register) TextButton(onClick={localError="忘记密码时，请联系你部署的服务管理员核验账号后重置；本版本尚未接入邮件或短信找回。"},modifier=Modifier.align(Alignment.CenterHorizontally)) {Text("忘记密码？")}
        Text("保持距离，不打扰，是最好的相遇。",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=24.dp))
    }
}

@Composable fun SettingsScreen(vm: CatsViewModel, back: () -> Unit) {
    val context = LocalContext.current
    var server by rememberSaveable { mutableStateOf(vm.server) }
    var permissionError by rememberSaveable { mutableStateOf(false) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var pendingServer by rememberSaveable { mutableStateOf<String?>(null) }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val address = pendingServer
        pendingServer = null
        permissionError = !granted
        if (granted && address != null) vm.server(address, back)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        PageHeader("连接设置","连接到你的拾猫服务",back)
        Paper {
            Glyph(Symbol.Settings,Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
            Text("让相遇安全抵达",style=MaterialTheme.typography.titleLarge)
            Text("账号、照片和图鉴保存在你配置的服务器上。更换服务器会退出当前账号。",color=Muted,modifier=Modifier.padding(top=8.dp))
        }
        OutlinedTextField(server,{server=it},label={Text("服务地址")},placeholder={Text("http://192.168.1.10:8000")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),enabled=!vm.busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
        Text("手机与电脑连接同一 Wi-Fi 时，填写电脑的局域网 IP。安卓模拟器可使用 http://10.0.2.2:8000/。",color=Muted)
        Text("本地调试支持 HTTP；部署到公网时请使用 HTTPS，保护账号和照片传输。",style=MaterialTheme.typography.bodySmall,color=Muted)
        if (permissionError) {
            Text("连接电脑需要本地网络权限。请在系统设置中允许拾猫访问本地网络，然后返回重试。", color=MaterialTheme.colorScheme.error)
            TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))}) { Text("打开应用设置") }
        }
        addressError?.let { Text(it, color=MaterialTheme.colorScheme.error) }
        PrimaryButton("测试连接并保存",{
            addressError = null
            val normalized = try { ApiClient.normalize(server) } catch (e: IllegalArgumentException) {
                addressError = e.message
                null
            }
            if (normalized != null) {
                if (Build.VERSION.SDK_INT >= 37 && ApiClient.isLocalServer(normalized) &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                    pendingServer = normalized
                    localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    permissionError = false
                    vm.server(normalized, back)
                }
            }
        },loading=vm.busy,symbol=Symbol.Check)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable fun ProfileScreen(vm: CatsViewModel, settings: () -> Unit, privacy: () -> Unit, history: () -> Unit) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf(vm.user?.nickname.orEmpty()) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var removalPassword by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        PageHeader("我的","把小小的喜欢，慢慢收集")
        Paper(color=Mint) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                CatArt(modifier=Modifier.size(88.dp))
                Column(Modifier.weight(1f)) { Text(vm.user?.nickname.orEmpty(),style=MaterialTheme.typography.headlineSmall);Text("@${vm.user?.username.orEmpty()}",color=Muted);Spacer(Modifier.height(8.dp));Tag("城市拾猫员",background=Color.White.copy(alpha=.65f)) }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceAround) {
                Metric("${vm.cats.count{it.collected}}","已点亮")
                Metric("${vm.total}","次相遇")
                Metric("${vm.cats.size}","图鉴类别")
            }
        }
        Paper {
            MenuRow(Symbol.Person,"修改昵称","让猫友更容易记住你") {nickname=vm.user?.nickname.orEmpty();dialog="nickname"}
            HorizontalDivider(color=Line)
            MenuRow(Symbol.History,"相遇记录","照片、笔记与收藏",history)
            HorizontalDivider(color=Line)
            MenuRow(Symbol.Lock,"修改密码","保护你的拾猫账号") {dialog="password"}
            HorizontalDivider(color=Line)
            MenuRow(Symbol.Settings,"连接设置","管理你的服务地址",settings)
            HorizontalDivider(color=Line)
            MenuRow(Symbol.Leaf,"关于拾猫","使用与隐私说明",privacy)
        }
        OutlinedButton(onClick={dialog="logout"},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),enabled=!vm.busy) {Text("退出登录")}
        TextButton(onClick={vm.clearMessage();removalPassword="";dialog="remove"},modifier=Modifier.align(Alignment.CenterHorizontally),enabled=!vm.busy) {Text("注销账号",color=MaterialTheme.colorScheme.error)}
        Text("拾猫 1.0 · 小小相遇，大大世界",style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=24.dp))
    }
    if(dialog=="nickname") AlertDialog(onDismissRequest={if(!vm.busy)dialog=null},title={Text("给自己一个新称呼")},text={OutlinedTextField(nickname,{nickname=it.take(30)},label={Text("昵称")},singleLine=true)},confirmButton={TextButton(onClick={vm.nickname(nickname){dialog=null}},enabled=nickname.isNotBlank()&&!vm.busy){Text("保存")}},dismissButton={TextButton(onClick={dialog=null},enabled=!vm.busy){Text("取消")}})
    if(dialog=="password") AlertDialog(onDismissRequest={if(!vm.busy)dialog=null},title={Text("修改密码")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        PasswordField(oldPassword,"原密码",{oldPassword=it.take(128)})
        PasswordField(newPassword,"新密码 · 至少 8 位",{newPassword=it.take(128)})
        PasswordField(confirmation,"确认新密码",{confirmation=it.take(128)})
        Text("修改后所有设备需重新登录。",style=MaterialTheme.typography.bodySmall,color=Muted)
        vm.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={vm.password(oldPassword,newPassword){dialog=null}},enabled=oldPassword.isNotEmpty()&&newPassword.length>=8&&newPassword==confirmation&&!vm.busy){Text("确认修改")}},dismissButton={TextButton(onClick={dialog=null;oldPassword="";newPassword="";confirmation=""},enabled=!vm.busy){Text("取消")}})
    if(dialog=="logout") AlertDialog(onDismissRequest={if(!vm.busy)dialog=null},title={Text("暂时告别？")},text={Column{Text("照片和图鉴会保留在服务器，重新登录就能继续收集。");vm.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={vm.logout{dialog=null}},enabled=!vm.busy){Text("退出登录")}},dismissButton={TextButton(onClick={dialog=null},enabled=!vm.busy){Text("继续拾猫")}})
    if(dialog=="remove") AlertDialog(onDismissRequest={if(!vm.busy){dialog=null;removalPassword=""}},title={Text("永久注销账号？")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("账号、照片、手记和收集进度将全部删除，无法恢复。请输入密码确认。")
        PasswordField(removalPassword,"当前密码",{removalPassword=it.take(128)},!vm.busy)
        vm.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={vm.deleteAccount(removalPassword){dialog=null;removalPassword=""}},enabled=removalPassword.isNotEmpty()&&!vm.busy){Text("永久注销",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={dialog=null;removalPassword=""},enabled=!vm.busy){Text("取消")}})
}

@Composable fun Metric(number: String,label: String) { Column(horizontalAlignment=Alignment.CenterHorizontally){Text(number,style=MaterialTheme.typography.headlineMedium,color=Forest);Text(label,style=MaterialTheme.typography.bodySmall,color=Muted)} }
@Composable fun MenuRow(symbol: Symbol,title: String,subtitle: String,onClick: () -> Unit) {
    TextButton(onClick=onClick,modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(vertical=14.dp),shape=RoundedCornerShape(12.dp)) {
        Glyph(symbol);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(title,color=Ink,style=MaterialTheme.typography.titleMedium);Text(subtitle,color=Muted,style=MaterialTheme.typography.bodySmall)};Glyph(Symbol.Arrow,Modifier.size(18.dp),Muted)
    }
}

@Composable fun AboutScreen(back: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        PageHeader("关于拾猫","拾猫 · 1.0",back)
        CatArt(modifier=Modifier.size(160.dp).align(Alignment.CenterHorizontally))
        Text("收集相遇，\n也保留温柔。",style=MaterialTheme.typography.headlineLarge)
        listOf(
            "识别与图鉴" to "拾猫通过照片检测猫咪并提供外观类别参考。模型可能误判，置信度也不代表血统鉴定结果。请核对识别结果后再收进图鉴；未识别到类别的照片仍会保存为相遇记录。",
            "你的数据" to "注册时保存账号、昵称和经过加盐哈希的密码。上传照片后保存照片、识别结果、笔记和收集状态。图片会移除 EXIF 信息，不主动收集地理位置。数据保存在连接设置指定的服务器，服务管理员具备管理权限。",
            "权限与删除" to "相机仅在拍摄时使用。选相册使用系统照片选择器，不要求访问整个相册。记录详情可删除照片及对应记录；若没有该类别的其他确认记录，图鉴会恢复未点亮状态。我的页面可输入密码注销账号，删除账号及全部相遇数据。",
            "温柔的拍摄约定" to "保持距离，不追赶、不惊吓，不使用闪光灯直照眼睛。不要为了拍摄移动幼猫。需要救助时联系当地正规动物救助机构。",
        ).forEach { (title,body) -> Paper {Text(title,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(8.dp));Text(body,color=Muted)} }
        Spacer(Modifier.height(24.dp))
    }
}
