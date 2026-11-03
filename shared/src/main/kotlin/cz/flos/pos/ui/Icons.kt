package cz.flos.pos.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.BrunchDining
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DinnerDining
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.EggAlt
import androidx.compose.material.icons.rounded.EmojiFoodBeverage
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.Icecream
import androidx.compose.material.icons.rounded.KebabDining
import androidx.compose.material.icons.rounded.Liquor
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.LocalPizza
import androidx.compose.material.icons.rounded.LunchDining
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RamenDining
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.RiceBowl
import androidx.compose.material.icons.rounded.SetMeal
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.SoupKitchen
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tapas
import androidx.compose.material.icons.rounded.WineBar
import androidx.compose.ui.graphics.vector.ImageVector
import cz.flos.core.PaymentMethod

val PaymentMethod.icon: ImageVector
    get() = when (this) {
        PaymentMethod.CASH -> Icons.Rounded.Payments
        PaymentMethod.CARD -> Icons.Rounded.CreditCard
        PaymentMethod.QR_TRANSFER -> Icons.Rounded.QrCode2
        PaymentMethod.BANK_TRANSFER -> Icons.Rounded.AccountBalance
        PaymentMethod.MEAL_VOUCHER -> Icons.Rounded.ConfirmationNumber
        PaymentMethod.INVOICE -> Icons.Rounded.Description
    }

/**
 * Icons a product or category can show on its tile. Stored by key in the database, so the
 * keys must never change; new icons are added at the end.
 */
object ProductIcons {
    val all: List<Pair<String, ImageVector>> = listOf(
        "coffee" to Icons.Rounded.Coffee,
        "cafe" to Icons.Rounded.LocalCafe,
        "tea" to Icons.Rounded.EmojiFoodBeverage,
        "drink" to Icons.Rounded.LocalDrink,
        "beer" to Icons.Rounded.SportsBar,
        "wine" to Icons.Rounded.WineBar,
        "cocktail" to Icons.Rounded.LocalBar,
        "spirits" to Icons.Rounded.Liquor,
        "pizza" to Icons.Rounded.LocalPizza,
        "burger" to Icons.Rounded.LunchDining,
        "fastfood" to Icons.Rounded.Fastfood,
        "kebab" to Icons.Rounded.KebabDining,
        "pasta" to Icons.Rounded.DinnerDining,
        "noodles" to Icons.Rounded.RamenDining,
        "rice" to Icons.Rounded.RiceBowl,
        "soup" to Icons.Rounded.SoupKitchen,
        "fish" to Icons.Rounded.SetMeal,
        "tapas" to Icons.Rounded.Tapas,
        "breakfast" to Icons.Rounded.BrunchDining,
        "egg" to Icons.Rounded.EggAlt,
        "salad" to Icons.Rounded.Eco,
        "bakery" to Icons.Rounded.BakeryDining,
        "cake" to Icons.Rounded.Cake,
        "cookie" to Icons.Rounded.Cookie,
        "icecream" to Icons.Rounded.Icecream,
        "meal" to Icons.Rounded.Restaurant,
        "grocery" to Icons.Rounded.LocalGroceryStore,
        "basket" to Icons.Rounded.ShoppingBasket,
        "flower" to Icons.Rounded.LocalFlorist,
        "star" to Icons.Rounded.Star,
    )
    private val byKey = all.toMap()

    fun of(key: String?): ImageVector? = key?.let(byKey::get)
}
