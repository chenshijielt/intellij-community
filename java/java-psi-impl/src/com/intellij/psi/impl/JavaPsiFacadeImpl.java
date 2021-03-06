/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFileFilter;
import com.intellij.psi.*;
import com.intellij.psi.impl.file.impl.JavaFileManager;
import com.intellij.psi.impl.source.DummyHolderFactory;
import com.intellij.psi.impl.source.JavaDummyHolder;
import com.intellij.psi.impl.source.JavaDummyHolderFactory;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.reference.SoftReference;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Processor;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.messages.MessageBus;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * @author max
 */
public class JavaPsiFacadeImpl extends JavaPsiFacadeEx {
  private volatile PsiElementFinder[] myElementFinders;
  private final PsiConstantEvaluationHelper myConstantEvaluationHelper;
  private volatile SoftReference<ConcurrentMap<String, PsiPackage>> myPackageCache;
  private final Project myProject;
  private final JavaFileManager myFileManager;

  public JavaPsiFacadeImpl(Project project,
                           PsiManager psiManager,
                           JavaFileManager javaFileManager,
                           MessageBus bus) {
    myProject = project;
    myFileManager = javaFileManager;
    myConstantEvaluationHelper = new PsiConstantEvaluationHelperImpl();

    final PsiModificationTracker modificationTracker = psiManager.getModificationTracker();

    if (bus != null) {
      bus.connect().subscribe(PsiModificationTracker.TOPIC, new PsiModificationTracker.Listener() {
        private long lastTimeSeen = -1L;

        @Override
        public void modificationCountChanged() {
          final long now = modificationTracker.getJavaStructureModificationCount();
          if (lastTimeSeen != now) {
            lastTimeSeen = now;
            myPackageCache = null;
          }
        }
      });
    }

    DummyHolderFactory.setFactory(new JavaDummyHolderFactory());
  }

  @Override
  public PsiClass findClass(@NotNull final String qualifiedName, @NotNull GlobalSearchScope scope) {
    ProgressIndicatorProvider.checkCanceled(); // We hope this method is being called often enough to cancel daemon processes smoothly

    if (shouldUseSlowResolve()) {
      PsiClass[] classes = findClassesInDumbMode(qualifiedName, scope);
      if (classes.length != 0) {
        return classes[0];
      }
      return null;
    }

    for (PsiElementFinder finder : finders()) {
      PsiClass aClass = finder.findClass(qualifiedName, scope);
      if (aClass != null) return aClass;
    }

    return null;
  }

  @NotNull
  private PsiClass[] findClassesInDumbMode(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
    final String packageName = StringUtil.getPackageName(qualifiedName);
    final PsiPackage pkg = findPackage(packageName);
    final String className = StringUtil.getShortName(qualifiedName);
    if (pkg == null && packageName.length() < qualifiedName.length()) {
      PsiClass[] containingClasses = findClassesInDumbMode(packageName, scope);
      if (containingClasses.length == 1) {
        return PsiElementFinder.filterByName(className, containingClasses[0].getInnerClasses());
      }

      return PsiClass.EMPTY_ARRAY;
    }

    if (pkg == null || !pkg.containsClassNamed(className)) {
      return PsiClass.EMPTY_ARRAY;
    }

    return pkg.findClassByShortName(className, scope);
  }

  @Override
  @NotNull
  public PsiClass[] findClasses(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
    if (shouldUseSlowResolve()) {
      return findClassesInDumbMode(qualifiedName, scope);
    }

    List<PsiClass> classes = new SmartList<PsiClass>();
    for (PsiElementFinder finder : finders()) {
      PsiClass[] finderClasses = finder.findClasses(qualifiedName, scope);
      ContainerUtil.addAll(classes, finderClasses);
    }

    return classes.toArray(new PsiClass[classes.size()]);
  }

  private boolean shouldUseSlowResolve() {
    DumbService dumbService = DumbService.getInstance(getProject());
    return dumbService.isDumb() && dumbService.isAlternativeResolveEnabled();
  }

