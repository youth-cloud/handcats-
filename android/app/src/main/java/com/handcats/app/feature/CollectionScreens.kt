package com.handcats.app.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.handcats.app.data.CatsViewModel
import com.handcats.app.data.remote.*
import com.handcats.app.ui.*
import com.handcats.app.ui.theme.*

@Composable fun HomeScreen(vm: CatsViewModel, camera: () -> Unit, catalog: () -> Unit, history: () -> Unit, detail: (Observation) -> Unit, catDetail: (CatType) -> Unit) {
    val collected=vm.cats.count { it.collected }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { PageHeader("拾猫", "你好，${vm.user?.nickname.orEmpty()}。今天也去走走吧。", action={IconButton(onClick=vm::refresh,enabled=!vm.refreshing&&!vm.busy,modifier=Modifier.semantics{contentDescription="刷新首页"}){Glyph(Symbol.Refresh)}}) }
        item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Mint)) {
                Column(Modifier.padding(24.dp)) {
                    Tag("城市寻猫计划 · VOL. 01",background=Color.White.copy(alpha=.7f))
                    Spacer(Modifier.height(18.dp))
                    Text("转过街角，\n遇见一只猫。",style=MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(10.dp))
                    Text("用一张照片，\n收藏生活里的小小惊喜。",color=Forest.copy(alpha=.75f))
                    Spacer(Modifier.height(20.dp))
                    Button(onClick=camera,shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=18.dp,vertical=14.dp)) { Glyph(Symbol.Camera,color=Color.White);Spacer(Modifier.width(8.dp));Text("去拾猫") }
                    Spacer(Modifier.height(18.dp))
                }
                CatArt(modifier=Modifier.align(Alignment.BottomEnd).padding(end=4.dp,bottom=12.dp).width(110.dp).height(120.dp))
            }
        }
        item { Paper(modifier=Modifier.clickable(onClick=catalog)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("我的猫咪图鉴",style=MaterialTheme.typography.titleMedium);Text("每一次确认，点亮一种新相遇",color=Muted,style=MaterialTheme.typography.bodySmall) }
                Text("$collected",style=MaterialTheme.typography.headlineMedium,color=Forest);Text(" / ${vm.cats.size}",color=Muted)
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress={if(vm.cats.isEmpty())0f else collected.toFloat()/vm.cats.size},modifier=Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(9.dp)),trackColor=Mint,color=Forest)
        } }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){ Text("先认识这些邻居",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick=catalog){Text("全部图鉴 →")} } }
        item {
            if(vm.cats.isEmpty()) Text("图鉴还未加载，点击右上角刷新重试。",color=Muted)
            else Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                vm.cats.take(4).forEach { cat -> CatCard(cat,Modifier.width(146.dp)){catDetail(cat)} }
            }
        }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){ Text("最近的相遇",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick=history){Text("查看记录 →")} } }
        if(vm.observations.isEmpty()) item { EmptyState("第一位猫咪朋友，还在路上","拍下一只猫，开启你的第一条相遇记录。","去拍一张",camera) }
        else items(vm.observations.take(3),key={it.id}) { ObservationRow(vm,it){detail(it)} }
        item { Paper(color=Peach) {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Glyph(Symbol.Leaf,color=Orange);Column{Text("温柔，是最好的靠近方式",style=MaterialTheme.typography.titleMedium);Text("不追赶，不惊吓。让猫咪自在地做自己。",style=MaterialTheme.typography.bodySmall,color=Muted)}}} }
    }
}

@Composable fun CatCard(cat: CatType,modifier: Modifier=Modifier,click: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).clickable(onClick=click)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(if(cat.group=="田园猫")Mint else Peach)) {
            CatCover(cat.code,Modifier.fillMaxSize(),ContentScale.Fit)
            if(cat.collected) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)){Tag("已点亮",background=Color.White)}
        }
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(displayName(cat.name),style=MaterialTheme.typography.titleMedium,minLines=2,maxLines=2)
            Text(if(cat.collected) "已相遇 ${cat.encounters} 次" else "等待与你相遇",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
}

