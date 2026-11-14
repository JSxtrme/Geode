package com.js.geode

/** One saved summary card. `takeaways` is a JSON array string in the DB. */
data class Summary(
    val id: Long = 0,
    val title: String,
    val category: String,
    val source: String, // Instagram | Facebook | YouTube
    val sourceUrl: String,
    val takeaways: List<String>,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isSample: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    /** null = not rated, true = thumbs up, false = thumbs down */
    val helpful: Boolean? = null
)

enum class Category(val label: String) {
    TUTORIAL("Tutorial"),
    RECIPE("Recipe"),
    TRAVEL("Travel"),
    STUDY("Study"),
    TECH("Tech"),
    OTHER("Other");

    companion object {
        val ALL = listOf("All") + entries.map { it.label }
        fun fromLabel(label: String): Category =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: OTHER
    }
}

enum class Source(val label: String) {
    INSTAGRAM("Instagram"),
    FACEBOOK("Facebook"),
    YOUTUBE("YouTube")
}
