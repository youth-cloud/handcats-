package com.handcats.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handcats.app.data.CatsViewModel
import com.handcats.app.feature.*
import com.handcats.app.feature.camera.CameraRoute
import com.handcats.app.ui.*
import com.handcats.app.ui.theme.*

@Composable fun HandCatsApp(vm: CatsViewModel = viewModel()) {
    var tab by rememberSaveable {mutableIntStateOf(0)}
    var route by rememberSaveable {mutableStateOf("main")}
    var previous by rememberSaveable {mutableStateOf("main")}
    var catCode by rememberSaveable {mutableStateOf<String?>(null)}
    var observationBack by rememberSaveable {mutableStateOf("main")}
    val camera={vm.clearMessage();route="camera"}
    val back={if(!vm.busy){vm.clearMessage();route=when(route){"about"->previous;"observation"->observationBack;else->"main"}}}
    LaunchedEffect(vm.user?.id){
        if(vm.user==null && route !in listOf("settings","about"))route="main"
        if(vm.user!=null)tab=0
    }
    BackHandler(enabled=route!="main" || tab!=0){if(!vm.busy){if(route!="main")back()else tab=0}}
    Scaffold(containerColor=Cream,bottomBar={
        if(vm.user!=null&&route=="main") {
            NavigationBar(containerColor=Color.White,tonalElevation=0.dp) {
                listOf("发现" to Symbol.Home,"图鉴" to Symbol.Book,"拾猫" to Symbol.Camera,"手记" to Symbol.History,"我的" to Symbol.Person).forEachIndexed{index,(label,icon)->
                    NavigationBarItem(selected=tab==index&&index!=2,onClick={if(!vm.busy){vm.clearMessage();if(index==2)camera()else tab=index}},icon={
                        if(index==2)Box(Modifier.background(Forest,RoundedCornerShape(16.dp)).padding(horizontal=18.dp,vertical=10.dp)){Glyph(icon,color=Color.White)}
                        else Glyph(icon,color=if(tab==index)Forest else Muted)
                    },label={Text(label)},colors=NavigationBarItemDefaults.colors(indicatorColor=Mint,selectedTextColor=Forest,unselectedTextColor=Muted))
                }
            }
        }
    }){padding->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter){
            Column(Modifier.widthIn(max=720.dp).fillMaxSize()) {
                if(vm.error!=null||vm.notice!=null) {
                    Row(Modifier.fillMaxWidth().background(if(vm.error!=null)MaterialTheme.colorScheme.errorContainer else Mint).padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(vm.error?:vm.notice.orEmpty(),modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=if(vm.error!=null)MaterialTheme.colorScheme.error else Forest)
                        TextButton(onClick=vm::clearMessage){Text("知道了")}
                    }
                }
                if(vm.refreshing || (vm.busy&&route!="camera"))LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                Box(Modifier.weight(1f)) {
                    when {
                        vm.starting -> Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){CatArt(modifier=Modifier.size(180.dp));Spacer(Modifier.height(20.dp));Text("拾猫 · 正在准备新的相遇",color=Forest);Spacer(Modifier.height(20.dp));CircularProgressIndicator(Modifier.size(24.dp))}
                        route=="settings" -> SettingsScreen(vm,back)
                        route=="about" -> AboutScreen(back)
                        vm.user==null -> AuthScreen(vm,settings={route="settings"},privacy={previous="main";route="about"})
                        route=="camera" -> CameraRoute(vm,back){observationBack="main";route="observation"}
                        route=="observation" -> ObservationScreen(vm,back,camera)
                        route=="cat" -> vm.cats.firstOrNull{it.code==catCode}?.let{CatDetailScreen(vm,it,back,camera){item->vm.select(item);observationBack="cat";route="observation"}}?:EmptyState("图鉴暂未加载","返回刷新后再试。","返回",back)
                        else -> when(tab){
                            0 -> HomeScreen(vm,camera,{tab=1},{tab=3},{vm.select(it);observationBack="main";route="observation"},{catCode=it.code;route="cat"})
                            1 -> CatalogScreen(vm){catCode=it.code;route="cat"}
                            3 -> HistoryScreen(vm,camera){vm.select(it);observationBack="main";route="observation"}
                            4 -> ProfileScreen(vm,settings={route="settings"},privacy={previous="main";route="about"},history={tab=3})
                        }
                    }
                }
            }
        }
    }
}
