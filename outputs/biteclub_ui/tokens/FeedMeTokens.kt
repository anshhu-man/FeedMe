package feedme.design

/** Visual handoff constants; map to dp/sp/Color in the native design-system adapter. */
object FeedMeTokens {
    object Argb {
        const val ElectricBlue: Long = 0xFF304FFE
        const val AcidLime: Long = 0xFFD4FF5A
        const val WarmCoral: Long = 0xFFFF7657
        const val SoftLilac: Long = 0xFFD7C9FF
        const val Ink: Long = 0xFF161917
        const val Paper: Long = 0xFFF7F7F2
        const val Muted: Long = 0xFF60665E
        const val Line: Long = 0xFFE4E6DF
    }
    object Spacing {
        const val Tiny = 4
        const val Small = 8
        const val Compact = 12
        const val Regular = 16
        const val Roomy = 20
        const val Wide = 24
        const val Section = 32
        const val ScreenInset = 22
    }
    object Shape {
        const val ButtonRadius = 14
        const val CardRadius = 22
        const val MinimumTouchTarget = 44
    }
}
