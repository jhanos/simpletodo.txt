package io.github.todotxt.app.model

/**
 * Task priority A–Z, plus NONE for unprioritized tasks.
 * Stored in the file as "(A)" etc., NONE produces no output.
 */
enum class Priority(val code: String) {
    NONE("-"),
    A("A"), B("B"), C("C"), D("D"), E("E"), F("F"),
    G("G"), H("H"), I("I"), J("J"), K("K"), L("L"),
    M("M"), N("N"), O("O"), P("P"), Q("Q"), R("R"),
    S("S"), T("T"), U("U"), V("V"), W("W"), X("X"),
    Y("Y"), Z("Z");

    /** The "(A)" style representation written to the file. */
    val fileFormat: String get() = if (this == NONE) "" else "($code)"

    companion object {
        fun fromCode(code: String): Priority =
            entries.firstOrNull { it.code == code } ?: NONE
    }
}
