package com.handcats.app.data

import com.handcats.app.data.remote.ObservationEdit
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationEditsTest {
    @Test fun favoriteAndUnfavoriteHaveDistinctMessages() {
        assertEquals("已收藏这次相遇", observationEditNotice(ObservationEdit(favorite = true)))
        assertEquals("已取消收藏", observationEditNotice(ObservationEdit(favorite = false)))
    }
    @Test fun noteAndCollectionMessagesRemainIndependent() {
        assertEquals("手记已保存", observationEditNotice(ObservationEdit(note = "")))
        assertEquals("已保存所选猫咪的图鉴收集", observationEditNotice(ObservationEdit(confirmedDetectionIds = listOf(1L))))
        assertEquals("已撤销本次收集", observationEditNotice(ObservationEdit(confirmedDetectionIds = emptyList())))
    }
}
