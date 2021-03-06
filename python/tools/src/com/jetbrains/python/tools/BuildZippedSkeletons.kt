// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.tools

import com.intellij.idea.IdeaTestApplication
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.io.Compressor
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.skeletons.DefaultPregeneratedSkeletonsProvider
import com.jetbrains.python.sdk.skeletons.PySkeletonRefresher
import com.jetbrains.python.sdk.skeletons.SkeletonVersionChecker
import com.jetbrains.python.tools.sdkTools.PySdkTools
import com.jetbrains.python.tools.sdkTools.SdkCreationType
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * @author traff
 */

const val PYCHARM_PYTHONS: String = "PYCHARM_PYTHONS"

fun main() {
  println("Starting build process")
  val app = IdeaTestApplication.getInstance()
  println("App started: ${app}")

  try {
    val root = System.getenv(PYCHARM_PYTHONS)
    val workingDir = System.getProperty("user.dir")
    val cacheDir = File(workingDir, "cache")
    println("Skeletons will share a common cache at $cacheDir")

    for (python in File(root).listFiles()!!) {
      println("Running on $python")

      val executable = PythonSdkType.getPythonExecutable(python.absolutePath)!!
      val sdk = PySdkTools.createTempSdk(VfsUtil.findFileByIoFile(File(executable), true)!!, SdkCreationType.SDK_PACKAGES_ONLY, null)

      val skeletonsDir = File(workingDir, "skeletons-${sdk.versionString!!.replace(" ", "_")}_" + abs(sdk.homePath!!.hashCode()))
      println("Generating skeletons in ${skeletonsDir.absolutePath}")

      val refresher = PySkeletonRefresher(null, null, sdk, skeletonsDir.absolutePath, null, null)
      refresher.regenerateSkeletons(SkeletonVersionChecker(SkeletonVersionChecker.PREGENERATED_VERSION))

      val artifactName = DefaultPregeneratedSkeletonsProvider.getPregeneratedSkeletonsName(sdk, refresher.generatorVersion, true, true)
      val dirPacked = File(skeletonsDir.parent, artifactName!!)
      println("Creating artifact $dirPacked")
      Compressor.Zip(dirPacked).use { it.addDirectory(skeletonsDir) }
    }
  }
  catch (e: Exception) {
    e.printStackTrace()
    exitProcess(1)
  }
  finally {
    exitProcess(0) //TODO: a graceful exit?
  }
}