package com.handcats.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class User(val id: String, val username: String, val nickname: String, @SerialName("created_at") val createdAt: String)
@Serializable data class Credentials(val username: String, val password: String)
@Serializable data class Registration(val username: String, val password: String, val nickname: String)
@Serializable data class AuthResponse(val token: String, val user: User)
@Serializable data class Nickname(val nickname: String)
@Serializable data class AccountRemoval(val password: String)
@Serializable data class PasswordChange(@SerialName("old_password") val old: String, @SerialName("new_password") val new: String)
@Serializable data class CatType(val code: String, val name: String, val subtitle: String, val description: String, val tip: String, val group: String, val collected: Boolean, val encounters: Int)
@Serializable data class DetectionItem(val label: String, val confidence: Float, @SerialName("bounding_box") val boundingBox: List<Float> = emptyList(), @SerialName("cat_code") val catCode: String? = null, val id: Long? = null, val confirmed: Boolean = false)
@Serializable data class Observation(val id: String, @SerialName("created_at") val createdAt: String, @SerialName("image_url") val imageUrl: String, val status: String, val note: String = "", val favorite: Boolean = false, val confirmed: Boolean = false, val detections: List<DetectionItem> = emptyList(), val message: String = "", @SerialName("confirmed_detection_ids") val confirmedDetectionIds: List<Long>? = null)
@Serializable data class ObservationPage(val items: List<Observation>, val total: Int)
@Serializable data class ObservationEdit(val note: String? = null, val favorite: Boolean? = null, val confirmed: Boolean? = null, @SerialName("confirmed_detection_ids") val confirmedDetectionIds: List<Long>? = null)
@Serializable data class HealthResponse(val status: String)
