package io.github.elvismanchkin.taskondemand.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.layout.ComponentPredicate

/** Visibility predicate for UI DSL rows that depends on a combo box selection. */
class ComboPredicate<T>(private val combo: ComboBox<T>, private val test: (T) -> Boolean) : ComponentPredicate() {
    @Suppress("UNCHECKED_CAST")
    override fun invoke(): Boolean = (combo.selectedItem as? T)?.let(test) ?: false

    override fun addListener(listener: (Boolean) -> Unit) {
        combo.addActionListener { listener(invoke()) }
    }
}
