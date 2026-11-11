package cz.flos.pos.sync

import cz.flos.core.backoffice.BackOfficeClient
import cz.flos.core.backoffice.BoCatalog
import cz.flos.core.backoffice.BoDeviceCatalog
import cz.flos.core.backoffice.BoDeviceCategory
import cz.flos.core.backoffice.BoDeviceProduct
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
class BackOfficeSync(
    private val settings: SettingsStore,
    private val db: FlosDatabase,
    private val appVersion: String = "0.4.0",
    /** Where product photos from the back office are kept. */
    val imageDir: java.io.File? = null,
    /** Which secrets are set on this till (names only), shown in the back office without their values. */
    private val secretsSet: () -> Map<String, Boolean> = { emptyMap() },
) {
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
        settings.resetSettingsSync()
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

    fun unpair() {
        settings.update { it.copy(boToken = "", boDeviceName = "", boCatalogSince = 0, boStaff = "") }
        settings.resetSettingsSync()
    }

    /**
     * Sends the settings changed on this till and takes those changed in the back office; per setting the later
     * change wins. Passwords and tokens never leave the till. Returns how many settings changed here.
     */
    suspend fun syncSettings(): Int = lock.withLock {
        val c = client()
        val send = settings.settingsToSend()
        val since = settings.settingsSince()
        val r = withContext(Dispatchers.IO) {
            c.syncSettings(cz.flos.core.backoffice.BoSettingsUpload(send, since, runCatching(secretsSet).getOrDefault(emptyMap())))
        }
        settings.applyRemote(send.mapValues { it.value.changedAt }, r.values.mapValues { it.value.value }, r.serverTime)
    }

    /** Downloads catalogue changes and uploads receipts. */
    suspend fun sync(): SyncResult = lock.withLock {
        val c = client()
        val config = withContext(Dispatchers.IO) { c.config() }
        settings.update { it.copy(boStaff = BackOfficeClient.staffToJson(config.staff), boDeviceName = config.deviceName) }
        // First what changed here, then what changed in the back office: the later change wins on both sides.
        val sentCatalog = uploadCatalog(c)
        val cat = withContext(Dispatchers.IO) { c.catalog(settings.state.value.boCatalogSince) }
        val changed = applyCatalog(cat) + sentCatalog
        fetchImages(c)
        settings.update { it.copy(boCatalogSince = cat.serverTime) }
        val sent = upload(c)
        settings.update { it.copy(boLastSync = System.currentTimeMillis()) }
        SyncResult(changed, sent)
    }

    /** Sends categories and products created or changed on this till; returns how many. */
    private suspend fun uploadCatalog(c: BackOfficeClient): Int {
        val cats = catalog.dirtyCategories()
        val deleted = settings.state.value.boDeletedCategories.split(',').mapNotNull { it.trim().toLongOrNull() }
        val now = System.currentTimeMillis()
        // Categories first, so that products of a new category already know its back office id.
        if (cats.isNotEmpty() || deleted.isNotEmpty()) {
            val payload = BoDeviceCatalog(
                categories = cats.map { BoDeviceCategory(it.id, it.remoteId, it.name, it.color, it.sortOrder, it.icon.orEmpty(), false, it.updatedAt) } +
                    deleted.map { BoDeviceCategory(-it, it, "", 0, deleted = true, updatedAt = now) },
            )
            val result = withContext(Dispatchers.IO) { c.uploadCatalog(payload) }
            cats.forEach { x -> result.categories[x.id]?.let { catalog.categorySent(x.id, it, x.updatedAt) } }
            if (deleted.isNotEmpty()) settings.update { s ->
                s.copy(boDeletedCategories = s.boDeletedCategories.split(',').filter { it.trim().toLongOrNull() !in deleted }.joinToString(","))
            }
        }
        val prods = catalog.dirtyProducts()
        if (prods.isNotEmpty()) {
            val remoteCategory = catalog.allCategories().associate { it.id to it.remoteId }
            val payload = BoDeviceCatalog(
                products = prods.map {
                    BoDeviceProduct(it.id, it.remoteId, it.categoryId?.let { id -> remoteCategory[id] }, it.name, it.price, it.vatPercent, it.barcode.orEmpty(),
                        it.sortOrder, it.active, it.options.orEmpty(), it.prices.orEmpty(), it.openPrice, it.icon.orEmpty(), it.updatedAt)
                },
            )
            val result = withContext(Dispatchers.IO) { c.uploadCatalog(payload) }
            prods.forEach { x -> result.products[x.id]?.let { catalog.productSent(x.id, it, x.updatedAt) } }
        }
        return cats.size + prods.size + deleted.size
    }

    private var lastSnapshot: String? = null
    private var lastSnapshotAt = 0L

    /** Sends what is going on at this till; skipped when nothing changed in the last ten minutes. Returns true when sent. */
    suspend fun uploadSnapshot(s: cz.flos.core.backoffice.BoTillSnapshot): Boolean = lock.withLock {
        val c = client()
        // The same picture apart from the time it was taken is not sent again for a while.
        val key = s.copy(takenAt = 0).toString()
        if (key == lastSnapshot && System.currentTimeMillis() - lastSnapshotAt < 10 * 60_000) return@withLock false
        withContext(Dispatchers.IO) { c.uploadSnapshot(s) }
        lastSnapshot = key
        lastSnapshotAt = System.currentTimeMillis()
        true
    }

    /** Downloads photos the till does not have yet. */
    private suspend fun fetchImages(c: BackOfficeClient) {
        val dir = imageDir ?: return
        val wanted = catalog.allProducts().mapNotNull { it.image }.toSet()
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            wanted.filterNot { java.io.File(dir, it).exists() }.forEach { ref ->
                c.image(ref)?.let { bytes -> java.io.File(dir, "$ref.part").apply { writeBytes(bytes); renameTo(java.io.File(dir, ref)) } }
            }
            // Photos no product uses any more.
            dir.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }
        }
    }

    /** The photo of a product as bytes, or null when it has none or it is not downloaded yet. */
    fun imageBytes(ref: String?): ByteArray? =
        ref?.let { r -> imageDir?.let { java.io.File(it, r) }?.takeIf { it.isFile }?.readBytes() }

    private suspend fun applyCatalog(cat: BoCatalog): Int {
        var changed = 0
        for (rc in cat.categories) {
            val local = catalog.categoryByRemote(rc.id)
            // Changed here after the back office changed it: keep it, it goes up with the next sync.
            if (local != null && local.dirty && local.updatedAt > rc.updatedAt) continue
            if (rc.deleted) {
                if (local != null) { catalog.clearCategory(local.id); catalog.deleteCategory(local.id); changed++ }
                continue
            }
            catalog.upsertCategory(
                local?.copy(name = rc.name, color = rc.color, sortOrder = rc.sortOrder, icon = rc.icon.ifBlank { null }, dirty = false)
                    ?: CategoryEntity(name = rc.name, color = rc.color, sortOrder = rc.sortOrder, icon = rc.icon.ifBlank { null }, remoteId = rc.id),
            )
            changed++
        }
        for (rp in cat.products) {
            val local = catalog.productByRemote(rp.id)
            if (local != null && local.dirty && local.updatedAt > rp.updatedAt) continue
            // Ingredients (not sold on their own) and removed products are not shown on the till.
            if (!rp.active || !rp.sellable) {
                if (local != null && local.active) { catalog.deactivateProduct(local.id); changed++ }
                continue
            }
            val categoryId = rp.categoryId?.let { catalog.categoryByRemote(it)?.id }
            val p = (local ?: ProductEntity(name = rp.name, price = rp.price, remoteId = rp.id)).copy(
                name = rp.name, price = rp.price, vatPercent = rp.vatPercent, categoryId = categoryId, active = true,
                sortOrder = rp.sortOrder, barcode = rp.barcode.ifBlank { null } ?: local?.barcode,
                options = rp.options.ifBlank { null }, prices = rp.prices.ifBlank { null }, openPrice = rp.openPrice,
                icon = rp.icon.ifBlank { null } ?: local?.icon, image = rp.image.ifBlank { null }, dirty = false,
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
