// ERROR: Unresolved reference: bar
// K2-ERROR: Unresolved reference 'bar'.
// K2-AFTER-ERROR: Unresolved reference 'bar'.
fun <caret>foo(): Unit = bar()