@Composable fun CatalogScreen(vm: CatsViewModel,detail: (CatType) -> Unit) {
    var query by rememberSaveable{mutableStateOf("")}
    var filter by rememberSaveable{mutableStateOf("全部")}
    val list=vm.cats.filter { (query.isBlank() || it.name.contains(query,true) || it.subtitle.contains(query,true)) && when(filter){"已点亮"->it.collected;"待遇见"->!it.collected;"田园猫"->it.group=="田园猫";else->true} }
    LazyVerticalGrid(columns=GridCells.Adaptive(148.dp),modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item(span={GridItemSpan(maxLineSpan)}){PageHeader("猫咪图鉴","${vm.cats.count{it.collected}} / ${vm.cats.size} 已点亮 · 把喜欢慢慢收集",action={IconButton(onClick=vm::refresh,enabled=!vm.refreshing,modifier=Modifier.semantics{contentDescription="刷新图鉴"}){Glyph(Symbol.Refresh)}})}
        item(span={GridItemSpan(maxLineSpan)}){OutlinedTextField(query,{query=it},placeholder={Text("寻找一种猫咪…")},singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth(),leadingIcon={Glyph(Symbol.Book)})}
        item(span={GridItemSpan(maxLineSpan)}){Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("全部","已点亮","待遇见","田园猫").forEach{label->FilterChip(filter==label,{filter=label},label={Text(label)})}}}
        if(list.isEmpty()) item(span={GridItemSpan(maxLineSpan)}){EmptyState(if(vm.cats.isEmpty())"图鉴暂未加载" else "这里还没有猫咪","试试其他关键词或筛选条件。",if(vm.cats.isEmpty())"重新加载" else "查看全部"){if(vm.cats.isEmpty())vm.refresh() else {query="";filter="全部"}}}
        items(list,key={it.code}){cat->CatCard(cat){detail(cat)}}
        item(span={GridItemSpan(maxLineSpan)}){Text("图鉴是外观类别参考，不作为血统鉴定依据。",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=8.dp))}
    }
}

@Composable fun CatDetailScreen(vm: CatsViewModel, cat: CatType,back: () -> Unit,camera: () -> Unit,detail: (Observation) -> Unit) {
    LaunchedEffect(cat.code) { vm.loadCategory(cat.code) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { PageHeader("猫咪档案",onBack=back) }
        item { Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp)).background(Mint),contentAlignment=Alignment.Center){CatCover(cat.code,Modifier.fillMaxSize(),ContentScale.Fit);Box(Modifier.align(Alignment.TopStart).padding(20.dp)){Tag(cat.group,background=Color.White)}} }
        item { Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(displayName(cat.name),style=MaterialTheme.typography.headlineMedium);Text(cat.subtitle,color=Muted)};Glyph(if(cat.collected)Symbol.Check else Symbol.Lock,Modifier.size(30.dp))} }
        item { Tag(if(cat.collected)"已点亮 · 确认收集 ${cat.encounters} 次" else "尚未确认收集") }
        item { Paper{Text("认识一下",style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(12.dp));Text(cat.description,color=Muted)} }
        item { Paper(color=Peach){Text("观察小贴士",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(8.dp));Text(cat.tip,color=Muted)} }
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("与它们的相遇",style=MaterialTheme.typography.titleLarge)
                    Text("本类别已保存 ${vm.categoryTotal} 条记录，包含待确认的识别结果",color=Muted,style=MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick={vm.loadCategory(cat.code)},enabled=!vm.categoryLoading&&!vm.busy,modifier=Modifier.semantics{contentDescription="刷新同品种记录"}){Glyph(Symbol.Refresh)}
            }
        }
        if(vm.categoryLoading && vm.categoryObservations.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(28.dp))} }
        if(!vm.categoryLoading && !vm.categoryFailed && vm.categoryObservations.isEmpty()) item { EmptyState("还没有它的相遇记录","拍照识别并保存后，就能在这里回看照片和手记。","去拍一张",camera) }
        items(vm.categoryObservations,key={it.id}) { item -> ObservationRow(vm,item){detail(item)} }
        if(vm.categoryFailed) item { OutlinedButton(onClick={vm.clearMessage();vm.loadCategory(cat.code,more=vm.categoryObservations.isNotEmpty())},modifier=Modifier.fillMaxWidth()){Text("记录加载失败，点击重试")} }
        else if(vm.categoryObservations.size<vm.categoryTotal) item { OutlinedButton(onClick={vm.loadCategory(cat.code,more=true)},enabled=!vm.categoryLoading,modifier=Modifier.fillMaxWidth()){Text(if(vm.categoryLoading)"加载中…" else "加载更多同品种记录")} }
        item { PrimaryButton("去寻找这位朋友",camera,symbol=Symbol.Camera) }
    }
}

