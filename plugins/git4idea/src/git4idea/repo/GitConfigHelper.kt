// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.repo

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import org.ini4j.Ini
import java.io.File
import java.io.IOException

private val LOG: Logger get() = logger(::LOG)

@Throws(IOException::class)
internal fun loadIniFile(file: File): Ini {
  val ini = Ini()
  ini.config.isMultiOption = true  // duplicate keys (e.g. url in [remote])
  ini.config.isTree = false        // don't need tree structure: it corrupts url in section name (e.g. [url "http://github.com/"]
  ini.config.isLowerCaseOption = true
  ini.config.isEmptyOption = true
  try {
    ini.load(file)
    return ini
  }
  catch (e: IOException) {
    LOG.warn("Couldn't load config file at ${file.path}", e)
    throw e
  }
}

internal fun findClassLoader(): ClassLoader? {
  val javaClass = ::findClassLoader.javaClass
  val plugin = PluginManagerCore.getPlugin(PluginManagerCore.getPluginByClassName(javaClass.name))
  return plugin?.pluginClassLoader ?: javaClass.classLoader  // null e.g. if IDEA is started from IDEA
}
