// WITH_STDLIB
// INTENTION_TEXT: "Remove return@find"

fun foo() {
    listOf(1,2,3).find {
        <caret>return@find true
    }
}