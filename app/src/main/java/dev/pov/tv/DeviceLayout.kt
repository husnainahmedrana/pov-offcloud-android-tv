package dev.pov.tv

/** The TV and phone apps share every screen; this is the one switch between their layouts. */
internal object DeviceLayout {
    val phone = BuildConfig.MOBILE
    val tv = !phone
    /**
     * Logical width that every layout size is drawn against: the TV's 960 x 540 canvas, or about one
     * real dp per unit across a portrait phone.
     */
    val canvasWidth = if (phone) 400 else 960
}
