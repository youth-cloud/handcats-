package com.handcats.app.data

import com.handcats.app.data.remote.ObservationEdit

internal fun observationEditNotice(change: ObservationEdit): String = when {
    change.favorite == true -> "已收藏这次相遇"
    change.favorite == false -> "已取消收藏"
    change.confirmedDetectionIds?.isNotEmpty() == true -> "已保存所选猫咪的图鉴收集"
    change.confirmedDetectionIds != null || change.confirmed == false -> "已撤销本次收集"
    change.confirmed == true -> "已收进图鉴，留住这次相遇"
    change.note != null -> "手记已保存"
    else -> "已保存"
}
