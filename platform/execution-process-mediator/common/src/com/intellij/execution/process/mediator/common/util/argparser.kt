// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.process.mediator.common.util

internal data class Arg(val option: String?, val value: String?) {
  init {
    requireNotNull(option ?: value)
  }
}

internal fun parseArgs(args: Array<out String>): Sequence<Arg> {
  return sequence {
    var skip = false
    var positional = false
    for ((i, arg) in args.withIndex()) {
      if (skip) {
        skip = false
        continue
      }
      if (!positional) {
        if (arg == "--") {
          positional = true
          continue
        }
        if (!arg.startsWith("--")) {
          positional = true
        }
      }

      val option = arg.takeIf { !positional }?.substringBefore("=")

      val value = when {
        option == null -> arg
        "=" in arg -> arg.substringAfter("=")
        else -> {
          args.getOrNull(i + 1)?.takeUnless { it.startsWith("--") }?.also { skip = true }
        }
      }

      yield(Arg(option, value))
    }
  }
}
