package cz.flos.pos.sync

import cz.flos.core.backoffice.BackOfficeClient
import cz.flos.core.backoffice.BoCatalog
import cz.flos.core.backoffice.BoSale
import cz.flos.core.backoffice.BoSaleItem
import cz.flos.core.backoffice.BoStaff
import cz.flos.pos.data.CategoryEntity
import cz.flos.pos.data.FlosDatabase
import cz.flos.pos.data.ProductEntity
import cz.flos.pos.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncResult(val productsChanged: Int, val salesSent: Int)

/**
 * Connects the till to the back office: pairing with a code, downloading categories and
 * products, uploading receipts (with their EET state) and checking staff PINs.
 * The till keeps working offline; whatever was not sent goes with the next sync.
 */
class BackOfficeSync(private val settings: SettingsStore, private val db: FlosDatabase, private val appVersion: String = "0.4.0") {
    private val lock = Mutex()
    private val catalog = db.catalog()
    private val sales = db.sales()
    private val eet = db.eet()

    private fun client(): BackOfficeClient {
        val s = settings.state.value
        check(s.paired) { "Pokladna není spárovaná s back office" }
        return BackOfficeClient(s.boUrl, s.boToken)
    }

    /** Pairs with the code shown in the back office and takes over the company, branch and EET details. */
    suspend fun pair(url: String, code: String): String = lock.withLock {
        val (token, config) = withContext(Dispatchers.IO) { BackOfficeClient(url).pair(code, appVersion) }
        settings.update {
            it.copy(
                boUrl = BackOfficeClient.normalize(url), boToken = token, boDeviceName = config.deviceName, boCatalogSince = 0,
                boStaff = BackOfficeClient.staffToJson(config.staff),
                businessName = config.org.name.ifBlank { it.businessName },
                ico = config.org.ico.ifBlank { it.ico },
                dic = config.org.dic.ifBlank { it.dic },
                vatPayer = config.org.vatPayer,
                address1 = config.branch.address.ifBlank { config.org.address }.ifBlank { it.address1 },
                eetIdPokl = config.eetIdPokl.ifBlank { it.eetIdPokl },
                eetIdJednotky = config.branch.eetIdJednotky?.toString() ?: it.eetIdJednotky,
            )
        }
        config.deviceName
    }

    fun unpair() = settings.update { it.copy(boToken = "", boDeviceName = "", boCatalogSince = 0, boStaff = "") }

    /** Downloads catalogue changes and uploads receipts. */
    suspend fun sync(): SyncResult = lock.withLock {
        val c = client()
        val config = withContext(Dispatchers.IO) { c.config() }
        settings.update { it.copy(boStaff = BackOfficeClient.staffToJson(config.staff), boDeviceName = config.deviceName) }
        val cat = withContext(Dispatchers.IO) { c.catalog(settings.state.value.boCatalogSince) }
        val changed = applyCatalog(cat)
        settings.update { it.copy(boCatalogSince = cat.serverTime) }
        val sent = upload(c)
        settings.update { it.copy(boLastSync = System.currentTimeMillis()) }
        SyncResult(changed, sent)
    }

    private suspend fun applyCatalog(cat: BoCatalog): Int {
        var changed = 0
        for (rc in cat.categories) {
            val local = catalog.categoryByRemote(rc.id)
            if (rc.deleted) {
                if (local != null) { catalog.clearCategory(local.id); catalog.deleteCategory(local.id); changed++ }
                continue
            }
            catalog.upsertCategory(
                local?.copy(name = rc.name, color = rc.color, sortOrder = rc.sortOrder)
                    ?: CategoryEntity(name = rc.name, color = rc.color, sortOrder = rc.sortOrder, remoteId = rc.id),
            )
            changed++
        }
        for (rp in cat.products) {
            val local = catalog.productByRemote(rp.id)
            // Ingredients (not sold on their own) and removed products are not shown on the till.
            if (!rp.active || !rp.sellable) {
                if (local != null && local.active) { catalog.deactivateProduct(local.id); changed++ }
                continue
            }
            val categoryId = rp.categoryId?.let { catalog.categoryByRemote(it)?.id }
            val p = (local ?: ProductEntity(name = rp.name, price = rp.price, remoteId = rp.id)).copy(
                name = rp.name, price = rp.price, vatPercent = rp.vatPercent, categoryId = categoryId, active = true,
                sortOrder = rp.sortOrder, barcode = rp.barcode.ifBlank { null } ?: local?.barcode,
            )
            catalog.upsertProduct(p)
            changed++
        }
        return changed
    }

    private suspend fun upload(c: BackOfficeClient): Int {
        val recent = System.currentTimeMillis() - 3 * 86_400_000L
        val list = sales.toUpload(recent)
        if (list.isEmpty()) return 0
        val remoteIds = catalog.allProducts().associate { it.id to it.remoteId }
        val payload = list.map { sw ->
            val record = eet.forSale(sw.sale.id).firstOrNull { !it.storno }
            BoSale(
                number = sw.sale.number, createdAt = sw.sale.createdAt, total = sw.sale.total, received = sw.sale.received,
                payment = sw.sale.payment, cashier = sw.sale.cashier, discountPercent = sw.sale.discountPercent,
                cancelled = sw.sale.cancelled, stornoNumber = sw.sale.stornoNumber, eetStatus = record?.status?.name, eetPok = record?.pok,
                items = sw.items.map { BoSaleItem(remoteIds[it.productId], it.name, it.unitPrice, it.vatPercent, it.quantity, it.discountPercent) },
            )
        }
        withContext(Dispatchers.IO) { c.uploadSales(payload) }
        sales.markUploaded(list.map { it.sale.id })
        return list.size
    }

    /** Staff member whose PIN this is, checked against the hashes from the back office (works offline). */
    suspend fun staffForPin(pin: String): BoStaff? = withContext(Dispatchers.Default) {
        BackOfficeClient.staffFromJson(settings.state.value.boStaff).firstOrNull { BackOfficeClient.verifyPin(pin, it.pinHash) }
    }

    /** Clock in or out in the back office attendance. Returns "IN" or "OUT" with the name. */
    suspend fun clock(pin: String): Pair<String, String> {
        val r = withContext(Dispatchers.IO) { client().clock(pin) }
        return r.name to r.action
    }
}