@Composable fun HistoryScreen(vm: CatsViewModel,camera: () -> Unit,detail: (Observation) -> Unit) {
    var filter by rememberSaveable{mutableStateOf("全部相遇")}
    val list=vm.observations.filter{when(filter){"已收藏"->it.favorite;"已收集"->it.confirmed;else->true}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {PageHeader("相遇手记","共 ${vm.total} 次相遇，每一只都独一无二",action={IconButton(onClick=vm::refresh,enabled=!vm.refreshing,modifier=Modifier.semantics{contentDescription="刷新记录"}){Glyph(Symbol.Refresh)}})}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("全部相遇","已收藏","已收集").forEach{label->FilterChip(filter==label,{filter=label},label={Text(label)})}}}
        if(list.isEmpty()) item{EmptyState(if(vm.total==0)"把第一次相遇写在这里" else "当前已加载记录中暂无匹配","拍照识别后，照片和结果会保存在这里。",if(vm.total==0)"去拍一张" else null,camera)}
        items(list,key={it.id}){item->ObservationRow(vm,item){detail(item)}}
        if(vm.observations.size<vm.total) item{OutlinedButton(onClick=vm::more,enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text(if(vm.busy)"加载中…" else "加载更多记录")}}
    }
}

@Composable fun ObservationRow(vm: CatsViewModel,item: Observation,click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).semantics{contentDescription="查看相遇记录 ${item.id}"}.clickable(onClick=click).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        RemotePhoto(vm,item.imageUrl,Modifier.size(82.dp).clip(RoundedCornerShape(14.dp)))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(observationTitle(item),style=MaterialTheme.typography.titleMedium,maxLines=2)
            if(item.detections.size>1)Text(item.detections.map{if(it.catCode==null)"类别待确定" else displayName(it.label)}.distinct().joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=2)
            Text(displayDate(item.createdAt),style=MaterialTheme.typography.bodySmall,color=Muted)
            Text(if(item.confirmed)"已收进图鉴" else if(item.note.isNotBlank())item.note else "点击查看这次相遇",style=MaterialTheme.typography.bodySmall,color=if(item.confirmed)Forest else Muted,maxLines=1)
        }
        Glyph(if(item.favorite)Symbol.Heart else Symbol.Arrow,Modifier.size(18.dp),if(item.favorite)Orange else Muted)
    }
}