  @NotNull
  private PsiElementFinder[] finders() {
    PsiElementFinder[] answer = myElementFinders;
    if (answer == null) {
      answer = calcFinders();
      myElementFinders = answer;
    }

    return answer;
  }

  @NotNull
  protected PsiElementFinder[] calcFinders() {
    List<PsiElementFinder> elementFinders = new ArrayList<PsiElementFinder>();
    ContainerUtil.addAll(elementFinders, myProject.getExtensions(PsiElementFinder.EP_NAME));
    return elementFinders.toArray(new PsiElementFinder[elementFinders.size()]);
  }

  @Override
  @NotNull
  public PsiConstantEvaluationHelper getConstantEvaluationHelper() {
    return myConstantEvaluationHelper;
  }

  @Override
  public PsiPackage findPackage(@NotNull String qualifiedName) {
    ConcurrentMap<String, PsiPackage> cache = SoftReference.dereference(myPackageCache);
    if (cache == null) {
      myPackageCache = new SoftReference<ConcurrentMap<String, PsiPackage>>(cache = ContainerUtil.newConcurrentMap());
    }

    PsiPackage aPackage = cache.get(qualifiedName);
    if (aPackage != null) {
      return aPackage;
    }

    for (PsiElementFinder finder : filteredFinders()) {
      aPackage = finder.findPackage(qualifiedName);
      if (aPackage != null) {
        return ConcurrencyUtil.cacheOrGet(cache, qualifiedName, aPackage);
      }
    }

    return null;
  }

  @NotNull
  private PsiElementFinder[] filteredFinders() {
    DumbService dumbService = DumbService.getInstance(getProject());
    PsiElementFinder[] finders = finders();
    if (dumbService.isDumb()) {
      List<PsiElementFinder> list = dumbService.filterByDumbAwareness(Arrays.asList(finders));
      finders = list.toArray(new PsiElementFinder[list.size()]);
    }
    return finders;
  }

  @Override
  @NotNull
  public PsiJavaParserFacade getParserFacade() {
    return getElementFactory(); // TODO: lighter implementation which doesn't mark all the elements as generated.
  }

  @Override
  @NotNull
  public PsiResolveHelper getResolveHelper() {
    return PsiResolveHelper.SERVICE.getInstance(myProject);
  }

  @Override
  @NotNull
  public PsiNameHelper getNameHelper() {
    return PsiNameHelper.getInstance(myProject);
  }

  @NotNull
  public Set<String> getClassNames(@NotNull PsiPackage psiPackage, @NotNull GlobalSearchScope scope) {
    Set<String> result = new THashSet<String>();
    for (PsiElementFinder finder : filteredFinders()) {
      result.addAll(finder.getClassNames(psiPackage, scope));
    }
    return result;
  }

  @NotNull
  public PsiClass[] getClasses(@NotNull PsiPackage psiPackage, @NotNull GlobalSearchScope scope) {
    List<PsiClass> result = null;
    for (PsiElementFinder finder : filteredFinders()) {
      PsiClass[] classes = finder.getClasses(psiPackage, scope);
      if (classes.length == 0) continue;
      if (result == null) result = new ArrayList<PsiClass>();
      ContainerUtil.addAll(result, classes);
    }

    return result == null ? PsiClass.EMPTY_ARRAY : result.toArray(new PsiClass[result.size()]);
  }

  @NotNull
  public PsiFile[] getPackageFiles(@NotNull PsiPackage psiPackage, @NotNull GlobalSearchScope scope) {
    Condition<PsiFile> filter = null;

    for (PsiElementFinder finder : filteredFinders()) {
      Condition<PsiFile> finderFilter = finder.getPackageFilesFilter(psiPackage, scope);
      if (finderFilter != null) {
        if (filter == null) {
          filter = finderFilter;
        }
        else {
          filter = Conditions.and(filter, finderFilter);
        }
      }
    }

    Set<PsiFile> result = new LinkedHashSet<PsiFile>();
    PsiDirectory[] directories = psiPackage.getDirectories(scope);
    for (PsiDirectory directory : directories) {
      for (PsiFile file : directory.getFiles()) {
        if (filter == null || filter.value(file)) {
          result.add(file);
        }
      }
    }

    for (PsiElementFinder finder : filteredFinders()) {
      Collections.addAll(result, finder.getPackageFiles(psiPackage, scope));
    }
    return result.toArray(new PsiFile[result.size()]);
  }

