// IS_APPLICABLE: false
// WITH_STDLIB
// ERROR: Unresolved reference: unresolved
// K2-ERROR: Unresolved reference 'unresolved'.

import java.util.Objects

val v = <caret>Objects.unresolved()