@Composable fun ObservationScreen(vm: CatsViewModel,back: () -> Unit,camera: () -> Unit) {
    val item=vm.selected
    if(item==null){EmptyState("这条记录已不在这里","返回查看其他相遇吧。","返回",back);return}
    var note by rememberSaveable(item.id,item.note){mutableStateOf(item.note)}
    var confirmDelete by remember{mutableStateOf(false)}
    var focusedIndex by rememberSaveable(item.id){mutableStateOf<Int?>(null)}
    val savedIds = item.detections.filter { it.confirmed || (item.confirmed && item.confirmedDetectionIds == null && it.catCode != null) }.mapNotNull { it.id }
    var chosenIds by rememberSaveable(item.id, savedIds){mutableStateOf(savedIds)}
    val eligibleIds = item.detections.filter{it.catCode!=null}.mapNotNull{it.id}
    val canCollect=eligibleIds.isNotEmpty()
    val selectionChanged=chosenIds.toSet()!=savedIds.toSet()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        PageHeader("这一次相遇",displayDate(item.createdAt),back,action={IconButton(onClick={vm.edit(item,ObservationEdit(favorite=!item.favorite))},enabled=!vm.busy,modifier=Modifier.semantics{contentDescription=if(item.favorite)"取消收藏" else "收藏照片"}){Glyph(Symbol.Heart,color=if(item.favorite)Orange else Muted)}})
        RemotePhoto(vm,item.imageUrl,Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(26.dp)),ContentScale.Fit,item.detections,focusedIndex)
        Tag(if(item.confirmed)"已收集 ${savedIds.size} 项猫咪结果" else if(canCollect)"识别完成 · 请逐一核对" else "相遇已记录")
        Text(if(item.detections.size>1)"这次，遇见了它们" else observationTitle(item),style=MaterialTheme.typography.headlineMedium)
        if(item.detections.size>1)Text("照片中有 ${item.detections.size} 个猫咪识别结果，编号对应下方卡片。",color=Muted)
        Text(item.message,color=Muted)
        if(item.detections.isNotEmpty()) Paper {
            Text("逐只核对",style=MaterialTheme.typography.titleMedium)
            item.detections.forEachIndexed{index,d->
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if(focusedIndex==index)Mint else Cream).clickable{focusedIndex=index}.padding(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Tag("${index+1} 号",color=Color.White,background=detectionColor(index))
                        Spacer(Modifier.width(8.dp))
                        Text("${(d.confidence*100).toInt()}%",color=detectionColor(index),fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                        if(d.catCode!=null && d.id!=null)Checkbox(checked=d.id in chosenIds,onCheckedChange={checked->chosenIds=if(checked)(chosenIds+d.id).distinct() else chosenIds-d.id;focusedIndex=index},enabled=!vm.busy,modifier=Modifier.semantics{contentDescription="收集 ${index+1} 号猫咪"})
                    }
                    Text(if(d.catCode==null)"猫 · 类别待确定" else displayName(d.label),style=MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp));LinearProgressIndicator(progress={d.confidence},modifier=Modifier.fillMaxWidth().height(4.dp),trackColor=Mint,color=detectionColor(index))
                    Spacer(Modifier.height(6.dp));Text(if(d.id in chosenIds)"已勾选 · 保存后收进图鉴" else if(d.catCode==null)"暂未匹配图鉴类别" else "核对正确后勾选收集",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
            }
            Spacer(Modifier.height(14.dp));Text("模型可能对同一只猫重复出框。请按编号核对位置与类别，取消错误或重复结果；未勾选的结果仍保留在这条手记里。",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        if(canCollect) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={chosenIds=eligibleIds},enabled=!vm.busy){Text("全选正确结果")}
                TextButton(onClick={chosenIds=emptyList()},enabled=!vm.busy){Text("取消全选")}
            }
            PrimaryButton(if(chosenIds.isEmpty())"保存为未收集" else "保存所选 ${chosenIds.size} 项，收进图鉴",{vm.edit(item,ObservationEdit(confirmedDetectionIds=chosenIds))},enabled=selectionChanged,loading=vm.busy,symbol=Symbol.Book)
            if(item.confirmed)OutlinedButton(onClick={vm.edit(item,ObservationEdit(confirmedDetectionIds=emptyList()))},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("撤销这张照片的全部收集")}
        }
        OutlinedTextField(note,{note=it.take(500)},label={Text("给这次相遇留句话")},placeholder={Text("比如：在公园长椅旁，遇见了晒太阳的它。")},supportingText={Text("${note.length} / 500")},modifier=Modifier.fillMaxWidth(),minLines=3,shape=RoundedCornerShape(18.dp))
        if(note!=item.note) PrimaryButton("保存手记",{vm.edit(item,ObservationEdit(note=note))},loading=vm.busy)
        OutlinedButton(onClick=camera,enabled=!vm.busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){Text("继续寻找下一只猫")}
        TextButton(onClick={confirmDelete=true},enabled=!vm.busy,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("删除这条相遇",color=MaterialTheme.colorScheme.error)}
        Spacer(Modifier.height(8.dp))
    }
    if(confirmDelete)AlertDialog(onDismissRequest={if(!vm.busy)confirmDelete=false},title={Text("删除这次相遇？")},text={Column{Text("照片与识别记录将一起删除，无法恢复。若这是该类别唯一的收集记录，对应图鉴将恢复为待遇见。");vm.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={vm.delete(item){confirmDelete=false;back()}},enabled=!vm.busy){Text("确认删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirmDelete=false},enabled=!vm.busy){Text("保留")}})
}
