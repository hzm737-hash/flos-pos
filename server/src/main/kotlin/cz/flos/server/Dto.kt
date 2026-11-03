package cz.flos.server

import kotlinx.serialization.Serializable

// ---- Auth / org ----
@Serializable data class RegisterRequest(val orgName: String, val name: String, val email: String, val password: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class TokenResponse(val token: String, val user: UserDto)
@Serializable data class OrgDto(val id: Long = 0, val name: String, val ico: String = "", val dic: String = "", val vatPayer: Boolean = false, val address: String = "")

// ---- Branches, devices, users, API keys ----
@Serializable data class BranchDto(val id: Long = 0, val name: String, val address: String = "", val eetIdJednotky: Int? = null, val active: Boolean = true)
@Serializable data class DeviceDto(
    val id: Long = 0, val branchId: Long, val name: String, val eetIdPokl: String = "",
    val pairingCode: String? = null, val pairedAt: Long? = null, val lastSeenAt: Long? = null, val appVersion: String? = null, val active: Boolean = true,
)
@Serializable data class UserDto(
    val id: Long = 0, val name: String, val email: String? = null, val role: String = "CASHIER", val active: Boolean = true,
    val hasPin: Boolean = false,
)
@Serializable data class UserWrite(val name: String, val email: String? = null, val role: String = "CASHIER", val password: String? = null, val pin: String? = null, val active: Boolean = true)
@Serializable data class ApiKeyDto(val id: Long = 0, val name: String, val prefix: String = "", val scope: String = "READ", val createdAt: Long = 0, val lastUsedAt: Long? = null, val revoked: Boolean = false, val key: String? = null)

// ---- Catalogue ----
@Serializable data class CategoryDto(val id: Long = 0, val name: String, val color: Long = 0xFF2E7D6B, val sortOrder: Int = 0, val deleted: Boolean = false, val updatedAt: Long = 0)
@Serializable data class RecipeItemDto(val ingredientId: Long, val qty: Double)
@Serializable data class ProductDto(
    val id: Long = 0, val categoryId: Long? = null, val name: String, val price: Long, val vatPercent: Int = 21,
    val sku: String = "", val barcode: String = "", val unit: String = "ks", val costPrice: Long = 0,
    val trackStock: Boolean = false, val sellable: Boolean = true, val minStock: Double = 0.0, val sortOrder: Int = 0,
    val active: Boolean = true, val updatedAt: Long = 0, val recipe: List<RecipeItemDto> = emptyList(),
)

// ---- Stock ----
@Serializable data class StockLevelDto(val branchId: Long, val productId: Long, val productName: String, val unit: String, val qty: Double, val minStock: Double, val low: Boolean, val value: Long)
@Serializable data class StockLineDto(val productId: Long, val qty: Double, val unitCost: Long? = null)
/** kind: RECEIPT (naskladnění), WASTE (odpis), ADJUST (+/- oprava), INVENTORY (inventura: nastaví stav), TRANSFER (převod). */
@Serializable data class StockMovementRequest(val branchId: Long, val kind: String, val lines: List<StockLineDto>, val toBranchId: Long? = null, val note: String = "", val ref: String = "")
@Serializable data class StockMovementDto(val id: Long, val branchId: Long, val productId: Long, val productName: String, val qty: Double, val kind: String, val note: String, val ref: String, val createdAt: Long)

// ---- Sales ----
@Serializable data class SaleItemDto(val productId: Long? = null, val name: String, val unitPrice: Long, val vatPercent: Int, val quantity: Int, val discountPercent: Int = 0)
@Serializable data class SaleDto(
    val id: Long = 0, val branchId: Long = 0, val deviceId: Long = 0, val number: String, val createdAt: Long, val total: Long,
    val received: Long = total, val payment: String, val cashier: String = "", val discountPercent: Int = 0,
    val cancelled: Boolean = false, val stornoNumber: String? = null, val eetStatus: String? = null, val eetPok: String? = null,
    val items: List<SaleItemDto> = emptyList(),
)
@Serializable data class SalesUpload(val sales: List<SaleDto>)
@Serializable data class UploadResult(val accepted: Int, val updated: Int, val serverTime: Long)

// ---- Reports ----
@Serializable data class Amount(val key: String, val amount: Long, val count: Int = 0)
@Serializable data class VatRow(val rate: Int, val base: Long, val vat: Long, val gross: Long)
@Serializable data class ProductRow(val productId: Long?, val name: String, val quantity: Int, val revenue: Long, val cost: Long)
@Serializable data class SummaryReport(
    val from: Long, val to: Long, val revenue: Long, val receipts: Int, val cancelled: Int, val averageReceipt: Long,
    val grossMargin: Long?, val byPayment: List<Amount>, val byVat: List<VatRow>, val byDay: List<Amount>, val byHour: List<Amount>,
    val byBranch: List<Amount>, val byCashier: List<Amount>, val topProducts: List<ProductRow>,
)

// ---- Attendance ----
@Serializable data class AttendanceDto(val id: Long, val userId: Long, val userName: String, val branchId: Long, val clockIn: Long, val clockOut: Long?, val minutes: Long?)
@Serializable data class ClockRequest(val pin: String, val time: Long? = null)
@Serializable data class ClockResponse(val userId: Long, val name: String, val action: String, val time: Long)

// ---- Devices ----
@Serializable data class PairRequest(val code: String, val appVersion: String = "")
@Serializable data class PairResponse(val token: String, val config: DeviceConfig)
@Serializable data class StaffDto(val id: Long, val name: String, val role: String, val pinHash: String?)
@Serializable data class DeviceConfig(val deviceId: Long, val deviceName: String, val eetIdPokl: String, val org: OrgDto, val branch: BranchDto, val staff: List<StaffDto>)
@Serializable data class CatalogResponse(val serverTime: Long, val categories: List<CategoryDto>, val products: List<ProductDto>)

// ---- EET overview ----
@Serializable data class EetOverview(val confirmed: Int, val pending: Int, val rejected: Int, val other: Int, val problems: List<SaleDto>)

@Serializable data class Ok(val ok: Boolean = true)
@Serializable data class ErrorBody(val error: String)

@Serializable data class MeResponse(val user: UserDto, val org: OrgDto)
@Serializable data class DeviceStock(val productId: Long, val qty: Double)

/** A company the signed-in user can work in. */
@Serializable data class CompanyDto(val id: Long, val name: String, val ico: String, val dic: String, val role: String, val current: Boolean)
@Serializable data class NewCompanyRequest(val name: String, val ico: String = "", val dic: String = "", val vatPayer: Boolean = false, val address: String = "")
