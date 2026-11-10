package cz.flos.core

/** Staff roles, the same as in the back office. The owner may always do everything. */
enum class StaffRole(val label: String) { OWNER("Majitel"), MANAGER("Vedoucí"), CASHIER("Pokladní") ;
    companion object {
        fun of(text: String?): StaffRole? = when (Pricing.key(text.orEmpty())) {
            "owner", "majitel" -> OWNER
            "manager", "vedouci" -> MANAGER
            "cashier", "pokladni", "obsluha", "cisnik", "waiter" -> CASHIER
            else -> null
        }
    }
}

/** Things the owner can forbid to a role. Nothing is forbidden until the owner says so. */
enum class Permission(val label: String) {
    STORNO("Storno účtenky"),
    REFUND("Vratka položek"),
    CANCEL_ORDER("Zrušení objednávky"),
    DISCOUNT("Ruční sleva"),
    CASH_MOVES("Vklad a výběr hotovosti"),
    CLOSE_SHIFT("Uzávěrka a uzavření směny"),
    PRODUCTS("Úprava produktů, import a hromadné úpravy"),
    DOCUMENTS("Nabídky a zálohové faktury"),
    CUSTOMERS("Úprava zákazníků"),
    STATISTICS("Statistiky a tržby"),
    SETTINGS("Nastavení"),
    MANUAL_PRICE("Ruční položka a cena zadaná při prodeji"),
}

/** What each role may not do, stored as "CASHIER=STORNO,REFUND;MANAGER=SETTINGS". */
data class Permissions(val denied: Map<StaffRole, Set<Permission>> = emptyMap()) {
    fun allowed(role: StaffRole, p: Permission): Boolean = role == StaffRole.OWNER || p !in denied[role].orEmpty()

    fun with(role: StaffRole, p: Permission, allow: Boolean): Permissions {
        if (role == StaffRole.OWNER) return this
        val set = denied[role].orEmpty().let { if (allow) it - p else it + p }
        return copy(denied = denied + (role to set))
    }

    fun format(): String = denied.filter { it.value.isNotEmpty() && it.key != StaffRole.OWNER }.entries.sortedBy { it.key }
        .joinToString(";") { (r, ps) -> r.name + "=" + ps.sorted().joinToString(",") { it.name } }

    companion object {
        fun parse(text: String?): Permissions = Permissions(text.orEmpty().split(';').mapNotNull { part ->
            val role = runCatching { StaffRole.valueOf(part.substringBefore('=').trim()) }.getOrNull() ?: return@mapNotNull null
            role to part.substringAfter('=', "").split(',').mapNotNull { runCatching { Permission.valueOf(it.trim()) }.getOrNull() }.toSet()
        }.toMap())

        /**
         * Role of the person at the till: from the back office staff list, else from the owner's
         * own list ("Eva: pokladní"), else the owner (so a till without staff set up works as before).
         */
        fun roleOf(name: String, backOffice: List<Pair<String, String>>, local: String?): StaffRole {
            backOffice.firstOrNull { it.first.equals(name, ignoreCase = true) }?.let { StaffRole.of(it.second) }?.let { return it }
            local.orEmpty().lines().forEach { l ->
                val n = l.substringBefore(':').trim()
                if (n.isNotEmpty() && n.equals(name.trim(), ignoreCase = true)) StaffRole.of(l.substringAfter(':', ""))?.let { return it }
            }
            return StaffRole.OWNER
        }
    }
}
