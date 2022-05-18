/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.project.model

// TODO Gradle allows having multiple capabilities in a published module, we need to figure out how we can include them in the module IDs

interface KotlinModule {
    val moduleIdentifier: KotlinModuleIdentifier

    val fragments: Iterable<KotlinFragment>

    val variants: Iterable<KotlinVariant>
        get() = fragments.filterIsInstance<KotlinVariant>()

    val plugins: Iterable<KotlinCompilerPlugin>

    // TODO: isSynthetic?
}

open class BasicKotlinModule(
    override val moduleIdentifier: KotlinModuleIdentifier
) : KotlinModule {
    override val fragments = mutableListOf<BasicKotlinFragment>()

    override val plugins = mutableListOf<KotlinCompilerPlugin>()

    override fun toString(): String = "module $moduleIdentifier"
}
