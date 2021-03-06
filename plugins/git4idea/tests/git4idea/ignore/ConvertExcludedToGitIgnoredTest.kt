// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.ignore

import com.intellij.configurationStore.saveComponentManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.CompilerProjectExtension
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vcs.VcsApplicationSettings
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.vcsUtil.VcsImplUtil
import git4idea.repo.GitRepositoryFiles.GITIGNORE
import git4idea.test.GitSingleRepoTest
import java.io.File

class ConvertExcludedToGitIgnoredTest : GitSingleRepoTest() {

  private lateinit var moduleContentRoot: VirtualFile
  private lateinit var gitIgnore: File

  override fun getProjectDirOrFile() = getProjectDirOrFile(true)

  override fun setUp() {
    super.setUp()
    VcsApplicationSettings.getInstance().MANAGE_IGNORE_FILES = true
    Registry.get("vcs.ignorefile.generation").setValue(true, testRootDisposable)
    Registry.get("ide.hide.excluded.files").setValue(false, testRootDisposable)
    gitIgnore = File("$projectPath/$GITIGNORE")
  }

  override fun setUpProject() {
    super.setUpProject()
    invokeAndWaitIfNeeded { saveComponentManager(project) } //will create .idea directory
  }

  override fun setUpModule() {
    runWriteAction {
      myModule = createMainModule()
      moduleContentRoot = myModule.moduleFile!!.parent
      myModule.addContentRoot(moduleContentRoot)
    }
  }

  fun testExcludedFolder() {
    val excluded = createChildDirectory(moduleContentRoot, "exc")
    createChildData(excluded, "excluded.txt") //Don't mark empty directories like ignored since versioning such directories not supported in Git
    PsiTestUtil.addExcludedRoot(myModule, excluded)

    VcsImplUtil.generateIgnoreFileIfNeeded(project, vcs, projectRoot)
    assertGitignoreValid(gitIgnore, """
    # Project exclude paths
    /exc/
    """)

    refreshChanges()
    assertFalse(changeListManager.isIgnoredFile(moduleContentRoot))
    assertTrue(changeListManager.isIgnoredFile(excluded))
  }

  fun testModuleOutput() {
    val output = createChildDirectory(moduleContentRoot, "out")
    PsiTestUtil.setCompilerOutputPath(myModule, output.url, false)
    createChildData(output, "out.class")

    VcsImplUtil.generateIgnoreFileIfNeeded(project, vcs, projectRoot)
    assertGitignoreValid(gitIgnore, """
    # Project exclude paths
    /out/
    """)

    refreshChanges()
    assertFalse(changeListManager.isIgnoredFile(moduleContentRoot))
    assertTrue(changeListManager.isIgnoredFile(output))
  }

  fun testProjectOutput() {
    val output = createChildDirectory(projectRoot, "projectOutput")
    createChildData(output, "out.class")
    CompilerProjectExtension.getInstance(project)!!.compilerOutputUrl = output.url

    VcsImplUtil.generateIgnoreFileIfNeeded(project, vcs, projectRoot)
    assertGitignoreValid(gitIgnore, """
    # Project exclude paths
    /projectOutput/
    """)

    refreshChanges()
    assertTrue(changeListManager.isIgnoredFile(output))
  }

  fun testModuleOutputUnderProjectOutput() {
    val output = createChildDirectory(projectRoot, "projectOutput")
    createChildData(output, "out.class")
    CompilerProjectExtension.getInstance(project)!!.compilerOutputUrl = output.url
    val moduleOutput = createChildDirectory(output, "module")
    PsiTestUtil.setCompilerOutputPath(myModule, moduleOutput.url, false)

    VcsImplUtil.generateIgnoreFileIfNeeded(project, vcs, projectRoot)
    assertGitignoreValid(gitIgnore, """
    # Project exclude paths
    /projectOutput/
    """)

    refreshChanges()
    assertTrue(changeListManager.isIgnoredFile(output))
    assertTrue(changeListManager.isIgnoredFile(moduleOutput))
  }

  fun testModuleOutputUnderExcluded() {
    val excluded = createChildDirectory(moduleContentRoot, "target")
    createChildData(excluded, "out.class")
    PsiTestUtil.addExcludedRoot(myModule, excluded)
    val moduleOutput = createChildDirectory(excluded, "classes")
    createChildData(moduleOutput, "out.class")
    PsiTestUtil.setCompilerOutputPath(myModule, moduleOutput.url, false)

    VcsImplUtil.generateIgnoreFileIfNeeded(project, vcs, projectRoot)
    assertGitignoreValid(gitIgnore, """
    # Project exclude paths
    /target/
    """)

    refreshChanges()
    assertTrue(changeListManager.isIgnoredFile(excluded))
    assertTrue(changeListManager.isIgnoredFile(moduleOutput))
  }

  fun testDoNotIgnoreInnerModuleExplicitlyMarkedAsExcludedFromOuterModule() {
    val inner = createChildDirectory(moduleContentRoot, "inner")
    createChildData(inner, "inner.txt")
    PsiTestUtil.addModule(myProject, ModuleType.EMPTY, "inner", inner)
    PsiTestUtil.addExcludedRoot(myModule, inner)

    refreshChanges()
    assertFalse(changeListManager.isIgnoredFile(inner))
  }

  private fun refreshChanges() {
    VcsDirtyScopeManager.getInstance(myProject).markEverythingDirty()
    changeListManager.ensureUpToDate()
    val exception = changeListManager.updateException
    if (exception != null) {
      throw RuntimeException(exception)
    }
  }
}