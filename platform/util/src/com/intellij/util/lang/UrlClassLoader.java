/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.util.lang;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.win32.IdeaWin32;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.misc.Resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A class loader that allows for various customizations, e.g. not locking jars or using a special cache to speed up class loading.
 * Should be constructed using {@link #build()} method.
 */
public class UrlClassLoader extends ClassLoader {
  private static final boolean INDEX_PERSISTENCE_DISABLED = Boolean.parseBoolean(System.getProperty("idea.classpath.index.disabled", "true"));
  @NonNls static final String CLASS_EXTENSION = ".class";

  static {
    // Since Java 7 classloading is parallel on parallel capable classloader (http://docs.oracle.com/javase/7/docs/technotes/guides/lang/cl-mt.html)
    // Parallel classloading avoids deadlocks like https://youtrack.jetbrains.com/issue/IDEA-131621
    // Unless explicitly disabled, request parallel loading capability via reflection due to current platform's Java 6 baseline
    // todo[r.sh] drop condition in IDEA 15
    // todo[r.sh] drop reflection after migrating to Java 7+
    boolean parallelLoader = Boolean.parseBoolean(System.getProperty("idea.parallel.class.loader", "true"));
    if (parallelLoader) {
      try {
        Method registerAsParallelCapable = ClassLoader.class.getDeclaredMethod("registerAsParallelCapable");
        registerAsParallelCapable.setAccessible(true);
        registerAsParallelCapable.invoke(null);
      }
      catch (Exception ignored) { }
    }
  }

  public static final class Builder {
    private List<URL> myURLs = ContainerUtil.emptyList();
    private ClassLoader myParent = null;
    private boolean myLockJars = false;
    private boolean myUseCache = false;
    private boolean myUsePersistentClasspathIndex = false;
    private boolean myAcceptUnescaped = false;
    private boolean myPreload = true;
    private boolean myAllowBootstrapResources = false;
    @Nullable private CachePoolImpl myCachePool = null;
    @Nullable private CachingCondition myCachingCondition = null;

    private Builder() { }

    public Builder urls(List<URL> urls) { myURLs = urls; return this; }
    public Builder urls(URL... urls) { myURLs = Arrays.asList(urls); return this; }
    public Builder parent(ClassLoader parent) { myParent = parent; return this; }
    public Builder allowLock() { myLockJars = true; return this; }
    public Builder allowLock(boolean lockJars) { myLockJars = lockJars; return this; }
    public Builder useCache() { myUseCache = true; return this; }
    public Builder useCache(boolean useCache) { myUseCache = useCache; return this; }

    private Builder usePersistentClasspathIndex(boolean usePersistentClasspathIndex) { myUsePersistentClasspathIndex = usePersistentClasspathIndex; return this; }

    /**
     * Requests the class loader being built to use cache and, if possible, retrieve and store the cached data from a special cache pool
     * that can be shared between several loaders.  

     * @param pool cache pool
     * @param condition a custom policy to provide a possibility to prohibit caching for some URLs.
     * @return this instance
     * 
     * @see #createCachePool() 
     */
    public Builder useCache(@NotNull CachePool pool, @NotNull CachingCondition condition) { 
      myUseCache = true;
      myCachePool = (CachePoolImpl)pool;
      myCachingCondition = condition; 
      return this; 
    }
    
    public Builder allowUnescaped() { myAcceptUnescaped = true; return this; }
    public Builder noPreload() { myPreload = false; return this; }
    public Builder allowBootstrapResources() { myAllowBootstrapResources = true; return this; }

    public UrlClassLoader get() { return new UrlClassLoader(this); }
  }

  public static Builder build() {
    return new Builder();
  }

  private final List<URL> myURLs;
  private final ClassPath myClassPath;
  private final boolean myAllowBootstrapResources;

  /** @deprecated use {@link #build()}, left for compatibility with java.system.class.loader setting */
  public UrlClassLoader(@NotNull ClassLoader parent) {
    this(build().urls(((URLClassLoader)parent).getURLs()).parent(parent.getParent()).allowLock().useCache().usePersistentClasspathIndex(!INDEX_PERSISTENCE_DISABLED));
  }

  /** @deprecated use {@link #build()} (to remove in IDEA 15) */
  public UrlClassLoader(List<URL> urls, @Nullable ClassLoader parent) {
    this(build().urls(urls).parent(parent));
  }

  /** @deprecated use {@link #build()} (to remove in IDEA 15) */
  public UrlClassLoader(URL[] urls, @Nullable ClassLoader parent) {
    this(build().urls(urls).parent(parent));
  }

  /** @deprecated use {@link #build()} (to remove in IDEA 15) */
  public UrlClassLoader(List<URL> urls, @Nullable ClassLoader parent, boolean lockJars, boolean useCache) {
    this(build().urls(urls).parent(parent).allowLock(lockJars).useCache(useCache));
  }

  /** @deprecated use {@link #build()} (to remove in IDEA 15) */
  public UrlClassLoader(List<URL> urls, @Nullable ClassLoader parent, boolean lockJars, boolean useCache, boolean allowUnescaped, boolean preload) {
    super(parent);
    myURLs = ContainerUtil.map(urls, new Function<URL, URL>() {
      @Override
      public URL fun(URL url) {
        return internProtocol(url);
      }
    });
    myClassPath = new ClassPath(myURLs, lockJars, useCache, allowUnescaped, preload, false, null, null);
    myAllowBootstrapResources = false;
  }

  protected UrlClassLoader(@NotNull Builder builder) {
    super(builder.myParent);
    myURLs = ContainerUtil.map(builder.myURLs, new Function<URL, URL>() {
      @Override
      public URL fun(URL url) {
        return internProtocol(url);
      }
    });
    myClassPath = new ClassPath(myURLs, builder.myLockJars, builder.myUseCache, builder.myAcceptUnescaped, builder.myPreload,
                                builder.myUsePersistentClasspathIndex, builder.myCachePool, builder.myCachingCondition);
    myAllowBootstrapResources = builder.myAllowBootstrapResources;
  }

  public static URL internProtocol(@NotNull URL url) {
    try {
      final String protocol = url.getProtocol();
      if ("file".equals(protocol) || "jar".equals(protocol)) {
        return new URL(protocol.intern(), url.getHost(), url.getPort(), url.getFile());
      }
      return url;
    }
    catch (MalformedURLException e) {
      Logger.getInstance(UrlClassLoader.class).error(e);
      return null;
    }
  }

  /** @deprecated to be removed in IDEA 15 */
  @SuppressWarnings({"unused", "deprecation"})
  public void addURL(URL url) {
    myClassPath.addURL(url);
    myURLs.add(url);
  }

  public List<URL> getUrls() {
    return Collections.unmodifiableList(myURLs);
  }

  @Override
  protected Class findClass(final String name) throws ClassNotFoundException {
    Resource res = myClassPath.getResource(name.replace('.', '/').concat(CLASS_EXTENSION), false);
    if (res == null) {
      throw new ClassNotFoundException(name);
    }

    try {
      return defineClass(name, res);
    }
    catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    }
  }

  @Nullable
  protected Class _findClass(@NotNull String name) {
    Resource res = myClassPath.getResource(name.replace('.', '/').concat(CLASS_EXTENSION), false);
    if (res == null) {
      return null;
    }

    try {
      return defineClass(name, res);
    }
    catch (IOException e) {
      return null;
    }
  }

  private Class defineClass(String name, Resource res) throws IOException {
    int i = name.lastIndexOf('.');
    if (i != -1) {
      String pkgName = name.substring(0, i);
      // Check if package already loaded.
      Package pkg = getPackage(pkgName);
      if (pkg == null) {
        try {
          definePackage(pkgName, null, null, null, null, null, null, null);
        }
        catch (IllegalArgumentException e) {
          // do nothing, package already defined by some other thread
        }
      }
    }

    byte[] b = res.getBytes();
    return _defineClass(name, b);
  }

  protected Class _defineClass(final String name, final byte[] b) {
    return defineClass(name, b, 0, b.length);
  }

  @Override
  @Nullable  // Accessed from PluginClassLoader via reflection // TODO do we need it?
  public URL findResource(final String name) {
    return findResourceImpl(name);
  }

  protected URL findResourceImpl(final String name) {
    Resource res = _getResource(name);
    return res != null ? res.getURL() : null;
  }

  @Nullable
  private Resource _getResource(final String name) {
    String n = name;
    if (n.startsWith("/")) n = n.substring(1);
    return myClassPath.getResource(n, true);
  }

  @Nullable
  @Override
  public InputStream getResourceAsStream(final String name) {
    if (myAllowBootstrapResources) return super.getResourceAsStream(name);
    try {
      Resource res = _getResource(name);
      if (res == null) return null;
      return res.getInputStream();
    }
    catch (IOException e) {
      return null;
    }
  }

  // Accessed from PluginClassLoader via reflection // TODO do we need it?
  @Override
  protected Enumeration<URL> findResources(String name) throws IOException {
    return myClassPath.getResources(name, true);
  }

  public static void loadPlatformLibrary(@NotNull String libName) {
    String libFileName = mapLibraryName(libName);
    String libPath = PathManager.getBinPath() + "/" + libFileName;

    if (!new File(libPath).exists()) {
      String platform = getPlatformName();
      if (!new File(libPath = PathManager.getHomePath() + "/community/bin/" + platform + libFileName).exists()) {
        if (!new File(libPath = PathManager.getHomePath() + "/bin/" + platform + libFileName).exists()) {
          if (!new File(libPath = PathManager.getHomePathFor(IdeaWin32.class) + "/bin/" + libFileName).exists()) {
            File libDir = new File(PathManager.getBinPath());
            throw new UnsatisfiedLinkError("'" + libFileName + "' not found in '" + libDir + "' among " + Arrays.toString(libDir.list()));
          }
        }
      }
    }

    System.load(libPath);
  }

  private static String mapLibraryName(String libName) {
    String baseName = libName;
    if (SystemInfo.is64Bit) {
      baseName = baseName.replace("32", "") + "64";
    }
    String fileName = System.mapLibraryName(baseName);
    if (SystemInfo.isMac) {
      fileName = fileName.replace(".jnilib", ".dylib");
    }
    return fileName;
  }

  private static String getPlatformName() {
    if (SystemInfo.isWindows) return "win/";
    else if (SystemInfo.isMac) return "mac/";
    else if (SystemInfo.isLinux) return "linux/";
    else return "";
  }

  /**
   * An interface for a pool to store internal class loader caches, that can be shared between several different class loaders,
   * if they contain the same URLs in their classpaths.<p/>
   * 
   * The implementation is subject to change so one shouldn't rely on it.
   * 
   * @see #createCachePool()
   * @see Builder#useCache(CachePool, CachingCondition) 
   */
  public interface CachePool {}

  /**
   * A condition to customize the caching policy when using {@link CachePool}. This might be needed when a class loader is used on a directory
   * that's being written into, to avoid the situation when a resource path is cached as nonexistent but then a file actually appears there,
   * and other class loaders with the same caching pool should have access to these new resources. This can happen during compilation process
   * with several module outputs.
   */
  public interface CachingCondition {

    /**
     * @param url
     * @return whether the internal information should be cached for files in a specific classpath component URL: inside the directory or
     * a jar.
     */
    boolean shouldCacheData(@NotNull URL url);
  }

  /**
   * @return a new pool to be able to share internal class loader caches between several different class loaders, if they contain the same URLs
   * in their classpaths.
   */
  @NotNull 
  public static CachePool createCachePool() {
    return new CachePoolImpl();
  }
}