  public boolean processPackageDirectories(@NotNull PsiPackage psiPackage,
                                           @NotNull GlobalSearchScope scope,
                                           @NotNull Processor<PsiDirectory> consumer,
                                           boolean includeLibrarySources) {
    for (PsiElementFinder finder : filteredFinders()) {
      if (!finder.processPackageDirectories(psiPackage, scope, consumer, includeLibrarySources)) {
        return false;
      }
    }
    return true;
  }

  @NotNull
  public PsiPackage[] getSubPackages(@NotNull PsiPackage psiPackage, @NotNull GlobalSearchScope scope) {
    LinkedHashMap<String, PsiPackage> result = new LinkedHashMap<String, PsiPackage>();
    for (PsiElementFinder finder : filteredFinders()) {
      // Ensure uniqueness of names in the returned list of subpackages. If a plugin PsiElementFinder
      // returns the same package from its getSubPackages() implementation that Java already knows about
      // (the Kotlin plugin can do that), the Java package takes precedence.
      PsiPackage[] packages = finder.getSubPackages(psiPackage, scope);
      for (PsiPackage aPackage : packages) {
        if (result.get(aPackage.getName()) == null) {
          result.put(aPackage.getName(), aPackage);
        }
      }
    }
    return result.values().toArray(new PsiPackage[result.size()]);
  }

  @Override
  public boolean isPartOfPackagePrefix(@NotNull String packageName) {
    final Collection<String> packagePrefixes = myFileManager.getNonTrivialPackagePrefixes();
    for (final String subpackageName : packagePrefixes) {
      if (PsiNameHelper.isSubpackageOf(subpackageName, packageName)) return true;
    }
    return false;
  }

  @Override
  public boolean isInPackage(@NotNull PsiElement element, @NotNull PsiPackage aPackage) {
    final PsiFile file = FileContextUtil.getContextFile(element);
    if (file instanceof JavaDummyHolder) {
      return ((JavaDummyHolder) file).isInPackage(aPackage);
    }
    if (file instanceof PsiJavaFile) {
      final String packageName = ((PsiJavaFile) file).getPackageName();
      return packageName.equals(aPackage.getQualifiedName());
    }
    return false;
  }

  @Override
  public boolean arePackagesTheSame(@NotNull PsiElement element1, @NotNull PsiElement element2) {
    PsiFile file1 = FileContextUtil.getContextFile(element1);
    PsiFile file2 = FileContextUtil.getContextFile(element2);
    if (Comparing.equal(file1, file2)) return true;
    if (file1 instanceof JavaDummyHolder && file2 instanceof JavaDummyHolder) return true;
    if (file1 instanceof JavaDummyHolder || file2 instanceof JavaDummyHolder) {
      JavaDummyHolder dummyHolder = (JavaDummyHolder) (file1 instanceof JavaDummyHolder ? file1 : file2);
      PsiElement other = file1 instanceof JavaDummyHolder ? file2 : file1;
      return dummyHolder.isSamePackage(other);
    }
    if (!(file1 instanceof PsiClassOwner)) return false;
    if (!(file2 instanceof PsiClassOwner)) return false;
    String package1 = ((PsiClassOwner) file1).getPackageName();
    String package2 = ((PsiClassOwner) file2).getPackageName();
    return Comparing.equal(package1, package2);
  }

  @Override
  @NotNull
  public Project getProject() {
    return myProject;
  }

  @Override
  @NotNull
  public PsiElementFactory getElementFactory() {
    return PsiElementFactory.SERVICE.getInstance(myProject);
  }

  @TestOnly
  @Override
  public void setAssertOnFileLoadingFilter(@NotNull final VirtualFileFilter filter, Disposable parentDisposable) {
    ((PsiManagerImpl)PsiManager.getInstance(myProject)).setAssertOnFileLoadingFilter(filter, parentDisposable);
  }
}
