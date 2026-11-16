package cz.flos.server

import cz.flos.core.backoffice.BoTestDataReset
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.sql.Connection

/**
 * Clearing test data before a till goes live. Only the till itself can ask (with its own token), and only for
 * itself: its receipts (with their items), clock-ins, live picture and open tables. Orders from the website
 * and from the tables belong to the company, not to one till, so the company's are cleared too.
 * Products, categories, menu, settings, users, branches and the pairing stay as they are.
 */
internal fun Route.testDataResetRoutes(db: Db) {
    post("/device/test-data/reset") {
        val d = call.device(db)
        call.respond(db.tx { resetTestData(d) })
    }
}

internal fun Connection.resetTestData(d: Caller.Device): BoTestDataReset {
    // Stock taken by receipts that were not cancelled goes back, as a cancelled receipt would give it back.
    val sales = query("SELECT id, number, cancelled FROM sales WHERE device_id = ?", d.deviceId) {
        Triple(it.getLong(1), it.getString(2), it.getBoolean(3))
    }
    for ((id, number, cancelled) in sales) {
        if (!cancelled) Stock.applySale(this, d.orgId, d.branchId, number, Sales.items(this, id), +1)
    }
    update("DELETE FROM sale_items WHERE sale_id IN (SELECT id FROM sales WHERE device_id = ?)", d.deviceId)
    val salesDeleted = update("DELETE FROM sales WHERE device_id = ?", d.deviceId)
    val attendance = update("DELETE FROM attendance WHERE device_id = ?", d.deviceId)
    update("DELETE FROM till_snapshots WHERE device_id = ?", d.deviceId)
    update("DELETE FROM open_tables WHERE device_id = ?", d.deviceId)
    val web = update("DELETE FROM web_orders WHERE org_id = ?", d.orgId)
    val table = update("DELETE FROM table_orders WHERE org_id = ?", d.orgId)
    return BoTestDataReset(salesDeleted, web, table, attendance)
}
