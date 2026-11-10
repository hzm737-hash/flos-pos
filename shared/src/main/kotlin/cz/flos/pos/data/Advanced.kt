package cz.flos.pos.data

/**
 * Optional features of Flos v2. Each is switched on or off in Pokročilé nastavení and is off
 * until the owner switches it on, so the till keeps working exactly as before.
 */
enum class AdvFeature(val label: String, val hint: String) {
    QTY_BEFORE("Množství před produktem", "Tlačítko „× ks“ na prodeji: zadáte počet (např. 5) a pak klepnete na produkt."),
    ADDONS("Přídavky pro kategorie", "Přídavky s cenou (sýr, slanina…) nastavené níže se nabídnou po klepnutí na produkt."),
    NOTES("Poznámky pro kuchyň", "Rychlé poznámky (pálivé, bez cibule…) a vlastní text u položky. Klepněte na název položky v košíku."),
    OPEN_PRICE("Cena při prodeji a ruční položka", "Produkt může mít cenu zadávanou při prodeji; na prodeji přibude tlačítko pro ruční položku."),
    SERVICE_CHARGE("Servisní poplatek", "Tlačítko „Servis“ v košíku přidá poplatek v % z účtu; počítá se znovu při každé změně košíku."),
    STORNO_REASON("Důvod storna a záznam", "Storno, vratka a zrušení objednávky chtějí důvod. Vše se zapíše do záznamu níže (kdo, kdy, proč)."),
    EUR_CASH("Hotovost v eurech", "Při platbě hotově ukáže částku v EUR a vrácení v Kč podle kurzu."),
    BIRTHDAYS("Narozeniny zákazníků", "Datum narození u zákazníka, přehled oslavenců a upozornění na prodeji."),
    WAITER_TABLES("Stoly podle obsluhy", "Každý číšník má své stoly; na mapě stolů lze zobrazit jen ty své."),
    TABLE_PLAN("Plán stolů a přesun účtu", "Stoly rozmístíte tažením jako v podniku a otevřený účet přesunete na jiný stůl (podržte stůl)."),
    QR_ORDERING("Objednávky z QR kódu na stole", "Host naskenuje QR kód stolu, vybere z menu a objednávka přijde sem na stůl. Potřebuje propojení s back office a zveřejněné QR menu."),
}
