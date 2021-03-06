// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.daemon.impl

import com.intellij.codeInsight.daemon.impl.analysis.JavaLensSettings
import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.ui.components.CheckBox
import com.intellij.util.ui.JBUI

class JavaLensConfigurable(val settings: JavaLensSettings) : ImmediateConfigurable {
  private val usagesCB = CheckBox("Usages")
  private val inheritCB = CheckBox("Inheritors")

  override fun createComponent(listener: ChangeListener): javax.swing.JPanel {
    reset()
    usagesCB.addItemListener { handleChange(listener) }
    inheritCB.addItemListener { handleChange(listener) }
    val panel = com.intellij.ui.layout.panel {
      row {
        usagesCB(pushX)
      }
      row {
        inheritCB(pushX)
      }
    }
    panel.border = JBUI.Borders.empty(0, 20, 0, 0)
    return panel
  }

  override val mainCheckboxText: String
    get() = "Show hints for:"

  private fun handleChange(listener: ChangeListener) {
    settings.isShowUsages = usagesCB.isSelected
    settings.isShowImplementations = inheritCB.isSelected
    if (!settings.isShowUsages && !settings.isShowImplementations) {
      listener.didDeactivated()
    }
    listener.settingsChanged()
  }

  override fun reset() {
    usagesCB.isSelected = settings.isShowUsages
    inheritCB.isSelected = settings.isShowImplementations
  }
}
