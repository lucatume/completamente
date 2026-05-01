package com.github.lucatume.completamente.walkthrough

internal fun disabledNav(): Pair<BooleanArray, Array<() -> Unit>> =
    BooleanArray(4) to Array(4) { {} }
